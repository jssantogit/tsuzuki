package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

    ScrollbarLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        if (!title.isNullOrBlank()) {
            item(key = "default_skin_settings_title") {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                )
            }
        }

        items.forEachIndexed { index, preference ->
            when (preference) {
                is Preference.PreferenceGroup -> {
                    if (!preference.enabled) return@forEachIndexed
                    item(key = "group_${index}_${preference.title}") {
                        DefaultSkinPreferenceGroup(
                            group = preference,
                            highlightKey = highlightKey,
                        )
                    }
                }

                is Preference.PreferenceItem<*, *> -> {
                    item(key = "preference_${index}_${preference.title}") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                        ) {
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

@Composable
private fun DefaultSkinPreferenceGroup(
    group: Preference.PreferenceGroup,
    highlightKey: String?,
) {
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        if (group.title.isNotBlank()) {
            Text(
                text = group.title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column {
                group.preferenceItems.forEachIndexed { index, item ->
                    PreferenceItem(
                        item = item,
                        highlightKey = highlightKey,
                    )
                    if (index < group.preferenceItems.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        )
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
