package eu.kanade.tachiyomi.ui.main

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinLegacyRouteContractTest {

    @Test
    fun `legacy source and extension shortcuts resolve to Providers`() {
        val source = readAppSource("eu/kanade/tachiyomi/ui/main/MainActivity.kt")

        source shouldContain "Constants.SHORTCUT_SOURCES"
        source shouldContain "Constants.SHORTCUT_EXTENSIONS"
        source shouldContain "SettingsScreen(SettingsScreen.Destination.Providers)"
        source shouldNotContain "SettingsScreen(SettingsScreen.Destination.TsuzukiAddons)"
    }

    @Test
    fun `legacy extension repository links never reopen Extension Stores UI`() {
        val source = readAppSource("eu/kanade/tachiyomi/ui/main/MainActivity.kt")

        source shouldContain "scheme == \"tachiyomi\""
        source shouldContain "scheme == \"mihon\""
        source shouldContain "SettingsScreen(SettingsScreen.Destination.Providers)"
        source shouldNotContain "navigator.push(ExtensionStoresScreen"
        source shouldNotContain "import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreen"
    }

    @Test
    fun `legacy destination id three is retained as Providers compatibility route`() {
        val source = readAppSource("eu/kanade/tachiyomi/ui/setting/SettingsScreen.kt")

        source shouldContain "data object Providers : Destination(3)"
        source shouldContain "Destination.Providers.id -> SettingsTsuzukiProvidersScreen()"
        source shouldNotContain "TsuzukiAddons"
        source shouldNotContain "SettingsTsuzukiAddonsScreen"
    }

    @Test
    fun `Providers top back delegates to Settings back contract`() {
        val source = readAppSource(
            "eu/kanade/presentation/more/settings/screen/SettingsTsuzukiProvidersScreen.kt",
        )

        source shouldContain "LocalBackPress.currentOrThrow"
        source shouldContain "TextButton(onClick = navigateUp)"
        source shouldNotContain "TextButton(onClick = navigator::pop)"
    }

    private fun readAppSource(relativePath: String): String {
        val candidates = listOf(
            Path.of("src/main/java/$relativePath"),
            Path.of("app/src/main/java/$relativePath"),
        )
        val path = candidates.firstOrNull(Files::exists)
            ?: error("$relativePath was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(path)
    }
}
