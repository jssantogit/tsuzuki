package tachiyomi.domain.tsuzuki.chapter.inventory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

/** Explicitly selected page/revision context plus a bounded Action API formatversion=2 response. */
data class SelectedWikimediaRevision(
    val scope: InventoryScope,
    val selectedPageId: Long,
    val selectedRevisionId: Long,
    val pageReference: String,
    val responseJson: String,
    val observedAt: Long,
    val snapshotDigest: String,
    val attributionReference: String?,
    val licenseReference: String?,
)

/**
 * Deterministic local adapter for a user-selected Wikimedia revision fixture.
 * It does not perform network access and never treats a page as editorial authority.
 */
object SelectedWikimediaChapterIndexAdapter {
    const val MAX_SOURCE_BYTES = 256 * 1024

    // A single selected article may enumerate long series; the response byte budget remains strict.
    const val MAX_ENTRIES = 2048
    private const val MAX_REFERENCE_LENGTH = 2048
    private const val MAX_UNIT_LENGTH = 512
    private val chapterNumber = Regex("^(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")
    private val chapterRange = Regex("^[0-9]+(?:\\.[0-9]+)?\\s*[-–—]\\s*[0-9]+(?:\\.[0-9]+)?$")
    private val specialChapter = Regex(
        """(?i)^(?:special chapter|bonus chapter)(?:\s+(?:0|[1-9][0-9]*)(?:\.[0-9]+)?|\s*[:#–—-]\s*[\p{L}][^\r\n]*)?$""",
    )
    // Even a descriptive special can mention multiple units or a reupload.
    // Neither can be treated as one individually identified editorial unit.
    private val ambiguousSpecialSequence = Regex(
        """(?i)(?:\b\d+(?:\.\d+)?\s*[-–—]\s*\d+(?:\.\d+)?\b|\b\d+(?:\.\d+)?v\d+\b)""",
    )
    private val plainTableCell = Regex("""^[\p{L}\p{N} .,;:()'!+–—-]{1,512}$""")
    private val otherHeading = Regex("^== [^=]+ ==$")
    private val internalTarget = Regex("^[\\p{L}\\p{N}_ .,:;()'!+–—\\-]+$")

