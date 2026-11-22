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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestMangaAllChapterSnapshotPermission
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import java.nio.file.Path

/**
 * NONSHIPPING: actual QuickJs module runtime + internal Host chapter snapshot
 * service over a controlled >2MiB source array. No installed App invocation,
 * Binder bridge, external network or signed Provider is involved.
 */
class MangaAllFutureHostQuickJsContinuationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun future_code11_quickjs_reconstructs_4341_source_rows_across_fresh_invocations(): Unit = runBlocking {
        val owner = ProviderResourceOwner("app.tsuzuki.mangaall", "quickjs-future-offline")
        val resources = ProviderResourceStore()
        val source = JsonArray(
            (1..4341).map { n ->
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(13100000 + n),
                        "chapter_number" to JsonPrimitive(n),
                        "language" to JsonPrimitive("en"),
                        "source" to JsonPrimitive("user"),
                        "chapter_title" to JsonPrimitive("Chapter $n"),
                        "groups" to JsonArray(
                            listOf(JsonObject(mapOf("name" to JsonPrimitive("Ju-Ni")))),
                        ),
                        "ignored" to JsonPrimitive("x".repeat(550)),
                    ),
                )
            },
        ).toString()
        (source.encodeToByteArray().size > 2 * 1024 * 1024) shouldBe true
        var upstreamCalls = 0
        val http = object : ProviderHttpHostService {
            override suspend fun getText(url: String): String =
                error("Large source arrays must not traverse the 192KiB script-text path")

            override suspend fun getResource(url: String): ProviderResourceHandle {
                url shouldBe "https://mangadot.net/api/manga/131/chapters/list?lang=en"
                upstreamCalls++
                return resources.put(
                    owner,
                    ProviderResourceKind.HTML,
                    source.encodeToByteArray(),
                )
            }
        }
        val snapshot = MangaAllFutureHostChapterSnapshot(
            owner = owner,
            http = http,
            resources = resources,
            storage = FileProviderStorageHostService(
                root = tempDir.resolve("future-quickjs-storage").toFile(),
                providerId = owner.providerId,
            ),
        )
        val services = ProviderHostServices(
            mangaAllChapterSnapshot = MangaAllFutureChapterSnapshotHostBridge(snapshot),
        )
        val main = """
            export default {
              reading: {
                chapters: async (input) => {
                  const workId = input.binding.externalWorkId;
                  const language = input.binding.facetId;
                  const raw = input.cursor
                    ? await tsuzuki.mangaAllChapterSnapshot.next(workId, language, input.cursor)
                    : await tsuzuki.mangaAllChapterSnapshot.start(workId, language);
                  return JSON.parse(raw);
                }
              }
            };
        """.trimIndent()
        val manifest = ProviderScriptManifest(
            manifestVersion = 1,
            id = owner.providerId,
            name = "MangaALL future synthetic",
            version = ProviderManifestVersion("0.6.0-unsigned", 11),
            minHostApi = 2,
            entrypoint = "main.js",
            capabilities = listOf(ProviderManifestCapability("reading.chapters", 1)),
            permissions = ProviderManifestPermissions(
                network = ProviderManifestNetworkPermission(origins = setOf("https://mangadot.net")),
                mangaAllChapterSnapshot = ProviderManifestMangaAllChapterSnapshotPermission(enabled = true),
            ),
        )
        val pkg = ParsedProviderPackage(
            manifest = manifest,
            entries = mapOf("main.js" to main.encodeToByteArray()),
        )
        val runtime = ProviderScriptPackageRuntime()
        val json = Json

        fun request(cursor: String?): String =
            JsonObject(
                mapOf(
                    "binding" to JsonObject(
                        mapOf(
                            "externalWorkId" to JsonPrimitive("mangadot:131"),
                            "facetId" to JsonPrimitive("en"),
                        ),
                    ),
                    "cursor" to (cursor?.let(::JsonPrimitive) ?: JsonNull),
                ),
            ).toString()

        // Even a caller-supplied bridge cannot grant the original signed
        // code10 module permission. No fetch or snapshot is performed.
        val denied = pkg.copy(
            manifest = manifest.copy(version = ProviderManifestVersion("0.5.5", 10)),
        )
        runtime.invoke(denied, "reading.chapters", 1, request(null), services) shouldBe
            ProviderPackageExecution.Failure(ProviderPackageFailure.MODULE_ERROR)
        upstreamCalls shouldBe 0

        var cursor: String? = null
        var firstCursor: String? = null
        var pages = 0
        val ids = mutableListOf<String>()
        while (true) {
            val execution = runtime.invoke(pkg, "reading.chapters", 1, request(cursor), services)
            (execution is ProviderPackageExecution.Success) shouldBe true
            val output = (execution as ProviderPackageExecution.Success).json
            (output.length <= 48 * 1024) shouldBe true
            (output.encodeToByteArray().size <= 48 * 1024) shouldBe true
            val page = json.parseToJsonElement(output).jsonObject
            val items = page["items"]!!.jsonArray
            (items.size <= 256) shouldBe true
            pages++
            (pages <= 32) shouldBe true
            ids += items.map { it.jsonObject["providerChapterId"]!!.jsonPrimitive.content }
            val next = page["nextCursor"]
            cursor = if (next == JsonNull) null else next!!.jsonPrimitive.content
            if (pages == 1) firstCursor = cursor
            if (cursor == null) break
        }
        (pages in 17..32) shouldBe true
        ids.size shouldBe 4341
        ids.distinct().size shouldBe 4341
        ids.first() shouldBe "mangadot:uploads:13100001"
        ids.last() shouldBe "mangadot:uploads:13104341"
        upstreamCalls shouldBe 1

        // New source generation intentionally invalidates the old cursor.
        val refreshed = runtime.invoke(pkg, "reading.chapters", 1, request(null), services)
        (refreshed is ProviderPackageExecution.Success) shouldBe true
        upstreamCalls shouldBe 2
        (firstCursor != null) shouldBe true
        runtime.invoke(pkg, "reading.chapters", 1, request(firstCursor), services) shouldBe
            ProviderPackageExecution.Failure(ProviderPackageFailure.HOST_ERROR)
    }
}
