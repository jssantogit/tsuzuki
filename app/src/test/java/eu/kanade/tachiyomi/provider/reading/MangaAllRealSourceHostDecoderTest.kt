package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.security.MessageDigest

/**
 * This test is NEVER a synthetic work matrix. The optional workflow passes
 * the exact real-HTTP source envelopes and officially signed MangaAll .tsz.
 * Without those environment paths it only executes negative contract tests.
 *
 * No canonical catalog identity, SQLite state or Reader pixels are asserted
 * until #207 provides the corresponding production stages.
 */
class MangaAllRealSourceHostDecoderTest {

    @Test
    fun `tampering with captured real source metadata fails before Host decode`() {
        // Contract-only empty-shard payload. No fake work, author, chapter or
        // source candidate is ever interpreted as a live lookup.
        val outer = buildJsonObject {
            put("schemaVersion", 1)
            put("captureKind", "REAL_PROVIDER_NORMALIZED_ENVELOPE_BATCH")
            put("origin", "LIVE_SIGNED_PROVIDER_JS")
            put("providerId", "app.tsuzuki.mangaall")
            put("providerVersionCode", 8)
            put("repositoryId", "app.tsuzuki.providers")
            put("signedIndexSequence", 34)
            put("packageSha256", "a".repeat(64))
            put("observedAt", "2026-11-16T00:00:00.000Z")
            put("selectionSource", "USER_INPUT")
            put("shardIndex", 2)
            put("shardCount", 3)
            put("catalogStage", "NOT_EXECUTED_HOST")
            put("canonicalStage", "NOT_EXECUTED_HOST")
            put("readerPixelStage", "NOT_EXECUTED_HOST")
            put("captureOutcome", "NO_WORK_ASSIGNED")
            put("cases", kotlinx.serialization.json.JsonArray(emptyList()))
        }
        val canonical = outer.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val signed = JsonObject(outer + mapOf("sha256" to kotlinx.serialization.json.JsonPrimitive(digest))).toString()
        MangaAllRealSourceReplay.validate(signed).size shouldBe 0
        val forged = signed.replace("\"NOT_EXECUTED_HOST\"", "\"CANONICAL_COMPLETE\"")
        assertThrows<IllegalArgumentException> {
            MangaAllRealSourceReplay.validate(forged)
        }
        assertThrows<IllegalArgumentException> {
            MangaAllRealSourceReplay.validate(signed.replace("NO_WORK_ASSIGNED", "SOURCE_ATTEMPTS_RECORDED"))
        }
        assertThrows<IllegalArgumentException> {
            MangaAllRealSourceReplay.validate(signed.replace(digest, "0".repeat(64)))
        }
    }

    @Test
    fun `captured real call cursor coverage distinguishes incomplete pages and broken continuations`() {
        // CONTRACT-ONLY transport pages, deliberately no candidate/author/HID
        // records. They are not marketed as a live work or a canonical test.
        fun lookup(requestCursor: String?, nextCursor: String?): JsonObject {
            val before = requestCursor?.let { "\"$it\"" } ?: "null"
            val after = nextCursor?.let { "\"$it\"" } ?: "null"
            return Json.parseToJsonElement(
                """{"operation":"lookup","outcome":"RESULT",
                   "request":{"titles":["CONTRACT_ONLY_QUERY"],"requestedLanguage":"en","cursor":$before},
                   "response":{"items":[],"nextCursor":$after}}""",
            ).jsonObject
        }
        val firstPage = lookup(null, "cursor-2")
        val unfinished = MangaAllRealSourceReplay.cursorCoverage(listOf(firstPage))
        unfinished.pendingLookupCursors shouldBe 1
        unfinished.orphanContinuations shouldBe 0

        val complete = MangaAllRealSourceReplay.cursorCoverage(
            listOf(firstPage, lookup("cursor-2", null)),
        )
        complete.pendingLookupCursors shouldBe 0
        complete.orphanContinuations shouldBe 0

        val disconnected = MangaAllRealSourceReplay.cursorCoverage(
            listOf(firstPage, lookup("wrong-cursor", null)),
        )
        disconnected.orphanContinuations shouldBe 1
        disconnected.abandonedContinuations shouldBe 1

        // A terminal empty response only certifies *this* source query;
        // without independent catalog identity it is not canonical NO_CHAPTERS.
        MangaAllRealSourceReplay.cursorCoverage(listOf(lookup(null, null)))
            .pendingLookupCursors shouldBe 0
    }

