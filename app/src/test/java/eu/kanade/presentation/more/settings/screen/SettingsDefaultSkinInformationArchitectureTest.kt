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
        (
            sections.indexOf("SettingsTsuzukiPersonalizationsScreen") <
                sections.indexOf("SettingsTsuzukiReadingHubScreen")
            ).shouldBeTrue()
        (
            sections.indexOf("SettingsTsuzukiProvidersScreen") <
                sections.indexOf("SettingsTrackingScreen")
            ).shouldBeTrue()
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

    @Test
    fun `promoted Settings surfaces keep public copy in resources`() {
        val hardcodedCopyByFile = mapOf(
            "SettingsMainScreen.kt" to listOf(
                "title = \"Conta\"",
                "title = \"Geral\"",
                "title = \"Conteúdo\"",
                "title = \"Downloads\"",
                "title = \"Dados e armazenamento\"",
                "title = \"Privacidade e segurança\"",
                "title = \"Diagnóstico\"",
                "title = \"Avançado\"",
                "title = \"Sobre\"",
                "text = \"›\"",
            ),
            "SettingsTsuzukiPersonalizationsScreen.kt" to listOf(
                "title = \"Preset visual\"",
                "title = \"Collections\"",
                "subtitle = \"Organize coleções, pastas e listas exibidas pelo Tsuzuki\"",
            ),
            "SettingsTsuzukiDiagnosticsScreen.kt" to listOf(
                "title = \"Diagnóstico\"",
            ),
            "SettingsTsuzukiVisualPresetScreen.kt" to listOf(
                "text = \"Preset visual\"",
                "text = \"PRESET ATUAL\"",
                "text = \"A prévia acompanha o tema atual sem criar outro preset.\"",
                "text = \"Selecionado\"",
            ),
            "SettingsDownloadScreen.kt" to listOf(
                "title = \"Rede e arquivos\"",
                "title = \"Performance\"",
            ),
            "SettingsDataScreen.kt" to listOf(
                "subtitle = \"Espaço disponível, cache e arquivos temporários\"",
                "title = \"Backup e restauração\"",
                "subtitle = \"Criar, restaurar e agendar backups\"",
                "title = \"Exportar biblioteca\"",
                "subtitle = \"CSV com título, autor e artista\"",
                "subtitle = \"Escolha os componentes antes de salvar o arquivo\"",
                "subtitle = \"Valida o backup antes de escolher o que restaurar\"",
                "\"Arquivos temporários podem ser criados durante aquisições e processamento. \"",
                "title = \"Exportar CSV\"",
                "subtitle = \"Título é obrigatório para incluir autor ou artista\"",
            ),
            "SettingsSecurityScreen.kt" to listOf(
                "Text(\"Permitir P2P direto?\")",
                "Text(\"Cancelar\")",
                "Text(\"Permitir\")",
                "title = \"Privacidade de rede\"",
                "title = \"Permitir P2P direto\"",
                "subtitle = \"Permite conexões P2P diretas quando um Provider precisar.",
                "title = \"Dados e telemetria\"",
            ),
            "SettingsTsuzukiLogsScreen.kt" to listOf(
                "title = { Text(\"Limpar logs?\") }",
                "Text(\"Cancelar\")",
                "Text(\"Limpar\")",
                "\"Isso remove os diagnósticos do Tsuzuki e o último crash salvo. \"",
                "title = \"O arquivo compartilhado pode conter dados de diagnóstico do dispositivo e do app.",
            ),
        )

        hardcodedCopyByFile.forEach { (fileName, forbiddenCopy) ->
            val source = readSource(fileName)
            forbiddenCopy.forEach(source::shouldNotContain)
        }
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
