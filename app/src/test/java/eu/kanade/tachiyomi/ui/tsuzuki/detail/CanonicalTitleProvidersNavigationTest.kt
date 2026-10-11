package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiIntegrationsScreen
import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiProvidersDefaultSkinScreen
import eu.kanade.tachiyomi.ui.tsuzuki.search.searchIntegrationsDestination
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class CanonicalTitleProvidersNavigationTest {

    @Test
    fun `Details public extensibility remains Providers while Search configuration is Integrations`() {
        canonicalDetailsProvidersDestination()::class shouldBe SettingsTsuzukiProvidersDefaultSkinScreen::class
        searchIntegrationsDestination()::class shouldBe SettingsTsuzukiIntegrationsScreen::class
        canonicalDetailsProvidersDestination()::class shouldNotBe searchIntegrationsDestination()::class
    }
}
