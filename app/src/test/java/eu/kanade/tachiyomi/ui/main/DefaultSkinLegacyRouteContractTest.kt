package eu.kanade.tachiyomi.ui.main

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinLegacyRouteContractTest {

    @Test
    fun `legacy source and extension shortcuts stay recognized through compatibility destination`() {
        val source = readAppSource("eu/kanade/tachiyomi/ui/main/MainActivity.kt")

        source shouldContain "Constants.SHORTCUT_SOURCES"
        source shouldContain "Constants.SHORTCUT_EXTENSIONS"
        source shouldContain "SettingsScreen(SettingsScreen.Destination.TsuzukiAddons)"
    }

    @Test
    fun `legacy destination id three canonicalizes directly to Providers`() {
        val source = readAppSource("eu/kanade/tachiyomi/ui/setting/SettingsScreen.kt")

        source shouldContain "data object Providers : Destination(3)"
        source shouldContain "val TsuzukiAddons: Destination get() = Providers"
        source shouldContain "if (destination == Destination.Providers.id)"
        source shouldContain "SettingsTsuzukiProvidersScreen().Content()"
        source shouldNotContain "SettingsTsuzukiAddonsScreen"
    }

    @Test
    fun `legacy extension repository deep links canonicalize to Providers`() {
        val mainSource = readAppSource("eu/kanade/tachiyomi/ui/main/MainActivity.kt")
        val storesSource = readAppSource(
            "eu/kanade/presentation/more/settings/screen/browse/ExtensionStoresScreen.kt",
        )

        mainSource shouldContain "scheme == \"tachiyomi\""
        mainSource shouldContain "scheme == \"mihon\""
        storesSource shouldContain "if (url != null)"
        storesSource shouldContain "navigator.replace(SettingsTsuzukiProvidersScreen())"
        storesSource shouldNotContain "viewModel.addFromDeeplink(url)"
    }

    @Test
    fun `tracking missing source recovery opens Providers instead of Add-ons`() {
        val source = readAppSource(
            "eu/kanade/presentation/more/settings/screen/SettingsTsuzukiTrackingServiceScreen.kt",
        )

        source shouldContain "navigator.push(SettingsTsuzukiProvidersScreen())"
        source shouldContain "MR.strings.tsuzuki_providers_title"
        source shouldNotContain "SettingsTsuzukiAddonsScreen"
        source shouldNotContain "MR.strings.label_extensions"
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
