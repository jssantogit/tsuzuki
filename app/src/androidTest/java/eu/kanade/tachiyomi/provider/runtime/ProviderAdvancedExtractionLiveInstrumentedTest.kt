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
import kotlinx.coroutines.withContext
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
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkSourcePortfolio
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
            httpRequestAllowed = { providerId -> providerId !in blocked },
            httpDiagnosticSink = { providerId, detail ->
                val status = detail.statusCode
                if (status == 401 || status == 403 || status == 429) {
                    if (blocked.putIfAbsent(providerId, status) == null) {
                        val source = if (providerId == "app.tsuzuki.mangafire") "mangafire" else "mangaall-ball"
                        Log.i("AdvancedLiveGate", "ADVANCED_DENIAL|$source|HTTP_$status|challenge_marker=${detail.challengeAdvertised}")
                    }
                }
            },
        )
        val client = ProviderRuntimeClient(context, factory)
        try {
            // The same JUnit method exercises the controlled contract and live
            // lanes. Report a failed contract without disguising live outcomes.
            suspend fun contract(name: String, block: suspend () -> Unit): Boolean {
                val passed = try {
                    withTimeout(45_000L) { withContext(Dispatchers.IO) { block() } }
                    true
                } catch (_: TimeoutCancellationException) {
                    false
                } catch (error: CancellationException) {
                    throw error
                } catch (failure: Exception) {
                    Log.i("AdvancedLiveGate", "ADVANCED_CONTRACT_FAILURE|$name|EXCEPTION")
                    false
                } catch (failure: AssertionError) {
                    Log.i("AdvancedLiveGate", "ADVANCED_CONTRACT_FAILURE|$name|ASSERTION")
                    false
                }
                Log.i("AdvancedLiveGate", "ADVANCED_CONTRACT_DETAIL|$name|" + if (passed) "PASS" else "FAIL")
                return passed
            }
            val controlled = supervisorScope {
                val adapters = async { contract("ADAPTERS_SQLITE") { ProviderAdvancedExtractionInstrumentedTest().runControlled() } }
                val primitives = async { contract("PROTOCOL_SESSION") { AdvancedExtractionLocalContract.run(context, root) } }
                awaitAll(adapters, primitives).all { it }
            }
            Log.i("AdvancedLiveGate", "ADVANCED_CONTRACT|" + if (controlled) "PASS" else "FAIL")
            val results = supervisorScope {
                listOf("mangaall-ball", "mangafire").map { name ->
                    async(Dispatchers.IO) {
                        val candidate = instrumentation.context.assets.open("advanced-live-$name-unsigned.zip").use { it.readBytes() }
                        val manifest = ProviderPackageParser().parse(candidate).manifest
                        check(manifest.permissions.network?.localNetwork == false)
                        check(manifest.permissions.browser?.origins.orEmpty().isEmpty())
                        val origins = manifest.permissions.network?.origins.orEmpty()
                        check(origins.isNotEmpty())
                        suspend fun invokeOnce(capability: String, input: JsonObject): tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse {
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
                            return client.invokePackage(request, candidate, input.toString(), policy)
                        }
                        suspend fun invoke(capability: String, input: JsonObject): JsonObject {
                            blocked[manifest.id]?.let { throw GateFailure("HTTP_$it") }
                            var response = invokeOnce(capability, input)
                            blocked[manifest.id]?.let { throw GateFailure("HTTP_$it") }
                            // Only the idempotent lookup may be retried, once,
                            // on an isolated process death. Never retry an HTTP
                            // denial or silently replay chapter/page operations.
                            if (capability == "reading.lookup" && response.failure == ProviderRuntimeFailureCode.RUNTIME_DIED) {
                                Log.i("AdvancedLiveGate", "ADVANCED_RECOVERY|$name|RUNTIME_DIED")
                                response = invokeOnce(capability, input)
                                blocked[manifest.id]?.let { throw GateFailure("HTTP_$it") }
                            }
                            response.failure?.let { throw GateFailure("RUNTIME_" + it.name) }
                            return Json.parseToJsonElement(requireNotNull(response.value)).jsonObject
                        }
                        works.map { work ->
                            blocked[manifest.id]?.let { status ->
                                return@map verdict(name, work.key, "NOT_ATTEMPTED_HTTP_$status", 0L)
                            }
                            val started = SystemClock.elapsedRealtime()
                            var stage = "LOOKUP"
                            val outcome = try {
                                blocked[manifest.id]?.let { throw GateFailure("HTTP_$it") }
                                withTimeout(75_000L) {
                                    val accepted = work.names.map(::normalize).toSet()
                                    val matching = linkedMapOf<String, ProviderWorkCandidate>()
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
                                                val id = hit["externalWorkId"]!!.jsonPrimitive.content
                                                matching[id] = ProviderWorkCandidate(
                                                    externalWorkId = id,
                                                    title = names.first(),
                                                    aliases = names.drop(1),
                                                    url = hit["url"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                                                )
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
                                    if (matching.isEmpty()) throw GateFailure("IDENTITY_NOT_FOUND")
                                    // Reuse the existing production negative edition
                                    // evidence rule. A qualified alternate cannot be
                                    // selected as the unqualified extraction target.
                                    // This does NOT certify a canonical editorial binding.
                                    val eligible = matching.values.filterNot { candidate ->
                                        work.names.any { title ->
                                            ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                                                ProviderId(manifest.id), candidate, title,
                                            )
                                        }
                                    }
                                    val excluded = matching.size - eligible.size
                                    Log.i("AdvancedLiveGate", "ADVANCED_IDENTITY|$name|"+work.key+"|matches="+matching.size+"|excluded="+excluded+"|eligible="+eligible.size)
                                    if (eligible.size != 1) throw GateFailure("IDENTITY_NOT_UNIQUE")
                                    val workId = eligible.single().externalWorkId
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
            assertTrue("Contract=$controlled; live=" + results.joinToString(";"), controlled && results.all { it.endsWith("|PASS") })
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
