package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsSearchCanonicalSurfaceTest {

    @Test
    fun `settings search does not revive superseded browse or addons surfaces`() {
        val source = readSearchSource()

        source shouldNotContain "SettingsBrowseScreen,"
        source shouldNotContain "SettingsTsuzukiAddonsScreen"
        source shouldContain "SettingsAppearanceScreen"
        source shouldContain "SettingsDownloadScreen"
        source shouldContain "SettingsDataScreen"
        source shouldContain "SettingsSecurityScreen"
        source shouldContain "SettingsAdvancedScreen"
    }

    private fun readSearchSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsSearchScreen.kt"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("SettingsSearchScreen.kt was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
