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
}
