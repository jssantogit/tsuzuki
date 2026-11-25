package eu.kanade.tachiyomi.provider.runtime

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.ProviderHttpPageLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One live test, two independent provider lanes; no fixtures or challenge retries. */
@RunWith(AndroidJUnit4::class)
class ProviderAdvancedExtractionLiveInstrumentedTest {
    @Test
    fun threeKnownWorksBothSources(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit live opt-in required", args.getString("advancedLive") == "true")
        val context = instrumentation.targetContext
        assertEquals("app.tsuzuki.dev", context.packageName)
        val root = File(context.cacheDir, "advanced-live-" + UUID.randomUUID()).apply { mkdirs() }
        val works = listOf(
            Work("death-note", "Death Note", setOf("Death Note")),
            Work("punpun", "Oyasumi Punpun", setOf("Oyasumi Punpun", "Goodnight Punpun", "Good Night Punpun")),
            Work("akame", "Akame ga Kill!", setOf("Akame ga Kill!", "Akame ga Kill")),
        )
        val network = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val blocked = ConcurrentHashMap<String, Int>()
        val factory = ProviderHostInvocationFactory(
            context = context,
            storageRoot = File(root, "storage"),
            baseHttpClient = network,
            httpDiagnosticSink = { providerId, detail ->
                val status = detail.statusCode
                if (status == 403 || status == 429) blocked.putIfAbsent(providerId, status)
            },
        )
        val client = ProviderRuntimeClient(context, factory)
        try {
            val results = supervisorScope {
                listOf("mangaall-ball", "mangafire").map { name ->
                    async(Dispatchers.IO) {
                        if (name == "mangaall-ball" && args.getString("mangaBallApiApproved") != "true") {
                            return@async works.map { verdict(name, it.key, "ACCESS_NOT_APPROVED", 0L) }
                        }
                        val candidate = instrumentation.context.assets.open("advanced-live-$name-unsigned.zip").use { it.readBytes() }
                        val manifest = ProviderPackageParser().parse(candidate).manifest
                        check(manifest.permissions.network?.localNetwork == false)
                        check(manifest.permissions.browser?.origins.orEmpty().isEmpty())
                        val origins = manifest.permissions.network?.origins.orEmpty()
                        check(origins.isNotEmpty())
                        suspend fun invoke(capability: String, input: JsonObject): JsonObject {
                            val invocationId = "advanced-live:" + UUID.randomUUID()
                            val policy = ProviderHostInvocationPolicy.fromManifest(manifest, invocationId).forCapability(capability)
                            val request = ProviderRuntimeInvocationRequest(
                                protocolVersion = ProviderRuntimeProtocol.VERSION,
                                invocationId = invocationId,
                                providerId = manifest.id,
                                artifactVersionCode = manifest.version.code,
                                capabilityId = capability,
                                capabilityVersion = 1,
                                configurationFingerprint = "advanced-live-three",
                                fileName = "main.js",
                                hostModules = policy.allowedHostModules(),
                                limits = ProviderRuntimeLimitsDto(wallClockTimeoutMs = 15_000L, jsExecutionTimeoutMs = 10_000L),
                            )
                            val response = client.invokePackage(request, candidate, input.toString(), policy)
                            blocked[manifest.id]?.let { throw GateFailure("HTTP_$it") }
                            response.failure?.let { throw GateFailure("RUNTIME_" + it.name) }
                            return Json.parseToJsonElement(requireNotNull(response.value)).jsonObject
                        }
                        works.map { work ->
                            val started = SystemClock.elapsedRealtime()
                            var stage = "LOOKUP"
                            val outcome = try {
                                blocked[manifest.id]?.let { throw GateFailure("HTTP_$it") }
                                withTimeout(75_000L) {
                                    val accepted = work.names.map(::normalize).toSet()
                                    val matching = mutableSetOf<String>()
                                    val lookupCursors = mutableSetOf<String>()
                                    var lookupCursor: String? = null
                                    var lookupTerminal = false
                                    repeat(3) {
                                        if (lookupTerminal) return@repeat
                                        val input = mutableMapOf<String, JsonElement>(
                                            "titles" to JsonArray(listOf(JsonPrimitive(work.query))),
                                            "requestedLanguage" to JsonPrimitive("en"),
                                        )
                                        lookupCursor?.let { input["cursor"] = JsonPrimitive(it) }
                                        val lookup = invoke("reading.lookup", JsonObject(input))
                                        for (entry in lookup["items"]!!.jsonArray) {
                                            val hit = entry.jsonObject
                                            val names = listOf(hit["title"]!!.jsonPrimitive.content) +
                                                hit["aliases"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
                                            if (names.any { normalize(it) in accepted }) {
                                                matching += hit["externalWorkId"]!!.jsonPrimitive.content
                                            }
                                        }
                                        val next = lookup["nextCursor"] ?: throw GateFailure("MISSING_CURSOR")
                                        lookupCursor = if (next == JsonNull) null else next.jsonPrimitive.content
                                        if (lookupCursor == null) {
                                            lookupTerminal = true
                                        } else if (!lookupCursors.add(requireNotNull(lookupCursor))) {
                                            throw GateFailure("REPEATED_CURSOR")
                                        }
                                    }
                                    if (!lookupTerminal) throw GateFailure("LOOKUP_NOT_TERMINAL")
                                    if (matching.size != 1) throw GateFailure("IDENTITY_NOT_UNIQUE")
                                    val workId = matching.single()
                                    if (name == "mangaall-ball" && !workId.startsWith("mangaball:")) throw GateFailure("SOURCE_ID")
                                    val binding = JsonObject(
                                        mapOf("providerId" to JsonPrimitive(manifest.id), "externalWorkId" to JsonPrimitive(workId), "facetId" to JsonPrimitive("en")),
                                    )
                                    stage = "CHAPTERS"
                                    val ids = mutableSetOf<String>()
                                    val cursors = mutableSetOf<String>()
                                    var firstChapter: String? = null
                                    var cursor: String? = null
                                    var terminal = false
                                    repeat(4) {
                                        if (terminal) return@repeat
                                        val input = mutableMapOf<String, JsonElement>("binding" to binding)
                                        cursor?.let { input["cursor"] = JsonPrimitive(it) }
                                        val result = invoke("reading.chapters", JsonObject(input))
                                        for (entry in result["items"]!!.jsonArray) {
                                            val chapter = entry.jsonObject
                                            val id = chapter["providerChapterId"]!!.jsonPrimitive.content
                                            if (!ids.add(id)) throw GateFailure("DUPLICATE_CHAPTER")
                                            if (name == "mangaall-ball" && !id.startsWith("mangaball:")) throw GateFailure("SOURCE_ID")
                                            if (chapter["language"]?.jsonPrimitive?.content != "en") throw GateFailure("WRONG_LANGUAGE")
                                            if (chapter["rawNumber"]?.jsonPrimitive?.content?.toDoubleOrNull() == 1.0 && firstChapter == null) firstChapter = id
                                        }
                                        val next = result["nextCursor"] ?: throw GateFailure("MISSING_CURSOR")
                                        cursor = if (next == JsonNull) null else next.jsonPrimitive.content
                                        if (cursor == null) terminal = true else if (!cursors.add(requireNotNull(cursor))) throw GateFailure("REPEATED_CURSOR")
                                    }
                                    if (!terminal) throw GateFailure("CHAPTER_CURSOR_LIMIT")
                                    val chapterId = firstChapter ?: throw GateFailure("CHAPTER_ONE_MISSING")
                                    stage = "PAGES"
                                    val delivery = invoke(
                                        "reading.pages",
                                        JsonObject(mapOf("binding" to binding, "providerChapterId" to JsonPrimitive(chapterId))),
                                    )
                                    if (delivery["type"]?.jsonPrimitive?.content != "page_list") throw GateFailure("UNSUPPORTED_DELIVERY")
                                    val pages = delivery["pages"]!!.jsonArray
                                    if (pages.isEmpty()) throw GateFailure("NO_PAGES")
                                    val first = pages.first().jsonObject
                                    val page = PreparedHttpPage(
                                        url = first["url"]!!.jsonPrimitive.content,
                                        headers = first["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                                        allowedOrigins = origins,
                                        allowLocalNetwork = false,
                                    )
                                    stage = "READER_TRANSPORT"
                                    val loader = ProviderHttpPageLoader(listOf(page), root, network)
                                    try {
                                        val readerPage = loader.getPages().single()
                                        withTimeout(15_000L) { loader.loadPage(readerPage) }
                                        if (readerPage.status != Page.State.Ready) throw GateFailure("IMAGE_FETCH")
                                        val stream = readerPage.stream ?: throw GateFailure("IMAGE_STREAM")
                                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                        stream().use { BitmapFactory.decodeStream(it, null, bounds) }
                                        if (bounds.outWidth !in 1..12000 || bounds.outHeight !in 1..12000) throw GateFailure("IMAGE_BOUNDS")
                                        val sample = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth / it, bounds.outHeight / it) <= 2048 }
                                        val bitmap = stream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                                            ?: throw GateFailure("IMAGE_DECODE")
                                        bitmap.recycle()
                                    } finally {
                                        loader.recycle()
                                    }
                                }
                                "PASS"
                            } catch (_: TimeoutCancellationException) {
                                stage + "_TIMEOUT"
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: GateFailure) {
                                stage + "_" + error.code
                            } catch (_: Exception) {
                                stage + "_FAILED"
                            }
                            verdict(name, work.key, outcome, SystemClock.elapsedRealtime() - started)
                        }
                    }
                }.awaitAll().flatten()
            }
            assertEquals(6, results.size)
            assertTrue("Live gate: " + results.joinToString(";"), results.all { it.endsWith("|PASS") })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verdict(source: String, work: String, result: String, elapsed: Long): String {
        val value = "$source|$work|$result"
        Log.i("AdvancedLiveGate", "ADVANCED_LIVE|$value|ms=$elapsed")
        return value
    }

    private fun normalize(value: String): String = value.trim().lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    private data class Work(val key: String, val query: String, val names: Set<String>)
    private class GateFailure(val code: String) : IllegalStateException(code)
}
