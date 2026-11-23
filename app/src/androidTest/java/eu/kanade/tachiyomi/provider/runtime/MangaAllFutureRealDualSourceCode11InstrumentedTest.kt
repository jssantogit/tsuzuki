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
import org.junit.Assert.assertFalse
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
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * NONSHIPPING: actual two-source JS code11 research ZIP,
 * generated only as androidTest asset. No installation, signing, real upstream
 * network, editorial binding, download, Reader pixels or user data migration.
 *
 * Unlike the earlier synthetic #263 ZIP, this tests MangaALL's original
 * main/router/MangaDex/MangaDot source module graph inside the
 * real Android isolated QuickJs/Binder runtime.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllFutureRealDualSourceCode11InstrumentedTest {
    private val providerId = "app.tsuzuki.mangaall"

    @Test
    fun real_dual_source_unsigned_code11_preserves_native_4341_chapter_paging(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val candidate = instrumentation.context.assets.open(
            "mangaall-dual-source-code11-unsigned-research.zip",
        ).use { it.readBytes() }
        val manifest = ProviderPackageParser().parse(candidate).manifest
        assertEquals(providerId, manifest.id)
        assertEquals(11L, manifest.version.code)
        assertEquals(2, manifest.minHostApi)
        val rawList = buildString {
            append("[")
            for (index in 1..4341) {
                if (index > 1) append(",")
                append("{\"id\":")
                append(13100000 + index)
                append(",\"chapter_number\":")
                append(index)
                append(",\"language\":\"en\",\"source\":\"user\",\"chapter_title\":\"Ch ")
                append(index)
                append("\",\"ignored\":\"")
                append("x".repeat(550))
                append("\"}")
            }
            append("]")
        }
        assertTrue(rawList.encodeToByteArray().size > 2 * 1024 * 1024)

        val mangaDex = AtomicInteger()
        val mangaDotSearch = AtomicInteger()
        val mangaDotList = AtomicInteger()
        val forbiddenThirdSource = AtomicInteger()
        val mangaDexId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        val baseHttp = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
            .addInterceptor { chain ->
                val url = chain.request().url
                val (status, payload) = when {
                    url.host == "api.mangadex.org" && url.encodedPath == "/manga" -> {
                        mangaDex.incrementAndGet()
                        200 to """
                            {"result":"ok","data":[{"id":"$mangaDexId","type":"manga",
                            "attributes":{"title":{"en":"Bleach"},"altTitles":[]}}],
                            "total":1,"offset":0}
                        """.trimIndent()
                    }
                    url.host == "mangadot.net" && url.encodedPath == "/api/search" -> {
                        mangaDotSearch.incrementAndGet()
                        200 to """
                            {"manga_list":[{"manga_id":131,"title":"Bleach"}],
                            "pagination":{"current_page":1,"total_pages":1}}
                        """.trimIndent()
                    }
                    url.host == "mangadot.net" &&
                        url.encodedPath == "/api/manga/131/chapters/list" -> {
                        mangaDotList.incrementAndGet()
                        200 to rawList
                    }
                    url.host == "mangafire.to" -> {
                        forbiddenThirdSource.incrementAndGet()
                        throw AssertionError("MangaFire was unexpectedly requested")
                    }
                    else -> throw AssertionError("Unexpected offline source route")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message(if (status == 200) "OK" else "Rate Limited")
                    .body(payload.toResponseBody())
                    .build()
            }.build()
        val root = File(context.cacheDir, "mangaall-future-dual-sources-offline").apply {
            deleteRecursively()
            mkdirs()
        }
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = root,
                baseHttpClient = baseHttp,
            ),
        )
        suspend fun invoke(capability: String, input: String): ProviderRuntimeInvocationResponse {
            val invocationId = "mangaall-dual-source-ipc:" + UUID.randomUUID().toString()
            val policy = ProviderHostInvocationPolicy.fromManifest(manifest, invocationId)
                .forCapability(capability)
            val request = ProviderRuntimeInvocationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = invocationId,
                providerId = providerId,
                artifactVersionCode = manifest.version.code,
                capabilityId = capability,
                capabilityVersion = 1,
                configurationFingerprint = "unsigned-offline-research-only",
                fileName = "main.js",
                hostModules = policy.allowedHostModules(),
                limits = ProviderRuntimeLimitsDto(
                    // Regress the ORIGINAL 20s Host / 10s JS envelope after
                    // the private chunking fix; never broaden the contract.
                    wallClockTimeoutMs = 20_000L,
                    jsExecutionTimeoutMs = 10_000L,
                ),
            )
            return client.invokePackage(request, candidate, input, policy)
        }
        val workBinding = JsonObject(
            mapOf(
                "providerId" to JsonPrimitive(providerId),
                "externalWorkId" to JsonPrimitive("mangadot:131"),
                "facetId" to JsonPrimitive("en"),
            ),
        )
        try {
            assertTrue(
                ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in
                    ProviderHostInvocationPolicy.fromManifest(manifest, "positive")
                        .forCapability("reading.chapters").allowedHostModules(),
            )
            assertFalse(
                ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in
                    ProviderHostInvocationPolicy.fromManifest(manifest, "negative")
                        .forCapability("reading.lookup").allowedHostModules(),
            )
            assertFalse(
                ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in
                    ProviderHostInvocationPolicy.fromManifest(manifest, "negative-pages")
                        .forCapability("reading.pages").allowedHostModules(),
            )

            val lookup = invoke(
                "reading.lookup",
                """{"titles":["Bleach"],"requestedLanguage":"en"}""",
            )
            assertNull("actual two-source lookup must complete its V1 page", lookup.failure)
            val lookupPage = Json.parseToJsonElement(requireNotNull(lookup.value)).jsonObject
            val candidates = lookupPage["items"]!!.jsonArray.map {
                it.jsonObject["externalWorkId"]!!.jsonPrimitive.content
            }
            assertTrue("MangaDex source evidence missing", "mangadex:$mangaDexId" in candidates)
            assertTrue("MangaDot source evidence missing", "mangadot:131" in candidates)
            assertEquals(
                "two complete source responses must end the lookup cursor",
                JsonNull,
                lookupPage["nextCursor"],
            )
            assertTrue(mangaDex.get() >= 1)
            assertTrue(mangaDotSearch.get() >= 1)
            assertEquals("third source must never be called", 0, forbiddenThirdSource.get())
            assertEquals(0, mangaDotList.get())

            val rows = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            while (true) {
                val arguments = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                    "binding" to workBinding,
                )
                if (cursor != null) arguments["cursor"] = JsonPrimitive(cursor)
                val startedMs = android.os.SystemClock.elapsedRealtime()
                val response = invoke("reading.chapters", JsonObject(arguments).toString())
                val pageDurationMs = android.os.SystemClock.elapsedRealtime() - startedMs
                assertNull(
                    "real JS/native chapter page failed: page=$pages; elapsedMs=$pageDurationMs; " +
                        "sourceRequests=${mangaDotList.get()}",
                    response.failure,
                )
                val text = requireNotNull(response.value)
                assertTrue(text.length <= 48 * 1024)
                assertTrue(text.encodeToByteArray().size <= 48 * 1024)
                val result = Json.parseToJsonElement(text).jsonObject
                val items = result["items"]!!.jsonArray
                assertTrue(items.size in 1..256)
                rows += items.map {
                    it.jsonObject["providerChapterId"]!!.jsonPrimitive.content
                }
                cursor = result["nextCursor"]!!.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                pages++
                assertTrue("chapter continuation must finish in 32 pages", pages <= 32)
                if (cursor == null) break
            }
            assertTrue(pages in 17..32)
            assertEquals(
                (1..4341).map { "mangadot:uploads:" + (13100000 + it) },
                rows,
            )
            assertEquals("one locally intercepted large source GET", 1, mangaDotList.get())
            assertEquals("snapshot cannot re-query other sources", 1, mangaDotSearch.get())
            // No editorial approval: these are source observations only.
        } finally {
            root.deleteRecursively()
        }
    }
}
