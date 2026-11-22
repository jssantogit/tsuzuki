package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * NONSHIPPING synthetic unsigned test ZIP, never installed or signed.
 * App client -> isolated runtime Binder -> fresh QuickJs -> Host Binder
 * callback -> private snapshot; all HTTP is locally intercepted and offline.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllFutureCode11InterprocessInstrumentedTest {
    private val providerId = "app.tsuzuki.mangaall"

    @Test
    fun unsigned_code11_paginates_4341_rows_over_real_isolated_quickjs_binder(): Unit = runBlocking {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "mangaall-future-code11-ipc-synthetic").apply {
            deleteRecursively()
            mkdirs()
        }
        val upstreamCalls = AtomicInteger()
        val source = buildString {
            append("[")
            for (number in 1..4341) {
                if (number > 1) append(",")
                append("{\"id\":")
                append(13100000 + number)
                append(",\"chapter_number\":")
                append(number)
                append(",\"source\":\"user\",\"language\":\"en\",\"chapter_title\":\"Ch ")
                append(number)
                append("\",\"ignored\":\"")
                append("x".repeat(550))
                append("\"}")
            }
            append("]")
        }
        assertTrue(source.encodeToByteArray().size > 2 * 1024 * 1024)
        val http = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
            .addInterceptor { chain ->
                assertEquals(
                    "https://mangadot.net/api/manga/131/chapters/list?lang=en",
                    chain.request().url.toString(),
                )
                upstreamCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(source.toResponseBody())
                    .build()
            }.build()
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = root,
                baseHttpClient = http,
            ),
        )
        val futurePackage = syntheticPackage(code = 11, chapterPermission = true)
        val futureManifest = ProviderPackageParser().parse(futurePackage).manifest
        suspend fun invoke(
            bytes: ByteArray,
            capability: String,
            input: String,
        ): ProviderRuntimeInvocationResponse {
            val manifest = ProviderPackageParser().parse(bytes).manifest
            val invocationId = "mangaall-ipc:" + UUID.randomUUID().toString()
            val policy = ProviderHostInvocationPolicy.fromManifest(manifest, invocationId)
                .forCapability(capability)
            val request = ProviderRuntimeInvocationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = invocationId,
                providerId = providerId,
                artifactVersionCode = manifest.version.code,
                capabilityId = capability,
                capabilityVersion = 1,
                configurationFingerprint = "offline-test-only",
                fileName = "main.js",
                hostModules = policy.allowedHostModules(),
                limits = ProviderRuntimeLimitsDto(
                    wallClockTimeoutMs = 20_000L,
                    jsExecutionTimeoutMs = 10_000L,
                ),
            )
            return client.invokePackage(request, bytes, input, policy)
        }

        try {
            assertEquals(11L, futureManifest.version.code)
            assertTrue(
                ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in
                    ProviderHostInvocationPolicy.fromManifest(futureManifest, "future-test")
                        .forCapability("reading.chapters").allowedHostModules(),
            )
            val ids = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            while (true) {
                val input = cursor?.let {
                    JsonObject(mapOf("cursor" to JsonPrimitive(it))).toString()
                } ?: "{}"
                val response = invoke(futurePackage, "reading.chapters", input)
                assertNull("cross-process chapter invocation failed", response.failure)
                val value = requireNotNull(response.value)
                assertTrue(value.length <= 48 * 1024)
                assertTrue(value.encodeToByteArray().size <= 48 * 1024)
                val parsed = Json.parseToJsonElement(value).jsonObject
                val rows = parsed["items"]!!.jsonArray
                assertTrue(rows.size in 1..256)
                ids += rows.map { it.jsonObject["providerChapterId"]!!.jsonPrimitive.content }
                cursor = parsed["nextCursor"]!!
                    .takeUnless { it == JsonNull }?.jsonPrimitive?.content
                pages++
                assertTrue("fresh VM continuations must finish in 32 pages", pages <= 32)
                if (cursor == null) break
            }
            assertTrue(pages in 17..32)
            assertEquals((1..4341).map { "mangadot:uploads:" + (13100000 + it) }, ids)
            assertEquals(1, upstreamCalls.get())

            // Same future manifest, other operation: no JS Host grant.
            val pagesResult = invoke(futurePackage, "reading.pages", "{}")
            assertNull(pagesResult.failure)
            assertEquals("""{"snapshot":"undefined"}""", pagesResult.value)
            assertEquals(1, upstreamCalls.get())

            // Code10 remains forbidden even if its synthetic JS tries the API.
            val legacyPackage = syntheticPackage(code = 10, chapterPermission = false)
            val legacy = invoke(legacyPackage, "reading.chapters", "{}")
            assertNotNull(legacy.failure)
            assertEquals(1, upstreamCalls.get())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun syntheticPackage(code: Long, chapterPermission: Boolean): ByteArray {
        val manifest = """
            {
              "manifestVersion":1,
              "id":"app.tsuzuki.mangaall",
              "name":"MangaALL synthetic IPC only",
              "version":{"name":"unsigned-test-only","code":$code},
              "minHostApi":2,
              "entrypoint":"main.js",
              "capabilities":[
                {"id":"reading.chapters","version":1},
                {"id":"reading.pages","version":1}
              ],
              "permissions":{
                "network":{"origins":["https://mangadot.net"],"localNetwork":false},
                "mangaAllChapterSnapshot":{"enabled":$chapterPermission},
                "storage":{"enabled":false}
              },
              "contentLanguages":["en"],
              "settings":[]
            }
        """.trimIndent()
        val main = """
            export default {
              reading: {
                chapters: async (input) => {
                  const work = "mangadot:131", lang = "en";
                  const result = input.cursor == null
                    ? await tsuzuki.mangaAllChapterSnapshot.start(work, lang)
                    : await tsuzuki.mangaAllChapterSnapshot.next(work, lang, input.cursor);
                  return JSON.parse(result);
                },
                pages: async () => ({
                  snapshot: typeof tsuzuki.mangaAllChapterSnapshot
                })
              }
            };
        """.trimIndent()
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(path: String, value: String) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.encodeToByteArray())
                    zip.closeEntry()
                }
                entry("manifest.json", manifest)
                entry("main.js", main)
            }
            output.toByteArray()
        }
    }
}
