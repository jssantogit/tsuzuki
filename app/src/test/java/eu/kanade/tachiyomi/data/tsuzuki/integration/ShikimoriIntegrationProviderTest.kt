package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriCollectionPage
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriCollectionQuery
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriIntegrationApi
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider

class ShikimoriIntegrationProviderTest {

    @Test
    fun `shikimori exposes public community rating without unlocking catalog identity`() = runTest {
        val provider = ShikimoriIntegrationProvider.forTest(
            FakeShikimoriIntegrationApi(
                searchResults = listOf(
                    track(
                        id = 42,
                        title = "Star Embracing Swordmaster",
                        score = 7.6,
                        year = "2023-09-19",
                    ),
                ),
            ),
        )

        (provider as Any is SearchProvider) shouldBe true
        (provider as Any is MetadataProvider) shouldBe true
        (provider as Any is RatingsProvider) shouldBe true

        val match = (provider as RatingsProvider).ratingFor(
            CatalogItem(
                provider = "kitsu",
                providerId = "k1",
                title = "Star-Embracing Swordmaster",
                startDate = "2023",
            ),
        ).getOrThrow()

        match?.rating?.providerId shouldBe "shikimori"
        match?.rating?.value shouldBe 7.6
        match?.rating?.scaleMax shouldBe 10.0
        match?.verifiedIdentity shouldBe false
    }

    @Test
    fun `shikimori exact identity returns native rating`() = runTest {
        val provider = ShikimoriIntegrationProvider.forTest(
            FakeShikimoriIntegrationApi(
                detailsById = mapOf(42 to track(42, "Monster", 8.9, "1994")),
            ),
        )

        val rating = (provider as RatingsProvider).ratings("42").getOrThrow().single()

        rating.providerId shouldBe "shikimori"
        rating.label shouldBe "Shikimori"
        rating.value shouldBe 8.9
        rating.scaleMax shouldBe 10.0
    }

    @Test
    fun `shikimori rating gate enforces second and minute budgets`() = runTest {
        var now = 0L
        val gate = ShikimoriRequestGate(
            nowMillis = { now },
            pause = { delayMillis -> now += delayMillis },
        )

        repeat(91) {
            gate.withPermit { Unit }
        }

        now shouldBe 60_000L
    }

    @Test
    fun `public catalog pushes down manhwa kind and advances exact windows`() = runTest {
        val requests = mutableListOf<ShikimoriCollectionQuery>()
        val api = object : ShikimoriIntegrationApi {
            override suspend fun searchPublic(query: String): List<TrackSearch> = error("Unpaged search forbidden")
            override suspend fun getMangaDetailsPublic(id: Int): TrackSearch? = null
            override suspend fun collectionSearch(query: ShikimoriCollectionQuery): ShikimoriCollectionPage {
                requests += query
                val start = (query.page - 1) * query.limit
                val rows = (start until minOf(start + query.limit, 26)).map {
                    track(it.toLong() + 1, "Title $it", 7.0, "2024").apply { publishing_type = "manhwa" }
                }
                return ShikimoriCollectionPage(rows, query.page, query.limit, start + rows.size < 26)
            }
        }
        val provider = ShikimoriIntegrationProvider.forTest(api)
        val query = CatalogQuery(query = "Title", format = CatalogItemFormat.MANHWA, limit = 25)
        val first = provider.search(query).getOrThrow()
        val second = provider.search(query.copy(offset = 25)).getOrThrow()
        first.items.size shouldBe 25
        first.hasNextPage shouldBe true
        second.items.single().providerId shouldBe "26"
        second.hasNextPage shouldBe false
        requests.map { Triple(it.page, it.limit, it.kind) } shouldBe listOf(
            Triple(1, 25, "manhwa"),
            Triple(2, 25, "manhwa"),
        )
    }

    private class FakeShikimoriIntegrationApi(
        private val searchResults: List<TrackSearch> = emptyList(),
        private val detailsById: Map<Int, TrackSearch> = emptyMap(),
    ) : ShikimoriIntegrationApi {
        override suspend fun searchPublic(query: String): List<TrackSearch> = searchResults

        override suspend fun getMangaDetailsPublic(id: Int): TrackSearch? = detailsById[id]
    }

    private fun track(id: Long, title: String, score: Double, year: String): TrackSearch =
        TrackSearch.create(4L).apply {
            remote_id = id
            this.title = title
            this.score = score
            start_date = year
        }
}
