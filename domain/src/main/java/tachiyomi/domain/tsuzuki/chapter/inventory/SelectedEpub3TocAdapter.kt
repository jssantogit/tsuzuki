package tachiyomi.domain.tsuzuki.chapter.inventory

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.XMLReader
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import javax.xml.parsers.SAXParserFactory

/**
 * Shared bounded-document guard before Android/JVM XML parsing.
 * DTDs and entity declarations are forbidden: no external or recursive entity
 * can be introduced into either metadata DOM or TOC SAX readers.
 */
object SelectedEpubXmlPreflight {
    private val forbiddenDeclaration = Regex("""<!\s*(?:DOCTYPE|ENTITY)\b""", RegexOption.IGNORE_CASE)

    fun rejectExternalDeclarations(xml: String) {
        require(!forbiddenDeclaration.containsMatchIn(xml)) {
            "Selected EPUB XML cannot declare DTD or entities"
        }
    }
}

/** A single, explicitly selected EPUB3 nav XHTML document and edition snapshot. */
data class SelectedEpub3Navigation(
    val scope: InventoryScope,
    val editionReference: String,
    val snapshotRevision: String,
    val snapshotDigest: String,
    val navXhtml: String,
    val observedAt: Long,
)

/**
 * Deterministic adapter for caller-selected EPUB3 navigation XHTML only.
 * It does not open archives, scan files, access page content, or bypass DRM.
 */
object SelectedEpub3TocAdapter {
    const val MAX_DOCUMENT_BYTES = 512 * 1024
    const val MAX_ENTRIES = 512
    const val MAX_DEPTH = 32
    private const val XHTML_NAMESPACE = "http://www.w3.org/1999/xhtml"
    private const val EPUB_OPS_NAMESPACE = "http://www.idpf.org/2007/ops"
    private const val MAX_LABEL_LENGTH = 512
    private const val MAX_HREF_LENGTH = 1024
    private val chapterNumber = Regex("^(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?$")
    private val namedChapter = Regex(
        """(?i)^(?:chapter|ch\.?|episode|ep\.?)\s+(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:\s*[:–—-]\s*[\p{L}][^\r\n]*)?$""",
    )
    private val specialChapter = Regex(
        """(?i)^(?:special chapter|bonus chapter)(?:\s+(?:0|[1-9][0-9]*)(?:\.[0-9]+)?|\s*[:#–—-]\s*[\p{L}][^\r\n]*)?$""",
    )
    private val safeEditionReference = Regex("^(?:selected-edition|user-selected):[A-Za-z0-9._:-]{1,192}$")
    private val safeSnapshotRevision = Regex("^[A-Za-z0-9._:-]{1,128}$")
    private val sha256Digest = Regex("^sha256:[0-9a-f]{64}$")

    fun adapt(selected: SelectedEpub3Navigation): Result<InventoryEvidenceBatch> =
        try {
            Result.success(adaptSelected(selected))
        } catch (error: Exception) {
            Result.failure(error)
        }

    private fun adaptSelected(selected: SelectedEpub3Navigation): InventoryEvidenceBatch {
        validateSelection(selected)
        val digest = selected.navXhtml.sha256SnapshotDigest()
        require(selected.snapshotDigest == digest) { "EPUB navigation digest does not match selected bytes" }
        SelectedEpubXmlPreflight.rejectExternalDeclarations(selected.navXhtml)
        val handler = TocHandler()
        val reader = secureReader()
        reader.contentHandler = handler
        reader.entityResolver = EntityResolver { _, _ ->
            throw SAXException("External XML entity resolution is disabled")
        }
        reader.errorHandler = object : DefaultHandler() {
            override fun fatalError(error: SAXParseException): Unit = throw error
        }
        reader.parse(InputSource(StringReader(selected.navXhtml)))
        require(handler.tocCount == 1) { "Exactly one EPUB3 toc navigation is required" }
        require(handler.items.isNotEmpty()) { "Selected EPUB3 toc has no navigation entries" }

        val claims = handler.items.map { item ->
            val (kind, category) = classify(item.label)
            val sourceRecordId = "epub3:${selected.snapshotRevision}:${sha256Short(item.href)}"
            InventoryClaim(
                sourceRecordId = sourceRecordId,
                label = item.label,
                volumeLabel = null,
                language = selected.scope.language,
                kind = kind,
                derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
                upstreamSourceId = selected.snapshotRevision,
                observedAt = selected.observedAt,
                provenance = InventoryCandidateProvenance(
                    sourceKind = InventoryCandidateSourceKind.USER_SELECTED_EPUB3_TOC,
                    originReference = selected.editionReference,
                    exactRevision = selected.snapshotRevision,
                    snapshotDigest = digest,
                    sourceUnitId = item.href,
                    structuralCategory = category,
                ),
            )
        }
        require(claims.map { it.sourceRecordId }.distinct().size == claims.size) {
            "Duplicate EPUB navigation unit references are ambiguous"
        }
        return InventoryEvidenceBatch(
            scope = selected.scope,
            pageKey = "snapshot:${selected.snapshotRevision}",
            observedAt = selected.observedAt,
            outcome = InventoryFetchOutcome.PARTIAL,
            claims = claims,
        )
    }

