package eu.kanade.tachiyomi.data.tsuzuki.inventory

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope

class NativeIndependentInventoryProducerTest {
    private fun scope(producer: String, externalId: String) = InventoryScope(
        canonicalTitleId = "title",
        producerId = producer,
        externalWorkId = externalId,
        editionKey = null,
        language = null,
        workIdentityVerified = true,
        editionIdentityVerified = false,
    )

    @Test
    fun `MangaUpdates release page retains ranges and cursor without chapter authority`() = runTest {
        val transport = RecordingInventoryTransport(
            """{"total_hits":26,"page":1,"per_page":25,"results":[{"record":{"id":10,"chapter":"246-251","volume":"8"},"metadata":{"series":{"series_id":123}}},{"record":{"id":11,"chapter":"0.01","volume":""},"metadata":{"series":{"series_id":123}}}]}""",
        )
        val page = MangaUpdatesInventoryProducer(
            transport,
        ).fetchPage(scope("mangaupdates", "123"), "start").getOrThrow()

        page.outcome shouldBe InventoryFetchOutcome.PARTIAL
        page.nextCursor shouldBe "page:2"
        page.declaredTotal shouldBe null
        page.claims.map { it.kind } shouldBe listOf(InventoryClaimKind.RELEASE_RANGE, InventoryClaimKind.INDIVIDUAL)
        page.claims.map { it.derivation }.distinct() shouldBe listOf(InventoryDerivation.RELEASE_RECORD)
        page.claims.last().label shouldBe "0.01"
        transport.calls.size shouldBe 1
    }

    @Test
    fun `cached response preserves original observed timestamp instead of inventing a fresh observation`() = runTest {
        val body = """
            {"total_hits":1,"page":1,"per_page":25,"results":[
                {"record":{"id":10,"chapter":"12.05"},"metadata":{"series":{"series_id":123}}}
            ]}
        """.trimIndent()
        val cached = InventoryHttpPayload(body, observedAt = 42L)
        val transport = object : InventoryReleaseTransport {
            override suspend fun fetch(url: String, postBody: String?) = cached
            override suspend fun fetchFresh(url: String, postBody: String?) = cached
        }
        val batch = MangaUpdatesInventoryProducer(transport)
            .fetchPage(scope("mangaupdates", "123"), "start").getOrThrow()

        batch.observedAt shouldBe 42L
        batch.claims.single().observedAt shouldBe 42L
    }

    @Test
    fun `MangaUpdates wrong series within HTTP 200 fails closed`() = runTest {
        val transport = RecordingInventoryTransport(
            """{"total_hits":1,"page":1,"per_page":25,"results":[{"record":{"id":10,"chapter":"1"},"metadata":{"series":{"series_id":999}}}]}""",
        )
        MangaUpdatesInventoryProducer(transport)
            .fetchPage(scope("mangaupdates", "123"), "start").isFailure shouldBe true
    }

    @Test
    fun `MangaDex feed must prove manga relationship on every release`() = runTest {
        val transport = RecordingInventoryTransport(
            """{"result":"ok","total":1,"offset":0,"limit":100,"data":[{"id":"release-1","attributes":{"chapter":"12.05","volume":"2","translatedLanguage":"en"},"relationships":[{"id":"12345678-1234-1234-1234-123456789abc","type":"manga"}]}]}""",
        )
        val page = MangaDexInventoryProducer(transport)
            .fetchPage(scope("mangadex", "12345678-1234-1234-1234-123456789abc"), "start").getOrThrow()

        page.outcome shouldBe InventoryFetchOutcome.EXHAUSTED
        page.claims.single().label shouldBe "12.05"
        page.claims.single().language shouldBe "en"
        page.claims.single().derivation shouldBe InventoryDerivation.RELEASE_RECORD
        page.declaredTotal shouldBe null
    }

    @Test
    fun `MangaDex missing or mismatched manga relationship rejects the entire page`() = runTest {
        val json = """
            {"result":"ok","total":1,"offset":0,"limit":100,"data":[
                {"id":"release-1","attributes":{"chapter":"1"},"relationships":[
                    {"id":"other-uuid","type":"manga"}
                ]}
            ]}
        """.trimIndent()
        MangaDexInventoryProducer(RecordingInventoryTransport(json))
            .fetchPage(scope("mangadex", "12345678-1234-1234-1234-123456789abc"), "start").isFailure shouldBe true
    }

    @Test
    fun `malformed MangaDex work identifiers fail before invoking the release transport`() = runTest {
        val transport = RecordingInventoryTransport("{}")
        val invalid = listOf("-".repeat(36), "a".repeat(36), "12345678-1234-1234-1234-123456789abg")
        invalid.forEach { id ->
            MangaDexInventoryProducer(transport)
                .fetchPage(scope("mangadex", id), "start").isFailure shouldBe true
        }
        transport.calls shouldBe emptyList()
    }

    @Test
    fun `invalid cursor never starts a request`() = runTest {
        val transport = RecordingInventoryTransport("{}")
        MangaUpdatesInventoryProducer(transport).fetchPage(scope("mangaupdates", "123"), "page:-1").isFailure shouldBe
            true
        MangaDexInventoryProducer(
            transport,
        ).fetchPage(scope("mangadex", "12345678-1234-1234-1234-123456789abc"), "offset:100000").isFailure shouldBe
            true
        transport.calls shouldBe emptyList()
    }

    private class RecordingInventoryTransport(private val response: String) : InventoryReleaseTransport {
        val calls = mutableListOf<Pair<String, String?>>()
        override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload {
            calls += url to postBody
            return InventoryHttpPayload(response, observedAt = 42L)
        }

        override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
            fetch(url, postBody)
    }
}
