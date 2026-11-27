package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.interactor.mangaAllCatalogCreatorGroups
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
    fun `adaptations sharing an original writer require the independently verified illustrator in either source`() {
        val groups = mangaAllCatalogCreatorGroups(
            listOf("Original Writer", "Manga Artist"),
            mapOf("Manga Artist" to listOf("Artist Alias")),
        )
        for (id in listOf("mangadot:42", "mangadex:00000000-0000-0000-0000-000000000042")) {
            val work = original.copy(externalWorkId = id, title = "Adaptation")
            fun assess(
                authors: List<String>,
                canonical: Set<String> = setOf("Original Writer", "Manga Artist"),
            ) = ProviderWorkSourcePortfolio.assess(
                ProviderId("app.tsuzuki.mangaall"),
                "en",
                work.title,
                canonical,
                listOf(work.copy(authors = authors)),
                requiredCreatorGroups = groups,
            ).single()
            assess(listOf("Original Writer", "Manga Artist")).canCollectProvisionalChapters shouldBe true
            assess(listOf("Original Writer", "Artist Alias")).canCollectProvisionalChapters shouldBe true
            assess(listOf("Original Writer", "Different Illustrator")).workVerification shouldBe
                ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE
            assess(listOf("Original Writer")).canCollectProvisionalChapters shouldBe false
            assess(listOf("Original Writer", "Manga Artist"), setOf("Original Writer")).workVerification shouldBe
                ProviderWorkVerification.CANONICAL_METADATA_UNVERIFIED
        }
    }

    @Test
    fun `catalog work attestation is scoped to title source facet version and configuration`() {
        val ref = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "en", "mangadot:42")
        val original = MangaAllCatalogWorkIdentityProof.fingerprint("title", ref, "123", 11, "config")
        requireNotNull(original)
        original.matches(Regex("catalog-work-v1:[0-9a-f]{64}")) shouldBe true
        MangaAllCatalogWorkIdentityProof.fingerprint("title", ref, "123", 11, "config") shouldBe original
        val changed = listOf(
            MangaAllCatalogWorkIdentityProof.fingerprint("other", ref, "123", 11, "config"),
            MangaAllCatalogWorkIdentityProof.fingerprint("title", ref, "124", 11, "config"),
            MangaAllCatalogWorkIdentityProof.fingerprint("title", ref, "123", 11, "other"),
            MangaAllCatalogWorkIdentityProof.fingerprint("title", ref.copy(facetId = "pt-br"), "123", 11, "config"),
            MangaAllCatalogWorkIdentityProof.fingerprint(
                "title",
                ref.copy(externalWorkId = "mangadot:43"),
                "123",
                11,
                "config",
            ),
        )
        changed.all { it != null && it != original } shouldBe true
        MangaAllCatalogWorkIdentityProof.fingerprint("title", ref, "123", 10, "config") shouldBe null
        MangaAllCatalogWorkIdentityProof.fingerprint("title", ref, "123", 11, "") shouldBe null
        MangaAllCatalogWorkIdentityProof.fingerprint("title", ref, "0", 11, "config") shouldBe null
        MangaAllCatalogWorkIdentityProof.fingerprint("title", ref.copy(facetId = "ja"), "123", 11, "config") shouldBe
            null
    }

    @Test
    fun `bibliographic ID verifies a creditless exact work but never grants edition or reader authority`() {
        val candidate = original.copy(
            externalWorkId = "mangadot:42",
            url = "https://mangadot.net/manga/42",
            authors = emptyList(),
            mangaUpdatesReferenceIds = listOf("123456789012345678"),
        )
        fun assess(
            item: ProviderWorkCandidate = candidate,
            expectedId: String? = "123456789012345678",
            providerId: ProviderId = ProviderId("app.tsuzuki.mangaall"),
        ) = ProviderWorkSourcePortfolio.assess(
            providerId,
            "en",
            candidate.title,
            setOf("Independent Creator"),
            listOf(item),
            expectedId,
        ).single()

        val verified = assess()
        verified.workVerification shouldBe ProviderWorkVerification.CATALOG_CORROBORATED
        verified.canCollectProvisionalChapters shouldBe false
        verified.canPromoteChapters shouldBe false
        verified.canOpenReader shouldBe false
        assess(expectedId = null).workVerification shouldBe ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE
        assess(expectedId = "123").workVerification shouldBe ProviderWorkVerification.CATALOG_REFERENCE_CONFLICT
        assess(candidate.copy(mangaUpdatesReferenceIds = listOf("123", "123456789012345678")))
            .workVerification shouldBe ProviderWorkVerification.CATALOG_REFERENCE_CONFLICT
        assess(candidate.copy(authors = listOf("Unrelated Creator")))
            .workVerification shouldBe ProviderWorkVerification.AUTHOR_CONFLICT
        assess(candidate.copy(url = "https://mangadot.net/manga/43"))
            .workVerification shouldBe ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE
        assess(candidate.copy(detailOutcome = ProviderWorkDetailOutcome.DETAIL_FAILED))
            .workVerification shouldBe ProviderWorkVerification.SOURCE_DETAIL_UNVERIFIED
        assess(candidate.copy(title = "Unrelated work"))
            .workVerification shouldBe ProviderWorkVerification.TITLE_UNVERIFIED
        assess(providerId = ProviderId("org.example.provider"))
            .workVerification shouldBe ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE
    }

    @Test
    fun `conflicting references for the same HID dominate independent of arrival order`() {
        val exact = original.copy(
            externalWorkId = "mangadot:42",
            url = "https://mangadot.net/manga/42",
            mangaUpdatesReferenceIds = listOf("123"),
        )
        val conflict = exact.copy(mangaUpdatesReferenceIds = listOf("456"))
        for (items in listOf(listOf(exact, conflict), listOf(conflict, exact))) {
            val result = ProviderWorkSourcePortfolio.assess(
                ProviderId("app.tsuzuki.mangaall"),
                "en",
                exact.title,
                exact.authors.toSet(),
                items,
                "123",
            ).single()
            result.workVerification shouldBe ProviderWorkVerification.CATALOG_REFERENCE_CONFLICT
            result.canCollectProvisionalChapters shouldBe false
            result.canOpenReader shouldBe false
        }
    }

    @Test
    fun `conflicting author and edition observations for one HID never depend on arrival order`() {
        val provider = ProviderId("app.tsuzuki.mangaall")
        val exact = original.copy(externalWorkId = "mangafire:fixture")
        val conflicting = exact.copy(authors = listOf("Unrelated Creator"))
        val variant = exact.copy(aliases = listOf("Chainsaw Man (Colored)"))
        for (items in listOf(listOf(exact, conflicting), listOf(conflicting, exact))) {
            val result = ProviderWorkSourcePortfolio.assess(
                provider,
                "en",
                exact.title,
                exact.authors.toSet(),
                items,
            ).single()
            result.workVerification shouldBe ProviderWorkVerification.AUTHOR_CONFLICT
            result.canCollectProvisionalChapters shouldBe false
        }
        for (items in listOf(listOf(exact, variant), listOf(variant, exact))) {
            val result = ProviderWorkSourcePortfolio.assess(
                provider,
                "en",
                exact.title,
                exact.authors.toSet(),
                items,
            ).single()
            result.editionQualification shouldBe ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT
            result.canCollectProvisionalChapters shouldBe false
        }
    }

    @Test
    fun `incomplete duplicate cannot hide a corroborated row or lend it chapter coverage`() {
        val provider = ProviderId("app.tsuzuki.mangaall")
        val verified = original.copy(externalWorkId = "mangadot:42")
        val missingCredit = verified.copy(authors = emptyList())
        val missingCoverage = verified.copy(
            coverageOutcome = null,
            coverageObservedCount = null,
            coverageMinMilli = null,
            coverageMaxMilli = null,
            coverageHasMore = null,
        )
        for (items in listOf(
            listOf(missingCredit, verified),
            listOf(verified, missingCredit),
            listOf(missingCoverage, verified),
            listOf(verified, missingCoverage),
        )) {
            val result = ProviderWorkSourcePortfolio.assess(
                provider,
                "en",
                verified.title,
                verified.authors.toSet(),
                items,
            ).single()
            result.workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
            result.canCollectProvisionalChapters shouldBe true
            result.canOpenReader shouldBe false
        }
        for (items in listOf(listOf(missingCredit, missingCoverage), listOf(missingCoverage, missingCredit))) {
            val result = ProviderWorkSourcePortfolio.assess(
                provider,
                "en",
                verified.title,
                verified.authors.toSet(),
                items,
            ).single()
            result.workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
            result.canCollectProvisionalChapters shouldBe false
        }
    }

    @Test
    fun `Dungeon Meshi known Ryoko romanizations allow source evidence but not editorial authority`() {
        // Galaxy A54 code8 captured the *real* MangaFire HID 52020 with
        // VERIFIED_WITH_ALIASES, 200 observed numeric chapters, and
        // AUTHOR_CONFLICT. MangaFire publicly credits "KUI Ryoko"; independently
        // sourced catalog credits include "Ryouko Kui" / "Ryōko Kui".
        // Neither name spelling, source coverage nor exact work title
        // grants a preferred edition or Reader authority.
        val observed = original.copy(
            externalWorkId = "mangafire:52020",
            title = "Dungeon Meshi",
            aliases = listOf("Delicious in Dungeon"),
            authors = listOf("KUI Ryoko"),
            detailOutcome = ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES,
            coverageOutcome = ProviderCoverageOutcome.OBSERVED,
            coverageObservedCount = 200,
            coverageMinMilli = 0,
            coverageMaxMilli = 74_000,
            coverageHasMore = true,
        )
        for (verifiedName in listOf("Ryouko Kui", "Ryōko Kui", "Kui Ryoko")) {
            val portfolio = ProviderWorkSourcePortfolio.assess(
                providerId = ProviderId("app.tsuzuki.mangaall"),
                facetId = "en",
                queryTitle = "Dungeon Meshi",
                independentlyVerifiedAuthors = setOf(verifiedName),
                candidates = listOf(observed),
            )
            portfolio.single().workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
            portfolio.single().canCollectProvisionalChapters shouldBe true
            portfolio.single().canPromoteChapters shouldBe false
            portfolio.single().canOpenReader shouldBe false
        }
        // No surname-only, different given name, or title-only relaxation.
        val other = ProviderWorkSourcePortfolio.assess(
            providerId = ProviderId("app.tsuzuki.mangaall"),
            facetId = "en",
            queryTitle = "Dungeon Meshi",
            independentlyVerifiedAuthors = setOf("Different Kui"),
            candidates = listOf(observed),
        )
        other.single().workVerification shouldBe ProviderWorkVerification.AUTHOR_CONFLICT
        other.single().canCollectProvisionalChapters shouldBe false
        other.single().canPromoteChapters shouldBe false
        other.single().canOpenReader shouldBe false
    }

    @Test
    fun `full names tolerate explicit hyphen spelling and decomposed Unicode without merging pseudonyms`() {
        fun check(credit: String, source: String, expected: ProviderWorkVerification) {
            ProviderWorkSourcePortfolio.assess(
                ProviderId("app.tsuzuki.mangaall"),
                "en",
                "Chainsaw Man",
                setOf(credit),
                listOf(original.copy(authors = listOf(source))),
            ).single().workVerification shouldBe expected
        }
        check("Kim, Yong-Hwan", "Yonghwan Kim", ProviderWorkVerification.AUTHOR_CORROBORATED)
        check("Yonghwan Kim", "Kim Yong-Hwan", ProviderWorkVerification.AUTHOR_CORROBORATED)
        check("Ryo\u0304ko Kui", "Ryoko Kui", ProviderWorkVerification.AUTHOR_CORROBORATED)
        check("Kim Yong Hwan", "Kim Yonghwan", ProviderWorkVerification.AUTHOR_CONFLICT)
        check("Kim Yong-Hwan", "Yonghwan Lee", ProviderWorkVerification.AUTHOR_CONFLICT)
        check("Kim", "Kim Yong-Hwan", ProviderWorkVerification.AUTHOR_CONFLICT)
        check("A-B", "AB", ProviderWorkVerification.AUTHOR_CONFLICT)
    }

    @Test
    fun `Japanese title and creator marks do not collapse distinct source identities`() {
        val provider = ProviderId("app.tsuzuki.mangaall")
        val candidate = original.copy(title = "がく", authors = listOf("パク 花"))
        fun assess(title: String, creator: String) = ProviderWorkSourcePortfolio.assess(
            provider,
            "en",
            title,
            setOf(creator),
            listOf(candidate),
        ).single().workVerification
        assess("かく", "パク 花") shouldBe ProviderWorkVerification.TITLE_UNVERIFIED
        assess("がく", "ハク 花") shouldBe ProviderWorkVerification.AUTHOR_CONFLICT
        assess("か\u3099く", "ハ\u309Aク 花") shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
    }

    @Test
    fun `explicit bilingual creator credits compare whole spellings without granting editorial authority`() {
        val mangaAll = ProviderId("app.tsuzuki.mangaall")
        val observed = original.copy(
            externalWorkId = "mangadex:11111111-2222-4333-8444-555555555555",
            title = "Unrelated Fictional Work",
        )
        fun assess(credit: String, source: String): ProviderWorkSourceAssessment =
            ProviderWorkSourcePortfolio.assess(
                mangaAll,
                "en",
                observed.title,
                setOf(credit),
                listOf(observed.copy(authors = listOf(source))),
            ).single()
        for ((credit, source) in listOf(
            "Seo Min-Jun" to "SEO Min Jun (서민준)",
            "Moonrise" to "Moonrise (문라이즈)",
            "별빛작가" to "Starlight (별빛작가)",
            "Kim Haejin (김해진)" to "Hae-Jin Kim",
            "Mira Han" to "Han Mira (한미라)",
            "문 서연" to "MS_Star (문서연)",
            "정하늘" to "Haneul Jeong (정 하늘)",
            "나 래 작가" to "나래작가",
        )) {
            val result = assess(credit, source)
            result.workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
            result.canCollectProvisionalChapters shouldBe true
            result.canPromoteChapters shouldBe false
            result.canOpenReader shouldBe false
        }
        for ((credit, source) in listOf(
            "Han" to "Han Mira (한미라)",
            "Moonrise" to "Moonrise (other creator)",
            "Moonrise" to "Moonrise (문라이즈) Studio",
            "Moonrise" to "Another Moonrise (문라이즈)",
            "Mira Han" to "Han Yuna (한유나)",
            "Moonrise" to "Moonrise (A문라이즈)",
            "Moonrise" to "Moonrise (문)",
            "문 서연" to "MS_Star (문서윤)",
            "하늘 정" to "Haneul Jeong (정 하늘)",
            "문" to "MS_Star (문서연)",
            "Moon Rise" to "Moonrise",
            "서연" to "문 서연",
        )) {
            assess(credit, source).workVerification shouldBe ProviderWorkVerification.AUTHOR_CONFLICT
        }
    }

    @Test
    fun `romanization is anchored in independently published complete Hangul credits`() {
        val provider = ProviderId("app.tsuzuki.mangaall")
        val item = original.copy(externalWorkId = "mangadot:42", title = "Independent Fictional Work")
        fun assess(
            names: Set<String>,
            source: String,
            candidate: ProviderWorkCandidate = item,
            query: String = item.title,
        ): ProviderWorkSourceAssessment = ProviderWorkSourcePortfolio.assess(
            provider,
            "en",
            query,
            names,
            listOf(candidate.copy(authors = listOf(source))),
        ).single()

        for ((native, latin) in listOf(
            "강 지훈" to "Ji-Hun Kang",
            "김 서윤" to "Kim Seo-Yun",
            "문 백현" to "Baek-Hyeon Mun",
            "우람" to "Woo-Ram",
            "유 소민" to "Usomin",
            "권 기운" to "Gi-Un Kwon",
        )) {
            val result = assess(setOf(native), latin)
            result.workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
            result.canPromoteChapters shouldBe false
            result.canOpenReader shouldBe false
        }

        for ((native, latin) in listOf(
            "강 지훈" to "Kang",
            "강 지훈" to "Ji-Hun Kim",
            "강 지훈" to "Ji-Hyeon Kang",
            "강지훈" to "Ji-Hun Kang",
            "김 서윤" to "Yun Seo Kim",
            "김 서윤" to "Kim Seo-Yun Studio",
            "김 서윤" to "Kim Seo-Yun (다른사람)",
            "강" to "Kang",
            "Ji-Hoon Kang" to "Ji-Hun Kang",
            "Moonrise" to "Moon Rise",
        )) {
            assess(setOf(native), latin).workVerification shouldBe ProviderWorkVerification.AUTHOR_CONFLICT
        }

        assess(setOf("구나", "쿠나"), "Kuna").workVerification shouldBe ProviderWorkVerification.AUTHOR_CONFLICT
        assess(setOf("강 지훈"), "Ji-Hun Kang", query = "Different Work")
            .workVerification shouldBe ProviderWorkVerification.TITLE_UNVERIFIED
        assess(setOf("강 지훈"), "Ji-Hun Kang", item.copy(detailOutcome = ProviderWorkDetailOutcome.DETAIL_FAILED))
            .workVerification shouldBe ProviderWorkVerification.SOURCE_DETAIL_UNVERIFIED
        val emptyCoverage = item.copy(
            coverageOutcome = ProviderCoverageOutcome.EMPTY,
            coverageObservedCount = 0,
            coverageMinMilli = null,
            coverageMaxMilli = null,
            coverageHasMore = false,
        )
        val emptyAssessment = assess(setOf("강 지훈"), "Ji-Hun Kang", emptyCoverage)
        emptyAssessment.workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
        emptyAssessment.canCollectProvisionalChapters shouldBe false
        emptyAssessment.canPromoteChapters shouldBe false
        emptyAssessment.canOpenReader shouldBe false
    }

    @Test
    fun `typographic apostrophes do not reject exact titles but suffix differences remain distinct`() {
        val observed = original.copy(
            title = "An Author’s Work",
        )
        val exact = ProviderWorkSourcePortfolio.assess(
            ProviderId("app.tsuzuki.mangafire"),
            "en",
            "An Author's Work",
            setOf("Fujimoto Tatsuki"),
            listOf(observed),
        )
        exact.single().workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
        val different = ProviderWorkSourcePortfolio.assess(
            ProviderId("app.tsuzuki.mangafire"),
            "en",
            "An Author's Work 2",
            setOf("Fujimoto Tatsuki"),
            listOf(observed),
        )
        different.single().workVerification shouldBe ProviderWorkVerification.TITLE_UNVERIFIED
    }

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
    fun `MangaAll excludes unverified MangaDex search hits from trusted source portfolio`() {
        val mangaAll = ProviderId("app.tsuzuki.mangaall")
        val verified = original.copy(
            externalWorkId = "mangafire:kx1p6",
            title = "Chainsaw Man",
            url = "https://mangafire.to/title/kx1p6-chainsaw-man",
        )
        val unverified = original.copy(
            externalWorkId = "mangadex:11111111-2222-4333-8444-555555555555",
            url = "https://mangafire.to/title/kx1p6-chainsaw-man",
            // A similarly named MangaDex search hit cannot borrow MangaFire's
            // details, authors or chapter coverage for automatic source trust.
            detailOutcome = ProviderWorkDetailOutcome.NOT_NEEDED,
            authors = emptyList(),
            coverageOutcome = ProviderCoverageOutcome.UNKNOWN,
            coverageObservedCount = null,
            coverageMinMilli = null,
            coverageMaxMilli = null,
            coverageHasMore = null,
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
                    verified.copy(
                        coverageOutcome = ProviderCoverageOutcome.FAILED,
                        coverageObservedCount = null,
                        coverageMinMilli = null,
                        coverageMaxMilli = null,
                        coverageHasMore = null,
                    ),
                ),
            )
            sources shouldBe listOf(verified)
            val assessed = ProviderWorkSourcePortfolio.assess(
                mangaAll,
                language,
                "Chainsaw Man",
                setOf("Tatsuki Fujimoto"),
                sources,
            )
            assessed.single().workVerification shouldBe ProviderWorkVerification.AUTHOR_CORROBORATED
            assessed.single().canCollectProvisionalChapters shouldBe true
            assessed.single().canPromoteChapters shouldBe false
            assessed.single().canOpenReader shouldBe false
        }
        ProviderWorkSourcePortfolio.trustedCandidates(mangaAll, "ja", listOf(verified)) shouldBe emptyList()
        ProviderWorkSourcePortfolio.trustedCandidates(
            mangaAll,
            "en",
            listOf(verified.copy(externalWorkId = "mangadot:317")),
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