    private fun validateSelection(selected: SelectedEpub3Navigation) {
        val documentBytes = selected.navXhtml.toByteArray(UTF_8)
        require(documentBytes.isNotEmpty() && documentBytes.size <= MAX_DOCUMENT_BYTES) {
            "EPUB navigation document exceeds the byte limit"
        }
        require(selected.scope.producerId == "epub3-user-selected") {
            "Unexpected selected EPUB producer"
        }
        require(!selected.scope.editionKey.isNullOrBlank() && !selected.scope.language.isNullOrBlank()) {
            "An exact selected edition and language are required"
        }
        require(safeEditionReference.matches(selected.editionReference)) {
            "EPUB origin must be an opaque selected-edition reference, not a file path"
        }
        require(safeSnapshotRevision.matches(selected.snapshotRevision)) {
            "EPUB snapshot revision must be an opaque bounded identifier"
        }
        require(selected.observedAt >= 0L)
        require(sha256Digest.matches(selected.snapshotDigest))
    }

    private fun secureReader(): XMLReader {
        // Android's SAX factory does not implement desktop JAXP XInclude and
        // secure-processing setters. The exact bounded text is preflighted
        // before parse, and the XMLReader's EntityResolver refuses external IO.
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newSAXParser().xmlReader
    }

    private fun classify(label: String): Pair<InventoryClaimKind, String> = when {
        chapterNumber.matches(label) || namedChapter.matches(label) ->
            InventoryClaimKind.INDIVIDUAL to "toc-chapter"
        specialChapter.matches(label) -> InventoryClaimKind.INDIVIDUAL to "toc-special-chapter"
        else -> InventoryClaimKind.AMBIGUOUS to "toc-navigation-ambiguous"
    }

    /** Shared EPUB2/EPUB3 local-only href validator; no network or traversal. */
    internal fun safeLocalReference(rawHref: String): String {
        require(rawHref.isNotBlank() && rawHref.length <= MAX_HREF_LENGTH) { "EPUB nav href is invalid" }
        require(!rawHref.contains('\\') && !rawHref.startsWith('/') && !rawHref.startsWith("//")) {
            "Absolute EPUB references are not permitted"
        }
        val uri = URI(rawHref)
        require(uri.scheme == null && uri.rawAuthority == null && !uri.isAbsolute && uri.rawQuery == null) {
            "External EPUB references are not permitted"
        }
        val decodedHref = URLDecoder.decode(rawHref.replace("+", "%2B"), UTF_8.name())
        require(decodedHref.none { it == '\\' || it.code < 0x20 || it.code in 0x7f..0x9f }) {
            "Encoded control and backslash characters are not permitted"
        }
        require(!Regex("%[0-9a-fA-F]{2}").containsMatchIn(decodedHref)) {
            "Nested encoded EPUB references are ambiguous"
        }
        val decodedUri = URI(decodedHref)
        require(decodedUri.scheme == null && decodedUri.rawAuthority == null && decodedUri.rawQuery == null) {
            "Encoded external EPUB references are not permitted"
        }
        val decodedPath = URLDecoder.decode(uri.rawPath.orEmpty().replace("+", "%2B"), UTF_8.name())
        require(decodedPath.isNotBlank() && !decodedPath.startsWith('/')) {
            "Absolute EPUB references are not permitted"
        }
        require(decodedPath.split('/').none { it == ".." || it == "." || it.isEmpty() }) {
            "EPUB href traversal is not permitted"
        }
        require(uri.fragment == null || uri.fragment.isNotBlank()) { "Empty EPUB fragment is not permitted" }
        return rawHref
    }

