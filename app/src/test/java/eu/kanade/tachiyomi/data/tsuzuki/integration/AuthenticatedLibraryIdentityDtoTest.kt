package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMUser
import eu.kanade.tachiyomi.data.track.mangaupdates.dto.MUCurrentUser
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALUser
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class AuthenticatedLibraryIdentityDtoTest {
    @Test
    fun `authenticated providers decode remote numeric user IDs independent of display names`() {
        val mal = Json.decodeFromString<MALUser>("""{"id":123,"name":"alice"}""")
        val bangumi = Json.decodeFromString<BGMUser>("""{"id":456,"username":"alice","nickname":"Alice"}""")
        val mangaUpdates = Json.decodeFromString<MUCurrentUser>(
            """{"user_id":789,"username":"alice"}""",
        )
        mal.id shouldBe 123L
        bangumi.id shouldBe 456L
        mangaUpdates.userId shouldBe 789L
    }

    @Test
    fun `old fixtures without stable ID remain parseable but cannot authenticate list ownership`() {
        Json.decodeFromString<MALUser>("""{"name":"alice"}""").id shouldBe null
        Json.decodeFromString<BGMUser>("""{"username":"alice","nickname":null}""").id shouldBe null
        Json.decodeFromString<MUCurrentUser>("""{"username":"alice"}""").userId shouldBe null
    }
}
