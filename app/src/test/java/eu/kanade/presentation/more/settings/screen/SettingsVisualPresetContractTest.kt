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
    fun `visual preset represents only the current Default Skin without inventing a catalog`() {
        val source = readSource("SettingsTsuzukiVisualPresetScreen.kt")

        source shouldContain "MR.strings.tsuzuki_visual_preset_name"
        source shouldContain "MR.strings.selected"
        source shouldContain "MR.strings.tsuzuki_visual_preset_summary"
        source shouldNotContain "Download"
        source shouldNotContain "Install"
        source shouldNotContain "Marketplace"
        source shouldNotContain "PreferenceStore"
    }

    @Test
    fun `palettes are selected inside Visual Preset while appearance retains theme mode`() {
        val appearance = readSource("SettingsAppearanceScreen.kt")
        val preset = readSource("SettingsTsuzukiVisualPresetScreen.kt")
        appearance shouldNotContain "AppThemePreferenceWidget("
        preset shouldContain "AppThemePreferenceWidget("
        preset shouldContain "appThemePref.set(it)"
        preset shouldContain "compactPreview = true"
    }

    @Test
    fun `visual preset uses the shared Settings shell and semantic presentation`() {
        val source = readSource("SettingsTsuzukiVisualPresetScreen.kt")

        source shouldContain "SettingsCanvas {"
        source shouldContain "SettingsPageHeader("
        source shouldContain "SettingsGroup(title = stringResource(MR.strings.tsuzuki_visual_preset_current))"
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