    private fun sha256Short(value: String): String =
        value.sha256SnapshotDigest().removePrefix("sha256:").take(24)

    private class TocHandler : DefaultHandler() {
        var tocCount: Int = 0
            private set
        val items = mutableListOf<TocItem>()
        private var xmlDepth = 0
        private var tocDepth: Int? = null
        private var activeLink: ActiveLink? = null
        private val elements = mutableListOf<ElementFrame>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            xmlDepth++
            require(xmlDepth <= MAX_DEPTH) { "EPUB navigation exceeds the XML depth limit" }
            require(uri != "http://www.w3.org/2001/XInclude") {
                "EPUB navigation cannot contain XInclude elements"
            }
            val name = localName.orEmpty().ifBlank { qName.orEmpty().substringAfter(':') }
            val parent = elements.lastOrNull()
            val isToc =
                name == "nav" &&
                    uri == XHTML_NAMESPACE &&
                    attributes.getValue(EPUB_OPS_NAMESPACE, "type") == "toc"
            if (isToc) {
                tocCount++
                require(tocCount == 1) { "Multiple EPUB3 toc navigation elements are ambiguous" }
                tocDepth = xmlDepth
            } else if (tocDepth != null) {
                require(uri == XHTML_NAMESPACE) { "Only XHTML elements are supported in the EPUB toc" }
                when (name) {
                    "ol" -> require(parent?.name == "nav" || parent?.name == "li") {
                        "EPUB toc lists must be nested under nav or li elements"
                    }
                    "li" -> require(parent?.name == "ol") { "EPUB toc items must belong to an ordered list" }
                    "a" -> {
                        require(parent?.name == "li") { "EPUB toc anchors must belong to a list item" }
                        require(activeLink == null) { "Nested EPUB navigation links are unsupported" }
                        val href = attributes.getValue("", "href")
                            ?: error("EPUB toc anchor has no unqualified href")
                        activeLink = ActiveLink(xmlDepth, safeLocalReference(href))
                        require(items.size + 1 <= MAX_ENTRIES) { "EPUB toc exceeds the entry limit" }
                    }
                    "h1" -> require(parent != null && parent.name == "nav" && parent.depth == tocDepth) {
                        "EPUB toc heading must be a nav child"
                    }
                    "span" -> require(parent?.name == "a") { "EPUB toc spans must be inside an anchor" }
                    "nav" -> error("Nested EPUB navigation is unsupported")
                    else -> error("Unsupported XHTML element in selected EPUB toc: $name")
                }
            }
            elements += ElementFrame(name, uri.orEmpty(), xmlDepth)
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            activeLink?.let { link ->
                if (link.label.length + length > MAX_LABEL_LENGTH) {
                    throw SAXException("EPUB toc label exceeds the text limit")
                }
                link.label.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = localName.orEmpty().ifBlank { qName.orEmpty().substringAfter(':') }
            val current = elements.lastOrNull()
            require(current != null && current.name == name && current.namespace == uri.orEmpty()) {
                "EPUB toc element namespace or structure changed during parsing"
            }
            val link = activeLink
            if (name == "a" && link != null && link.depth == xmlDepth) {
                val label = link.label.toString().trim()
                require(label.isNotBlank()) { "EPUB toc label is empty" }
                items += TocItem(link.href, label)
                activeLink = null
            }
            if (name == "nav" && tocDepth == xmlDepth) tocDepth = null
            elements.removeAt(elements.lastIndex)
            xmlDepth--
        }
    }

    private data class ElementFrame(val name: String, val namespace: String, val depth: Int)

    private data class TocItem(val href: String, val label: String)

    private class ActiveLink(val depth: Int, val href: String) {
        val label = StringBuilder()
    }
}
