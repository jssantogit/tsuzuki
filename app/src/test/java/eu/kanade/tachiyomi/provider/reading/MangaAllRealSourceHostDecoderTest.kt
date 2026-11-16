package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
        val signed = JsonObject(outer + ("sha256" to
            kotlinx.serialization.json.JsonPrimitive(digest))).toString()
        MangaAllRealSourceReplay.validate(signed).size shouldBe 0
        assertThrows<IllegalArgumentException> {
            MangaAllRealSourceReplay.validate(signed.replace(
                "\"NOT_EXECUTED_HOST\"", "\"CANONICAL_COMPLETE\"",
            ))
        }
        assertThrows<IllegalArgumentException> {
            MangaAllRealSourceReplay.validate(signed.replace("NO_WORK_ASSIGNED", "SOURCE_ATTEMPTS_RECORDED"))
        }
        assertThrows<IllegalArgumentException> {
            MangaAllRealSourceReplay.validate(signed.replace(digest, "0".repeat(64)))
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
        assumeTrue(!capture.isNullOrBlank() && !packagePath.isNullOrBlank(),
            "Only the opt-in REAL source-capture workflow supplies external data")

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
