package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** All embedded source documents are synthetic fixtures, not evidence of real content or permission. */
class SelectedInventoryCandidateAdapterTest {
    @Test
    fun `selected exact Wikimedia page revision is a partial community candidate fixture`() {
        val selection = syntheticWikimediaRevision()

        val batch = SelectedWikimediaChapterIndexAdapter.adapt(selection).getOrThrow()

        batch.scope shouldBe selection.scope
        batch.pageKey shouldBe "wikimedia:123:77001"
        batch.outcome shouldBe InventoryFetchOutcome.PARTIAL
        batch.claims.map { it.label }.shouldContainExactly("1", "1.1")
        batch.claims.all { it.kind == InventoryClaimKind.INDIVIDUAL } shouldBe true
        batch.claims.all { it.derivation == InventoryDerivation.COMMUNITY_CURATED } shouldBe true
        batch.claims.none { InventoryClaimPolicy.mayPromote(batch.scope, it) } shouldBe true
        batch.claims.first().provenance shouldBe InventoryCandidateProvenance(
            sourceKind = InventoryCandidateSourceKind.WIKIMEDIA_SELECTED_PAGE_REVISION,
            originReference = selection.pageReference,
            exactRevision = "77001",
            attributionReference = selection.attributionReference,
            licenseReference = selection.licenseReference,
            snapshotDigest = selection.snapshotDigest,
            sourceUnitId = "Chapter_1",
            structuralCategory = "chapter-list-entry",
        )
    }

    @Test
    fun `Wikimedia page and revision must match the host selected identifiers`() {
        val selection = syntheticWikimediaRevision()
        val wrongPage = selection.copy(
            responseJson = selection.responseJson.replace("\"pageid\":123", "\"pageid\":124"),
        )
        val wrongRevision = selection.copy(
            responseJson = selection.responseJson.replace("\"revid\":77001", "\"revid\":77002"),
        )

        SelectedWikimediaChapterIndexAdapter.adapt(wrongPage).isFailure shouldBe true
        SelectedWikimediaChapterIndexAdapter.adapt(wrongRevision).isFailure shouldBe true
    }

    @Test
    fun `Wikimedia ranges remain one nonindividual observation and unsupported structures fail`() {
        val range = SelectedWikimediaChapterIndexAdapter.adapt(
            syntheticWikimediaRevision("== Chapters ==\n* [[Chapters 1–5|1–5]]"),
        ).getOrThrow()
        val unsupported = SelectedWikimediaChapterIndexAdapter.adapt(
            syntheticWikimediaRevision("== Summary ==\n* [[Chapter 1|1]]"),
        )

        range.claims.size shouldBe 1
        range.claims.single().kind shouldBe InventoryClaimKind.RELEASE_RANGE
        range.claims.none { InventoryClaimPolicy.isNonEditorialCandidate(it) } shouldBe true
        unsupported.isFailure shouldBe true
    }

    @Test
    fun `Wikimedia response beyond the byte budget is rejected`() {
        val oversized = syntheticWikimediaRevision(
            "x".repeat(SelectedWikimediaChapterIndexAdapter.MAX_SOURCE_BYTES + 1),
        )

        SelectedWikimediaChapterIndexAdapter.adapt(oversized).isFailure shouldBe true
    }

    @Test
    fun `selected EPUB toc preserves decimal and special units but excludes front matter and landmarks`() {
        val selected = syntheticEpubNavigation()

        val batch = SelectedEpub3TocAdapter.adapt(selected).getOrThrow()
        val candidates = batch.claims.filter { InventoryClaimPolicy.isNonEditorialCandidate(it) }

        batch.scope shouldBe selected.scope
        batch.outcome shouldBe InventoryFetchOutcome.PARTIAL
        candidates.map { it.label }.shouldContainExactly("12.05", "12.5", "0.01", "0.1", "Special Chapter: Bonus")
        candidates.mapNotNull { it.provenance?.sourceUnitId }.shouldContainExactly(
            "Text/ch-1205.xhtml#chapter-1205",
            "Text/ch-125.xhtml#chapter-125",
            "Text/ch-001.xhtml#chapter-001",
            "Text/ch-01.xhtml#chapter-01",
            "Text/special.xhtml#bonus",
        )
        batch.claims.none { it.label == "Preface" && InventoryClaimPolicy.isNonEditorialCandidate(it) } shouldBe true
        batch.claims.none { it.label == "Cover" && InventoryClaimPolicy.isNonEditorialCandidate(it) } shouldBe true
        batch.claims.all { it.derivation == InventoryDerivation.USER_SUPPLIED_EDITION_TOC } shouldBe true
        batch.claims.none { InventoryClaimPolicy.mayPromote(batch.scope, it) } shouldBe true
    }

