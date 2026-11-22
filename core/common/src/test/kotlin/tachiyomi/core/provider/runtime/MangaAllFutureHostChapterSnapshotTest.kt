package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MangaAllFutureHostChapterSnapshotTest {
    @TempDir
    lateinit var tempDir: Path

    private val providerId = "app.tsuzuki.mangaall"
    private val fixedClock = 1_783_000_000_000L
    private var now = fixedClock
    private var nonce = 0
    private val nextNonce: () -> String = {
        (++nonce).toString(16).padStart(16, '0')
    }

    private fun source(count: Int, padding: Int = 550, title: String = "Chapter"): String {
        // Extra public API fields are deliberately omitted from the compact
        // seven-field metadata snapshot.
        val records = (1..count).map { number ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(13100000 + number),
                    "chapter_number" to JsonPrimitive(number),
                    "language" to JsonPrimitive("en"),
                    "source" to JsonPrimitive("user"),
                    "chapter_title" to JsonPrimitive(title + " " + number),
                    "groups" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("Ju-Ni"))))),
                    "ignored" to JsonPrimitive("x".repeat(padding)),
                ),
            )
        }
        return JsonArray(records).toString()
    }

    private fun fixture(
        rawSource: () -> String,
        existingStorage: FileProviderStorageHostService? = null,
    ): Pair<MangaAllFutureHostChapterSnapshot, () -> Int> {
        val owner = ProviderResourceOwner(providerId, "test-invocation")
        val resources = ProviderResourceStore()
        var calls = 0
        val http = object : ProviderHttpHostService {
            override suspend fun getText(url: String): String =
                error("Large chapter lists must never use the 192KiB text interface")

            override suspend fun getResource(url: String): ProviderResourceHandle {
                url shouldBe "https://mangadot.net/api/manga/131/chapters/list?lang=en"
                calls++
                return resources.put(
                    owner,
                    ProviderResourceKind.HTML,
                    rawSource().toByteArray(Charsets.UTF_8),
                )
            }
        }
        val service = MangaAllFutureHostChapterSnapshot(
            owner = owner,
            http = http,
            resources = resources,
            storage = existingStorage ?: FileProviderStorageHostService(
                root = tempDir.resolve("snapshots").toFile(),
                providerId = providerId,
            ),
            clock = { now },
            generation = nextNonce,
        )
        return service to { calls }
    }

    @Test
    fun host_reconstructs_4341_chapters_from_over_2mib_body_in_bounded_pages_across_calls(): Unit = runBlocking {
        val body = source(4341)
        (body.toByteArray().size > 2 * 1024 * 1024) shouldBe true
        val (service, calls) = fixture { body }
        var page = service.start("mangadot:131", "en")
        val ids = mutableListOf<String>()
        var pages = 0
        do {
            pages++
            val serialized = JsonObject(
                mapOf(
                    "items" to JsonArray(page.items),
                    "nextCursor" to (page.nextCursor?.let(::JsonPrimitive) ?: JsonNull),
                ),
            ).toString()
            (serialized.length <= 48 * 1024) shouldBe true
            (serialized.toByteArray().size <= 48 * 1024) shouldBe true
            (page.items.size <= 256) shouldBe true
            ids += page.items.map { it["providerChapterId"]!!.jsonPrimitive.content }
            val cursor = page.nextCursor ?: break
            page = service.next("mangadot:131", "en", cursor)
        } while (pages <= 32)
        (pages in 17..32) shouldBe true
        ids.size shouldBe 4341
        ids.distinct().size shouldBe 4341
        ids.first() shouldBe "mangadot:uploads:13100001"
        ids.last() shouldBe "mangadot:uploads:13104341"
        calls() shouldBe 1
    }

    @Test
    fun snapshot_cursor_is_work_facet_generation_and_ttl_scoped(): Unit = runBlocking {
        val (service, calls) = fixture { source(400, 1) }
        val first = service.start("mangadot:131", "en")
        val cursor = first.nextCursor!!
        (
            runCatching {
                service.next("mangadot:999", "en", cursor)
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true
        (
            runCatching {
                service.next("mangadot:131", "pt-br", cursor)
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true
        (
            runCatching {
                service.next("mangadot:131", "en", cursor.replace(":256", ":99999"))
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true
        val second = service.start("mangadot:131", "en")
        (second.nextCursor != first.nextCursor) shouldBe true
        (
            runCatching {
                service.next("mangadot:131", "en", cursor)
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true
        now += 5 * 60 * 1000 + 1
        (
            runCatching {
                service.next("mangadot:131", "en", second.nextCursor!!)
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true
        calls() shouldBe 2
    }

    @Test
    fun chapter_identity_keeps_upload_and_independent_source_chapter_with_same_numeric_id(): Unit = runBlocking {
        val raw = """[
            {"id":171,"chapter_number":2,"language":"en","source":"user"},
            {"id":171,"chapter_number":2,"language":"en","source":"other"},
            {"id":171,"chapter_number":2,"language":"en","source":"user"}
        ]""".trimIndent()
        val (service, calls) = fixture { raw }
        val result = service.start("mangadot:131", "en")
        result.items.map { it["providerChapterId"]!!.jsonPrimitive.content } shouldBe listOf(
            "mangadot:uploads:171",
            "mangadot:chapters:171",
        )
        result.nextCursor shouldBe null
        calls() shouldBe 1
    }

    @Test
    fun failed_shared_quota_preflight_keeps_unrelated_storage_and_previous_snapshot(): Unit = runBlocking {
        val shared = FileProviderStorageHostService(
            root = tempDir.resolve("shared-storage").toFile(),
            providerId = providerId,
        )
        repeat(6) { shared.set("other" + it, "a".repeat(60 * 1024)) }
        var current = source(400, 0)
        val (service, _) = fixture({ current }, existingStorage = shared)
        val committed = service.start("mangadot:131", "en")
        val oldCursor = committed.nextCursor!!
        val oldIndex = shared.get("mangaall_private_chapters_index")
        (oldIndex != null) shouldBe true

        // Compact source rows fit the dedicated 440KiB envelope, but the
        // combined full provider storage exceeds 512KiB due to unrelated keys.
        current = source(4341, 0)
        (runCatching {
            service.start("mangadot:131", "en")
        }.exceptionOrNull() is ProviderHostServiceException) shouldBe true

        shared.get("mangaall_private_chapters_index") shouldBe oldIndex
        repeat(6) { shared.get("other" + it) shouldBe "a".repeat(60 * 1024) }
        service.next("mangadot:131", "en", oldCursor).items.isNotEmpty() shouldBe true
    }

    @Test
    fun utf8_overbudget_and_invalid_source_do_not_evict_committed_snapshot(): Unit = runBlocking {
        var current = source(400, 1)
        val (service, _) = fixture { current }
        val first = service.start("mangadot:131", "en")
        val next = first.nextCursor!!
        // Fifty CJK titles fit the 440KiB *character* envelope, not UTF-8 bytes.
        current = source(50, 0, "界".repeat(3900))
        val error = runCatching { service.start("mangadot:131", "en") }.exceptionOrNull()
        (error is ProviderHostServiceException) shouldBe true
        service.next("mangadot:131", "en", next).items.isNotEmpty() shouldBe true

        current = """[{"id":17,"chapter_number":1,"language":"pt-br"}]"""
        (
            runCatching {
                service.start("mangadot:131", "en")
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true
        service.next("mangadot:131", "en", next).items.isNotEmpty() shouldBe true

        (
            runCatching {
                service.start("mangadot:131", "fr")
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true
        (
            runCatching {
                MangaAllFutureHostChapterSnapshot(
                    ProviderResourceOwner("foreign.provider", "test"),
                    object : ProviderHttpHostService {
                        override suspend fun getText(url: String) = ""
                        override suspend fun getResource(url: String) =
                            ProviderResourceHandle("none")
                    },
                    ProviderResourceStore(),
                    FileProviderStorageHostService(tempDir.resolve("foreign").toFile(), "foreign.provider"),
                )
            }.exceptionOrNull() is IllegalArgumentException
            ) shouldBe true
    }
}
