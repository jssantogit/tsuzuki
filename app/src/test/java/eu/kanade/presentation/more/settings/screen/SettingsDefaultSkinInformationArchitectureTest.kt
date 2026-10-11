package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsDefaultSkinInformationArchitectureTest {

    @Test
    fun `settings expose approved public families in canonical route order`() {
        val source = readSource("SettingsMainScreen.kt")
        val sections = source.substringAfter("private val sections = listOf(")
        val familyAnchors = listOf(
            "SettingsTsuzukiAccountScreen",
            "SettingsTsuzukiPersonalizationsScreen",
            "SettingsTsuzukiProvidersScreen",
            "SettingsDownloadScreen",
            "SettingsDataScreen",
            "SettingsSecurityScreen",
            "SettingsTsuzukiDiagnosticsScreen",
            "SettingsAdvancedScreen",
            "AboutScreen",
        )

        familyAnchors.forEach(sections::shouldContain)
        familyAnchors.zipWithNext().forEach { (first, second) ->
            (sections.indexOf(first) < sections.indexOf(second)).shouldBeTrue()
        }
        (sections.indexOf("SettingsTsuzukiPersonalizationsScreen") <
            sections.indexOf("SettingsTsuzukiReadingHubScreen")).shouldBeTrue()
        (sections.indexOf("SettingsTsuzukiProvidersScreen") <
            sections.indexOf("SettingsTrackingScreen")).shouldBeTrue()
    }

    @Test
    fun `settings route canonical families to existing runtime owners`() {
        val source = readSource("SettingsMainScreen.kt")

        source shouldContain "SettingsDownloadScreen"
        source shouldContain "SettingsDataScreen"
        source shouldContain "SettingsSecurityScreen"
        source shouldContain "SettingsTsuzukiDiagnosticsScreen"
        source shouldContain "SettingsAdvancedScreen"
        source shouldContain "AboutScreen"
    }

    @Test
    fun `addons are not a public Settings family`() {
        val source = readSource("SettingsMainScreen.kt")
        source shouldNotContain "SettingsTsuzukiAddonsScreen"
    }

    @Test
    fun `reading hub reuses localized reader and library contracts`() {
        val source = readSource("SettingsTsuzukiReadingHubScreen.kt")

        source shouldContain "MR.strings.tsuzuki_reading_settings_title"
        source shouldContain "MR.strings.pref_category_reader"
        source shouldContain "MR.strings.pref_reader_summary"
        source shouldContain "MR.strings.pref_category_library"
        source shouldContain "MR.strings.pref_library_summary"
        source shouldNotContain "title = \"Leitura\""
        source shouldNotContain "title = \"Leitor\""
        source shouldNotContain "title = \"Comportamento da biblioteca\""
    }

    @Test
    fun `diagnostics hub reuses localized log and debug contracts`() {
        val source = readSource("SettingsTsuzukiDiagnosticsScreen.kt")

        source shouldContain "MR.strings.tsuzuki_logs_title"
        source shouldContain "MR.strings.tsuzuki_logs_summary"
        source shouldContain "MR.strings.pref_debug_info"
        source shouldNotContain "title = \"Logs\""
        source shouldNotContain "title = \"Informações técnicas\""
    }

    @Test
    fun `personalization hub reuses localized appearance contracts`() {
        val source = readSource("SettingsTsuzukiPersonalizationsScreen.kt")

        source shouldContain "MR.strings.tsuzuki_personalizations_title"
        source shouldContain "MR.strings.pref_category_appearance"
        source shouldContain "MR.strings.pref_appearance_summary"
        source shouldNotContain "title = \"Personalização\""
        source shouldNotContain "title = \"Aparência\""
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
