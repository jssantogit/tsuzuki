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
    fun `MangaUpdates empty ID release search retries by verified series title and filters exact series`() = runTest {
        val calls = mutableListOf<Pair<String, String?>>()
        val transport = object : InventoryReleaseTransport {
            override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload {
                calls += url to postBody
                val payload = when {
                    url.endsWith("/series/123") -> """{"series_id":123,"title":"Exact Manga"}"""
                    postBody?.contains("Exact Manga") == true ->
                        """{"total_hits":1,"page":1,"per_page":25,"results":[
                            {"record":{"id":10,"chapter":"4","volume":"1"},
                             "metadata":{"series":{"series_id":123}}}
                        ]}"""
                    else -> """{"total_hits":0,"page":1,"per_page":25,"results":[]}"""
                }
                return InventoryHttpPayload(payload, observedAt = 42L)
            }
            override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
                fetch(url, postBody)
        }

        val batch = MangaUpdatesInventoryProducer(transport)
            .fetchPage(scope("mangaupdates", "123"), "start").getOrThrow()

        batch.claims.single().label shouldBe "4"
        batch.claims.single().derivation shouldBe InventoryDerivation.RELEASE_RECORD
        calls.size shouldBe 3
    }

    @Test
    fun `verified MangaUpdates title fallback persists across all release pages`() = runTest {
        val transport = object : InventoryReleaseTransport {
            override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload {
                val page = if (postBody?.contains("\"page\":2") == true) 2 else 1
                val content = when {
                    url.endsWith("/series/123") -> """{"series_id":123,"title":"Exact Manga"}"""
                    postBody?.contains("Exact Manga") == true -> """
                        {"total_hits":2,"page":$page,"per_page":1,"results":[
                            {"record":{"id":$page,"chapter":"$page"},
                             "metadata":{"series":{"series_id":123}}}
                        ]}
                    """.trimIndent()
                    else -> """{"total_hits":0,"page":$page,"per_page":1,"results":[]}"""
                }
                return InventoryHttpPayload(content, observedAt = 42L)
            }

            override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
                fetch(url, postBody)
        }
        val producer = MangaUpdatesInventoryProducer(transport)
        val first = producer.fetchPage(scope("mangaupdates", "123"), "start").getOrThrow()
        val second = producer.fetchPage(scope("mangaupdates", "123"), "page:2").getOrThrow()

        first.nextCursor shouldBe "page:2"
        first.claims.single().label shouldBe "1"
        second.claims.single().label shouldBe "2"
        second.outcome shouldBe InventoryFetchOutcome.EXHAUSTED
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
    fun `MangaUpdates fallback checks live consent before each release HTTP request`() = runTest {
        var enabled = true
        val requested = mutableListOf<String>()
        val transport = object : InventoryReleaseTransport {
            override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload =
                error("Release producer bypassed the authorized transport")

            override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
                error("Release producer bypassed the authorized transport")

            override suspend fun fetchAuthorized(
                url: String,
                postBody: String?,
                isEnabled: suspend () -> Boolean,
            ): InventoryHttpPayload {
                check(isEnabled()) { "Consent revoked before HTTP request" }
                requested += url
                if (requested.size == 1) {
                    enabled = false
                    return InventoryHttpPayload(
                        """{"total_hits":0,"page":1,"per_page":25,"results":[]}""",
                        observedAt = 42L,
                    )
                }
                error("No request may start after revocation")
            }
        }

        val result = MangaUpdatesInventoryProducer(transport)
            .fetchPageAuthorized(scope("mangaupdates", "123"), "start") { enabled }

        result.isFailure shouldBe true
        requested shouldBe listOf("https://api.mangaupdates.com/v1/releases/search")
    }

    @Test
    fun `MangaDex release fetch cannot bypass the live authorization callback`() = runTest {
        var unsafeCalls = 0
        var guardedCalls = 0
        val transport = object : InventoryReleaseTransport {
            override suspend fun fetch(url: String, postBody: String?): InventoryHttpPayload {
                unsafeCalls++
                return InventoryHttpPayload("{}", observedAt = 42L)
            }

            override suspend fun fetchFresh(url: String, postBody: String?): InventoryHttpPayload =
                fetch(url, postBody)

            override suspend fun fetchAuthorized(
                url: String,
                postBody: String?,
                isEnabled: suspend () -> Boolean,
            ): InventoryHttpPayload {
                guardedCalls++
                check(isEnabled()) { "Consent revoked before HTTP dispatch" }
                error("Unexpected dispatched request")
            }
        }

        MangaDexInventoryProducer(transport)
            .fetchPageAuthorized(
                scope("mangadex", "12345678-1234-1234-1234-123456789abc"),
                "start",
            ) { false }.isFailure shouldBe true

        unsafeCalls shouldBe 0
        guardedCalls shouldBe 1
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
