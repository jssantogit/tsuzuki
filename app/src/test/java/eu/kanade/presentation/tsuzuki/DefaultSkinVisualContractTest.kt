package eu.kanade.presentation.tsuzuki

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinVisualContractTest {

    @Test
    fun `shared Default Skin owns the approved neutral palette`() {
        val source = readSource("components/DefaultSkinPrimitives.kt")

        source shouldContain "0xFFF5F3EC"
        source shouldContain "0xFF0B0C0D"
        source shouldContain "0xFF242628"
        source shouldContain "0xFFC9CCCE"
        source shouldNotContain "0xFF275A80"
    }

    @Test
    fun `promoted root surfaces consume shared Default Skin primitives`() {
        readSource("entry/TsuzukiEntryScreen.kt") shouldContain "defaultSkinColors()"
        readSource("search/TsuzukiSearchScreen.kt") shouldContain "defaultSkinColors()"
        readSource("profile/TsuzukiProfileScreen.kt") shouldContain "defaultSkinColors()"
        readSource("library/CanonicalLibraryScreen.kt") shouldContain "defaultSkinColors()"
        readSettingsSource("SettingsMainScreen.kt") shouldContain "defaultSkinColors()"
        readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt") shouldContain "defaultSkinColors()"
        providerLanding() shouldContain "defaultSkinColors()"
    }

    @Test
    fun `public text entry surfaces do not fall back to stock outlined Material fields`() {
        val files = listOf(
            readSource("entry/TsuzukiEntryScreen.kt"),
            readSource("search/TsuzukiSearchScreen.kt"),
            readSource("library/CanonicalLibraryScreen.kt"),
            readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt"),
        )

        files.forEach { source -> source shouldNotContain "OutlinedTextField(" }
        readSource("entry/TsuzukiEntryScreen.kt") shouldContain "BasicTextField("
        readSource("search/TsuzukiSearchScreen.kt") shouldContain "BasicTextField("
    }

    @Test
    fun `library advanced filters use Default Skin controls instead of stock filter chips`() {
        val library = readSource("library/CanonicalLibraryScreen.kt")

        library shouldContain "DefaultSkinChoiceChip("
        library shouldNotContain "FilterChip("
        library shouldNotContain "SuggestionChip("
    }

    @Test
    fun `integrations and provider landing use promoted page composition instead of stock app bars`() {
        val integrations = readSettingsSource("SettingsTsuzukiIntegrationsScreen.kt")
        val providers = providerLanding()

        integrations shouldContain "DefaultSkinPageTitle("
        providers shouldContain "DefaultSkinPageTitle("
        integrations shouldNotContain "TopAppBar("
        providers shouldNotContain "TopAppBar("
    }

    @Test
    fun `provider landing keeps approved selectors and hides rejected global acquisition`() {
        val providers = providerLanding()

        providers shouldNotContain "SettingsTsuzukiTorrentAcquisitionScreen()"
        providers shouldNotContain "tsuzuki_provider_acquisition_title"
        providers shouldContain "tsuzuki_providers_installed"
        providers shouldContain "tsuzuki_providers_discover"
        providers shouldContain "tsuzuki_providers_repositories"
    }

    @Test
    fun `search empty state stays owned by metadata integrations`() {
        val search = readSource("search/TsuzukiSearchScreen.kt")

        search shouldContain "onOpenIntegrations"
        search shouldContain "tsuzuki_search_configure_integrations"
        search shouldNotContain "tsuzuki_search_configure_providers"
    }

    private fun providerLanding(): String = readSettingsSource("SettingsTsuzukiProvidersScreen.kt")
        .substringBefore("class SettingsTsuzukiProviderDetailScreen")

    private fun readSource(relativePath: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
        Path.of("app/src/main/java/eu/kanade/presentation/tsuzuki/$relativePath"),
    )

    private fun readSettingsSource(fileName: String): String = readFirst(
        Path.of("src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
        Path.of("app/src/main/java/eu/kanade/presentation/more/settings/screen/$fileName"),
    )

    private fun readFirst(vararg candidates: Path): String {
        val source = candidates.firstOrNull(Files::exists)
            ?: error("Source file was not found from ${Path.of("").toAbsolutePath()}")
        return Files.readString(source)
    }
}
