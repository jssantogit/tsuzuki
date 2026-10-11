package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsGroupDivider
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import kotlin.time.Duration.Companion.seconds

/**
 * Default Skin preference surface.
 *
 * Groups remain data-driven by the existing Settings runtime. This layer only owns hierarchy,
 * spacing and grouping so preference persistence and side effects stay with their current owners.
 */
@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    navigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    if (highlightKey != null) {
        LaunchedEffect(Unit) {
            val i = items.findHighlightedGroupIndex(highlightKey)
            if (i >= 0) {
                delay(0.5.seconds)
                state.animateScrollToItem(i + if (title.isNullOrBlank()) 0 else 1)
            }
            SearchableSettings.highlightKey = null
        }
    }

    SettingsCanvas(modifier = modifier) {
        ScrollbarLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
        ) {
            if (!title.isNullOrBlank()) {
                item(key = "default_skin_settings_title") {
                    SettingsPageHeader(
                        title = title,
                        navigateUp = navigateUp,
                        actions = actions,
                    )
                }
            }

            items.forEachIndexed { index, preference ->
                when (preference) {
                    is Preference.PreferenceGroup -> {
                        if (!preference.enabled) return@forEachIndexed
                        item(key = "group_${index}_${preference.title}") {
                            SettingsGroup(title = preference.title) {
                                preference.preferenceItems.forEachIndexed { itemIndex, item ->
                                    PreferenceItem(
                                        item = item,
                                        highlightKey = highlightKey,
                                    )
                                    if (itemIndex < preference.preferenceItems.lastIndex) {
                                        SettingsGroupDivider()
                                    }
                                }
                            }
                        }
                    }

                    is Preference.PreferenceItem<*, *> -> {
                        item(key = "preference_${index}_${preference.title}") {
                            SettingsGroup {
                                PreferenceItem(
                                    item = preference,
                                    highlightKey = highlightKey,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun List<Preference>.findHighlightedGroupIndex(highlightKey: String): Int {
    return indexOfFirst { preference ->
        when (preference) {
            is Preference.PreferenceGroup -> preference.preferenceItems.any { it.title == highlightKey }
            is Preference.PreferenceItem<*, *> -> preference.title == highlightKey
        }
    }
}
