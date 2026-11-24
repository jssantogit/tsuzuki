package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpPageLoader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/**
 * NONSHIPPING, source-HID-only Android CI probe for EXACTLY the frozen 30 manhwas.
 *
 * Each of three emulator shards audits ten works. Never selects a canonical
 * edition, merges rival HIDs, persists an editorial binding or claims Reader
 * image validation. A real source HID with a terminal chapter-one inventory
 * is the only successful outcome for each work.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllFrozen30Code11AndroidLiveInstrumentedTest {
    private val expectedCorpusSha =
        "19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1"
    private val workHid = Regex("mangadot:[1-9][0-9]{0,15}|mangadex:[a-fA-F0-9-]{36}")
    private val dotChapterHid = Regex("mangadot:(uploads|chapters):[1-9][0-9]{0,15}")

    private fun normalizeTitle(title: String): String =
        title.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')
            .trim().lowercase()

    private fun extraQueries(title: String): List<String> = when (title) {
        "SSS-Class Revival Hunter" -> listOf("SSS-Class Suicide Hunter")
        "Return of the Blossoming Blade" -> listOf("Return of the Mount Hua Sect")
        "The Reason Why Raeliana Ended Up at the Duke's Mansion" ->
            listOf("Why Raeliana Ended Up at the Duke's Mansion", "그녀가 공작저로 가야 했던 사정")
        else -> emptyList()
    }

    @Test(timeout = 1_800_000L)
    fun frozen30_android_real_source_hid_chapter_continuations(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals(
            "I_ACCEPT_MANGAALL_TWO_SOURCE_CODE11_ANDROID_LIVE",
            args.getString("mangaAllDualCode11LiveOptIn"),
        )
        val shard = requireNotNull(args.getString("mangaAllFrozen30Shard")).toInt()
        val readerMode = args.getString("mangaAllReaderPagesProbe") ?: "0"
        assertTrue("Invalid Reader metadata probe opt-in", readerMode == "0" || readerMode == "1")
        val readerProbe = readerMode == "1"
        val pixelMode = args.getString("mangaAllPixelDecodeProbe") ?: "0"
        assertTrue("Invalid pixel decode opt-in", pixelMode == "0" || pixelMode == "1")
        val pixelProbe = pixelMode == "1"
        assertTrue("Pixel probe requires source Reader metadata probe", !pixelProbe || readerProbe)
        val appReaderMode = args.getString("mangaAllAppReaderLoaderProbe") ?: "0"
        assertTrue("Invalid real App Reader loader opt-in", appReaderMode == "0" || appReaderMode == "1")
        val appReaderProbe = appReaderMode == "1"
        assertTrue("App Reader loader requires source Reader metadata probe", !appReaderProbe || readerProbe)
        assertTrue("Only three fixed 10-work shards are permitted", shard in 0..2)
        val cohort = instrumentation.context.assets.open(
            "mangaall-dual-source-30-cohort-lock.json",
        ).use { Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject }
        assertEquals("MANHWA_30_FIXED_20261123", cohort["cohort"]?.jsonPrimitive?.content)
        assertEquals("manhwa", cohort["format"]?.jsonPrimitive?.content)
        val works = requireNotNull(cohort["works"]).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(30, works.size)
        assertEquals(30, works.toSet().size)
        val canon = JsonArray(works.map { JsonPrimitive(it) }).toString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canon.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Frozen manhwa work vector unexpectedly rotated", expectedCorpusSha, digest)

        val zip = instrumentation.context.assets.open(
            "mangaall-dual-source-code11-unsigned-research.zip",
        ).use { it.readBytes() }
        val manifest = ProviderPackageParser().parse(zip).manifest
        assertEquals("app.tsuzuki.mangaall", manifest.id)
        assertEquals(11L, manifest.version.code)
        assertEquals(2, manifest.minHostApi)
        assertFalse(
            manifest.permissions.network?.origins.orEmpty().any {
                it.contains("mangafire", ignoreCase = true) || it.contains("mfcdn", ignoreCase = true)
            },
        )
        val context: Context = instrumentation.targetContext
        val root = File(context.cacheDir, "mangaall-frozen30-real-shard-$shard").apply {
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
        suspend fun invoke(capability: String, input: JsonObject): ProviderRuntimeInvocationResponse {
            val invocationId = "mangaall-frozen30:" + UUID.randomUUID().toString()
            val policy = ProviderHostInvocationPolicy.fromManifest(manifest, invocationId)
                .forCapability(capability)
            return client.invokePackage(
                ProviderRuntimeInvocationRequest(
                    protocolVersion = ProviderRuntimeProtocol.VERSION,
                    invocationId = invocationId,
                    providerId = manifest.id,
                    artifactVersionCode = manifest.version.code,
                    capabilityId = capability,
                    capabilityVersion = 1,
                    configurationFingerprint = "frozen30-source-only-unsigned",
                    fileName = "main.js",
                    hostModules = policy.allowedHostModules(),
                    limits = ProviderRuntimeLimitsDto(
                        wallClockTimeoutMs = 30_000L,
                        jsExecutionTimeoutMs = 15_000L,
                    ),
                ),
                zip,
                input.toString(),
                policy,
            )
        }

        try {
            for (offset in 0..9) {
                val index = shard * 10 + offset
                val workTitle = works[index]
                val terms = (listOf(workTitle) + extraQueries(workTitle))
                val permittedTitles = terms.map(::normalizeTitle).toSet()
                // A source query never changes the frozen canonical title or its index.
                // Exact primary titles/verified alternate names only: no token-fuzzy binding.
                val candidates = linkedMapOf<String, String>()
                for (query in terms) {
                    var cursor: String? = null
                    val seenCursors = mutableSetOf<String>()
                    for (page in 1..12) {
                        val queryInput = mutableMapOf<String, JsonElement>(
                            "titles" to JsonArray(listOf(JsonPrimitive(query))),
                            "requestedLanguage" to JsonPrimitive("en"),
                        )
                        if (cursor != null) queryInput["cursor"] = JsonPrimitive(cursor)
                        val response = invoke("reading.lookup", JsonObject(queryInput))
                        assertNull(
                            "Android source lookup failed for locked index ${index + 1} page $page",
                            response.failure,
                        )
                        val body = Json.parseToJsonElement(requireNotNull(response.value)).jsonObject
                        val items = requireNotNull(body["items"]).jsonArray
                        for (item in items) {
                            val obj = item.jsonObject
                            val id = obj["externalWorkId"]?.jsonPrimitive?.content ?: continue
                            if (!workHid.matches(id)) continue
                            val names = listOfNotNull(obj["title"]?.jsonPrimitive?.content) +
                                (
                                    (obj["aliases"] as? JsonArray)?.mapNotNull {
                                        (it as? JsonPrimitive)?.content
                                    } ?: emptyList()
                                    )
                            if (names.any { normalizeTitle(it) in permittedTitles }) {
                                candidates[id] = id.substringBefore(':')
                            }
                        }
                        val next = body["nextCursor"] ?: JsonNull
                        cursor = if (next == JsonNull) null else next.jsonPrimitive.content
                        if (cursor == null) break
                        assertTrue(
                            "Android lookup cursor loop at locked index ${index + 1}",
                            seenCursors.add(cursor),
                        )
                        // Source lookup discovery is limited to 12 pages per alias.
                        // This is NOT equivalent to the independent 80-page Node source audit.
                        // Prefer inspecting a valid HID over broad irrelevant search pages.
                        if (candidates.keys.any { it.startsWith("mangadot:") }) break
                    }
                    if (candidates.keys.any { it.startsWith("mangadot:") }) break
                }
                assertTrue(
                    "No exact-source candidate for frozen manhwa index ${index + 1}",
                    candidates.isNotEmpty(),
                )
                // Audit candidates as source HIDs only. If a rival HID lacks
                // chapter one it remains a rival, never rebinding its chapters.
                var provedChapterOne = false
                var provedChapters = 0
                var provedPages = 0
                var verifiedSource = ""
                var sourceBindingForReader: JsonObject? = null
                var chapterOneIdForReader: String? = null
                var attempts = 0
                for ((hid, source) in candidates.toList().sortedBy {
                    if (it.second == "mangadot") 0 else 1
                }.take(6)) {
                    attempts++
                    val binding = JsonObject(
                        mapOf(
                            "providerId" to JsonPrimitive("app.tsuzuki.mangaall"),
                            "externalWorkId" to JsonPrimitive(hid),
                            "facetId" to JsonPrimitive("en"),
                        ),
                    )
                    val ids = mutableSetOf<String>()
                    val seenChapterCursors = mutableSetOf<String>()
                    var chapterCursor: String? = null
                    var chapterOne = false
                    var chapterOneId: String? = null
                    var terminal = false
                    var pages = 0
                    var failure = false
                    for (page in 1..48) {
                        val input = mutableMapOf<String, JsonElement>("binding" to binding)
                        if (chapterCursor != null) input["cursor"] = JsonPrimitive(chapterCursor)
                        val reply = invoke("reading.chapters", JsonObject(input))
                        if (reply.failure != null || reply.value == null) {
                            failure = true
                            break
                        }
                        val raw = requireNotNull(reply.value)
                        assertTrue(
                            "Host output exceeds 48KiB at work ${index + 1}",
                            raw.toByteArray(Charsets.UTF_8).size <= 48 * 1024,
                        )
                        val result = Json.parseToJsonElement(raw).jsonObject
                        val chapters = requireNotNull(result["items"]).jsonArray
                        assertTrue("Host chapter page exceeds 256 rows", chapters.size <= 256)
                        for (chapter in chapters) {
                            val obj = chapter.jsonObject
                            val id = requireNotNull(obj["providerChapterId"]?.jsonPrimitive?.content)
                            assertTrue("Invalid chapter source prefix", id.startsWith("$source:"))
                            if (source == "mangadot") {
                                assertTrue("Invalid MangaDot chapter HID", dotChapterHid.matches(id))
                            }
                            assertTrue("Repeated chapter HID for frozen index ${index + 1}", ids.add(id))
                            if (obj["rawNumber"]?.jsonPrimitive?.content?.toDoubleOrNull() == 1.0) {
                                chapterOne = true
                                if (chapterOneId == null) chapterOneId = id
                            }
                        }
                        pages++
                        val next = result["nextCursor"] ?: JsonNull
                        chapterCursor = if (next == JsonNull) null else next.jsonPrimitive.content
                        if (chapterCursor == null) {
                            terminal = true
                            break
                        }
                        assertTrue(
                            "Repeated chapter cursor for frozen index ${index + 1}",
                            seenChapterCursors.add(chapterCursor),
                        )
                    }
                    if (!failure && terminal && chapterOne && ids.isNotEmpty()) {
                        provedChapterOne = true
                        provedChapters = ids.size
                        provedPages = pages
                        verifiedSource = source
                        sourceBindingForReader = binding
                        chapterOneIdForReader = requireNotNull(chapterOneId)
                        break
                    }
                }
                assertTrue(
                    "No terminal source-HID chapter-one proof for locked work ${index + 1}; inspected=$attempts",
                    provedChapterOne,
                )
                if (readerProbe) {
                    // Metadata-only Reader probe on exactly the successful
                    // chapter-one source HID. It is NOT pixel delivery or
                    // editorial canonical authority. No alternate chapters
                    // are silently substituted after a Reader failure.
                    val readerInput = JsonObject(
                        mapOf(
                            "binding" to requireNotNull(sourceBindingForReader),
                            "providerChapterId" to JsonPrimitive(requireNotNull(chapterOneIdForReader)),
                            "diagnosticsVersion" to JsonPrimitive(2),
                        ),
                    )
                    val readerResponse = invoke("reading.pages", readerInput)
                    assertNull(
                        "Reader metadata Host failure for frozen index ${index + 1}",
                        readerResponse.failure,
                    )
                    val readerJson = Json.parseToJsonElement(requireNotNull(readerResponse.value)).jsonObject
                    assertEquals(
                        "Reader source pages not available for frozen index ${index + 1}",
                        "page_list",
                        readerJson["type"]?.jsonPrimitive?.content,
                    )
                    val readerPages = requireNotNull(readerJson["pages"]).jsonArray
                    assertTrue(
                        "Reader metadata pages empty for frozen index ${index + 1}",
                        readerPages.isNotEmpty() && readerPages.size <= 5000,
                    )
                    for (readerPage in readerPages) {
                        val imageUrl = readerPage.jsonObject["url"]?.jsonPrimitive?.content
                        assertTrue(
                            "Reader image URL not HTTPS at frozen index ${index + 1}",
                            imageUrl?.startsWith("https://") == true,
                        )
                    }
                    if (appReaderProbe) {
                        // Exercise the app's actual HTTP Reader transport and
                        // per-provider origin policy, NOT only a raw HTTP client.
                        // This does not create editorial canonical authority
                        // or certify on-screen Reader UI/viewer navigation.
                        val first = readerPages.first().jsonObject
                        val firstHeaders = (first["headers"] as? JsonObject)
                            ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                            ?: emptyMap()
                        val readerNetworkOrigins = manifest.permissions.network
                            ?.origins.orEmpty().toSet()
                        val request = PreparedHttpPage(
                            url = requireNotNull(first["url"]?.jsonPrimitive?.content),
                            headers = firstHeaders,
                            allowedOrigins = readerNetworkOrigins,
                            allowLocalNetwork = false,
                        )
                        val readerLoader = ProviderHttpPageLoader(
                            requests = listOf(request),
                            cacheRoot = File(context.cacheDir, "mangaall-app-reader-$shard"),
                        )
                        try {
                            val loadedPage = readerLoader.getPages().single()
                            readerLoader.loadPage(loadedPage)
                            assertTrue(
                                "App Reader loader not ready for frozen index ${index + 1}",
                                loadedPage.status == Page.State.Ready,
                            )
                            val downloaded = requireNotNull(loadedPage.stream).invoke().use { input ->
                                val output = ByteArrayOutputStream()
                                val block = ByteArray(16 * 1024)
                                while (true) {
                                    val count = input.read(block)
                                    if (count < 0) break
                                    assertTrue(
                                        "App Reader cached pixel exceeds 16MiB for frozen index ${index + 1}",
                                        output.size() + count <= 16 * 1024 * 1024,
                                    )
                                    output.write(block, 0, count)
                                }
                                output.toByteArray()
                            }
                            val sampled = BitmapFactory.Options().apply { inSampleSize = 8 }
                            val bitmap = BitmapFactory.decodeByteArray(
                                downloaded, 0, downloaded.size, sampled,
                            )
                            assertTrue(
                                "App Reader cached bitmap decode failed for frozen index ${index + 1}",
                                bitmap != null && bitmap.width > 0 && bitmap.height > 0,
                            )
                            bitmap!!.getPixel(0, 0)
                            bitmap.recycle()
                            println(
                                "MANGAALL_FROZEN30_APP_READER|index=${index + 1}|shard=$shard" +
                                    "|loader=REAL_APP_OKHTTP|bitmap=DECODED|uiReader=NOT_TESTED" +
                                    "|canonical=NOT_TESTED|signed=NO",
                            )
                        } finally {
                            readerLoader.recycle()
                        }
                    }
                    if (pixelProbe) {
                        // Android HTTPS transport and BitmapFactory decoding ONLY:
                        // not a production Reader UI/policy or canonical guarantee.
                        // Never upload source image bytes, URL or raster pixels.
                        val page = readerPages.first().jsonObject
                        val imageUrl = requireNotNull(page["url"]?.jsonPrimitive?.content)
                        val uri = URI(imageUrl)
                        val imageHost = uri.host?.lowercase() ?: ""
                        val permittedHost = imageHost == "mangadot.net" ||
                            imageHost == "uploads.mangadex.org" ||
                            imageHost.endsWith(".mangadex.network")
                        assertTrue(
                            "Reader pixel source origin not allowed at frozen index ${index + 1}",
                            uri.scheme == "https" && uri.userInfo == null &&
                                (uri.port == -1 || uri.port == 443) && permittedHost,
                        )
                        val connection = URL(imageUrl).openConnection() as HttpsURLConnection
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 12_000
                        connection.readTimeout = 18_000
                        val imageHeaders = page["headers"] as? JsonObject ?: JsonObject(emptyMap())
                        for ((name, header) in imageHeaders) {
                            assertTrue(
                                "Reader image header not allowed at frozen index ${index + 1}",
                                name in setOf("Referer", "Origin", "User-Agent"),
                            )
                            val value = header.jsonPrimitive.content
                            assertTrue(
                                "Reader image header malformed at frozen index ${index + 1}",
                                value.length in 1..2048 && '\r' !in value && '\n' !in value,
                            )
                            connection.setRequestProperty(name, value)
                        }
                        try {
                            assertEquals(
                                "Reader pixel HTTP failure for frozen index ${index + 1}",
                                200,
                                connection.responseCode,
                            )
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            connection.inputStream.use { stream ->
                                while (true) {
                                    val bytesRead = stream.read(buffer)
                                    if (bytesRead < 0) break
                                    assertTrue(
                                        "Reader pixel exceeds 12MiB at frozen index ${index + 1}",
                                        output.size() + bytesRead <= 12 * 1024 * 1024,
                                    )
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                            val image = output.toByteArray()
                            assertTrue(
                                "Reader pixel response empty at frozen index ${index + 1}",
                                image.isNotEmpty(),
                            )
                            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                            val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size, options)
                            assertTrue(
                                "Reader pixel decode failed at frozen index ${index + 1}",
                                bitmap != null && bitmap.width > 0 && bitmap.height > 0,
                            )
                            bitmap!!.getPixel(0, 0)
                            bitmap.recycle()
                            println(
                                "MANGAALL_FROZEN30_PIXEL|index=${index + 1}|shard=$shard" +
                                    "|firstPage=DECODED|host=ANDROID_HTTP|uiReader=NOT_TESTED" +
                                    "|canonical=NOT_TESTED|signed=NO",
                            )
                        } finally {
                            connection.disconnect()
                        }
                    }
                    println(
                        "MANGAALL_FROZEN30_READER|index=${index + 1}|shard=$shard" +
                            "|pages=${readerPages.size}|metadata=PASS|pixels=NOT_TESTED" +
                            "|canonical=NOT_TESTED|signed=NO",
                    )
                }
                println(
                    "MANGAALL_FROZEN30_ANDROID|index=${index + 1}|shard=$shard|source=$verifiedSource" +
                        "|chapters=$provedChapters|pages=$provedPages|chapterOne=YES|terminal=YES" +
                        "|rivalCandidates=${candidates.size}|canonical=NOT_TESTED|reader=NOT_TESTED",
                )
            }
            println(
                "MANGAALL_FROZEN30_ANDROID|outcome=PASS|shard=$shard|works=10" +
                    "|sourceHidOnly=YES|canonical=NOT_TESTED|reader=NOT_TESTED|signed=NO",
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
