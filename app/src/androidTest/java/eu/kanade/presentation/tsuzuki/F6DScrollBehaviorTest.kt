package eu.kanade.presentation.tsuzuki

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.collections.CollectionBrowseScreen
import eu.kanade.presentation.tsuzuki.search.TsuzukiSearchScreen
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionBrowseScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.search.SearchState
import org.junit.Rule
import org.junit.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.home.model.HomeCollectionBrowse

class F6DScrollBehaviorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun searchHeaderScrollsAwayAndReturnsWithItsQuery() {
        val items = CatalogItemFormat.entries.map { format ->
            CatalogItem(provider = "fixture", providerId = format.name, title = format.name, format = format)
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp).height(320.dp)) {
                    TsuzukiSearchScreen(
                        state = SearchState.Results("keep query", items),
                        query = "keep query",
                        onQueryChange = {}, onSearch = {}, onRecentSearch = {}, onResultClick = {},
                        onClearRecent = {}, onRetry = {}, onOpenIntegrations = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("tsuzuki_search_header").assertIsDisplayed()
        compose.onNodeWithTag("tsuzuki_search_scroll").performScrollToIndex(4)
        compose.onNodeWithTag("tsuzuki_search_header").assertIsNotDisplayed()
        compose.onNodeWithTag("tsuzuki_search_scroll").performScrollToIndex(0)
        compose.onNodeWithTag("tsuzuki_search_header").assertIsDisplayed()
        compose.onNodeWithText("keep query").assertIsDisplayed()
    }

    @Test
    fun emptyCollectionRetainsItsContextualTitle() {
        compose.setContent {
            MaterialTheme {
                CollectionBrowseScreen(
                    state = CollectionBrowseScreenState.Ready(HomeCollectionBrowse("collection", "CCVV", emptyList())),
                    navigateUp = {},
                    onFolder = {},
                )
            }
        }
        compose.onNodeWithText("CCVV").assertIsDisplayed()
    }
}
