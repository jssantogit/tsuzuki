package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class MangaAllMangaDotNativeCoverageTest {
    private val provider = ProviderId("app.tsuzuki.mangaall")
    private val binding = ProviderBindingRef(provider, "en", "mangadot:42")
    private val candidate = ProviderWorkCandidate(
        externalWorkId = binding.externalWorkId,
        title = "Fixture Work",
        url = "https://mangadot.net/manga/42",
        authors = listOf("Fixture Creator"),
        detailOutcome = ProviderWorkDetailOutcome.VERIFIED_NO_ALIASES,
    )
    private val row = ProviderChapterObservation(
        providerChapterId = "mangadot:uploads:1",
        rawLabel = "Ch. 1",
        rawNumber = 1.0,
        language = "en",
    )

    @Test
    fun `native first page contributes only explicit bounded numeric facet evidence`() {
        val page = ProviderPage(
            listOf(
                row,
                row.copy(providerChapterId = "mangadot:chapters:2", rawNumber = 2.125),
                row.copy(providerChapterId = "mangadot:uploads:3", rawNumber = 99.0, language = null),
            ),
            ProviderCursor("next"),
        )
        val observed = MangaAllMangaDotNativeCoverage.fromFirstPage(11L, binding, candidate, page)
        observed?.coverageOutcome shouldBe ProviderCoverageOutcome.OBSERVED
        observed?.coverageObservedCount shouldBe 2
        observed?.coverageMinMilli shouldBe 1_000
        observed?.coverageMaxMilli shouldBe 2_125
        observed?.coverageHasMore shouldBe true
        observed?.authors shouldBe candidate.authors
        // The source portfolio is intentionally not enabled by this helper.
        val trusted = ProviderWorkSourcePortfolio.trustedCandidates(provider, "en", listOf(requireNotNull(observed)))
        trusted shouldBe emptyList()
    }

    @Test
    fun `native preview never accepts another provider version work or unverified metadata`() {
        val page = ProviderPage(listOf(row), null)
        MangaAllMangaDotNativeCoverage.fromFirstPage(10L, binding, candidate, page) shouldBe null
        MangaAllMangaDotNativeCoverage.fromFirstPage(12L, binding, candidate, page) shouldBe null
        MangaAllMangaDotNativeCoverage.fromFirstPage(
            11L,
            binding.copy(providerId = ProviderId("org.example.reader")),
            candidate,
            page,
        ) shouldBe null
        MangaAllMangaDotNativeCoverage.fromFirstPage(
            11L,
            binding.copy(externalWorkId = "mangadot:43"),
            candidate,
            page,
        ) shouldBe null
        for (invalid in listOf(
            candidate.copy(authors = emptyList()),
            candidate.copy(detailOutcome = ProviderWorkDetailOutcome.DETAIL_FAILED),
            candidate.copy(url = "https://mangadot.net/manga/43"),
        )) {
            MangaAllMangaDotNativeCoverage.fromFirstPage(11L, binding, invalid, page) shouldBe null
        }
    }

    @Test
    fun `native preview rejects duplicate foreign and structurally invalid chapter rows`() {
        val invalidPages = listOf(
            ProviderPage(emptyList(), null),
            ProviderPage(listOf(row, row), null),
            ProviderPage(listOf(row.copy(providerChapterId = "mangadex:other")), null),
            ProviderPage(listOf(row.copy(language = "pt-br")), null),
            ProviderPage(listOf(row.copy(language = null)), null),
            ProviderPage(listOf(row.copy(rawNumber = -1.0)), null),
            ProviderPage(listOf(row.copy(rawNumber = 1.0001)), null),
            ProviderPage(listOf(row), null, listOf(ProviderCursor("parallel"))),
        )
        invalidPages.forEach {
            MangaAllMangaDotNativeCoverage.fromFirstPage(11L, binding, candidate, it) shouldBe null
        }
    }
}
