package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

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
        existingStorage: FileProviderStorageHostService? = null,
        expectedUrl: String = "https://mangadot.net/api/manga/131/chapters/list?lang=en",
        rawSource: () -> String,
    ): Pair<MangaAllFutureHostChapterSnapshot, () -> Int> {
        val owner = ProviderResourceOwner(providerId, "test-invocation")
        val resources = ProviderResourceStore()
        var calls = 0
        val http = object : ProviderHttpHostService {
            override suspend fun getText(url: String): String =
                error("Large chapter lists must never use the 192KiB text interface")

            override suspend fun getResource(url: String): ProviderResourceHandle {
                url shouldBe expectedUrl
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
    fun snapshots_for_two_works_and_languages_survive_new_host_instances(): Unit = runBlocking {
        val root = tempDir.resolve("scoped-snapshots").toFile()
        val shared = FileProviderStorageHostService(root, providerId)
        val (first, _) = fixture(existingStorage = shared) { source(400, 0, "First") }
        val (second, _) = fixture(
            existingStorage = shared,
            expectedUrl = "https://mangadot.net/api/manga/132/chapters/list?lang=en",
        ) { source(400, 0, "Second") }
        val (translated, _) = fixture(
            existingStorage = shared,
            expectedUrl = "https://mangadot.net/api/manga/131/chapters/list?lang=pt-br",
        ) { source(400, 0, "Translated").replace("\"en\"", "\"pt-br\"") }

        val firstCursor = first.start("mangadot:131", "en").nextCursor!!
        val secondCursor = second.start("mangadot:132", "en").nextCursor!!
        val translatedCursor = translated.start("mangadot:131", "pt-br").nextCursor!!
        val (reopened, calls) = fixture(
            existingStorage = FileProviderStorageHostService(root, providerId),
        ) { error("Resume must not acquire HTTP") }

        reopened.next("mangadot:131", "en", firstCursor)
            .items.first()["title"]!!.jsonPrimitive.content.startsWith("First ") shouldBe true
        reopened.next("mangadot:132", "en", secondCursor)
            .items.first()["title"]!!.jsonPrimitive.content.startsWith("Second ") shouldBe true
        reopened.next("mangadot:131", "pt-br", translatedCursor)
            .items.first()["title"]!!.jsonPrimitive.content.startsWith("Translated ") shouldBe true
        (
            runCatching { reopened.next("mangadot:132", "en", firstCursor) }.exceptionOrNull()
                is ProviderHostServiceException
            ) shouldBe true
        calls() shouldBe 0

        first.start("mangadot:131", "en")
        (
            runCatching { reopened.next("mangadot:131", "en", firstCursor) }.exceptionOrNull()
                is ProviderHostServiceException
            ) shouldBe true
        reopened.next("mangadot:132", "en", secondCursor).items.isNotEmpty() shouldBe true
        reopened.next("mangadot:131", "pt-br", translatedCursor).items.isNotEmpty() shouldBe true
    }

    @Test
    fun quota_rejection_preserves_other_active_scope_and_expired_scope_is_pruned(): Unit = runBlocking {
        val shared = FileProviderStorageHostService(
            root = tempDir.resolve("scoped-quota").toFile(),
            providerId = providerId,
        )
        repeat(6) { shared.set("other" + it, "a".repeat(60 * 1024)) }
        val (first, _) = fixture(existingStorage = shared) { source(1900, 0, "First") }
        val (second, _) = fixture(
            existingStorage = shared,
            expectedUrl = "https://mangadot.net/api/manga/132/chapters/list?lang=en",
        ) { source(1900, 0, "Second") }
        val cursor = first.start("mangadot:131", "en").nextCursor!!
        (
            runCatching { second.start("mangadot:132", "en") }.exceptionOrNull()
                is ProviderHostServiceException
            ) shouldBe true
        first.next("mangadot:131", "en", cursor).items.isNotEmpty() shouldBe true
        shared.get("mangaall_private_chapters_132_en_index") shouldBe null

        now += 5 * 60 * 1000 + 1
        second.start("mangadot:132", "en").items.isNotEmpty() shouldBe true
        shared.get("mangaall_private_chapters_131_en_index") shouldBe null
        shared.get("mangaall_private_chapters_131_en_part_0") shouldBe null
        repeat(6) { shared.get("other" + it) shouldBe "a".repeat(60 * 1024) }
    }

    @Test
    fun completed_generation_is_replayed_until_pressure_then_reclaimed_without_waiting_for_ttl(): Unit = runBlocking {
        val shared = FileProviderStorageHostService(
            root = tempDir.resolve("terminal-quota").toFile(),
            providerId = providerId,
        )
        repeat(6) { shared.set("other" + it, "a".repeat(60 * 1024)) }
        val (first, _) = fixture(existingStorage = shared) { source(1900, 0, "First") }
        val (second, _) = fixture(
            existingStorage = shared,
            expectedUrl = "https://mangadot.net/api/manga/132/chapters/list?lang=en",
        ) { source(1900, 0, "Second") }
        var page = first.start("mangadot:131", "en")
        var terminalRequest = page.nextCursor!!
        while (page.nextCursor != null) {
            terminalRequest = page.nextCursor!!
            page = first.next("mangadot:131", "en", terminalRequest)
        }
        first.next("mangadot:131", "en", terminalRequest) shouldBe page
        val secondPage = second.start("mangadot:132", "en")
        secondPage.nextCursor.isNullOrBlank() shouldBe false
        shared.get("mangaall_private_chapters_131_en_index") shouldBe null
        shared.get("mangaall_private_chapters_131_en_part_0") shouldBe null
        second.next("mangadot:132", "en", secondPage.nextCursor!!).items.isNotEmpty() shouldBe true
        repeat(6) { shared.get("other" + it) shouldBe "a".repeat(60 * 1024) }
    }

    @Test
    fun completion_of_old_generation_does_not_mark_replacement(): Unit = runBlocking {
        val shared = FileProviderStorageHostService(tempDir.resolve("terminal-cas").toFile(), providerId)
        val (service, _) = fixture(existingStorage = shared) { source(400, 0) }
        val original = service.start("mangadot:131", "en").nextCursor!!
        val replacement = service.start("mangadot:131", "en").nextCursor!!
        shared.markFutureMangaAllChapterSnapshotCompleted("mangadot:131", "en", original.split(':')[3])
        val header = Json.parseToJsonElement(shared.get("mangaall_private_chapters_131_en_index")!!).jsonObject
        header["completed"] shouldBe JsonPrimitive(false)
        service.next("mangadot:131", "en", replacement).items.isNotEmpty() shouldBe true
    }

    @Test
    fun chapter_identity_keeps_upload_and_independent_source_chapter_with_same_numeric_id(): Unit = runBlocking {
        val raw = """[
            {"id":171,"chapter_number":2,"language":"en","source":"user"},
            {"id":171,"chapter_number":2,"language":"en","source":"other"},
            {"id":171,"chapter_number":2,"language":"en","source":"user"}
        ]
        """.trimIndent()
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
        val (service, _) = fixture(existingStorage = shared) { current }
        val committed = service.start("mangadot:131", "en")
        val oldCursor = committed.nextCursor!!
        val oldIndex = shared.get("mangaall_private_chapters_131_en_index")
        (oldIndex != null) shouldBe true

        // Compact source rows fit the dedicated 440KiB envelope, but the
        // combined full provider storage exceeds 512KiB due to unrelated keys.
        current = source(4341, 0)
        (
            runCatching {
                service.start("mangadot:131", "en")
            }.exceptionOrNull() is ProviderHostServiceException
            ) shouldBe true

        shared.get("mangaall_private_chapters_131_en_index") shouldBe oldIndex
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

    @Test
    fun real_host_http_broker_imports_over_2mib_source_without_script_text_ipc(): Unit = runBlocking {
        val raw = source(4341, 550)
        (raw.toByteArray().size > 2 * 1024 * 1024) shouldBe true
        val owner = ProviderResourceOwner(providerId, "broker-test")
        val resources = ProviderResourceStore()
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().url.toString() shouldBe
                "https://mangadot.net/api/manga/131/chapters/list?lang=en"
            calls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(raw.toResponseBody())
                .build()
        }.build()
        val host = DefaultProviderHttpHostService(
            owner = owner,
            resources = resources,
            policy = ProviderNetworkPolicy(
                allowedOrigins = setOf("https://mangadot.net"),
                allowLocalNetwork = true,
            ),
            cookieJar = ProviderHttpSessionStore().cookieJar(providerId),
            baseClient = client,
        )
        try {
            val service = MangaAllFutureHostChapterSnapshot(
                owner = owner,
                http = host,
                resources = resources,
                storage = FileProviderStorageHostService(
                    root = tempDir.resolve("real-host-broker").toFile(),
                    providerId = providerId,
                ),
            )
            var page = service.start("mangadot:131", "en")
            val ids = mutableListOf<String>()
            var countPages = 0
            while (true) {
                countPages++
                ids += page.items.map { it["providerChapterId"]!!.jsonPrimitive.content }
                val cursor = page.nextCursor ?: break
                page = service.next("mangadot:131", "en", cursor)
                (countPages <= 32) shouldBe true
            }
            (countPages in 17..32) shouldBe true
            ids.size shouldBe 4341
            ids.toSet().size shouldBe 4341
            calls.get() shouldBe 1
        } finally {
            host.close()
        }
    }

    @Test
    fun real_host_broker_denies_unlisted_origin_and_http_403_without_committing_inventory(): Unit = runBlocking {
        val owner = ProviderResourceOwner(providerId, "denied-broker-test")
        val resources = ProviderResourceStore()
        val calls = AtomicInteger()
        val status = 403
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("Denied")
                .body("challenge".toResponseBody())
                .build()
        }.build()
        val storage = FileProviderStorageHostService(
            root = tempDir.resolve("denied-host-broker").toFile(),
            providerId = providerId,
        )
        fun broker(origins: Set<String>) = DefaultProviderHttpHostService(
            owner = owner,
            resources = resources,
            policy = ProviderNetworkPolicy(
                allowedOrigins = origins,
                allowLocalNetwork = true,
            ),
            cookieJar = ProviderHttpSessionStore().cookieJar(providerId),
            baseClient = client,
        )
        val forbiddenOrigin = broker(setOf("https://api.mangadex.org"))
        try {
            val service = MangaAllFutureHostChapterSnapshot(
                owner,
                forbiddenOrigin,
                resources,
                storage,
            )
            (
                runCatching {
                    service.start("mangadot:131", "en")
                }.exceptionOrNull() is ProviderNetworkPolicyException
                ) shouldBe true
            calls.get() shouldBe 0
        } finally {
            forbiddenOrigin.close()
        }

        val statusDenied = broker(setOf("https://mangadot.net"))
        try {
            val service = MangaAllFutureHostChapterSnapshot(
                owner,
                statusDenied,
                resources,
                storage,
            )
            (
                runCatching {
                    service.start("mangadot:131", "en")
                }.exceptionOrNull() is ProviderHostServiceException
                ) shouldBe true
            calls.get() shouldBe 1
            storage.get("mangaall_private_chapters_131_en_index") shouldBe null
        } finally {
            statusDenied.close()
        }
    }

    @Test
    fun future_source_numeric_ids_numbers_and_explicit_utc_dates_match_signed_source_semantics(): Unit = runBlocking {
        // Fixed offline source fixture, never live MangaDot data. The source
        // JS accepts 1e3/1.0 numbers after JS String(number) normalization,
        // rejects unsafe numeric IDs but permits 16-digit STRING chapter IDs.
        // UTC date-only and offset timestamps must retain their real millis;
        // dates before 1970 are null in the original dateMillis helper.
        val raw = """
            [
              {"id":1001,"chapter_number":1.0,"language":"EN","source":"user",
               "chapter_title":"  First  ","date_added":"2026-11-22"},
              {"id":1002,"chapter_number":1e3,"language":"en","source":"scanlator",
               "date_added":"2026-11-22T10:00:00+03:00"},
              {"id":1003,"chapter_number":0.001,"language":"en","source":"user",
               "date_added":"1969-12-31T23:59:59Z"},
              {"id":9007199254740992,"chapter_number":4,"language":"en","source":"user"},
              {"id":"9007199254740992","chapter_number":5,"language":"en","source":"user"},
              {"id":1004,"chapter_number":1e10,"language":"en","source":"user"},
              {"id":1e3,"chapter_number":3.0,"language":"en","source":"user",
               "date_added":"1970-01-01T00:00:00Z"}
            ]
        """.trimIndent()
        val (service, requests) = fixture { raw }
        val page = service.start("mangadot:131", "en")
        page.nextCursor shouldBe null
        page.items.map { it["providerChapterId"]!!.jsonPrimitive.content } shouldBe listOf(
            "mangadot:uploads:1001",
            "mangadot:chapters:1002",
            "mangadot:uploads:1003",
            "mangadot:uploads:9007199254740992",
            "mangadot:uploads:1000",
        )
        page.items.map { it["rawLabel"]!!.jsonPrimitive.content } shouldBe listOf(
            "Ch. 1 First",
            "Ch. 1000",
            "Ch. 0.001",
            "Ch. 5",
            "Ch. 3",
        )
        page.items[0]["language"]!!.jsonPrimitive.content shouldBe "en"
        page.items[0]["title"]!!.jsonPrimitive.content shouldBe "First"
        page.items[0]["releaseDateMillis"]!!.jsonPrimitive.content shouldBe "1795305600000"
        page.items[1]["releaseDateMillis"]!!.jsonPrimitive.content shouldBe "1795330800000"
        page.items[2]["releaseDateMillis"] shouldBe JsonNull
        page.items[4]["releaseDateMillis"]!!.jsonPrimitive.content shouldBe "0"
        requests() shouldBe 1
    }

    @Test
    fun page_budget_matches_reference_serialization_at_utf8_and_row_boundaries(): Unit = runBlocking {
        for (title in listOf("Plain", "界".repeat(90), "Quote \\" + '"' + " newline\n")) {
            val (service, calls) = fixture { source(601, padding = 0, title = title) }
            var page = service.start("mangadot:131", "en")
            val pages = mutableListOf<MangaAllFutureChapterPage>()
            while (true) {
                pages += page
                val cursor = page.nextCursor ?: break
                page = service.next("mangadot:131", "en", cursor)
            }
            pages.sumOf { it.items.size } shouldBe 601
            for ((index, current) in pages.withIndex()) {
                val serialized = JsonObject(
                    mapOf(
                        "items" to JsonArray(current.items),
                        "nextCursor" to (current.nextCursor?.let(::JsonPrimitive) ?: JsonNull),
                    ),
                ).toString()
                (serialized.length <= 48 * 1024) shouldBe true
                (serialized.encodeToByteArray().size <= 48 * 1024) shouldBe true
                (current.items.size <= 256) shouldBe true
                if (index < pages.lastIndex && current.items.size < 256) {
                    val following = pages[index + 1]
                    val nextPosition = current.nextCursor!!.substringAfterLast(':').toInt() + 1
                    val cursor = if (nextPosition < 601) {
                        current.nextCursor.substringBeforeLast(':') + ":" + nextPosition
                    } else {
                        null
                    }
                    val proposed = JsonObject(
                        mapOf(
                            "items" to JsonArray(current.items + following.items.first()),
                            "nextCursor" to (cursor?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    ).toString()
                    (proposed.length > 48 * 1024 || proposed.encodeToByteArray().size > 48 * 1024) shouldBe true
                }
            }
            calls() shouldBe 1
        }
    }

    @Test
    fun optimized_snapshot_chunking_is_byte_identical_to_reference_json_arrays_with_cjk(): Unit = runBlocking {
        // Deliberately use a *quadratic reference* on just 400 synthetic rows
        // to lock output compatibility. Production now performs each row's
        // serialization once, not once per growing intermediate array.
        val title = "界".repeat(30)
        val storage = FileProviderStorageHostService(
            root = tempDir.resolve("cjk-boundary-parity").toFile(),
            providerId = providerId,
        )
        val (service, calls) = fixture(existingStorage = storage) {
            source(400, padding = 0, title = title)
        }
        service.start("mangadot:131", "en")
        val headerText = storage.get("mangaall_private_chapters_131_en_index")!!
        val header = Json.parseToJsonElement(headerText).jsonObject
        val generation = header["generation"]!!.jsonPrimitive.content
        // Reuse the exact *serialized* compact tuples as chunker input.
        // Rebuilding them manually from source metadata would accidentally
        // test field normalization (including numeric rendering) instead of
        // isolating the array-partition algorithm under test here.
        val originalRows = header["ranges"]!!.jsonArray.indices.flatMap { index ->
            val stored = storage.get("mangaall_private_chapters_131_en_part_$index")
                ?: throw AssertionError("MANGAALL_CJK_PARITY|missingPart=$index")
            Json.parseToJsonElement(stored).jsonObject["rows"]!!.jsonArray.map { it.jsonArray }
        }
        originalRows.size shouldBe 400
        originalRows.map { it[0].jsonPrimitive.content } shouldBe
            (1..400).map { (13100000 + it).toString() }
        originalRows.first()[3].jsonPrimitive.content shouldBe "$title 1"
        originalRows.first()[4].jsonPrimitive.content shouldBe "en"
        originalRows.first()[5].jsonPrimitive.content shouldBe "Ju-Ni"
        val referenceParts = mutableListOf<List<JsonArray>>()
        var current = mutableListOf<JsonArray>()
        // A JsonArray is itself Iterable<JsonElement>: current + row
        // FLATTENS the tuple through Kotlin's plus(Iterable) overload.
        // The original legacy budget check made exactly that mistake and
        // could compare a different JSON shape than the persisted array.
        // Explicit listOf(row) yields the actual saved array-of-tuples shape.
        val first = originalRows.first()
        (
            JsonArray(mutableListOf<JsonArray>() + first).toString() !=
                JsonArray(listOf(first)).toString()
            ) shouldBe true
        for (row in originalRows) {
            val proposed = JsonArray(current + listOf(row)).toString()
            if (proposed.length > 48 * 1024 || proposed.encodeToByteArray().size > 48 * 1024) {
                referenceParts += current
                current = mutableListOf()
            }
            current += row
        }
        if (current.isNotEmpty()) referenceParts += current
        val referenceRanges = referenceParts.map { it.size }
        val savedRanges = header["ranges"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
        if (referenceParts.size < 2 || savedRanges != referenceRanges) {
            throw AssertionError(
                "MANGAALL_CJK_PARITY|expectedRanges=$referenceRanges|storedRanges=$savedRanges",
            )
        }
        for ((index, part) in referenceParts.withIndex()) {
            val expected = JsonObject(
                mapOf(
                    "generation" to JsonPrimitive(generation),
                    "rows" to JsonArray(part),
                ),
            ).toString()
            val observed = storage.get("mangaall_private_chapters_131_en_part_$index")
            if (observed != expected) {
                val firstMismatch = expected.indices.firstOrNull { pos ->
                    expected[pos] != observed?.getOrNull(pos)
                } ?: expected.length
                throw AssertionError(
                    "MANGAALL_CJK_PARITY|part=$index|expectedChars=${expected.length}|" +
                        "observedChars=${observed?.length}|firstMismatch=$firstMismatch",
                )
            }
        }
        calls() shouldBe 1
    }
}
