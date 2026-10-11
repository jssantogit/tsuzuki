package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsAdvancedProviderTorrentConsentTest {

    @Test
    fun `privacy and security settings own explicit direct P2P consent`() {
        val source = readSettingsSource("SettingsSecurityScreen.kt")

        source shouldContain "graph.providerTorrentPreferences"
        source shouldContain "providerTorrentPreferences.directP2pAllowed"
        source shouldContain "MR.strings.tsuzuki_direct_p2p_confirm_title"
        source shouldContain "MR.strings.tsuzuki_direct_p2p_confirm_message"
        source shouldContain "MR.strings.tsuzuki_action_allow"
    }

    @Test
    fun `advanced settings no longer expose direct P2P consent`() {
        val source = readSettingsSource("SettingsAdvancedScreen.kt")

        source shouldNotContain "Provider torrent acquisition"
        source shouldNotContain "providerTorrentPreferences.directP2pAllowed"
        source shouldNotContain "Allow direct P2P"
    }

    private fun readSettingsSource(fileName: String): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("$fileName was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
