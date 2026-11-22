package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestMangaAllChapterSnapshotPermission
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.ProviderHostModule
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * NONSHIPPING Android Host AIDL Stub exercise with offline intercepted HTTP.
 * This test does not install/sign code11 or call the isolated QuickJs process.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllFutureBinderSnapshotInstrumentedTest {

    private fun manifest(code: Long, enabled: Boolean): ProviderScriptManifest =
        ProviderScriptManifest(
            manifestVersion = 1,
            id = "app.tsuzuki.mangaall",
            name = "MangaALL future synthetic",
            version = ProviderManifestVersion("future-unsigned", code),
            minHostApi = 2,
            entrypoint = "main.js",
            capabilities = listOf(ProviderManifestCapability("reading.chapters", 1)),
            permissions = ProviderManifestPermissions(
                network = ProviderManifestNetworkPermission(origins = setOf("https://mangadot.net")),
                mangaAllChapterSnapshot = ProviderManifestMangaAllChapterSnapshotPermission(enabled),
            ),
        )

    @Test
    fun android_host_stub_paginates_4341_rows_and_code10_never_fetches() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        val storageRoot = File(context.cacheDir, "mangaall-future-binder-synthetic").apply {
            deleteRecursively()
        }
        val source = buildString {
            append("[")
            for (n in 1..4341) {
                if (n > 1) append(",")
                append("{\"id\":")
                append(13100000 + n)
                append(",\"chapter_number\":")
                append(n)
                append(",\"source\":\"user\",\"language\":\"en\",")
                append("\"chapter_title\":\"Chapter ")
                append(n)
                append("\",\"ignored\":\"")
                append("x".repeat(550))
                append("\"}")
            }
            append("]")
        }
        assertTrue(source.encodeToByteArray().size > 2 * 1024 * 1024)
        val networkCalls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .dns { listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
            .addInterceptor { chain ->
                assertEquals(
                    "https://mangadot.net/api/manga/131/chapters/list?lang=en",
                    chain.request().url.toString(),
                )
                networkCalls.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(source.toResponseBody())
                    .build()
            }.build()
        val factory = ProviderHostInvocationFactory(
            context = context,
            storageRoot = storageRoot,
            baseHttpClient = client,
        )
        val legacy = factory.create(
            ProviderHostInvocationPolicy.fromManifest(manifest(10, false), "legacy-code10"),
        )
        try {
            assertTrue(
                ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT !in
                    ProviderHostInvocationPolicy.fromManifest(manifest(10, false), "legacy-check")
                        .allowedHostModules(),
            )
            assertTrue(
                runCatching {
                    legacy.bridge.mangaAllChapterSnapshotStart("mangadot:131", "en")
                }.isFailure,
            )
            assertEquals(0, networkCalls.get())
        } finally {
            legacy.close()
        }

        val authorized = factory.create(
            ProviderHostInvocationPolicy.fromManifest(manifest(11, true), "future-code11"),
        )
        try {
            val ids = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            while (true) {
                val response = if (cursor == null) {
                    authorized.bridge.mangaAllChapterSnapshotStart("mangadot:131", "en")
                } else {
                    authorized.bridge.mangaAllChapterSnapshotNext("mangadot:131", "en", cursor)
                }
                assertTrue(response.length <= 48 * 1024)
                assertTrue(response.encodeToByteArray().size <= 48 * 1024)
                val parsed = Json.parseToJsonElement(response).jsonObject
                val items = parsed["items"]!!.jsonArray
                assertTrue(items.size <= 256)
                ids += items.map { it.jsonObject["providerChapterId"]!!.jsonPrimitive.content }
                cursor = parsed["nextCursor"]!!.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                pages++
                assertTrue(pages <= 32)
                if (cursor == null) break
            }
            assertTrue(pages >= 17)
            assertEquals((1..4341).map { "mangadot:uploads:${13100000 + it}" }, ids)
            assertEquals(1, networkCalls.get())
        } finally {
            authorized.close()
            storageRoot.deleteRecursively()
        }
    }
}
