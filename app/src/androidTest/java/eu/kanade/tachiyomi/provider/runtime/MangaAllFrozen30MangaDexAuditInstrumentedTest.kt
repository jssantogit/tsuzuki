package eu.kanade.tachiyomi.provider.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.packageformat.ProviderPackageParser
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Diagnostic only: exhaust the MangaDex branch of the real unsigned router
 * without allowing MangaDot success to conceal missing MangaDex coverage.
 * The initial cursor deliberately selects only MangaDex's existing V1 branch.
 * This is not the ordinary combined lookup or a canonical edition proof.
 */
@RunWith(AndroidJUnit4::class)
class MangaAllFrozen30MangaDexAuditInstrumentedTest {
    @Test(timeout = 1_800_000L)
    fun auditMangaDexIndependently(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("I_ACCEPT_FROZEN30_MANGADEX_AUDIT", args.getString("mangaDexAuditOptIn"))
        val shard = requireNotNull(args.getString("mangaAllFrozen30Shard")).toInt()
        require(shard in 0..2)
        val context = instrument.targetContext
        assertEquals("app.tsuzuki.dev", context.packageName)
        val lock = instrument.context.assets.open("mangaall-dual-source-30-cohort-lock.json")
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val works = lock.getValue("works").jsonArray
        assertEquals(30, works.size)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(works.toString().encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1", digest)
        val zip = instrument.context.assets.open("mangaall-dual-source-code11-unsigned-research.zip")
            .use { it.readBytes() }
        val manifest = ProviderPackageParser().parse(zip).manifest
        assertEquals("app.tsuzuki.mangaall", manifest.id)
        assertEquals(11L, manifest.version.code)
        assertTrue(manifest.permissions.network?.origins.orEmpty().none { it.contains("mangafire", true) })
        val root = File(context.cacheDir, "mangaall-frozen30-dex-audit-$shard").apply {
            deleteRecursively()
            mkdirs()
        }
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(context = context, storageRoot = root),
        )

        suspend fun call(capability: String, input: JsonObject): ProviderRuntimeInvocationResponse {
            val invocationId = "mangaall-dex-audit:" + UUID.randomUUID()
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
                    configurationFingerprint = "frozen30-dex-audit",
                    fileName = "main.js",
                    hostModules = policy.allowedHostModules(),
                    limits = ProviderRuntimeLimitsDto(wallClockTimeoutMs = 30_000L, jsExecutionTimeoutMs = 15_000L),
                ),
                zip,
                input.toString(),
                policy,
            )
        }

        suspend fun inspect(title: String, facts: Facts) {
            val terms = listOf(title) + when (title) {
                "SSS-Class Revival Hunter" -> listOf("SSS-Class Suicide Hunter")
                "Return of the Blossoming Blade" -> listOf("Return of the Mount Hua Sect")
                "The Reason Why Raeliana Ended Up at the Duke's Mansion" ->
                    listOf("Why Raeliana Ended Up at the Duke's Mansion", "그녀가 공작저로 가야 했던 사정")
                else -> emptyList()
            }
            val names = terms.map(::normalize).toSet()
            val candidates = linkedMapOf<String, JsonObject>()
            for (term in terms) {
                var cursor: String? = DEX_START
                val seenCursors = mutableSetOf<String>()
                var terminal = false
                for (page in 1..12) {
                    val request = JsonObject(
                        mapOf(
                            "titles" to JsonArray(listOf(JsonPrimitive(term))),
                            "requestedLanguage" to JsonPrimitive("en"),
                            "cursor" to JsonPrimitive(requireNotNull(cursor)),
                            "editorialEvidenceVersion" to JsonPrimitive(1),
                            "coverageEvidenceVersion" to JsonPrimitive(1),
                            "diagnosticsVersion" to JsonPrimitive(2),
                        ),
                    )
                    val reply = call("reading.lookup", request)
                    if (reply.failure != null || reply.value == null) break
                    val body = Json.parseToJsonElement(requireNotNull(reply.value)).jsonObject
                    if (body["lookupFailureCode"] != null) break
                    for (row in body.getValue("items").jsonArray) {
                        val candidate = row.jsonObject
                        val hid = candidate.getValue("externalWorkId").jsonPrimitive.content
                        check(DEX_ID.matches(hid))
                        val candidateNames = listOf(candidate.getValue("title").jsonPrimitive.content) +
                            (candidate["aliases"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                        if (candidateNames.none { normalize(it) in names }) continue
                        val old = candidates[hid]
                        if (old == null || hasCredits(candidate)) candidates[hid] = candidate
                    }
                    cursor = next(body)
                    if (cursor == null) {
                        terminal = true
                        break
                    }
                    val remaining = Json.parseToJsonElement(cursor).jsonObject.getValue("remaining").jsonObject
                    check(remaining.keys == setOf("mangadex") && seenCursors.add(cursor))
                }
                if (!terminal) facts.incompleteLookup++
            }
            facts.candidates = candidates.size
            facts.credited = candidates.values.count(::hasCredits)
            if (candidates.size > 8) {
                facts.truncatedCandidates = true
                return
            }
            for ((hid, _) in candidates) {
                val binding = JsonObject(
                    mapOf(
                        "providerId" to JsonPrimitive("app.tsuzuki.mangaall"),
                        "externalWorkId" to JsonPrimitive(hid),
                        "facetId" to JsonPrimitive("en"),
                    ),
                )
                val ids = mutableSetOf<String>()
                val one = mutableListOf<String>()
                val seenCursors = mutableSetOf<String>()
                var cursor: String? = null
                var terminal = false
                for (page in 1..48) {
                    val input = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("binding" to binding)
                    cursor?.let { input["cursor"] = JsonPrimitive(it) }
                    val reply = call("reading.chapters", JsonObject(input))
                    if (reply.failure != null || reply.value == null) break
                    val raw = requireNotNull(reply.value)
                    check(raw.encodeToByteArray().size <= 48 * 1024)
                    val body = Json.parseToJsonElement(raw).jsonObject
                    for (row in body.getValue("items").jsonArray) {
                        val item = row.jsonObject
                        val id = item.getValue("providerChapterId").jsonPrimitive.content
                        check(DEX_ID.matches(id) && ids.add(id))
                        check(item["language"]?.jsonPrimitive?.content == "en")
                        if (item["rawNumber"]?.jsonPrimitive?.content?.toDoubleOrNull() == 1.0) one += id
                    }
                    cursor = next(body)
                    if (cursor == null) {
                        terminal = true
                        break
                    }
                    check(seenCursors.add(cursor))
                }
                if (!terminal) {
                    facts.incompleteInventories++
                    continue
                }
                facts.terminalInventories++
                if (ids.isEmpty()) facts.emptyInventories++
                facts.chapterOne += one.size
                if (one.size > 8) facts.truncatedChapterOne = true
                for (chapterId in one.take(8)) {
                    val request = JsonObject(
                        mapOf(
                            "binding" to binding,
                            "providerChapterId" to JsonPrimitive(chapterId),
                            "diagnosticsVersion" to JsonPrimitive(2),
                        ),
                    )
                    val reply = call("reading.pages", request)
                    if (reply.failure != null || reply.value == null) {
                        facts.pageFailures++
                        continue
                    }
                    val body = Json.parseToJsonElement(requireNotNull(reply.value)).jsonObject
                    when (body["type"]?.jsonPrimitive?.content) {
                        "page_list" -> {
                            val pages = body.getValue("pages").jsonArray
                            check(pages.isNotEmpty() && pages.size <= 5000)
                            check(pages.all { it.jsonObject["url"]?.jsonPrimitive?.content?.startsWith("https://") == true })
                            facts.pageLists++
                        }
                        "page_failure" -> {
                            if (body["pageFailureStage"]?.jsonPrimitive?.content == "EXTERNAL_CHAPTER") {
                                facts.externalChapters++
                            } else {
                                facts.pageFailures++
                            }
                        }
                        else -> facts.pageFailures++
                    }
                }
            }
        }

        try {
            for (index in shard * 10 until (shard + 1) * 10) {
                val facts = Facts()
                try {
                    inspect(works[index].jsonPrimitive.content, facts)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    facts.contractFailure = true
                }
                instrument.sendStatus(
                    1,
                    Bundle().apply { putString("stream", facts.report(index + 1)) },
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun normalize(title: String): String =
        title.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'').trim().lowercase()

    private fun next(body: JsonObject): String? =
        body["nextCursor"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

    private fun hasCredits(candidate: JsonObject): Boolean =
        candidate["detailOutcome"]?.jsonPrimitive?.content in setOf("VERIFIED_WITH_ALIASES", "VERIFIED_NO_ALIASES") &&
            (candidate["authors"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content.isNotBlank() }

    private data class Facts(
        var candidates: Int = 0,
        var credited: Int = 0,
        var incompleteLookup: Int = 0,
        var terminalInventories: Int = 0,
        var emptyInventories: Int = 0,
        var incompleteInventories: Int = 0,
        var chapterOne: Int = 0,
        var externalChapters: Int = 0,
        var pageLists: Int = 0,
        var pageFailures: Int = 0,
        var truncatedCandidates: Boolean = false,
        var truncatedChapterOne: Boolean = false,
        var contractFailure: Boolean = false,
    ) {
        fun report(index: Int): String {
            val outcome = when {
                contractFailure -> "CONTRACT_FAILED"
                incompleteLookup > 0 || truncatedCandidates || truncatedChapterOne || incompleteInventories > 0 ->
                    "INCOMPLETE"
                candidates == 0 -> "NO_EXACT_HID"
                terminalInventories == emptyInventories -> "EMPTY_EN"
                chapterOne == 0 -> "NO_CHAPTER_ONE_EN"
                pageLists > 0 -> "SOURCE_PAGE_LIST_READY"
                externalChapters == chapterOne -> "EXTERNAL_ONLY"
                else -> "PAGE_LIST_UNAVAILABLE"
            }
            return "MANGAALL_DEX_AUDIT|index=$index|outcome=$outcome|candidates=$candidates|credited=$credited" +
                "|lookupIncomplete=$incompleteLookup|terminal=$terminalInventories|empty=$emptyInventories" +
                "|inventoryIncomplete=$incompleteInventories|chapterOne=$chapterOne|external=$externalChapters" +
                "|pageLists=$pageLists|pageFailures=$pageFailures|canonical=NOT_TESTED"
        }
    }

    private companion object {
        const val DEX_START = """{"v":1,"remaining":{"mangadex":{"c":null,"o":0}}}"""
        val DEX_ID = Regex("mangadex:[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")
    }
}
