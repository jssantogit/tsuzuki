package eu.kanade.tachiyomi.provider.runtime

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
import tachiyomi.core.provider.runtime.ProviderRuntimeProtocol
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Source creator observation only, no canonical association or reading authority. */
@RunWith(AndroidJUnit4::class)
class MangaAllMangaDotCreatorLiveInstrumentedTest {
    @Test(timeout = 240_000L)
    fun frozenCreatorSamplesThroughRealHost(): Unit = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        assertEquals(
            "I_ACCEPT_MANGADOT_CREATOR_RESEARCH",
            InstrumentationRegistry.getArguments().getString("mangaDotCreatorOptIn"),
        )
        val works = instrument.context.assets.open("mangaall-dual-source-30-cohort-lock.json")
            .bufferedReader().use {
                Json.parseToJsonElement(it.readText()).jsonObject.getValue("works").jsonArray
            }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(works.toString().encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(30, works.size)
        assertEquals("19996790a934514c7877447610ee09a9e0dd2bd8fe8b2c42ff46a7a5cfe64bd1", digest)
        val zip = instrument.context.assets.open("mangaall-dual-source-code11-unsigned-research.zip")
            .use { it.readBytes() }
        val parsed = ProviderPackageParser().parse(zip)
        val manifest = parsed.manifest
        assertEquals("app.tsuzuki.mangaall", manifest.id)
        assertEquals(11L, manifest.version.code)
        assertTrue("modules/sources/mangadot/modules/creator-evidence.js" in parsed.entries)
        assertTrue(manifest.permissions.network?.origins.orEmpty().none { it.contains("mangafire", true) })
        val context = instrument.targetContext
        val root = File(context.cacheDir, "mangaall-dot-creator-probe")
        val httpFacts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = ProviderRuntimeClient(
            context = context,
            hostInvocationFactory = ProviderHostInvocationFactory(
                context = context,
                storageRoot = root,
                httpDiagnosticSink = { _, event ->
                    if (event.host == "mangadot.net") {
                        httpFacts += "${event.phase.name}:${event.statusCode ?: 0}:${event.failureFamily?.name ?: "NONE"}"
                    }
                },
            ),
        )
        val results = mutableListOf<Boolean>()
        try {
            for (index in listOf(1, 3, 10, 12)) {
                httpFacts.clear()
                val title = works[index - 1].jsonPrimitive.content
                val invocationId = "mangaall-dot-creators:" + UUID.randomUUID().toString()
                val policy = ProviderHostInvocationPolicy.fromManifest(manifest, invocationId)
                    .forCapability("reading.lookup")
                val response = client.invokePackage(
                    ProviderRuntimeInvocationRequest(
                        protocolVersion = ProviderRuntimeProtocol.VERSION,
                        invocationId = invocationId,
                        providerId = manifest.id,
                        artifactVersionCode = manifest.version.code,
                        capabilityId = "reading.lookup",
                        capabilityVersion = 1,
                        configurationFingerprint = "dot-creator-probe",
                        fileName = "main.js",
                        hostModules = policy.allowedHostModules(),
                        limits = ProviderRuntimeLimitsDto(
                            wallClockTimeoutMs = 30_000L,
                            jsExecutionTimeoutMs = 15_000L,
                        ),
                    ),
                    zip,
                    JsonObject(
                        mapOf(
                            "titles" to JsonArray(listOf(JsonPrimitive(title))),
                            "requestedLanguage" to JsonPrimitive("en"),
                            "editorialEvidenceVersion" to JsonPrimitive(1),
                        ),
                    ).toString(),
                    policy,
                )
                val rows = response.value?.let {
                    Json.parseToJsonElement(it).jsonObject["items"]?.jsonArray
                }.orEmpty().map { it.jsonObject }.filter {
                    it["externalWorkId"]?.jsonPrimitive?.content?.startsWith("mangadot:") == true &&
                        it["title"]?.jsonPrimitive?.content.equals(title, ignoreCase = true)
                }
                val verified = rows.filter {
                    it["detailOutcome"]?.jsonPrimitive?.content == "VERIFIED_NO_ALIASES" &&
                        (it["authors"] as? JsonArray)?.any { author ->
                            author.jsonPrimitive.content.isNotBlank()
                        } == true
                }
                val detail = rows.singleOrNull()?.get("detailOutcome")?.jsonPrimitive?.content
                    ?.takeIf { it in setOf("VERIFIED_NO_ALIASES", "DETAIL_FAILED", "INVALID_RESPONSE", "IDENTITY_MISMATCH") }
                    ?: "NOT_OBSERVED"
                val facts = synchronized(httpFacts) { httpFacts.toList() }
                val succeeded = facts.count { it.startsWith("SUCCEEDED:200:") }
                val failed = facts.count { it.startsWith("FAILED:") }
                val forbidden = facts.count { it.startsWith("FAILED:403:") }
                val throttled = facts.count { it.startsWith("FAILED:429:") }
                val passed = response.failure == null && rows.size == 1 && verified.size == 1
                results += passed
                instrument.sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "MANGAALL_DOT_CREATOR|index=$index|outcome=${if (passed) "PASS" else "RED"}" +
                                "|exact=${rows.size}|verified=${verified.size}|canonical=NOT_TESTED" +
                                "|detail=$detail|http200=$succeeded|httpFailed=$failed|http403=$forbidden|http429=$throttled",
                        )
                    },
                )
            }
            assertEquals("All four existing frozen observations must pass via actual Host", 4, results.count { it })
        } finally {
            root.deleteRecursively()
        }
    }
}