    @Test
    fun `signed code9 V1 canonical tape only accepts exact English Host lookup DTO`() {
        // CONTRACT-ONLY empty pages; not a live source or editorial success.
        // The 3-work live #44763491195 exposed a gap because the capture
        // included unsupported editorialEvidenceVersion and extra pt-br facets.
        val original = Json.parseToJsonElement(
            """{"operation":"lookup","outcome":"RESULT",
              "request":{"titles":["CONTRACT_ONLY_LOOKUP"],"cursor":null,
                         "requestedLanguage":"en","coverageEvidenceVersion":1},
              "response":{"items":[],"nextCursor":null}}""",
        ).jsonObject
        val hostV1 = original.getValue("request").jsonObject
        val exact = MangaAllCapturedRequestTape(listOf(original))
        exact.replay("lookup", hostV1) shouldBe original.getValue("response")
        exact.consumedCount shouldBe 1
        exact.replayGapCount shouldBe 0

        val capturedWithUnsupportedField = Json.parseToJsonElement(
            """{"operation":"lookup","outcome":"RESULT",
              "request":{"titles":["CONTRACT_ONLY_LOOKUP"],"cursor":null,
                         "requestedLanguage":"en","coverageEvidenceVersion":1,
                         "editorialEvidenceVersion":1},
              "response":{"items":[],"nextCursor":null}}""",
        ).jsonObject
        val extra = MangaAllCapturedRequestTape(listOf(capturedWithUnsupportedField))
        assertThrows<MangaAllReplayGapException> { extra.replay("lookup", hostV1) }
        extra.replayGapCount shouldBe 1

        val uncapturedLanguage = Json.parseToJsonElement(
            """{"titles":["CONTRACT_ONLY_LOOKUP"],"cursor":null,
              "requestedLanguage":"pt-br","coverageEvidenceVersion":1}""",
        ).jsonObject
        val portuguese = MangaAllCapturedRequestTape(listOf(original))
        assertThrows<MangaAllReplayGapException> {
            portuguese.replay("lookup", uncapturedLanguage)
        }
        portuguese.replayGapCount shouldBe 1

        val wrongCursor = Json.parseToJsonElement(
            """{"titles":["CONTRACT_ONLY_LOOKUP"],"cursor":"uncaptured-cursor",
              "requestedLanguage":"en","coverageEvidenceVersion":1}""",
        ).jsonObject
        assertThrows<MangaAllReplayGapException> {
            MangaAllCapturedRequestTape(listOf(original)).replay("lookup", wrongCursor)
        }
    }

    @Test
    fun `recorded source calls traverse actual Host ScriptProviderReadingGateway decoding`() = runBlocking {
        val capture = System.getenv("MANGAALL_REAL_ENVELOPES_FILE")
        val packagePath = System.getenv("MANGAALL_SIGNED_PACKAGE_FILE")
        val required = System.getenv("MANGAALL_REAL_REPLAY_REQUIRED") == "1"
        if (required) {
            require(!capture.isNullOrBlank() && !packagePath.isNullOrBlank()) {
                "Explicit real Host gate requires both genuine signed package and live source capture"
            }
        }
        assumeTrue(
            !capture.isNullOrBlank() && !packagePath.isNullOrBlank(),
            "Only the opt-in REAL source-capture workflow supplies external data",
        )

        val reports = MangaAllRealSourceReplay.decode(
            captureText = File(requireNotNull(capture)).readText(),
            signedPackageBytes = File(requireNotNull(packagePath)).readBytes(),
        )
        for (report in reports) {
            // Each result is the production Host V1 decoder result, with
            // explicit source replay gaps. No work-specific expectations.
            println("MANGAALL_REAL_HOST_DECODER|" + report.toClosedLine())
            report.catalogStage shouldBe "NOT_EXECUTED_HOST"
            report.canonicalStage shouldBe "NOT_EXECUTED_HOST"
            report.readerStage shouldBe "NOT_EXECUTED_HOST"
        }
    }
}