    fun adapt(selected: SelectedWikimediaRevision): Result<InventoryEvidenceBatch> = try {
        Result.success(adaptSelected(selected))
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun adaptSelected(selected: SelectedWikimediaRevision): InventoryEvidenceBatch {
        validateSelection(selected)
        val digest = selected.responseJson.sha256SnapshotDigest()
        require(selected.snapshotDigest == digest) { "Wikimedia response digest does not match selected bytes" }

        val root = Json.parseToJsonElement(selected.responseJson) as? JsonObject
            ?: error("Wikimedia response is not a JSON object")
        val query = root["query"] as? JsonObject ?: error("Wikimedia response has no query object")
        val pages = query["pages"] as? JsonArray ?: error("Wikimedia response is not formatversion=2 pages data")
        require(pages.size == 1) { "Exactly one explicitly selected page is required" }
        val page = pages.single() as? JsonObject ?: error("Wikimedia page entry is not an object")
        require(page.long("pageid") == selected.selectedPageId) { "Wikimedia returned a different page" }
        val revisions = page["revisions"] as? JsonArray ?: error("Wikimedia page has no revisions")
        require(revisions.size == 1) { "Exactly one selected revision is required" }
        val revision = revisions.single() as? JsonObject ?: error("Wikimedia revision is not an object")
        require(revision.long("revid") == selected.selectedRevisionId) {
            "Wikimedia returned a different revision"
        }
        val slots = revision["slots"] as? JsonObject ?: error("Wikimedia revision has no slots")
        val main = slots["main"] as? JsonObject ?: error("Wikimedia main slot is missing")
        require(main.string("contentmodel") == "wikitext" && main.string("contentformat") == "text/x-wiki") {
            "Only the selected wikitext revision format is supported"
        }
        val wikitext = main.string("content") ?: error("Wikimedia revision content is missing")
        val units = parseChapterList(wikitext)
        require(units.isNotEmpty()) { "Selected Wikimedia revision has no supported chapter-list entries" }
        require(units.size <= MAX_ENTRIES) { "Wikimedia chapter list exceeds the entry limit" }
        require(units.map { it.target }.distinct().size == units.size) {
            "Duplicate Wikimedia chapter targets are ambiguous"
        }

        return InventoryEvidenceBatch(
            scope = selected.scope,
            pageKey = "wikimedia:${selected.selectedPageId}:${selected.selectedRevisionId}",
            observedAt = selected.observedAt,
            outcome = InventoryFetchOutcome.PARTIAL,
            claims = units.map { unit ->
                InventoryClaim(
                    sourceRecordId =
                    "wikimedia:${selected.selectedPageId}:${selected.selectedRevisionId}:${unit.target}",
                    label = unit.label,
                    volumeLabel = null,
                    language = selected.scope.language,
                    kind = unit.kind,
                    derivation = InventoryDerivation.COMMUNITY_CURATED,
                    upstreamSourceId = "wikimedia:${selected.selectedPageId}",
                    observedAt = selected.observedAt,
                    provenance = InventoryCandidateProvenance(
                        sourceKind = InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION,
                        originReference = selected.pageReference,
                        exactRevision = selected.selectedRevisionId.toString(),
                        attributionReference = selected.attributionReference,
                        licenseReference = selected.licenseReference,
                        snapshotDigest = digest,
                        sourceUnitId = unit.target,
                        structuralCategory = unit.category,
                    ),
                )
            },
        )
    }

    private fun validateSelection(selected: SelectedWikimediaRevision) {
        val bytes = selected.responseJson.toByteArray(UTF_8)
        require(bytes.size in 1..MAX_SOURCE_BYTES) { "Wikimedia response exceeds the byte limit" }
        require(selected.scope.producerId == "wikimedia") { "Unexpected Wikimedia inventory producer" }
        require(!selected.scope.editionKey.isNullOrBlank() && !selected.scope.language.isNullOrBlank()) {
            "An exact selected edition and language are required"
        }
        require(selected.selectedPageId > 0L && selected.selectedRevisionId > 0L)
        require(selected.observedAt >= 0L)
        require(selected.snapshotDigest.matches(SHA256_DIGEST))
        require(selected.pageReference.length <= MAX_REFERENCE_LENGTH)
        val pageUri = URI(selected.pageReference)
        require(
            pageUri.scheme == "https" &&
                pageUri.userInfo == null &&
                pageUri.port == -1 &&
                pageUri.rawFragment == null &&
                isSelectedWikimediaRevisionUrl(pageUri, selected.selectedRevisionId),
        ) { "Wikimedia source reference must identify the selected Wikimedia revision" }
        val attribution = selected.attributionReference ?: error("Attribution reference is required")
        val license = selected.licenseReference ?: error("License reference is required")
        require(attribution.isNotBlank() && attribution.length <= MAX_REFERENCE_LENGTH)
        require(license.isNotBlank() && license.length <= MAX_REFERENCE_LENGTH)
    }

    private fun isSelectedWikimediaRevisionUrl(uri: URI, selectedRevisionId: Long): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val officialProjects = listOf(
            "wikipedia.org",
            "wikimedia.org",
            "wikidata.org",
            "wikibooks.org",
            "wikinews.org",
            "wikiquote.org",
            "wikisource.org",
            "wikiversity.org",
            "wikivoyage.org",
            "wiktionary.org",
        )
        if (officialProjects.none { domain -> host == domain || host.endsWith(".$domain") }) return false
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery.orEmpty()
        val expectedRevision = "oldid=$selectedRevisionId"
        return when {
            path.startsWith("/wiki/") && path.length > "/wiki/".length -> query == expectedRevision
            path == "/w/index.php" -> {
                val parameters = query.split('&')
                parameters.size == 2 &&
                    expectedRevision in parameters &&
                    parameters.any { it.startsWith("title=") && it.length > "title=".length }
            }
            else -> false
        }
    }

