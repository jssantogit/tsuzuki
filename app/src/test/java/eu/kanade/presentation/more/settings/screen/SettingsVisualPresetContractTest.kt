package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsVisualPresetContractTest {

    @Test
    fun `personalization exposes the approved visual preset destination`() {
        val source = readSource("SettingsTsuzukiPersonalizationsScreen.kt")

        source shouldContain "MR.strings.tsuzuki_visual_preset_title"
        source shouldContain "SettingsTsuzukiVisualPresetScreen"
    }

    @Test
    fun `every supported AppTheme is exposed as a described visual preset`() {
        val source = readSource("SettingsTsuzukiVisualPresetScreen.kt")
        source shouldContain "AppTheme.entries"
        source shouldContain "presetDescription("
        source shouldContain "TachiyomiTheme("
        source shouldContain "AppTheme.DEFAULT"
        source shouldContain "MR.strings.tsuzuki_visual_preset_name"
        source shouldContain "AppThemePalettePreview("
        source shouldNotContain "AppThemePreferenceWidget("
        source shouldNotContain "Marketplace"
        source shouldNotContain "Download"
        source shouldNotContain "Install"
    }

    @Test
    fun `appearance owns light dark mode and visual presets own persisted palette`() {
        val appearance = readSource("SettingsAppearanceScreen.kt")
        val preset = readSource("SettingsTsuzukiVisualPresetScreen.kt")
        appearance shouldNotContain "AppThemePreferenceWidget("
        preset shouldContain "appThemePref.set(theme)"
        preset shouldContain "ActivityCompat.recreate"
    }

    @Test
    fun `visual preset uses the shared Settings shell and semantic presentation`() {
        val source = readSource("SettingsTsuzukiVisualPresetScreen.kt")

        source shouldContain "SettingsCanvas {"
        source shouldContain "SettingsPageHeader("
        source shouldContain "SettingsGroup("
        source shouldContain "defaultSkinColors()"
        source shouldNotContain "Scaffold("
        source shouldNotContain "AppBar("
        source shouldNotContain "tonalElevation ="
        source shouldNotContain "MaterialTheme.colorScheme.primary"
    }

    private fun readSource(fileName: String): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("$fileName was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
