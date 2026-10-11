package eu.kanade.presentation.tsuzuki.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldLayout
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldState
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TsuzukiRootNavigationScaffold(
    navigationSuiteType: NavigationSuiteType,
    state: NavigationSuiteScaffoldState,
    navigationItems: @Composable (Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    val rail = navigationSuiteType == NavigationSuiteType.NavigationRail
    val insets = if (rail) NavigationRailDefaults.windowInsets else NavigationBarDefaults.windowInsets
    var navigationBarHeight by remember { mutableIntStateOf(0) }
    val colors = defaultSkinColors()

    Surface(
        color = colors.canvas,
        contentColor = colors.foreground,
    ) {
        NavigationSuiteScaffoldLayout(
            navigationSuiteType = navigationSuiteType,
            state = state,
            navigationSuite = {
                if (rail) {
                    NavigationRail(containerColor = colors.surface) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            navigationItems(Modifier)
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.onSizeChanged { navigationBarHeight = it.height },
                        color = colors.surface,
                        shadowElevation = 0.dp,
                        tonalElevation = 0.dp,
                    ) {
                        Column {
                            HorizontalDivider(color = colors.outline.copy(alpha = 0.7f))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(insets)
                                    .selectableGroup()
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                navigationItems(Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
        ) {
            val consumedInsets = if (state.currentValue == NavigationSuiteScaffoldValue.Hidden && !state.isAnimating) {
                WindowInsets(0, 0, 0, 0)
            } else if (rail) {
                insets.only(WindowInsetsSides.Start)
            } else {
                WindowInsets(bottom = navigationBarHeight)
            }
            Box(Modifier.consumeWindowInsets(consumedInsets)) { content() }
        }
    }
}

@Composable
fun TsuzukiRootNavigationItem(
    selected: Boolean,
    rail: Boolean,
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    val foreground = if (selected) colors.foreground else colors.secondary
    val iconContent: @Composable () -> Unit = {
        Icon(painter = icon, contentDescription = null, modifier = Modifier.size(21.dp), tint = foreground)
    }
    val labelContent: @Composable () -> Unit = {
        Text(
            text = label,
            color = foreground,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (rail) {
        NavigationRailItem(
            modifier = modifier,
            selected = selected,
            onClick = onClick,
            icon = iconContent,
            label = labelContent,
            colors = NavigationRailItemDefaults.colors(
                indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                selectedIconColor = colors.foreground,
                selectedTextColor = colors.foreground,
                unselectedIconColor = colors.secondary,
                unselectedTextColor = colors.secondary,
            ),
        )
    } else {
        Column(
            modifier = modifier
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = 2.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            iconContent()
            labelContent()
        }
    }
}
