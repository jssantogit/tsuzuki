package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsDefaultSkinInformationArchitectureTest {

    @Test
    fun `settings expose approved public families in canonical order`() {
        val source = readSettingsMainSource()
        val families = listOf(
            "Conta",
            "Geral",
            "Conteúdo",
            "Downloads",
            "Dados e armazenamento",
            "Privacidade e segurança",
            "Diagnóstico",
            "Avançado",
            "Sobre",
        )

        families.forEach(source::shouldContain)
        families.zipWithNext().forEach { (first, second) ->
            (source.indexOf(first) < source.indexOf(second)).shouldBeTrue()
        }
    }

    @Test
    fun `settings route canonical families to existing runtime owners`() {
        val source = readSettingsMainSource()

        source shouldContain "SettingsDownloadScreen"
        source shouldContain "SettingsDataScreen"
        source shouldContain "SettingsSecurityScreen"
        source shouldContain "SettingsTsuzukiDiagnosticsScreen"
        source shouldContain "SettingsAdvancedScreen"
        source shouldContain "AboutScreen"
    }

    @Test
    fun `addons are not a public Settings family`() {
        val source = readSettingsMainSource()
        source shouldNotContain "SettingsTsuzukiAddonsScreen"
    }

    private fun readSettingsMainSource(): String {
        val candidates = listOf(
            Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt"),
            Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt"),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("SettingsMainScreen.kt was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
