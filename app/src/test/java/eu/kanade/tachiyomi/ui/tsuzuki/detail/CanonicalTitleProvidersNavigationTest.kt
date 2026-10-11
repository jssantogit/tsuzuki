package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiProvidersScreen
import eu.kanade.tachiyomi.ui.tsuzuki.search.searchProvidersDestination
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CanonicalTitleProvidersNavigationTest {

    @Test
    fun `Details public extensibility opens the existing Providers destination`() {
        canonicalDetailsProvidersDestination()::class shouldBe SettingsTsuzukiProvidersScreen::class
        canonicalDetailsProvidersDestination()::class shouldBe searchProvidersDestination()::class
    }
}
