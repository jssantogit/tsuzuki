package eu.kanade.tachiyomi.ui.home

import eu.kanade.presentation.more.settings.screen.SettingsTsuzukiProvidersScreen
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.tsuzuki.home.TsuzukiHomeTab
import eu.kanade.tachiyomi.ui.tsuzuki.profile.TsuzukiProfileTab
import eu.kanade.tachiyomi.ui.tsuzuki.search.TsuzukiSearchTab
import eu.kanade.tachiyomi.ui.tsuzuki.search.searchProvidersDestination
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TsuzukiNavigationContractTest {

    @Test
    fun `primary shell contains exactly Home Search Library Profile`() {
        HomeScreen.tabs shouldContainExactly listOf(
            TsuzukiHomeTab,
            TsuzukiSearchTab,
            LibraryTab,
            TsuzukiProfileTab,
        )
    }

    @Test
    fun `settings is no longer a root destination`() {
        HomeScreen.tabs.any { it::class.simpleName == "TsuzukiSettingsTab" } shouldBe false
    }

    @Test
    fun `legacy Mihon tabs are absent from primary shell`() {
        val legacyTabNames = setOf("UpdatesTab", "HistoryTab", "BrowseTab", "MoreTab")
        HomeScreen.tabs.any { it::class.simpleName in legacyTabNames } shouldBe false
    }

    @Test
    fun `Search Providers action opens the public Providers settings destination`() {
        searchProvidersDestination()::class shouldBe SettingsTsuzukiProvidersScreen::class
    }
}
