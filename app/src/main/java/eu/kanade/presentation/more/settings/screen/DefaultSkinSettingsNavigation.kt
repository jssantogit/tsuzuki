package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsGroupDivider
import eu.kanade.presentation.more.settings.components.SettingsNavigationRow
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

internal data class DefaultSkinSettingsNavItem(
    val title: String,
    val subtitle: String? = null,
    val onClick: () -> Unit,
)

internal data class DefaultSkinSettingsNavGroup(
    val title: String? = null,
    val items: List<DefaultSkinSettingsNavItem>,
)

@Composable
internal fun DefaultSkinSettingsNavigationScreen(
    title: String,
    navigateUp: () -> Unit,
    groups: List<DefaultSkinSettingsNavGroup>,
) {
    SettingsCanvas {
        ScrollbarLazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "default_skin_navigation_title") {
                SettingsPageHeader(
                    title = title,
                    navigateUp = navigateUp,
                )
            }

            groups.forEachIndexed { groupIndex, group ->
                item(key = "group_surface_$groupIndex") {
                    SettingsGroup(title = group.title) {
                        group.items.forEachIndexed { itemIndex, item ->
                            SettingsNavigationRow(
                                title = item.title,
                                subtitle = item.subtitle,
                                onClick = item.onClick,
                            )
                            if (itemIndex < group.items.lastIndex) {
                                SettingsGroupDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}