    private fun parseChapterList(wikitext: String): List<ParsedUnit> {
        var inChapterSection = false
        var foundChapterSection = false
        var inTable = false
        var hasTableHeader = false
        var rowStarted = false
        var rowHasChapter = false
        val units = mutableListOf<ParsedUnit>()

        fun addLinkedUnit(markup: String, table: Boolean) {
            require(markup.startsWith("[[") && markup.endsWith("]]")) {
                "Only an explicit internal chapter link is supported"
            }
            val link = markup.substring(2, markup.length - 2)
            val separator = link.indexOf('|')
            require(separator == link.lastIndexOf('|')) { "Unsupported Wikimedia chapter-link syntax" }
            val target = (if (separator < 0) link else link.substring(0, separator)).trim()
            val text = if (separator < 0) "" else link.substring(separator + 1)
            val label = (text.ifEmpty { target.replace('_', ' ') }).trim()
            require(target.isNotBlank() && target.length <= MAX_UNIT_LENGTH && internalTarget.matches(target)) {
                "Wikimedia chapter target is not a bounded internal page reference"
            }
            require(
                label.isNotBlank() && label.length <= MAX_UNIT_LENGTH &&
                    !label.contains('[') && !label.contains(']'),
            ) { "Wikimedia chapter label is invalid" }
            require(units.size < MAX_ENTRIES) { "Wikimedia chapter list exceeds the entry limit" }
            val individuallyLabeledSpecial =
                specialChapter.matches(label) && !ambiguousSpecialSequence.containsMatchIn(label)
            val kind = when {
                chapterNumber.matches(label) -> InventoryClaimKind.INDIVIDUAL
                chapterRange.matches(label) -> InventoryClaimKind.RELEASE_RANGE
                individuallyLabeledSpecial -> InventoryClaimKind.INDIVIDUAL
                else -> InventoryClaimKind.AMBIGUOUS
            }
            val category = when (kind) {
                InventoryClaimKind.RELEASE_RANGE -> "chapter-range"
                InventoryClaimKind.AMBIGUOUS -> "unclassified"
                else -> if (individuallyLabeledSpecial) "special-chapter" else "chapter"
            } + if (table) "-table-entry" else "-list-entry"
            units += ParsedUnit(target, label, kind, category)
        }

        // Only an exact, explicitly selected Chapters section is interpreted.
        // Lead text and later sections are ignored; unknown syntax inside it fails closed.
        for (rawLine in wikitext.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("=")) {
                require(!inTable) { "A Wikimedia chapter table must close before another heading" }
                when {
                    line == "== Chapters ==" -> {
                        require(!foundChapterSection) { "Duplicate Chapters sections are ambiguous" }
                        foundChapterSection = true
                        inChapterSection = true
                    }
                    inChapterSection -> {
                        require(otherHeading.matches(line)) { "Unsupported nested chapter heading" }
                        inChapterSection = false
                    }
                }
                continue
            }
            if (!inChapterSection) continue

            if (line == "{| class=\"wikitable\"") {
                require(!inTable) { "Nested Wikimedia chapter tables are unsupported" }
                inTable = true
                hasTableHeader = false
                rowStarted = false
                rowHasChapter = false
                continue
            }

            if (inTable) {
                when {
                    line.startsWith("! ") -> {
                        require(!hasTableHeader && !rowStarted && line.startsWith("! Chapter !! ")) {
                            "A chapter table must declare the first column as Chapter"
                        }
                        require(plainTableCell.matches(line.removePrefix("! Chapter !! "))) {
                            "Unsupported Wikimedia chapter table header"
                        }
                        hasTableHeader = true
                    }
                    line == "|-" -> {
                        require(hasTableHeader && (!rowStarted || rowHasChapter)) {
                            "Every Wikimedia table row must identify an individual linked observation"
                        }
                        rowStarted = true
                        rowHasChapter = false
                    }
                    line.startsWith("| ") -> {
                        require(rowStarted && !rowHasChapter) { "Unsupported table row structure" }
                        val endOfLink = line.indexOf("]] || ")
                        require(endOfLink > 4) { "Chapter cell must be an explicit link" }
                        val markup = line.substring(2, endOfLink + 2)
                        val notes = line.substring(endOfLink + 6)
                        require(plainTableCell.matches(notes)) { "Unsupported table notes or wiki markup" }
                        addLinkedUnit(markup, table = true)
                        rowHasChapter = true
                    }
                    line == "|}" -> {
                        require(hasTableHeader && rowStarted && rowHasChapter) {
                            "Selected chapter table contains an incomplete row"
                        }
                        inTable = false
                    }
                    else -> error("Unsupported Wikimedia table syntax in selected Chapters section")
                }
                continue
            }

            require(line.startsWith("* ")) { "Only explicitly linked bullets or chapter tables are supported" }
            addLinkedUnit(line.substring(2), table = false)
        }
        require(foundChapterSection) { "Selected revision has no exact Chapters heading" }
        require(!inTable) { "Selected Wikimedia chapter table was not closed" }
        return units
    }

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private data class ParsedUnit(
        val target: String,
        val label: String,
        val kind: InventoryClaimKind,
        val category: String,
    )

    private val SHA256_DIGEST = Regex("^sha256:[0-9a-f]{64}$")
}

internal fun String.sha256SnapshotDigest(): String {
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "sha256:$hash"
}
