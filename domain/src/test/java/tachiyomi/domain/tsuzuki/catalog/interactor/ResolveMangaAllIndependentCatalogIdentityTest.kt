package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider

/** Test fixtures exercise the identity policy, not live catalog availability. */
class ResolveMangaAllIndependentCatalogIdentityTest {

    @Test
    fun `no enabled independent catalogs is unavailable not no-match`() = runTest {
        val result = ResolveMangaAllIndependentCatalogIdentity(registry()).execute("Any Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_UNAVAILABLE
        result.identity shouldBe null
    }

    @Test
    fun `a real catalog integration result must be verified by its own detail endpoint`() = runTest {
        val mal = source(
            "mal",
            search = { CatalogPage(listOf(item("mal", "42", "WORK", authors = listOf("Search Only"))), false) },
            detail = { item("mal", "42", "Work", authors = listOf("Verified Creator")) },
        )
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.RESOLVED
        result.identity?.provider shouldBe "mal"
        result.identity?.providerId shouldBe "42"
        result.identity?.authors shouldBe listOf("Verified Creator")
        result.catalogsQueried shouldBe 1
    }

    @Test
    fun `same title from two catalogs is ambiguous without verified crosswalk`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work")), false)
        }, detail = { error("Do not fetch details when identities conflict") })
        val mu = source("mangaupdates", search = {
            CatalogPage(listOf(item("mangaupdates", "71", "Work")), false)
        }, detail = { error("Do not fetch details when identities conflict") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal, mu)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_AMBIGUOUS
        result.candidateCount shouldBe 2
        result.identity shouldBe null
    }

    @Test
    fun `verified provider published crosswalk merges identities but not by title`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(
                listOf(item("mal", "42", "Work", externalIds = mapOf("mangaupdates" to "71"))),
                false,
            )
        }, detail = { item("mal", "42", "Work", authors = listOf("Real Creator")) })
        val mu = source("mangaupdates", search = {
            CatalogPage(listOf(item("mangaupdates", "71", "Work")), false)
        }, detail = { error("Not needed") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal, mu)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.RESOLVED
        result.candidateCount shouldBe 1
    }

    @Test
    fun `one unavailable catalog makes a lone match incomplete and unsafe to promote`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work")), false)
        }, detail = { error("Cannot accept partial search") })
        val mu = source("mangaupdates", searchResult = {
            Result.failure(IllegalStateException("HTTP 403"))
        })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal, mu)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE
        result.identity shouldBe null
        result.catalogsFailed shouldBe 1
    }

    @Test
    fun `pagination must exhaust all pages before declaring one catalog identity`() = runTest {
        val offsets = mutableListOf<Int>()
        val mal = source("mal", search = { query ->
            offsets += query.offset
            if (query.offset == 0) {
                CatalogPage(listOf(item("mal", "42", "Work")), true)
            } else {
                CatalogPage(listOf(item("mal", "43", "Unrelated Work")), false)
            }
        }, detail = { item("mal", "42", "Work") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.RESOLVED
        // Advance by the number actually received, never skip real candidates.
        offsets shouldBe listOf(0, 1)
    }

    @Test
    fun `duplicate identity on terminal page cannot certify pagination`() = runTest {
        val mal = source("mal", search = { query ->
            CatalogPage(listOf(item("mal", "42", "Work")), query.offset == 0, totalCount = 2)
        }, detail = { error("Repeated page cannot authorize identity") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE
        result.identity shouldBe null
    }

    @Test
    fun `changing catalog total cannot certify pagination`() = runTest {
        val mal = source("mal", search = { query ->
            if (query.offset == 0) {
                CatalogPage(listOf(item("mal", "42", "Work")), true, totalCount = 3)
            } else {
                CatalogPage(listOf(item("mal", "43", "Unrelated Work")), false, totalCount = 2)
            }
        }, detail = { error("Changing total cannot authorize identity") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE
        result.identity shouldBe null
    }

    @Test
    fun `narrowed MangaUpdates manhwa lookup consumes eight real pages before verification`() = runTest {
        val offsets = mutableListOf<Int>()
        val mu = source("mangaupdates", search = { query ->
            offsets += query.offset
            query.format shouldBe CatalogItemFormat.MANHWA
            val rows = (query.offset until minOf(query.offset + query.limit, 176)).map { index ->
                item("mangaupdates", "${index + 1}", if (index == 175) "Work" else "Other $index")
                    .copy(format = CatalogItemFormat.MANHWA)
            }
            CatalogPage(rows, query.offset + rows.size < 176, totalCount = 176)
        }, detail = { id ->
            id shouldBe "176"
            item("mangaupdates", id, "Work", authors = listOf("Verified Creator"))
                .copy(format = CatalogItemFormat.MANHWA)
        })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mu))
            .execute("Work", CatalogItemFormat.MANHWA)
        result.status shouldBe MangaAllCatalogIdentityStatus.RESOLVED
        offsets shouldBe (0..7).map { it * 25 }
        result.identity?.providerId shouldBe "176"
    }

    @Test
    fun `oversized advertised catalog total stops after first page without accepting its exact match`() = runTest {
        val offsets = mutableListOf<Int>()
        val mu = source("mangaupdates", search = { query ->
            offsets += query.offset
            CatalogPage(
                listOf(item("mangaupdates", "42", "Work").copy(format = CatalogItemFormat.MANHWA)),
                hasNextPage = true,
                totalCount = 8_574,
            )
        }, detail = { error("Incomplete search cannot authorize identity") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mu))
            .execute("Work", CatalogItemFormat.MANHWA)
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE
        result.identity shouldBe null
        result.catalogsFailed shouldBe 0
        offsets shouldBe listOf(0)
    }

    @Test
    fun `detail cannot change requested manhwa into a novel or unknown format`() = runTest {
        for (format in listOf(CatalogItemFormat.NOVEL, CatalogItemFormat.UNKNOWN)) {
            val mu = source("mangaupdates", search = {
                CatalogPage(listOf(item("mangaupdates", "42", "Work").copy(format = CatalogItemFormat.MANHWA)), false)
            }, detail = {
                item("mangaupdates", it, "Work").copy(format = format)
            })
            val result = ResolveMangaAllIndependentCatalogIdentity(registry(mu))
                .execute("Work", CatalogItemFormat.MANHWA)
            result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED
            result.identity shouldBe null
        }
    }

    @Test
    fun `unbounded catalog cursor is incomplete instead of claiming full match`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work")), true)
        }, detail = { error("Truncated inventory cannot be verified") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE
        result.identity shouldBe null
    }

    @Test
    fun `terminal catalog page contradicting its real total count is incomplete`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work")), false, totalCount = 30)
        }, detail = { error("Never certify a partial catalog response") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE
        result.identity shouldBe null
    }

    @Test
    fun `duplicate enabled catalog registration is not silently reduced to the first one`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work")), false)
        }, detail = { error("Conflicting registrations cannot authorize an identity") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal, mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_INCOMPLETE
        result.identity shouldBe null
    }

    @Test
    fun `detail endpoint cannot contradict a provider-published external id crosswalk`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work", externalIds = mapOf("mangaupdates" to "71"))), false)
        }, detail = {
            item("mal", "42", "Work", externalIds = mapOf("mangaupdates" to "different"))
        })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED
        result.identity shouldBe null
    }

    @Test
    fun `detail endpoint must confirm the same provider and exact external id`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work")), false)
        }, detail = { item("mal", "99", "Work") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED
        result.identity shouldBe null
    }

    @Test
    fun `catalog published alias must also be corroborated by the owning detail`() = runTest {
        val searchItem = item("shikimori", "42", "Primary")
            .copy(titles = mapOf("alternate" to "Requested Alias"))
        val verified = source("shikimori", search = {
            CatalogPage(listOf(searchItem), false)
        }, detail = { searchItem.copy(authors = listOf("Verified Creator")) })
        val unverified = source("shikimori", search = {
            CatalogPage(listOf(searchItem), false)
        }, detail = { searchItem.copy(titles = emptyMap()) })

        ResolveMangaAllIndependentCatalogIdentity(registry(verified))
            .execute("Requested Alias").status shouldBe MangaAllCatalogIdentityStatus.RESOLVED
        ResolveMangaAllIndependentCatalogIdentity(registry(unverified))
            .execute("Requested Alias").status shouldBe MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED
    }

    @Test
    fun `no exact title or supplied alias match is not found when all catalogs complete`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Different Work")), false)
        })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_NOT_FOUND
        result.identity shouldBe null
    }

    @Test
    fun `cancelled lookup propagates rather than becoming a false empty catalog`() = runTest {
        val mal = source("mal", searchResult = { throw CancellationException("cancelled") })
        shouldThrow<CancellationException> {
            ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        }
    }

    private fun item(
        provider: String,
        id: String,
        title: String,
        authors: List<String> = emptyList(),
        externalIds: Map<String, String> = emptyMap(),
    ) = CatalogItem(
        provider = provider,
        providerId = id,
        title = title,
        authors = authors,
        externalIds = externalIds,
    )

    private fun source(
        id: String,
        search: (CatalogQuery) -> CatalogPage = { CatalogPage(emptyList(), false) },
        detail: (String) -> CatalogItem = { item(id, it, "Work") },
        searchResult: ((CatalogQuery) -> Result<CatalogPage>)? = null,
    ) = object : SearchProvider, MetadataProvider {
        override val integrationId = IntegrationId(id)
        override suspend fun search(query: CatalogQuery): Result<CatalogPage> =
            searchResult?.invoke(query) ?: Result.success(search(query))
        override suspend fun getDetails(externalId: String): Result<CatalogItem> =
            Result.success(detail(externalId))
    }

    private fun registry(vararg sources: SearchProvider) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = sources.toList()
        override fun metadataProviders(): List<MetadataProvider> = sources.filterIsInstance<MetadataProvider>()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }
}
