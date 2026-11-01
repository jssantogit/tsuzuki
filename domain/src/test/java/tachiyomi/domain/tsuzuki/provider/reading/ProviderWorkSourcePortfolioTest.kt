package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import tachiyomi.domain.tsuzuki.provider.ProviderId
import org.junit.jupiter.api.Test

class ProviderWorkSourcePortfolioTest {

    private val original = ProviderWorkCandidate(
        externalWorkId = "kx1p6",
        title = "Chainsaw Man",
        authors = listOf("Fujimoto Tatsuki"),
        detailOutcome = ProviderWorkDetailOutcome.VERIFIED_NO_ALIASES,
        coverageOutcome = ProviderCoverageOutcome.OBSERVED,
        coverageObservedCount = 97,
        coverageMinMilli = 1_000,
        coverageMaxMilli = 97_000,
        coverageHasMore = false,
    )

    @Test
    fun `same work with two readable HIDs preserves both identities without choosing an edition`() {
        val portfolio = ProviderWorkSourcePortfolio.assess(
            providerId = ProviderId("app.tsuzuki.mangafire"),
            facetId = "en",
            queryTitle = "Chainsaw Man",
            independentlyVerifiedAuthors = setOf("Tatsuki Fujimoto"),
            candidates = listOf(
                original,
                original.copy(
                    externalWorkId = "0w5k",
                    coverageObservedCount = 101,
                    coverageMaxMilli = 232_000,
                    coverageHasMore = true,
                ),
            ),
        )

        portfolio.map { it.workVerification } shouldBe
            listOf(ProviderWorkVerification.AUTHOR_CORROBORATED, ProviderWorkVerification.AUTHOR_CORROBORATED)
        portfolio.map { it.editionQualification } shouldBe
            listOf(ProviderSourceEditionQualification.UNRESOLVED, ProviderSourceEditionQualification.UNRESOLVED)
        portfolio.map { it.ref.externalWorkId } shouldBe listOf("kx1p6", "0w5k")
        portfolio.map { it.canPromoteChapters } shouldBe listOf(false, false)
        portfolio.map { it.canOpenReader } shouldBe listOf(false, false)
    }

    @Test
    fun `matching titles and coverage never compensate missing or conflicting verified authors`() {
        val portfolio = ProviderWorkSourcePortfolio.assess(
            providerId = ProviderId("app.tsuzuki.mangafire"),
            facetId = "en",
            queryTitle = "Naruto",
            independentlyVerifiedAuthors = setOf("Kishimoto Masashi"),
            candidates = listOf(
                original.copy(externalWorkId = "missing", title = "Naruto", authors = emptyList()),
                original.copy(externalWorkId = "conflict", title = "Naruto", authors = listOf("Different Author")),
                original.copy(
                    externalWorkId = "unknown-detail",
                    title = "Naruto",
                    detailOutcome = ProviderWorkDetailOutcome.DETAIL_FAILED,
                ),
            ),
        )
        portfolio.map { it.workVerification } shouldBe listOf(
            ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE,
            ProviderWorkVerification.AUTHOR_CONFLICT,
            ProviderWorkVerification.SOURCE_DETAIL_UNVERIFIED,
        )
        portfolio.any { it.canPromoteChapters || it.canOpenReader } shouldBe false
    }

    @Test
    fun `colored variant remains distinct even when its author and title match`() {
        val portfolio = ProviderWorkSourcePortfolio.assess(
            providerId = ProviderId("app.tsuzuki.mangafire"),
            facetId = "en",
            queryTitle = "Vagabond",
            independentlyVerifiedAuthors = setOf("Takehiko Inoue"),
            candidates = listOf(
                original.copy(
                    externalWorkId = "qnlvj",
                    title = "Vagabond",
                    aliases = listOf("Vagabond (Official Colored)"),
                    authors = listOf("Inoue Takehiko"),
                    detailOutcome = ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES,
                ),
                original.copy(
                    externalWorkId = "4mx",
                    title = "Vagabond",
                    authors = listOf("Inoue Takehiko"),
                ),
            ),
        )
        portfolio.map { it.editionQualification } shouldBe listOf(
            ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT,
            ProviderSourceEditionQualification.UNRESOLVED,
        )
        portfolio.map { it.canPromoteChapters } shouldBe listOf(false, false)
    }

    @Test
    fun `without independently verified canonical authors no source can be work corroborated`() {
        val portfolio = ProviderWorkSourcePortfolio.assess(
            providerId = ProviderId("app.tsuzuki.mangafire"),
            facetId = "en",
            queryTitle = "Bleach",
            independentlyVerifiedAuthors = null,
            candidates = listOf(
                original.copy(externalWorkId = "703ny", title = "Bleach", authors = listOf("Tite Kubo")),
                original.copy(externalWorkId = "jjrxn", title = "Bleach", authors = listOf("Kubo Tite")),
            ),
        )
        portfolio.map { it.workVerification } shouldBe listOf(
            ProviderWorkVerification.CANONICAL_METADATA_UNVERIFIED,
            ProviderWorkVerification.CANONICAL_METADATA_UNVERIFIED,
        )
    }

    @Test
    fun `duplicate HID does not become a separate provider work association`() {
        val portfolio = ProviderWorkSourcePortfolio.assess(
            providerId = ProviderId("app.tsuzuki.mangafire"),
            facetId = "en",
            queryTitle = "Chainsaw Man",
            independentlyVerifiedAuthors = setOf("Fujimoto Tatsuki"),
            candidates = listOf(original, original.copy(coverageObservedCount = 101)),
        )
        portfolio.map { it.ref.externalWorkId } shouldBe listOf("kx1p6")
    }
}
