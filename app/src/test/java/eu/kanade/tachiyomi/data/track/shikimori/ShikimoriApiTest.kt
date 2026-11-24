package eu.kanade.tachiyomi.data.track.shikimori

import eu.kanade.tachiyomi.data.track.shikimori.dto.SMCreatorResponse
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Test

class ShikimoriApiTest {

    @Test
    fun `auth url uses user owned client id and Tsuzuki callback`() {
        val url = ShikimoriApi.authUrl("user-client").toHttpUrl()

        url.queryParameter("client_id") shouldBe "user-client"
        url.queryParameter("redirect_uri") shouldBe ShikimoriApi.CALLBACK_URL
    }

    @Test
    fun `refresh request uses user owned oauth credentials`() {
        val request = ShikimoriApi.refreshTokenRequest(
            token = "refresh",
            clientId = "user-client",
            clientSecret = "user-secret",
        )
        val body = request.body as FormBody

        body.formValue("client_id") shouldBe "user-client"
        body.formValue("client_secret") shouldBe "user-secret"
    }

    @Test
    fun `declared alternate titles survive the tracker projection without replacing primary identity`() {
        val manga = Json.decodeFromString<SMManga>(
            """
            {
                "id": 42, "name": "Primary", "chapters": 0, "score": null,
                "url": "https://shikimori.io/mangas/42", "status": null, "poster": null,
                "airedOn": null, "description": null, "kind": "manhwa", "personRoles": null,
                "english": "English title", "japanese": null,
                "synonyms": ["Alternate", " Primary ", "English title", " ", "Alternate"]
            }
            """.trimIndent(),
        )
        val track = manga.toTrack(1)
        track.remote_id shouldBe 42L
        track.title shouldBe "Primary"
        track.alternate_titles shouldBe listOf("English title", "Alternate")
    }

    @Test
    fun `omitted alternate fields never fabricate aliases`() {
        val manga = SMManga(42, "Primary", 0, null, "", null, null, null, null, "manhwa", null)
        manga.toTrack(1).alternate_titles shouldBe emptyList()
    }

    @Test
    fun `aliases are tied to exact work person and role`() {
        val response = Json.decodeFromString<SMCreatorResponse>(
            """
            {"data":{"mangas":[{"id":"42","personRoles":[
                {"person":{"id":"7","name":"Mun, Baek-Gyeong","japanese":"문백경","russian":null},
                 "rolesEn":["Story"]},
                {"person":{"name":"Unverified","japanese":"Fake"},"rolesEn":["Art"]}
            ]}]}}
            """.trimIndent(),
        )
        val aliases = response.verifiedAliases(42)
        aliases.size shouldBe 1
        aliases.single().creatorExternalId shouldBe "7"
        aliases.single().aliases shouldBe listOf("문백경")
        response.verifiedAliases(43) shouldBe emptyList()
    }

    private fun FormBody.formValue(name: String): String =
        (0 until size).first { this.name(it) == name }.let { value(it) }
}
