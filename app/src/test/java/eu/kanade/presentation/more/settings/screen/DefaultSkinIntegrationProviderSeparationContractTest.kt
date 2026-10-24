package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultSkinIntegrationProviderSeparationContractTest {

    @Test
    fun `profile settings enters the settings navigator owner`() {
        val source = readSource(
            "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/profile/TsuzukiProfileTab.kt",
            "src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/profile/TsuzukiProfileTab.kt",
        )

        source shouldContain "navigator.push(SettingsScreen())"
        source shouldNotContain "navigator.push(SettingsMainScreen)"
    }

    @Test
    fun `profile tracking opens integrations instead of legacy tracking settings`() {
        val source = readSource(
            "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/profile/TsuzukiProfileTab.kt",
            "src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/profile/TsuzukiProfileTab.kt",
        )

        source shouldContain "navigator.push(SettingsTsuzukiIntegrationsScreen())"
        source shouldNotContain "SettingsTrackingScreen"
    }

    @Test
    fun `search configuration is owned by metadata integrations not content providers`() {
        val tabSource = readSource(
            "app/src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/search/TsuzukiSearchTab.kt",
            "src/main/java/eu/kanade/tachiyomi/ui/tsuzuki/search/TsuzukiSearchTab.kt",
        )
        val screenSource = readSource(
            "app/src/main/java/eu/kanade/presentation/tsuzuki/search/TsuzukiSearchScreen.kt",
            "src/main/java/eu/kanade/presentation/tsuzuki/search/TsuzukiSearchScreen.kt",
        )

        tabSource shouldContain "SettingsTsuzukiIntegrationsScreen"
        tabSource shouldContain "searchIntegrationsDestination"
        tabSource shouldNotContain "SettingsTsuzukiProvidersScreen"
        tabSource shouldNotContain "searchProvidersDestination"

        screenSource shouldContain "onOpenIntegrations"
        screenSource shouldContain "MR.strings.tsuzuki_search_no_integration"
        screenSource shouldContain "MR.strings.tsuzuki_search_integration_prompt"
        screenSource shouldContain "MR.strings.tsuzuki_search_configure_integrations"
        screenSource shouldNotContain "MR.strings.tsuzuki_search_no_provider"
        screenSource shouldNotContain "MR.strings.tsuzuki_search_provider_prompt"
        screenSource shouldNotContain "MR.strings.tsuzuki_search_configure_providers"
    }

    @Test
    fun `content settings expose integrations and providers as separate destinations`() {
        val mainSource = readSource(
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt",
            "src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt",
        )
        val contentSection = mainSource
            .substringAfter("key = \"content\"")
            .substringBefore("key = \"data\"")

        mainSource shouldContain "screen = SettingsTsuzukiIntegrationsScreen()"
        mainSource shouldContain "screen = SettingsTsuzukiProvidersDefaultSkinScreen()"
        contentSection shouldNotContain "screen = SettingsTrackingScreen"
    }

    @Test
    fun `integration configuration and provider platform keep distinct runtime owners`() {
        val integrationSource = readSource(
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiIntegrationsScreen.kt",
            "src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiIntegrationsScreen.kt",
        )
        val providerSource = readSource(
            "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiProvidersScreen.kt",
            "src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTsuzukiProvidersScreen.kt",
        )

        integrationSource shouldContain "TsuzukiIntegrationsSettingsScreenModel"
        integrationSource shouldNotContain "providerRepositoryManager"
        integrationSource shouldNotContain "providerRegistry"
        integrationSource shouldContain "IntegrationCategory.entries"
        providerSource shouldContain "providerRepositoryManager"
        providerSource shouldContain "providerRegistry"
    }

    private fun readSource(vararg candidates: String): String {
        val source = candidates
            .map(Path::of)
            .firstOrNull(Files::exists)
            ?: error("Source was not found from ${Path.of("").toAbsolutePath()}: ${candidates.joinToString()}")
        return Files.readString(source)
    }
}
