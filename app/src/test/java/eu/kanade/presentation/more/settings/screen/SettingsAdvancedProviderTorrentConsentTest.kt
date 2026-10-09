package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsAdvancedProviderTorrentConsentTest {

    @Test
    fun `advanced settings expose explicit direct P2P consent preference`() {
        val source = readAdvancedSettingsSource()

        source shouldContain "graph.providerTorrentPreferences"
        source shouldContain "Provider torrent acquisition"
        source shouldContain "providerTorrentPreferences.directP2pAllowed"
        source shouldContain "Allow direct P2P"
    }

    private fun readAdvancedSettingsSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAdvancedScreen.kt"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsAdvancedScreen.kt"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("SettingsAdvancedScreen.kt was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
