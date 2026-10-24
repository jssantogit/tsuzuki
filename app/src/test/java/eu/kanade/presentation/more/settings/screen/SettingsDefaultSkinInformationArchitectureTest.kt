package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsDefaultSkinInformationArchitectureTest {

    @Test
    fun `settings preserve real routes under the six approved visual macrogroups`() {
        val source = readSource("SettingsMainScreen.kt")
        val groups = source.substringAfter("private val settingsGroups = listOf(")
        val groupKeys = Regex("SettingsMacroGroup\\(\\s*key = \"([^\"]+)\"")
            .findAll(groups)
            .map { it.groupValues[1] }
            .toList()
        groupKeys shouldContainExactly listOf("account", "experience", "content", "data", "system", "about")
        val familyAnchors = listOf(
            "SettingsTsuzukiAccountScreen",
            "SettingsTrackingScreen",
            "SettingsTsuzukiPersonalizationsScreen",
            "SettingsTsuzukiReadingHubScreen",
            "SettingsLibraryScreen",
            "SettingsTsuzukiIntegrationsScreen",
            "SettingsTsuzukiProvidersDefaultSkinScreen",
            "SettingsDownloadScreen",
            "SettingsDataScreen",
            "SettingsSecurityScreen",
            "SettingsTsuzukiDiagnosticsScreen",
            "SettingsAdvancedScreen",
            "AboutScreen",
        )

        familyAnchors.forEach(groups::shouldContain)
        familyAnchors.zipWithNext().forEach { (first, second) ->
            (groups.indexOf(first) < groups.indexOf(second)).shouldBeTrue()
        }
        (
            groups.indexOf("SettingsTsuzukiAccountScreen") <
                groups.indexOf("SettingsTrackingScreen")
            ).shouldBeTrue()
        (
            groups.indexOf("SettingsTsuzukiPersonalizationsScreen") <
                groups.indexOf("SettingsTsuzukiReadingHubScreen")
            ).shouldBeTrue()
        (
            groups.indexOf("SettingsTsuzukiReadingHubScreen") <
                groups.indexOf("SettingsLibraryScreen")
            ).shouldBeTrue()
        (
            groups.indexOf("SettingsTsuzukiIntegrationsScreen") <
                groups.indexOf("SettingsTsuzukiProvidersDefaultSkinScreen")
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
    fun `settings navigation indicators use the RTL aware material icon`() {
        val root = readSource("SettingsMainScreen.kt")
        val nested = readSource("DefaultSkinSettingsNavigation.kt")
        val presentation = readSettingsComponentSource("SettingsPresentation.kt")

        presentation shouldContain "MaterialSymbols.AutoMirroredRounded.ArrowRight"
        root shouldNotContain "text = \"›\""
        nested shouldNotContain "text = \"›\""
    }

    @Test
    fun `settings root uses shared grouped rows without card per destination`() {
        val root = readSource("SettingsMainScreen.kt")

        root shouldContain "SettingsCanvas {"
        root shouldContain "SettingsPageHeader("
        root shouldContain "SettingsGroup("
        root shouldContain "SettingsNavigationRow("
        root shouldContain "settingsGroups.forEach { group ->"
        root shouldContain "title = stringResource(group.titleRes)"
        root shouldContain "surfaceTestTag = \"settings_surface_\${group.key}\""
        root shouldContain "headingTestTag = \"settings_heading_\${group.key}\""
        root shouldContain "group.items.forEachIndexed { index, item ->"
        root shouldContain "if (index < group.items.lastIndex) {"
        root shouldContain "SettingsGroupDivider("
        root shouldContain "SettingsMacroGroup("
        root shouldNotContain "private val sections = listOf("
        root shouldNotContain "DefaultSkinNavigationRow("
        root shouldNotContain "AppBar("
        root shouldNotContain "topBar ="
    }

    @Test
    fun `nested settings navigation shares the grouped page and row composition`() {
        val nested = readSource("DefaultSkinSettingsNavigation.kt")

        nested shouldContain "SettingsCanvas {"
        nested shouldContain "SettingsPageHeader("
        nested shouldContain "SettingsGroup("
        nested shouldContain "SettingsNavigationRow("
        nested shouldContain "group.items.forEachIndexed { itemIndex, item ->"
        nested shouldContain "if (itemIndex < group.items.lastIndex) {"
        nested shouldContain "SettingsGroupDivider()"
        nested shouldNotContain "DefaultSkinSettingsNavRow("
        nested shouldNotContain "MaterialTheme.colorScheme."
        nested shouldNotContain "tonalElevation ="
    }

    @Test
    fun `settings shells and groups use semantic Default Skin roles`() {
        val presentation = readSettingsComponentSource("SettingsPresentation.kt")
        val root = readSource("SettingsMainScreen.kt")
        val nested = readSource("DefaultSkinSettingsNavigation.kt")
        val preferenceScaffold = readPreferenceSource("PreferenceScaffold.kt")
        val preferenceScreen = readPreferenceSource("PreferenceScreen.kt")
        val preferenceScaffoldImplementation = preferenceScaffold.substringAfter("fun PreferenceScaffold(")
        val groupPresentation = presentation
            .substringAfter("internal fun SettingsGroup(")
            .substringBefore("internal fun SettingsGroupDivider(")
        val rowPresentation = presentation
            .substringAfter("internal fun SettingsNavigationRow(")

        listOf(root, nested, preferenceScaffold, preferenceScreen).forEach { source ->
            source shouldNotContain "MaterialTheme.colorScheme."
            source shouldNotContain "tonalElevation ="
        }
        preferenceScaffoldImplementation shouldNotContain "Scaffold("
        preferenceScaffold shouldNotContain "AppBar("
        preferenceScreen shouldContain "SettingsCanvas("
        preferenceScreen shouldContain "SettingsGroup("

        presentation shouldContain ".background(color = colors.canvas)"
        presentation shouldContain ".windowInsetsPadding(WindowInsets.systemBars)"
        presentation shouldContain "color = colors.surface"
        presentation shouldContain "colors.foreground"
        presentation shouldContain "colors.secondary"
        presentation shouldContain "colors.outline"
        presentation shouldContain "HorizontalDivider("
        presentation shouldContain "shape = DefaultSkinShape.Card"
        presentation shouldContain "shadowElevation = 0.dp"
        presentation shouldContain "tonalElevation = 0.dp"
        groupPresentation.split("Surface(").size shouldBe 2
        groupPresentation shouldContain "Column(content = content)"
        rowPresentation shouldNotContain "Surface("
        rowPresentation shouldContain ".heightIn(min = 48.dp)"
        rowPresentation shouldContain ".clickable(role = Role.Button, onClick = onClick)"
    }

    @Test
    fun `privacy keeps the approved direct P2P consent copy contract`() {
        val source = readSource("SettingsSecurityScreen.kt")

        source shouldContain "MR.strings.tsuzuki_action_allow"
        source shouldContain "MR.strings.tsuzuki_direct_p2p_summary"
        source shouldNotContain "MR.strings.tsuzuki_provider_direct_p2p_warning"
    }

    @Test
    fun `logs clear dialog keeps the approved compact action copy`() {
        val source = readSource("SettingsTsuzukiLogsScreen.kt")

        source shouldContain "Text(stringResource(MR.strings.tsuzuki_action_clear))"
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
                "title = \"CONTA\"",
                "title = \"EXPERIÊNCIA\"",
                "title = \"CONTEÚDO\"",
                "title = \"DADOS\"",
                "title = \"SISTEMA\"",
                "title = \"SOBRE\"",
                "text = \"›\"",
            ),
            "DefaultSkinSettingsNavigation.kt" to listOf(
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
                "text = \"Tsuzuki Default Skin\"",
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

    private fun readPreferenceSource(fileName: String): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/$fileName"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/$fileName"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("$fileName was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }

    private fun readSettingsComponentSource(fileName: String): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/components/$fileName"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/components/$fileName"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("$fileName was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
