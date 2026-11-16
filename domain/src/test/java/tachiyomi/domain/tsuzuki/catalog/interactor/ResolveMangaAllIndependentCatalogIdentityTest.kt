package tachiyomi.domain.tsuzuki.catalog.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
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
            CatalogPage(listOf(item("mal", "42", "Work")), query.offset == 0)
        }, detail = { item("mal", "42", "Work") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.RESOLVED
        offsets shouldBe listOf(0, 25)
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
    fun `detail endpoint must confirm the same provider and exact external id`() = runTest {
        val mal = source("mal", search = {
            CatalogPage(listOf(item("mal", "42", "Work")), false)
        }, detail = { item("mal", "99", "Work") })
        val result = ResolveMangaAllIndependentCatalogIdentity(registry(mal)).execute("Work")
        result.status shouldBe MangaAllCatalogIdentityStatus.CATALOG_UNVERIFIED
        result.identity shouldBe null
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
        assertThrows<CancellationException> {
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
