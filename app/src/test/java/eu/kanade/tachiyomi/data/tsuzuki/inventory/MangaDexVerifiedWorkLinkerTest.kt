package eu.kanade.tachiyomi.data.tsuzuki.inventory

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.IntegrationSettings
import tachiyomi.domain.tsuzuki.integration.repository.IntegrationSettingsRepository
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.MangaDexWorkAssociationResult
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.model.TitleNameObservation
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

class MangaDexVerifiedWorkLinkerTest {
    private val work = "12345678-1234-1234-1234-123456789abc"
    private val integrationSettings = MutableIntegrationSettingsRepository(
        IntegrationSettings(
            integrationId = IntegrationId("mangadex"),
            enabled = true,
            configJson = """{"chapter_inventory":true}""",
            updatedAt = 1L,
        ),
    )

    @Test
    fun `MangaDex id can be linked only through a matching verified external work crosswalk`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val network = FakeTransport(details(mu = "8675309"))
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
        )
        coEvery { repo.getByExternalIdentity("mangadex", work) } returns null
        coEvery {
            repo.associateMangaDexWorkIfCurrent(any(), any())
        } returns MangaDexWorkAssociationResult.ASSOCIATED

        val linker = linker(repo, network)
        val candidate = linker.preview("title", work) { true }.getOrThrow()
        candidate.displayTitle shouldBe "Exact Manga"
        candidate.matchedProvider shouldBe "mangaupdates"
        linker.confirm("title", candidate) { true }.isSuccess shouldBe true
        coVerify(exactly = 1) {
            repo.associateMangaDexWorkIfCurrent(
                match {
                    it.canonicalTitleId == "title" && it.mangaDexWorkId == work &&
                        it.expectedIntegrationSettings == integrationSettings.settings &&
                        it.expectedVerifiedCrosswalks.single().externalId == "8675309"
                },
                any(),
            )
        }
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `title similarity without exact crosswalk cannot verify a MangaDex work`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "123", true, 1L),
        )
        val linker = linker(repo, FakeTransport(details(mu = "999")))
        linker.preview("title", work) { true }.isFailure shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `unverified crosswalk and conflicting verified ids both fail closed`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "123", false, 1L),
            ExternalIdentity("title", "myanimelist", "500", true, 1L),
        )
        linker(repo, FakeTransport(details(mu = "123", mal = "501")))
            .preview("title", work) { true }.isFailure shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `verified MAL cross-reference is parsed and links to its exact identity`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "myanimelist", "500", true, 1L),
        )
        val candidate = linker(
            repo,
            FakeTransport(details(mu = "8675309", mal = "500")),
        ).preview("title", work) { true }.getOrThrow()
        candidate.matchedProvider shouldBe "myanimelist"
        candidate.matchedExternalId shouldBe "500"
    }

    @Test
    fun `matching one provider does not override a contradictory verified crosswalk`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
            ExternalIdentity("title", "myanimelist", "500", true, 1L),
        )
        linker(
            repo,
            FakeTransport(details(mu = "8675309", mal = "501")),
        ).preview("title", work) { true }.isFailure shouldBe true
    }

    @Test
    fun `MangaDex response with different work id is rejected`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val invalid = details(mu = "8675309")
            .replace(work, "ffffffff-ffff-ffff-ffff-ffffffffffff")
        linker(repo, FakeTransport(invalid))
            .preview("title", work) { true }.isFailure shouldBe true
    }

    @Test
    fun `existing MangaDex ownership by another canonical title is not overwritten`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val other = mockk<tachiyomi.domain.tsuzuki.model.CanonicalTitle>()
        coEvery { other.id } returns "other"
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
        )
        coEvery { repo.getByExternalIdentity("mangadex", work) } returns other

        val linker = linker(repo, FakeTransport(details(mu = "8675309")))
        val candidate = linker.preview("title", work) { true }.getOrThrow()
        linker.confirm("title", candidate) { true }.isFailure shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `confirmation refuses a crosswalk withdrawn after a cached preview`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val network = FakeTransport(details(mu = "8675309"))
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
        )
        val linker = linker(repo, network)
        val preview = linker.preview("title", work) { true }.getOrThrow()
        network.freshResponse = details(mu = "999999")

        linker.confirm("title", preview) { true }.isFailure shouldBe true
        network.freshCalls shouldBe 1
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `confirmation refuses an independently verified work link revoked after preview`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        var identityReads = 0
        coEvery { repo.getExternalIdentities("title") } coAnswers {
            if (identityReads++ == 0) {
                listOf(ExternalIdentity("title", "mangaupdates", "8675309", true, 1L))
            } else {
                emptyList()
            }
        }
        val transport = FakeTransport(details(mu = "8675309"))
        val linker = linker(repo, transport)
        val candidate = linker.preview("title", work) { true }.getOrThrow()

        linker.confirm("title", candidate) { true }.isFailure shouldBe true
        transport.freshCalls shouldBe 1
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `invalid UUID cannot cause a network request`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val network = FakeTransport(details(mu = "8675309"))
        linker(repo, network).preview("title", "bad-id") { true }.isFailure shouldBe true
        network.calls shouldBe 0
    }

    @Test
    fun `revoked inventory permission prevents the MangaDex HTTP request`() = runTest {
        val network = FakeTransport(details(mu = "8675309"))
        val result = linker(mockk(), network)
            .preview("title", work) { false }

        result.isFailure shouldBe true
        network.calls shouldBe 0
    }

    @Test
    fun `revoked inventory permission after HTTP response prevents identity lookup`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        var enabled = true
        val network = FakeTransport(details(mu = "8675309")) { enabled = false }

        val result = linker(repo, network)
            .preview("title", work) { enabled }

        result.isFailure shouldBe true
        network.calls shouldBe 1
        coVerify(exactly = 0) { repo.getExternalIdentities("title") }
    }

    @Test
    fun `default off persisted capability prevents MangaDex preview request`() {
        runTest {
            integrationSettings.settings = integrationSettings.settings!!.copy(configJson = "{}")
            val network = FakeTransport(details(mu = "8675309"))

            val result = linker(mockk(), network).preview("title", work) { true }

            result.isFailure shouldBe true
            network.calls shouldBe 0
        }
    }

    @Test
    fun `revoked persisted integration prevents confirmation request and association`() {
        runTest {
            val repo = mockk<CanonicalTitleRepository>()
            coEvery { repo.getExternalIdentities("title") } returns listOf(
                ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
            )
            val network = FakeTransport(details(mu = "8675309"))
            val linker = linker(repo, network)
            val candidate = linker.preview("title", work) { true }.getOrThrow()
            integrationSettings.settings = integrationSettings.settings!!.copy(
                enabled = false,
                updatedAt = 2L,
            )

            linker.confirm("title", candidate) { true }.isFailure shouldBe true
            network.freshCalls shouldBe 0
            coVerify(exactly = 0) { repo.associateMangaDexWorkIfCurrent(any(), any()) }
            coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
        }
    }

    @Test
    fun `revoked inventory permission before persistence prevents MangaDex identity write`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        var enabled = true
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
        )
        coEvery { repo.getByExternalIdentity("mangadex", work) } coAnswers {
            enabled = false
            null
        }
        coEvery { repo.addExternalIdentity(any()) } returns Unit
        val linker = linker(repo, FakeTransport(details(mu = "8675309")))
        val candidate = linker.preview("title", work) { enabled }.getOrThrow()

        linker.confirm("title", candidate) { enabled }.isFailure shouldBe true

        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `persisted revocation after HTTP response prevents identity lookup when registry remains enabled`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val network = FakeTransport(details(mu = "8675309")) {
            integrationSettings.settings = integrationSettings.settings!!.copy(
                enabled = false,
                updatedAt = 2L,
            )
        }

        val result = linker(repo, network).preview("title", work) { true }

        result.isFailure shouldBe true
        network.calls shouldBe 1
        coVerify(exactly = 0) { repo.getExternalIdentities("title") }
    }

    @Test
    fun `persisted revocation while waiting for dispatch blocks HTTP when registry remains enabled`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val network = DispatchGateTransport(details(mu = "8675309"))
        val preview = async { linker(repo, network).preview("title", work) { true } }
        runCurrent()
        network.dispatchWaiting.await()
        integrationSettings.settings = integrationSettings.settings!!.copy(
            enabled = false,
            updatedAt = 2L,
        )
        network.resumeDispatch.complete(Unit)

        preview.await().isFailure shouldBe true
        network.requests shouldBe 0
        coVerify(exactly = 0) { repo.getExternalIdentities("title") }
    }

    @Test
    fun `crosswalk revoked during ownership lookup prevents MangaDex identity write`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        var identityReads = 0
        coEvery { repo.getExternalIdentities("title") } coAnswers {
            if (identityReads++ < 3) {
                listOf(ExternalIdentity("title", "mangaupdates", "8675309", true, 1L))
            } else {
                emptyList()
            }
        }
        val ownerResponse = CompletableDeferred<tachiyomi.domain.tsuzuki.model.CanonicalTitle?>()
        coEvery { repo.getByExternalIdentity("mangadex", work) } coAnswers { ownerResponse.await() }
        coEvery { repo.addExternalIdentity(any()) } returns Unit
        val linker = linker(repo, FakeTransport(details(mu = "8675309")))
        val candidate = linker.preview("title", work) { true }.getOrThrow()

        val confirmation = async { linker.confirm("title", candidate) { true } }
        runCurrent()
        ownerResponse.complete(null)
        advanceUntilIdle()

        confirmation.await().isFailure shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `cancellation during ownership lookup prevents MangaDex identity write`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
        )
        val ownerResponse = CompletableDeferred<tachiyomi.domain.tsuzuki.model.CanonicalTitle?>()
        coEvery { repo.getByExternalIdentity("mangadex", work) } coAnswers {
            try {
                ownerResponse.await()
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { ownerResponse.await() }
            }
        }
        coEvery { repo.addExternalIdentity(any()) } returns Unit
        val linker = linker(repo, FakeTransport(details(mu = "8675309")))
        val candidate = linker.preview("title", work) { true }.getOrThrow()
        val confirmation = async { linker.confirm("title", candidate) { true } }
        runCurrent()

        confirmation.cancel()
        ownerResponse.complete(null)
        advanceUntilIdle()

        confirmation.isCancelled shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `automatic title discovery selects only a unique independently cross-referenced MangaDex work`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val canonical = mockk<tachiyomi.domain.tsuzuki.model.CanonicalTitle>()
        io.mockk.every { canonical.displayTitle } returns "Exact Manga"
        coEvery { repo.getById("title") } returns canonical
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "myanimelist", "500", true, 1L),
        )
        val network = FakeTransport(details(mu = "8675309", mal = "500")).apply {
            searchResponse = """{"result":"ok","data":[
                {"id":"$work","type":"manga","attributes":{"title":{"en":"Exact Manga"},"links":{"mal":"500"}}},
                {"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","type":"manga",
                 "attributes":{"title":{"en":"Similar Manga"},"links":{"mal":"501"}}}
            ]}"""
        }

        val match = linker(repo, network).discoverUnique("title") { true }.getOrThrow()

        match?.mangaDexId shouldBe work
        match?.matchedProvider shouldBe "myanimelist"
        network.calls shouldBe 1
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `automatic discovery refuses ambiguous crosswalks and never verifies from title alone`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val canonical = mockk<tachiyomi.domain.tsuzuki.model.CanonicalTitle>()
        io.mockk.every { canonical.displayTitle } returns "Exact Manga"
        coEvery { repo.getById("title") } returns canonical
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "myanimelist", "500", true, 1L),
        )
        val network = FakeTransport(details(mu = "8675309", mal = "500")).apply {
            searchResponse = """{"result":"ok","data":[
                {"id":"$work","type":"manga","attributes":{"title":{"en":"Exact Manga"},"links":{"mal":"500"}}},
                {"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","type":"manga",
                 "attributes":{"title":{"en":"Exact Manga"},"links":{"mal":"500"}}}
            ]}"""
        }
        linker(repo, network).discoverUnique("title") { true }.isFailure shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }

        coEvery { repo.getExternalIdentities("title") } returns emptyList()
        linker(repo, network).discoverUnique("title") { true }.getOrThrow() shouldBe null
    }

    @Test
    fun `bounded observed aliases discover works that are absent from canonical display-title search`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val canonical = mockk<tachiyomi.domain.tsuzuki.model.CanonicalTitle>()
        io.mockk.every { canonical.displayTitle } returns "Main Title"
        coEvery { repo.getById("title") } returns canonical
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "myanimelist", "500", true, 1L),
        )
        val names = mockk<TitleNameObservationRepository>()
        coEvery { names.getByTitle("title") } returns listOf(
            TitleNameObservation("title", "kitsu", "alternative", "Alt Title", 1L),
        )
        val network = FakeTransport(details(mu = "8675309", mal = "500"))
        network.searchResponse = """{"result":"ok","data":[]}"""
        network.aliasSearchResponse = """{"result":"ok","data":[
            {"id":"$work","type":"manga","attributes":{"title":{"en":"Exact Manga"},"links":{"mal":"500"}}}
        ]}"""
        val found = MangaDexVerifiedWorkLinker(repo, integrationSettings, network, names)
            .discoverUnique("title") { true }.getOrThrow()

        found?.mangaDexId shouldBe work
        network.calls shouldBe 2
    }

    private fun details(mu: String, mal: String? = null): String {
        val malField = mal?.let { ",\"mal\":\"$it\"" } ?: ""
        return """{"result":"ok","data":{"id":"$work","type":"manga","attributes":""" +
            """{"title":{"en":"Exact Manga"},"links":{"mu":"$mu"$malField}}}}"""
    }

    private fun linker(
        titles: CanonicalTitleRepository,
        transport: InventoryReleaseTransport,
    ) = MangaDexVerifiedWorkLinker(
        titles, integrationSettings, transport,
        mockk<TitleNameObservationRepository>().also {
            coEvery { it.getByTitle(any()) } returns emptyList()
        },
    )

    private class MutableIntegrationSettingsRepository(
        var settings: IntegrationSettings?,
    ) : IntegrationSettingsRepository {
        override suspend fun get(id: IntegrationId): IntegrationSettings? =
            settings?.takeIf { it.integrationId == id }

        override fun observeAll(): Flow<List<IntegrationSettings>> = flowOf(listOfNotNull(settings))

        override suspend fun upsert(settings: IntegrationSettings) {
            this.settings = settings
        }
    }

    private class DispatchGateTransport(
        private val response: String,
    ) : InventoryReleaseTransport {
        val dispatchWaiting = CompletableDeferred<Unit>()
        val resumeDispatch = CompletableDeferred<Unit>()
        var requests = 0

        override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload =
            error("Authorized requests must use the guarded transport path")

        override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
            error("Authorized requests must use the guarded transport path")

        override suspend fun fetchAuthorized(
            url: String,
            postBody: String?,
            isEnabled: suspend () -> Boolean,
        ): InventoryHttpPayload {
            check(isEnabled())
            dispatchWaiting.complete(Unit)
            resumeDispatch.await()
            check(isEnabled())
            requests++
            return InventoryHttpPayload(response, observedAt = 10L)
        }

        override suspend fun fetchFreshAuthorized(
            url: String,
            postBody: String?,
            isEnabled: suspend () -> Boolean,
        ): InventoryHttpPayload = fetchAuthorized(url, postBody, isEnabled)
    }

    private class FakeTransport(
        private val response: String,
        private val onResponse: (() -> Unit)? = null,
    ) : InventoryReleaseTransport {
        var calls = 0
        var freshCalls = 0
        var freshResponse = response
        var searchResponse = response
        var aliasSearchResponse: String? = null
        override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload {
            calls++
            onResponse?.invoke()
            return InventoryHttpPayload(
                if (url.startsWith("https://api.mangadex.org/manga?")) {
                    if (url.contains("Alt%20Title")) aliasSearchResponse ?: searchResponse else searchResponse
                } else {
                    response
                },
                observedAt = 10L,
            )
        }
        override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload {
            freshCalls++
            return InventoryHttpPayload(freshResponse, observedAt = 11L)
        }
    }
}
