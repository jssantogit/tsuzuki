package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

class ProviderEditionCandidateMetadataTest {

    private val ref = ProviderBindingRef(
        providerId = ProviderId("app.tsuzuki.mangafire"),
        facetId = "en",
        externalWorkId = "jjrxn",
    )

    @Test
    fun `source declared chapter count and creators survive into edition picker`() {
        val source = ProviderWorkCandidate(
            externalWorkId = "jjrxn",
            title = "Bleach",
            language = "en",
            chapterCount = 742,
            authors = listOf("Tite Kubo"),
            coverageOutcome = ProviderCoverageOutcome.OBSERVED,
            coverageObservedCount = 200,
            coverageMinMilli = 1000,
            coverageMaxMilli = 200000,
            coverageHasMore = true,
        )
        val candidate = editionCandidateFromLookup(
            ref = ref,
            providerName = "MangaFire",
            work = source,
            query = "Bleach",
            providerVersionCode = 13,
            configurationFingerprint = "fingerprint",
        )

        candidate.chapterCount shouldBe 742
        candidate.observedChapterCount shouldBe 200
        candidate.coverageHasMore shouldBe true
        candidate.coverageMinMilli shouldBe 1000
        candidate.coverageMaxMilli shouldBe 200000
        candidate.authors shouldBe listOf("Tite Kubo")
        candidate.language shouldBe "en"
    }

    @Test
    fun `unknown full count is not inferred from first page or missing source metadata`() {
        val source = ProviderWorkCandidate(
            externalWorkId = "jjrxn",
            title = "Bleach",
            language = null,
            coverageOutcome = ProviderCoverageOutcome.OBSERVED,
            coverageObservedCount = 200,
            coverageMinMilli = 1000,
            coverageMaxMilli = 200000,
            coverageHasMore = true,
        )
        val candidate = editionCandidateFromLookup(
            ref = ref,
            providerName = "MangaFire",
            work = source,
            query = "Bleach",
            providerVersionCode = 13,
            configurationFingerprint = "fingerprint",
        )

        candidate.chapterCount shouldBe null
        candidate.observedChapterCount shouldBe 200
        candidate.coverageHasMore shouldBe true
        candidate.authors shouldBe emptyList()
        candidate.language shouldBe "en"
    }

    @Test
    fun `MangaAll candidate exposes content source but never invents language from lookup facet`() {
        val mangaAllRef = ProviderBindingRef(
            providerId = ProviderId("app.tsuzuki.mangaall"),
            facetId = "pt-br",
            externalWorkId = "mangadex:11111111-2222-4333-8444-555555555555",
        )
        val unknownLanguage = editionCandidateFromLookup(
            ref = mangaAllRef,
            providerName = "MangaAll",
            work = ProviderWorkCandidate(
                externalWorkId = mangaAllRef.externalWorkId,
                title = "Chainsaw Man",
                language = null,
            ),
            query = "Chainsaw Man",
            providerVersionCode = 1,
            configurationFingerprint = "signed",
        )
        unknownLanguage.contentSourceId shouldBe "mangadex"
        unknownLanguage.language shouldBe null
        unknownLanguage.ref.facetId shouldBe "pt-br"
        val attested = editionCandidateFromLookup(
            ref = mangaAllRef.copy(externalWorkId = "mangadot:317"),
            providerName = "MangaAll",
            work = ProviderWorkCandidate(
                externalWorkId = "mangadot:317",
                title = "Chainsaw Man",
                language = "pt-br",
            ),
            query = "Chainsaw Man",
            providerVersionCode = 1,
            configurationFingerprint = "signed",
        )
        attested.contentSourceId shouldBe "mangadot"
        attested.language shouldBe "pt-br"
        val legacy = editionCandidateFromLookup(
            ref = ref,
            providerName = "MangaFire",
            work = ProviderWorkCandidate(externalWorkId = "jjrxn", title = "Bleach"),
            query = "Bleach",
            providerVersionCode = 13,
            configurationFingerprint = "signed",
        )
        legacy.contentSourceId shouldBe null
        legacy.language shouldBe "en"
    }

    @Test
    fun `edition picker rejects unrelated fuzzy matches but keeps close editions`() {
        relevantEditionCandidateTitle("Vagabond", "Patati patata") shouldBe false
        relevantEditionCandidateTitle("Vagabond", "PATATI PATATA") shouldBe false
        relevantEditionCandidateTitle("Vagabond", "Vagabond") shouldBe true
        relevantEditionCandidateTitle("Vagabond", "Vagabond (Hong Kong Colored Version)") shouldBe true
        relevantEditionCandidateTitle("One Punch Man", "One Piece") shouldBe false
        relevantEditionCandidateTitle("One Punch Man", "One-Punch Man") shouldBe true
        relevantEditionCandidateTitle("Chainsaw Man", "Chainsaw") shouldBe false
        relevantEditionCandidateTitle("Kimetsu no Yaiba", "Kimetsu no Yaiba: Gaiden") shouldBe true
        relevantEditionCandidateTitle("Bleach", "Bleach: Thousand-Year Blood War") shouldBe true
        relevantEditionCandidateTitle("", "Vagabond") shouldBe false
    }

    @Test
    fun `changing counts does not invalidate exact confirmed source identity`() {
        val original = ProviderEditionCandidate(
            ref = ref,
            providerName = "MangaFire",
            title = "Bleach",
            url = "https://mangafire.to/title/jjrxn-bleach",
            query = "Bleach",
            providerVersionCode = 13,
            configurationFingerprint = "fingerprint",
            chapterCount = 742,
            authors = listOf("Tite Kubo"),
            language = "en",
        )
        val refreshed = original.copy(chapterCount = 743, authors = emptyList(), observedChapterCount = null)

        original.sameSourceIdentityAs(refreshed) shouldBe true
        original.sameSourceIdentityAs(
            refreshed.copy(ref = ref.copy(externalWorkId = "027")),
        ) shouldBe false
        original.sameSourceIdentityAs(refreshed.copy(url = "https://mangafire.to/title/027-bleach")) shouldBe false
    }

    @Test
    fun `coverage range is preserved for MangaAll without implying a complete chapter inventory`() {
        val candidate = editionCandidateFromLookup(
            ref = ProviderBindingRef(
                providerId = ProviderId("app.tsuzuki.mangaall"),
                facetId = "pt-br",
                externalWorkId = "mangadex:sample",
            ),
            providerName = "MangaAll",
            work = ProviderWorkCandidate(
                externalWorkId = "mangadex:sample",
                title = "Vagabond",
                coverageOutcome = ProviderCoverageOutcome.OBSERVED,
                coverageObservedCount = 2,
                coverageMinMilli = 67000,
                coverageMaxMilli = 67100,
                coverageHasMore = true,
            ),
            query = "Vagabond",
            providerVersionCode = 3,
            configurationFingerprint = "signed",
        )
        candidate.chapterCount shouldBe null
        candidate.observedChapterCount shouldBe 2
        candidate.coverageMinMilli shouldBe 67000
        candidate.coverageMaxMilli shouldBe 67100
    }
}