    @Test
    fun `EPUB toc rejects doctypes external absolute and traversing references`() {
        val selected = syntheticEpubNavigation()
        val unsafeDocuments = listOf(
            "<!DOCTYPE html [<!ENTITY xxe SYSTEM \"file:///synthetic/private.txt\">]>$TOC_DOCUMENT",
            tocWithHref("https://external.invalid/chapter.xhtml"),
            tocWithHref("/Text/chapter.xhtml"),
            tocWithHref("../Text/chapter.xhtml"),
        )

        unsafeDocuments.forEach { document ->
            SelectedEpub3TocAdapter.adapt(selected.copy(navXhtml = document)).isFailure shouldBe true
        }
    }

    @Test
    fun `EPUB navigation rejects document entry and nesting limits`() {
        val selected = syntheticEpubNavigation()
        val tooLarge = selected.copy(navXhtml = "x".repeat(SelectedEpub3TocAdapter.MAX_DOCUMENT_BYTES + 1))
        val tooManyEntries = selected.copy(
            navXhtml = tocWithItems(SelectedEpub3TocAdapter.MAX_ENTRIES + 1),
        )
        val tooDeep = selected.copy(
            navXhtml = "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
                "xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>" +
                "<nav epub:type=\"toc\"><ol>" +
                "<li><ol>".repeat(SelectedEpub3TocAdapter.MAX_DEPTH + 1) +
                "<li><a href=\"Text/ch.xhtml\">Chapter 1</a></li>" +
                "</ol></li>".repeat(SelectedEpub3TocAdapter.MAX_DEPTH + 1) +
                "</ol></nav></body></html>",
        )

        listOf(tooLarge, tooManyEntries, tooDeep).forEach { input ->
            SelectedEpub3TocAdapter.adapt(input).isFailure shouldBe true
        }
    }

    private fun syntheticWikimediaRevision(wikitext: String = WIKITEXT): SelectedWikimediaRevision {
        val response = """
            {"batchcomplete":"","query":{"pages":[
              {"pageid":123,"title":"Synthetic index","revisions":[
                {"revid":77001,"slots":{"main":{"contentmodel":"wikitext",
                "contentformat":"text/x-wiki","content":${wikitext.toJsonString()}}}}
              ]}
            ]}}
        """.trimIndent()
        return SelectedWikimediaRevision(
            scope = InventoryScope(
                canonicalTitleId = "selected-title",
                producerId = "wikimedia",
                externalWorkId = "selected-work",
                editionKey = "selected-edition-ja",
                language = "ja",
                workIdentityVerified = true,
                editionIdentityVerified = true,
            ),
            selectedPageId = 123L,
            selectedRevisionId = 77001L,
            pageReference = "https://example.invalid/wiki/Synthetic_index?oldid=77001",
            responseJson = response,
            observedAt = 42L,
            snapshotDigest = "sha256:${"0".repeat(64)}",
            attributionReference = "Synthetic fixture attribution; not a Wikimedia claim",
            licenseReference = "Synthetic fixture license reference; not a permission claim",
        )
    }

    private fun syntheticEpubNavigation() = SelectedEpub3Navigation(
        scope = InventoryScope(
            canonicalTitleId = "selected-title",
            producerId = "epub3-user-selected",
            externalWorkId = "selected-work",
            editionKey = "selected-epub-edition",
            language = "ja",
            workIdentityVerified = true,
            editionIdentityVerified = true,
        ),
        editionReference = "selected-edition:sha256:${"1".repeat(64)}",
        snapshotRevision = "local-snapshot-7",
        snapshotDigest = "sha256:${"1".repeat(64)}",
        navXhtml = TOC_DOCUMENT,
        observedAt = 43L,
    )

    private fun tocWithHref(href: String) =
        "<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
            "xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol>" +
            "<li><a href=\"$href\">Chapter 1</a></li></ol></nav></body></html>"

    private fun tocWithItems(count: Int): String =
        "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body>" +
            "<nav epub:type=\"toc\"><ol>" +
            (1..count).joinToString("") { "<li><a href=\"Text/$it.xhtml\">Chapter $it</a></li>" } +
            "</ol></nav></body></html>"

    private fun String.toJsonString(): String = "\"" +
        replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") +
        "\""

    private companion object {
        const val WIKITEXT = "== Chapters ==\n* [[Chapter_1|1]]\n* [[Chapter_1_1|1.1]]"
        val TOC_DOCUMENT = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc"><h1>Contents</h1><ol>
                  <li><a href="Text/preface.xhtml">Preface</a></li>
                  <li><a href="Text/ch-1205.xhtml#chapter-1205">12.05</a></li>
                  <li><a href="Text/ch-125.xhtml#chapter-125">12.5</a></li>
                  <li><a href="Text/ch-001.xhtml#chapter-001">0.01</a></li>
                  <li><a href="Text/ch-01.xhtml#chapter-01">0.1</a></li>
                  <li><a href="Text/special.xhtml#bonus">Special Chapter: Bonus</a></li>
                </ol></nav>
                <nav epub:type="landmarks"><ol>
                  <li><a href="Text/cover.xhtml">Cover</a></li>
                </ol></nav>
              </body>
            </html>
        """.trimIndent()
    }
}
