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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.File
import java.util.UUID

/**
 * NONSHIPPING, explicitly authorized Android emulator network investigation.
 *
 * Runs the original pinned MangaDex/MangaDot module graph in a locally staged
 * UNSIGNED code11 research ZIP. It never binds an editorial/canonical edition.
 * Unlike the synthetic 4341 test, the Host here uses real network responses.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllFutureRealDualSourceCode11LiveInstrumentedTest {
    @Test(timeout = 800_000L)
    fun real_dual_source_unsigned_code11_bleach_real_network_chapters(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals(
            "I_ACCEPT_MANGAALL_TWO_SOURCE_CODE11_ANDROID_LIVE",
            InstrumentationRegistry.getArguments().getString("mangaAllDualCode11LiveOptIn"),
        )
        val context: Context = instrumentation.targetContext
        val candidate = instrumentation.context.assets.open(
            "mangaall-dual-source-code11-unsigned-research.zip",
        ).use { it.readBytes() }
        val manifest = ProviderPackageParser().parse(candidate).manifest
        assertEquals("app.tsuzuki.mangaall", manifest.id)
        assertEquals(11L, manifest.version.code)
        assertEquals(2, manifest.minHostApi)
        assertFalse(
            manifest.permissions.network?.origins.orEmpty().any {
                it.contains("mangafire", ignoreCase = true) || it.contains("mfcdn", ignoreCase = true)
            },
        )
        val root = File(context.cacheDir, "mangaall-real-dual-code11-live").apply {
            deleteRecursively()
            mkdirs()
        }
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = root,
            ),
        )
        suspend fun invoke(capability: String, input: String): ProviderRuntimeInvocationResponse {
            val invocationId = "mangaall-dual-live:" + UUID.randomUUID().toString()
            val policy = ProviderHostInvocationPolicy.fromManifest(manifest, invocationId)
                .forCapability(capability)
            val request = ProviderRuntimeInvocationRequest(
                protocolVersion = ProviderRuntimeProtocol.VERSION,
                invocationId = invocationId,
                providerId = manifest.id,
                artifactVersionCode = manifest.version.code,
                capabilityId = capability,
                capabilityVersion = 1,
                configurationFingerprint = "unsigned-android-source-observation-only",
                fileName = "main.js",
                hostModules = policy.allowedHostModules(),
                limits = ProviderRuntimeLimitsDto(
                    wallClockTimeoutMs = 30_000L,
                    jsExecutionTimeoutMs = 15_000L,
                ),
            )
            return client.invokePackage(request, candidate, input, policy)
        }

        try {
            // Source discovery only: no title or author similarity is sufficient
            // to promote a candidate to the app's canonical work binding.
            val seen = mutableSetOf<String>()
            val dotCandidates = mutableListOf<String>()
            var cursor: String? = null
            for (page in 1..8) {
                val args = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                    "titles" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("Bleach"))),
                    "requestedLanguage" to JsonPrimitive("en"),
                )
                if (cursor != null) args["cursor"] = JsonPrimitive(cursor)
                val response = invoke("reading.lookup", JsonObject(args).toString())
                assertNull("real dual-source lookup failed at page $page", response.failure)
                val result = Json.parseToJsonElement(requireNotNull(response.value)).jsonObject
                for (item in result["items"]!!.jsonArray) {
                    val id = item.jsonObject["externalWorkId"]?.jsonPrimitive?.content
                        ?: continue
                    if (id.startsWith("mangadot:") && Regex("mangadot:[1-9][0-9]{0,15}").matches(id)) {
                        if (seen.add(id)) dotCandidates.add(id)
                    }
                }
                val next = result["nextCursor"] ?: JsonNull
                cursor = if (next == JsonNull) null else next.jsonPrimitive.content
                if (cursor == null || dotCandidates.isNotEmpty()) break
            }
            assertTrue("MangaDot Bleach candidate not observed in real source lookup", dotCandidates.isNotEmpty())
            // Audit the first *source HID*, without claiming it is the
            // editorial identity or silently resolving rival HIDs.
            val selected = dotCandidates.first()
            val binding = JsonObject(
                mapOf(
                    "providerId" to JsonPrimitive("app.tsuzuki.mangaall"),
                    "externalWorkId" to JsonPrimitive(selected),
                    "facetId" to JsonPrimitive("en"),
                ),
            )
            val ids = mutableSetOf<String>()
            var chapterOne = false
            var total = 0
            var chapterCursor: String? = null
            var pages = 0
            val knownCursors = mutableSetOf<String>()
            for (page in 1..32) {
                val args = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                    "binding" to binding,
                )
                if (chapterCursor != null) args["cursor"] = JsonPrimitive(chapterCursor)
                val response = invoke("reading.chapters", JsonObject(args).toString())
                assertNull("real Android native chapter snapshot failed at page $page", response.failure)
                val raw = requireNotNull(response.value)
                assertTrue("Host output exceeds 48 KiB", raw.encodeToByteArray().size <= 48 * 1024)
                val result = Json.parseToJsonElement(raw).jsonObject
                val items = result["items"]!!.jsonArray
                assertTrue("empty/non-granular chapter page", items.size in 1..256)
                for (entry in items) {
                    val obj = entry.jsonObject
                    val id = requireNotNull(obj["providerChapterId"]?.jsonPrimitive?.content)
                    assertTrue(
                        "unexpected source chapter identifier",
                        Regex(
                            "mangadot:(uploads|chapters):[1-9][0-9]{0,15}",
                        ).matches(id),
                    )
                    assertTrue("duplicate real chapter ID", ids.add(id))
                    val number = obj["rawNumber"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    assertNotNull(number)
                    if (number == 1.0) chapterOne = true
                    total++
                }
                val next = result["nextCursor"] ?: JsonNull
                chapterCursor = if (next == JsonNull) null else next.jsonPrimitive.content
                pages++
                if (chapterCursor == null) break
                assertTrue("chapter cursor repeated", knownCursors.add(chapterCursor))
            }
            assertNull("large real MangaDot chapter list missing terminal cursor", chapterCursor)
            assertTrue("real MangaDot corpus must be large", total >= 1000)
            assertTrue("real MangaDot corpus must span multiple Host pages", pages > 5)
            assertTrue("real source chapter one not attested", chapterOne)
            assertEquals(total, ids.size)
            println(
                "MANGAALL_DUAL_ANDROID_LIVE|work=bleach|source=MANGADOT" +
                    "|chapters=$total|pages=$pages|chapterOne=YES|terminal=YES" +
                    "|duplicates=0|sourceHid=REDACTED|canonical=NOT_TESTED" +
                    "|reader=NOT_TESTED|signed=NO|mangafire=NOT_CALLED",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
