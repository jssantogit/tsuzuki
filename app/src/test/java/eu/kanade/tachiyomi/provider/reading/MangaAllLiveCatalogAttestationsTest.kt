package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest

/** CONTRACT-ONLY metadata transport: names below are not a source replay. */
class MangaAllLiveCatalogAttestationsTest {
    @Test
    fun `verified public creator details are carried without creating reading HIDs`() {
        val item = validCase()
        val cases = MangaAllLiveCatalogAttestations.parse(batch(item))
        cases.size shouldBe 1
        val identity = cases.single().identity
        identity?.provider shouldBe "mangaupdates"
        identity?.providerId shouldBe "123"
        identity?.authors shouldBe listOf("Actual Catalog Creator")
        cases.single().canonicalMetadata?.authors?.externalId shouldBe "123"
    }

    @Test
    fun `unavailable catalog has no author and cannot become a canonical seed`() {
        val unresolved = JsonObject(
            validCase() + mapOf(
                "status" to JsonPrimitive("CATALOG_UNAVAILABLE"),
                "provider" to JsonPrimitive(""),
                "providerId" to JsonPrimitive(""),
                "title" to JsonPrimitive(""),
                "authors" to JsonArray(emptyList()),
                "artists" to JsonArray(emptyList()),
                "creatorsAttested" to JsonPrimitive(false),
            ),
        )
        val parsed = MangaAllLiveCatalogAttestations.parse(batch(unresolved))
        parsed.single().canonicalMetadata shouldBe null
    }

    @Test
    fun `tampered case IDs and creator claims are rejected before Host collection`() {
        val mismatch = JsonObject(validCase() + ("caseId" to JsonPrimitive("user-incorrect")))
        assertThrows<IllegalArgumentException> {
            MangaAllLiveCatalogAttestations.parse(batch(mismatch))
        }
        val forged = JsonObject(validCase() + ("provider" to JsonPrimitive("app.tsuzuki.mangaall")))
        assertThrows<IllegalArgumentException> {
            MangaAllLiveCatalogAttestations.parse(batch(forged))
        }
    }

    @Test
    fun `batch and case hashes must remain intact`() {
        val original = batch(validCase())
        val altered = original.replace("Actual Catalog Creator", "Substituted Creator")
        assertThrows<IllegalArgumentException> {
            MangaAllLiveCatalogAttestations.parse(altered)
        }
        assertThrows<IllegalArgumentException> {
            MangaAllLiveCatalogAttestations.parse(original.replace("DIRECT_LIVE_ANDROID_CATALOG_ONLY", "CANONICAL_DONE"))
        }
    }

    private fun validCase(): JsonObject = Json.parseToJsonElement(
        """{"schemaVersion":1,"mode":"DIRECT_LIVE_ANDROID_CATALOG_ONLY","caseId":"user-""" +
            caseHash("Work") +
            """","query":"Work","status":"RESOLVED","catalogSource":"mangaupdates","provider":""" +
            """"mangaupdates","providerId":"123","title":"Work","authors":["Actual Catalog Creator"],""" +
            """"artists":[],"externalIds":{},"creatorsAttested":true,"catalogsQueried":1,""" +
            """"catalogsFailed":0,"canonical":"NOT_EXECUTED_HOST"}""",
    ).let { it as JsonObject }

    private fun batch(record: JsonObject): String {
        val inner = buildJsonObject {
            put("schemaVersion", 1)
            put("origin", "DIRECT_LIVE_ANDROID_CATALOG_ONLY")
            put("canonicalStage", "NOT_EXECUTED_HOST")
            put("cases", JsonArray(listOf(record)))
        }
        val sha = MessageDigest.getInstance("SHA-256")
            .digest(inner.toString().toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JsonObject(inner + ("sha256" to JsonPrimitive(sha))).toString()
    }

    private fun caseHash(title: String): String = MessageDigest.getInstance("SHA-256")
        .digest(title.lowercase().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        .take(12)
}
