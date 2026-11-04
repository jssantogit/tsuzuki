package tachiyomi.domain.tsuzuki.chapter.inventory

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** User-selected EPUB2/NCX only: no provider, publisher authority or reader pages. */
data class SelectedEpub2Navigation(
    val scope: InventoryScope,
    val editionReference: String,
    val snapshotRevision: String,
    val snapshotDigest: String,
    val ncxXml: String,
    val observedAt: Long,
)

object SelectedEpub2NcxAdapter {
    const val MAX_DOCUMENT_BYTES = 512 * 1024
    private const val MAX_ENTRIES = 512
    private const val MAX_DEPTH = 32
    private const val MAX_LABEL_LENGTH = 512
    private const val NCX_NS = "http://www.daisy.org/z3986/2005/ncx/"
    private val numericLabel = Regex("""^(?:0|[1-9][0-9]*)$""")

    // Only an explicit terminal chapter marker (with optional work-title prefix)
    // may be normalized as an individual candidate; other labels remain ambiguous.
    private val namedChapter = Regex(
        """(?i)^(?:.{1,160}\s+)?(?:chapter|ch\.?|cap\.?|episode|ep\.?)\s*0*([0-9]{1,9})$""",
    )

    fun adapt(selected: SelectedEpub2Navigation): Result<InventoryEvidenceBatch> = runCatching {
        require(selected.scope.producerId == "epub2-user-selected")
        require(!selected.scope.editionKey.isNullOrBlank() && !selected.scope.language.isNullOrBlank())
        require(selected.observedAt >= 0L)
        require(Regex("^user-selected:[A-Za-z0-9._:-]{1,192}$").matches(selected.editionReference))
        require(Regex("^[0-9a-f]{64}$").matches(selected.snapshotRevision))
        require(Regex("^sha256:[0-9a-f]{64}$").matches(selected.snapshotDigest))
        require(selected.ncxXml.toByteArray(UTF_8).size in 1..MAX_DOCUMENT_BYTES)
        require(selected.ncxXml.sha256SnapshotDigest() == selected.snapshotDigest)
        SelectedEpubXmlPreflight.rejectExternalDeclarations(selected.ncxXml)

        // Bounded text, banned DTDs, and a rejecting entity resolver keep
        // Android's XML parser offline without unsupported JAXP flags.
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().apply {
                setEntityResolver { _, _ -> error("External NCX entities are forbidden") }
            }
        val doc = parser.parse(InputSource(StringReader(selected.ncxXml)))
        require(doc.doctype == null)
        require(doc.getElementsByTagNameNS("http://www.w3.org/2001/XInclude", "*").length == 0)
        val root = doc.documentElement
        require(root.namespaceURI == NCX_NS && root.localName == "ncx")
        val maps = root.getElementsByTagNameNS(NCX_NS, "navMap")
        require(maps.length == 1)
        val map = maps.item(0) as Element
        require(map.parentNode == root)
        val points = map.getElementsByTagNameNS(NCX_NS, "navPoint")
        require(points.length in 1..MAX_ENTRIES)
        val claims = (0 until points.length).map { index ->
            val point = points.item(index) as Element
            var depth = 0
            var ancestor = point.parentNode
            while (ancestor !== map) {
                require(
                    ancestor is Element && ancestor.namespaceURI == NCX_NS &&
                        ancestor.localName == "navPoint",
                ) { "NCX navigation hierarchy is ambiguous" }
                ancestor = ancestor.parentNode
                depth++
                require(depth <= MAX_DEPTH)
            }
            fun direct(name: String): Element {
                val elements = (0 until point.childNodes.length).mapNotNull { point.childNodes.item(it) as? Element }
                    .filter { it.namespaceURI == NCX_NS && it.localName == name }
                require(elements.size == 1) { "NCX navigation item is missing or duplicated" }
                return elements.single()
            }
            val navLabel = direct("navLabel")
            val texts = navLabel.getElementsByTagNameNS(NCX_NS, "text")
            require(texts.length == 1)
            val rawLabel = texts.item(0).textContent.trim()
            require(rawLabel.isNotBlank() && rawLabel.length <= MAX_LABEL_LENGTH)
            val href = SelectedEpub3TocAdapter.safeLocalReference(direct("content").getAttribute("src"))
            val normalized = numericLabel.matchEntire(rawLabel)?.value
                ?: namedChapter.matchEntire(rawLabel)?.groupValues?.get(1)?.toIntOrNull()?.toString()
            val isChapter = normalized != null && normalized.toIntOrNull() != null
            val label = if (isChapter) "Chapter " + normalized else rawLabel
            val memberDigest = MessageDigest.getInstance("SHA-256").digest(href.toByteArray(UTF_8))
                .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            InventoryClaim(
                sourceRecordId = "epub2:" + selected.snapshotRevision + ":" + memberDigest,
                label = label,
                volumeLabel = null,
                language = selected.scope.language,
                kind = if (isChapter) InventoryClaimKind.INDIVIDUAL else InventoryClaimKind.AMBIGUOUS,
                derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
                upstreamSourceId = selected.snapshotRevision,
                observedAt = selected.observedAt,
                provenance = InventoryCandidateProvenance(
                    sourceKind = InventoryCandidateSourceKind.USER_SELECTED_EPUB2_NCX,
                    originReference = selected.editionReference,
                    exactRevision = selected.snapshotRevision,
                    snapshotDigest = selected.snapshotDigest,
                    sourceUnitId = href,
                    structuralCategory = if (isChapter) "toc-chapter" else "toc-navigation-ambiguous",
                ),
            )
        }
        require(claims.map { it.sourceRecordId }.distinct().size == claims.size) {
            "Duplicate NCX links are ambiguous"
        }
        InventoryEvidenceBatch(
            scope = selected.scope,
            pageKey = "snapshot:" + selected.snapshotRevision,
            observedAt = selected.observedAt,
            outcome = InventoryFetchOutcome.PARTIAL,
            claims = claims,
        )
    }
}
