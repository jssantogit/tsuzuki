package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SettingsTsuzukiProvidersRootContractTest {

    @Test
    fun `providers root uses default skin hierarchy without legacy separators`() {
        val source = readSource(
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/TsuzukiProvidersRootContent.kt",
        )

        source shouldContain "AppBar("
        source shouldContain "MaterialTheme.typography.displaySmall"
        source shouldContain "RoundedCornerShape(16.dp)"
        source shouldContain "heightIn(min = 48.dp)"
        source shouldNotContain "TopAppBar("
        source shouldNotContain "HorizontalDivider("
    }

    @Test
    fun `providers root keeps existing runtime owners`() {
        val source = readSource(
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiProvidersScreen.kt",
        )

        source shouldContain "manager.refresh("
        source shouldContain "manager.install("
        source shouldContain "manager.rollback("
        source shouldContain "builtinRegistry.setEnabled("
        source shouldContain "scriptRegistry.setEnabled("
    }

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            Path.of(relativePath.removePrefix("app/")),
            Path.of(relativePath),
        )
        val source = candidates.firstOrNull(Files::exists)
            ?: error("$relativePath was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
