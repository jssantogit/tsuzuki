package eu.kanade.tachiyomi.data.tsuzuki.inventory

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.VerifiedInventoryWorkAssociationResult
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class MangaUpdatesVerifiedWorkLinkerTest {
    private val mangaDexId = "12345678-1234-1234-1234-123456789abc"
    private val mangaUpdatesId = "8675309"
    private val settings = Settings()

    @Test
    fun `search plus verified MangaDex crosswalk discovers series without manual ID and confirms atomically`() =
        runTest {
            val repo = repository()
            val network = StubTransport(mangaDexId, mangaUpdatesId)
            coEvery { repo.associateVerifiedInventoryWorkIfCurrent(any(), any()) } returns
                VerifiedInventoryWorkAssociationResult.ASSOCIATED
            val linker = MangaUpdatesVerifiedWorkLinker(repo, settings, network)

            val candidate = linker.discoverUnique("title") { true }.getOrThrow()
            candidate?.mangaUpdatesId shouldBe mangaUpdatesId
            candidate?.mangaDexId shouldBe mangaDexId
            linker.confirm("title", candidate!!) { true }.isSuccess shouldBe true
            coVerify(exactly = 1) {
                repo.associateVerifiedInventoryWorkIfCurrent(
                    match {
                        it.producerId == "mangaupdates" &&
                            it.externalWorkId == mangaUpdatesId &&
                            it.expectedVerifiedCrosswalks.any { identity ->
                                identity.provider == "mangadex" && identity.externalId == mangaDexId
                            }
                    },
                    any(),
                )
            }
            coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
        }

    @Test
    fun `title match without MangaDex proof or revoked permission never writes an external identity`() =
        runTest {
            val repo = repository()
            val network = StubTransport(mangaDexId, mangaUpdatesId)
            val linker = MangaUpdatesVerifiedWorkLinker(repo, settings, network)
            network.linkId = "9999999"
            linker.discoverUnique("title") { true }.getOrThrow() shouldBe null
            network.linkId = mangaUpdatesId
            settings.mu = settings.mu.copy(enabled = false, updatedAt = 2L)
            linker.discoverUnique("title") { true }.isFailure shouldBe true
            coVerify(exactly = 0) { repo.associateVerifiedInventoryWorkIfCurrent(any(), any()) }
        }

    @Test
    fun `MangaUpdates base36 URL identity is normalized to the numeric API series ID`() = runTest {
        val network = StubTransport(mangaDexId, "64897697818")
        network.linkId = "ttaew4a"
        val result = MangaUpdatesVerifiedWorkLinker(repository(), settings, network)
            .discoverUnique("title") { true }.getOrThrow()

        result?.mangaUpdatesId shouldBe "64897697818"
    }

    @Test
    fun `MangaUpdates verified series detail is a safe fallback for an empty title index`() = runTest {
        val network = StubTransport(mangaDexId, mangaUpdatesId)
        network.searchEmpty = true
        val result = MangaUpdatesVerifiedWorkLinker(repository(), settings, network)
            .discoverUnique("title") { true }.getOrThrow()

        result?.mangaUpdatesId shouldBe mangaUpdatesId
        result?.displayTitle shouldBe "Exact Manga"
    }

    @Test
    fun `crosswalk-verified series is discovered even when title search finds only homonyms`() = runTest {
        val network = StubTransport(mangaDexId, mangaUpdatesId)
        network.unrelatedSearchResults = true
        network.seriesTitle = "Alternate Japanese Title"
        val result = MangaUpdatesVerifiedWorkLinker(repository(), settings, network)
            .discoverUnique("title") { true }.getOrThrow()

        result?.mangaUpdatesId shouldBe mangaUpdatesId
        result?.displayTitle shouldBe "Alternate Japanese Title"
    }

    @Test
    fun `unrelated title candidates cannot become verified series when crosswalk points elsewhere`() = runTest {
        val repo = repository()
        val network = StubTransport(mangaDexId, mangaUpdatesId)
        network.unrelatedSearchResults = true
        network.linkId = "555555"
        val candidate = MangaUpdatesVerifiedWorkLinker(repo, settings, network)
            .discoverUnique("title") { true }.getOrNull()

        candidate shouldBe null
        coVerify(exactly = 0) { repo.associateVerifiedInventoryWorkIfCurrent(any(), any()) }
    }

    private fun repository(): CanonicalTitleRepository = mockk<CanonicalTitleRepository>().also { repo ->
        coEvery { repo.getById("title") } returns CanonicalTitle(
            id = "title",
            displayTitle = "Exact Manga",
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = 1L,
            updatedAt = 1L,
        )
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangadex", mangaDexId, true, 1L),
        )
        coEvery { repo.getByExternalIdentity("mangaupdates", mangaUpdatesId) } returns null
    }

    private class Settings : IntegrationSettingsRepository {
        var mu = IntegrationSettings(IntegrationId("mangaupdates"), true, """{"chapter_inventory":true}""", 1L)
        var md = IntegrationSettings(IntegrationId("mangadex"), true, """{"chapter_inventory":true}""", 1L)
        override suspend fun get(id: IntegrationId): IntegrationSettings? = when (id.value) {
            "mangaupdates" -> mu
            "mangadex" -> md
            else -> null
        }
        override fun observeAll(): Flow<List<IntegrationSettings>> = flowOf(listOf(mu, md))
        override suspend fun upsert(settings: IntegrationSettings) = Unit
    }

    private class StubTransport(private val mdId: String, private val muId: String) : InventoryReleaseTransport {
        var linkId = muId
        var searchEmpty = false
        var unrelatedSearchResults = false
        var seriesTitle = "Exact Manga"
        override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload {
            val content = when {
                url.endsWith("/manga/$mdId") -> """
                    {"result":"ok","data":{"id":"$mdId","type":"manga","attributes":{"links":{"mu":"$linkId"}}}}
                """.trimIndent()
                url.endsWith("/v1/series/search") && unrelatedSearchResults ->
                    """{"page":1,"per_page":10,"total_hits":2,"results":[
                       {"record":{"series_id":22222,"title":"Exact Manga"}},
                       {"record":{"series_id":33333,"title":"Exact Manga Alternative"}}
                    ]}"""
                url.endsWith("/v1/series/search") && searchEmpty ->
                    """{"page":1,"per_page":10,"total_hits":0,"results":[]}"""
                url.endsWith("/v1/series/search") -> """
                    {"page":1,"per_page":10,"total_hits":1,"results":[
                      {"record":{"series_id":$muId,"title":"Exact Manga"}}
                    ]}
                """.trimIndent()
                url.endsWith("/v1/series/$muId") -> """{"series_id":$muId,"title":"$seriesTitle"}"""
                else -> error("Unapproved URL $url")
            }
            return InventoryHttpPayload(content, observedAt = 1L)
        }
        override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
            fetch(url, postBody)
    }
}
