package eu.kanade.tachiyomi.data.tsuzuki.inventory

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class MangaDexVerifiedWorkLinkerTest {
    private val work = "12345678-1234-1234-1234-123456789abc"

    @Test
    fun `MangaDex id can be linked only through a matching verified external work crosswalk`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val network = FakeTransport(details(mu = "8675309"))
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "8675309", true, 1L),
        )
        coEvery { repo.getByExternalIdentity("mangadex", work) } returns null
        coEvery { repo.addExternalIdentity(any()) } returns Unit

        val linker = MangaDexVerifiedWorkLinker(repo, network)
        val candidate = linker.preview("title", work) { true }.getOrThrow()
        candidate.displayTitle shouldBe "Exact Manga"
        candidate.matchedProvider shouldBe "mangaupdates"
        linker.confirm("title", candidate) { true }.isSuccess shouldBe true
        coVerify(exactly = 1) {
            repo.addExternalIdentity(
                match {
                    it.canonicalTitleId == "title" && it.provider == "mangadex" &&
                        it.externalId == work && it.verified
                },
            )
        }
    }

    @Test
    fun `title similarity without exact crosswalk cannot verify a MangaDex work`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "mangaupdates", "123", true, 1L),
        )
        val linker = MangaDexVerifiedWorkLinker(repo, FakeTransport(details(mu = "999")))
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
        MangaDexVerifiedWorkLinker(repo, FakeTransport(details(mu = "123", mal = "501")))
            .preview("title", work) { true }.isFailure shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `verified MAL cross-reference is parsed and links to its exact identity`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        coEvery { repo.getExternalIdentities("title") } returns listOf(
            ExternalIdentity("title", "myanimelist", "500", true, 1L),
        )
        val candidate = MangaDexVerifiedWorkLinker(
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
        MangaDexVerifiedWorkLinker(
            repo,
            FakeTransport(details(mu = "8675309", mal = "501")),
        ).preview("title", work) { true }.isFailure shouldBe true
    }

    @Test
    fun `MangaDex response with different work id is rejected`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val invalid = details(mu = "8675309")
            .replace(work, "ffffffff-ffff-ffff-ffff-ffffffffffff")
        MangaDexVerifiedWorkLinker(repo, FakeTransport(invalid))
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

        val linker = MangaDexVerifiedWorkLinker(repo, FakeTransport(details(mu = "8675309")))
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
        val linker = MangaDexVerifiedWorkLinker(repo, network)
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
        val linker = MangaDexVerifiedWorkLinker(repo, transport)
        val candidate = linker.preview("title", work) { true }.getOrThrow()

        linker.confirm("title", candidate) { true }.isFailure shouldBe true
        transport.freshCalls shouldBe 1
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    @Test
    fun `invalid UUID cannot cause a network request`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        val network = FakeTransport(details(mu = "8675309"))
        MangaDexVerifiedWorkLinker(repo, network).preview("title", "bad-id") { true }.isFailure shouldBe true
        network.calls shouldBe 0
    }

    @Test
    fun `revoked inventory permission prevents the MangaDex HTTP request`() = runTest {
        val network = FakeTransport(details(mu = "8675309"))
        val result = MangaDexVerifiedWorkLinker(mockk(), network)
            .preview("title", work) { false }

        result.isFailure shouldBe true
        network.calls shouldBe 0
    }

    @Test
    fun `revoked inventory permission after HTTP response prevents identity lookup`() = runTest {
        val repo = mockk<CanonicalTitleRepository>()
        var enabled = true
        val network = FakeTransport(details(mu = "8675309")) { enabled = false }

        val result = MangaDexVerifiedWorkLinker(repo, network)
            .preview("title", work) { enabled }

        result.isFailure shouldBe true
        network.calls shouldBe 1
        coVerify(exactly = 0) { repo.getExternalIdentities("title") }
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
        val linker = MangaDexVerifiedWorkLinker(repo, FakeTransport(details(mu = "8675309")))
        val candidate = linker.preview("title", work) { enabled }.getOrThrow()

        linker.confirm("title", candidate) { enabled }.isFailure shouldBe true

        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
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
        val linker = MangaDexVerifiedWorkLinker(repo, FakeTransport(details(mu = "8675309")))
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
        val linker = MangaDexVerifiedWorkLinker(repo, FakeTransport(details(mu = "8675309")))
        val candidate = linker.preview("title", work) { true }.getOrThrow()
        val confirmation = async { linker.confirm("title", candidate) { true } }
        runCurrent()

        confirmation.cancel()
        ownerResponse.complete(null)
        advanceUntilIdle()

        confirmation.isCancelled shouldBe true
        coVerify(exactly = 0) { repo.addExternalIdentity(any()) }
    }

    private fun details(mu: String, mal: String? = null): String {
        val malField = mal?.let { ",\"mal\":\"$it\"" } ?: ""
        return """{"result":"ok","data":{"id":"$work","type":"manga","attributes":""" +
            """{"title":{"en":"Exact Manga"},"links":{"mu":"$mu"$malField}}}}"""
    }

    private class FakeTransport(
        private val response: String,
        private val onResponse: (() -> Unit)? = null,
    ) : InventoryReleaseTransport {
        var calls = 0
        var freshCalls = 0
        var freshResponse = response
        override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload {
            calls++
            onResponse?.invoke()
            return InventoryHttpPayload(response, observedAt = 10L)
        }
        override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload {
            freshCalls++
            return InventoryHttpPayload(freshResponse, observedAt = 11L)
        }
    }
}
