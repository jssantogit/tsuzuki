package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId

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
    fun `MangaFire source slug explicitly colored cannot masquerade as unqualified original edition`() {
        val provider = ProviderId("app.tsuzuki.mangafire")
        val candidates = listOf(
            original.copy(
                externalWorkId = "729pj",
                title = "One Punch-Man",
                url = "https://mangafire.to/title/729pj-one-punch-man-fan-coloredd",
                authors = listOf("ONE"),
            ),
            original.copy(
                externalWorkId = "oo4",
                title = "One Punch-Man",
                url = "https://mangafire.to/title/oo4-one-punch-man",
                authors = listOf("ONE"),
            ),
        )
        val portfolio = ProviderWorkSourcePortfolio.assess(
            providerId = provider,
            facetId = "en",
            queryTitle = "One Punch-Man",
            independentlyVerifiedAuthors = setOf("ONE"),
            candidates = candidates,
        )
        portfolio.map { it.workVerification } shouldBe listOf(
            ProviderWorkVerification.AUTHOR_CORROBORATED,
            ProviderWorkVerification.AUTHOR_CORROBORATED,
        )
        portfolio.map { it.editionQualification } shouldBe listOf(
            ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT,
            ProviderSourceEditionQualification.UNRESOLVED,
        )
        portfolio.map { it.canCollectProvisionalChapters } shouldBe listOf(false, true)
    }

    @Test
    fun `source slug qualifier requires exact MangaFire HTTPS work HID and does not guess variants`() {
        val invalidUrls = listOf(
            "https://evil.example/title/729pj-one-punch-man-fan-colored",
            "http://mangafire.to/title/729pj-one-punch-man-fan-colored",
            "https://mangafire.to/title/foreign-one-punch-man-fan-colored",
            "https://mangafire.to/read/one-punch-man-fan-colored.729pj",
            "https://mangafire.to/title/729pj-one-punch-man?variant=colored",
        )
        invalidUrls.forEach { url ->
            val item = original.copy(
                externalWorkId = "729pj",
                title = "One Punch-Man",
                url = url,
                authors = listOf("ONE"),
            )
            val portfolio = ProviderWorkSourcePortfolio.assess(
                providerId = ProviderId("app.tsuzuki.mangafire"),
                facetId = "en",
                queryTitle = "One Punch-Man",
                independentlyVerifiedAuthors = setOf("ONE"),
                candidates = listOf(item),
            )
            portfolio.single().editionQualification shouldBe ProviderSourceEditionQualification.UNRESOLVED
        }
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

    @Test
    fun `MangaAll accepts only internal MangaFire candidates into verified source portfolio`() {
        val mangaAll = ProviderId("app.tsuzuki.mangaall")
        val verified = original.copy(
            externalWorkId = "mangafire:kx1p6",
            title = "Chainsaw Man",
            url = "https://mangafire.to/title/kx1p6-chainsaw-man",
        )
        val unverified = original.copy(
            externalWorkId = "mangadex:11111111-2222-4333-8444-555555555555",
            url = "https://mangafire.to/title/kx1p6-chainsaw-man",
        )
        val fake = original.copy(externalWorkId = "mangafire:", title = "Chainsaw Man")
        val candidates = ProviderWorkSourcePortfolio.trustedCandidates(
            providerId = mangaAll,
            facetId = "en",
            candidates = listOf(verified, unverified, fake),
        )
        candidates shouldBe listOf(verified)
        ProviderWorkSourcePortfolio.trustedCandidates(mangaAll, "pt-br", listOf(verified)) shouldBe emptyList()
        ProviderWorkSourcePortfolio.trustedCandidates(
            ProviderId("org.example.unrelated"),
            "en",
            listOf(verified),
        ) shouldBe emptyList()
        val assessments = ProviderWorkSourcePortfolio.assess(
            providerId = mangaAll,
            facetId = "en",
            queryTitle = "Chainsaw Man",
            independentlyVerifiedAuthors = setOf("Fujimoto Tatsuki"),
            candidates = candidates,
        )
        assessments.single().ref.externalWorkId shouldBe "mangafire:kx1p6"
        assessments.single().workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
        assessments.single().canPromoteChapters shouldBe false
        assessments.single().canOpenReader shouldBe false
    }

    @Test
    fun `MangaDex enters provisional portfolio only with exact author and facet-bound chapter evidence`() {
        val mangaAll = ProviderId("app.tsuzuki.mangaall")
        val verified = original.copy(
            externalWorkId = "mangadex:11111111-2222-4333-8444-555555555555",
            title = "Chainsaw Man",
            url = null,
            detailOutcome = ProviderWorkDetailOutcome.VERIFIED_NO_ALIASES,
            authors = listOf("Fujimoto Tatsuki"),
            coverageOutcome = ProviderCoverageOutcome.OBSERVED,
            coverageObservedCount = 1,
            coverageMinMilli = 1_000,
            coverageMaxMilli = 1_000,
            coverageHasMore = true,
        )
        for (language in listOf("en", "pt-br")) {
            val sources = ProviderWorkSourcePortfolio.trustedCandidates(
                mangaAll,
                language,
                listOf(
                    verified,
                    verified.copy(externalWorkId = "mangadex:not-a-uuid"),
                    verified.copy(authors = emptyList()),
                    verified.copy(detailOutcome = ProviderWorkDetailOutcome.DETAIL_FAILED),
                    verified.copy(coverageOutcome = ProviderCoverageOutcome.FAILED, coverageObservedCount = null,
                        coverageMinMilli = null, coverageMaxMilli = null, coverageHasMore = null),
                ),
            )
            sources shouldBe listOf(verified)
            val assessed = ProviderWorkSourcePortfolio.assess(
                mangaAll, language, "Chainsaw Man", setOf("Tatsuki Fujimoto"), sources,
            )
            assessed.single().workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
            assessed.single().canCollectProvisionalChapters shouldBe true
            assessed.single().canPromoteChapters shouldBe false
            assessed.single().canOpenReader shouldBe false
        }
        ProviderWorkSourcePortfolio.trustedCandidates(mangaAll, "ja", listOf(verified)) shouldBe emptyList()
        ProviderWorkSourcePortfolio.trustedCandidates(
            mangaAll, "en", listOf(verified.copy(externalWorkId = "mangadot:317")),
        ) shouldBe emptyList()
    }

    @Test
    fun `MangaAll MangaFire internal HID URL still attests special edition and forbids foreign origin`() {
        val mangaAll = ProviderId("app.tsuzuki.mangaall")
        val colored = original.copy(
            externalWorkId = "mangafire:729pj",
            title = "One Punch-Man",
            url = "https://mangafire.to/title/729pj-one-punch-man-fan-coloredd",
            authors = listOf("ONE"),
        )
        ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
            mangaAll,
            colored,
            "One Punch-Man",
        ) shouldBe true
        ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
            mangaAll,
            colored.copy(url = "https://mangadot.net/title/729pj-one-punch-man-fan-coloredd"),
            "One Punch-Man",
        ) shouldBe false
        ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
            mangaAll,
            colored.copy(externalWorkId = "mangadot:729pj"),
            "One Punch-Man",
        ) shouldBe false
    }
}
