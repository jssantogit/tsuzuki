package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ChromeReaderMode
import mihon.icons.materialsymbols.rounded.Code
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.Info
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Security
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen

object SettingsMainScreen : Screen() {

    @Composable
    override fun Content() {
        val backPress = LocalBackPress.currentOrThrow
        Content(
            twoPane = false,
            navigateUp = backPress,
        )
    }

    @Composable
    private fun getPalerSurface(): Color {
        val surface = MaterialTheme.colorScheme.surface
        val dark = isSystemInDarkTheme()
        return remember(surface, dark) {
            val arr = FloatArray(3)
            ColorUtils.colorToHSL(surface.toArgb(), arr)
            arr[2] = if (dark) {
                arr[2] - 0.05f
            } else {
                arr[2] + 0.02f
            }.coerceIn(0f, 1f)
            Color.hsl(arr[0], arr[1], arr[2])
        }
    }

    @Composable
    fun Content(twoPane: Boolean) {
        val backPress = LocalBackPress.currentOrThrow
        Content(
            twoPane = twoPane,
            navigateUp = backPress,
        )
    }

    @Composable
    fun Content(
        twoPane: Boolean,
        navigateUp: (() -> Unit)?,
    ) {
        val navigator = LocalNavigator.currentOrThrow
        val containerColor = if (twoPane) getPalerSurface() else MaterialTheme.colorScheme.surfaceContainerLowest
        val topBarState = rememberTopAppBarState()
        val selectedClass = if (twoPane) navigator.items.firstOrNull()?.let { it::class } else null
        val state = rememberLazyListState()

        LaunchedEffect(selectedClass) {
            if (selectedClass == null) return@LaunchedEffect
            val selectedSection = sections.indexOfFirst { section ->
                section.items.any { it.screen::class == selectedClass }
            }
            if (selectedSection >= 0) {
                state.animateScrollToItem(1 + selectedSection * 2)
            }
        }

        Scaffold(
            topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(topBarState),
            topBar = { scrollBehavior ->
                AppBar(
                    title = "",
                    navigateUp = navigateUp,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_search),
                                    icon = MaterialSymbols.Rounded.Search,
                                    onClick = { navigator.navigate(SettingsSearchScreen(), twoPane) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = containerColor,
            content = { contentPadding ->
                LazyColumn(
                    state = state,
                    modifier = Modifier.padding(contentPadding),
                ) {
                    item(key = "settings_title") {
                        Text(
                            text = stringResource(MR.strings.label_settings),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                        )
                    }

                    sections.forEach { section ->
                        item(key = "section_label_${section.title}") {
                            Text(
                                text = section.title.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                            )
                        }
                        item(key = "section_card_${section.title}") {
                            SettingsSectionCard(
                                section = section,
                                selectedClass = selectedClass,
                                onClick = { navigator.navigate(it.screen, twoPane) },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            )
                        }
                    }
                }
            },
        )
    }

    @Composable
    private fun SettingsSectionCard(
        section: Section,
        selectedClass: kotlin.reflect.KClass<out VoyagerScreen>?,
        onClick: (Item) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            Column {
                section.items.forEachIndexed { index, item ->
                    val selected = selectedClass != null && item.screen::class == selectedClass
                    SettingsNavigationRow(
                        item = item,
                        selected = selected,
                        onClick = { onClick(item) },
                    )
                    if (index < section.items.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsNavigationRow(
        item: Item,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val rowColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
        } else {
            Color.Transparent
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowColor)
                .clickable(onClick = onClick)
                .sizeIn(minHeight = 64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.formatSubtitle()?.takeIf(String::isNotBlank)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }

    private fun Navigator.navigate(screen: VoyagerScreen, twoPane: Boolean) {
        if (twoPane) replaceAll(screen) else push(screen)
    }

    private data class Item(
        val titleRes: StringResource,
        val subtitleRes: StringResource? = null,
        val formatSubtitle: @Composable () -> String? = { subtitleRes?.let { stringResource(it) } },
        val icon: ImageVector,
        val screen: VoyagerScreen,
    )

    private data class Section(
        val title: String,
        val items: List<Item>,
    )

    private val sections = listOf(
        Section(
            title = "Conta",
            items = listOf(
                Item(
                    titleRes = MR.strings.tsuzuki_account_title,
                    icon = MaterialSymbols.Rounded.Security,
                    screen = SettingsTsuzukiAccountScreen,
                ),
            ),
        ),
        Section(
            title = "Geral",
            items = listOf(
                Item(
                    titleRes = MR.strings.tsuzuki_personalizations_title,
                    icon = MaterialSymbols.Rounded.Palette,
                    screen = SettingsTsuzukiPersonalizationsScreen,
                ),
                Item(
                    titleRes = MR.strings.tsuzuki_reading_settings_title,
                    icon = MaterialSymbols.AutoMirroredRounded.ChromeReaderMode,
                    screen = SettingsTsuzukiReadingHubScreen,
                ),
            ),
        ),
        Section(
            title = "Conteúdo",
            items = listOf(
                Item(
                    titleRes = MR.strings.tsuzuki_providers_title,
                    icon = MaterialSymbols.Rounded.Explore,
                    screen = SettingsTsuzukiProvidersScreen(),
                ),
                Item(
                    titleRes = MR.strings.pref_category_tracking,
                    icon = MaterialSymbols.Rounded.Explore,
                    screen = SettingsTrackingScreen,
                ),
            ),
        ),
        Section(
            title = "Downloads",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_category_downloads,
                    subtitleRes = MR.strings.pref_downloads_summary,
                    icon = MaterialSymbols.Rounded.Download,
                    screen = SettingsDownloadScreen,
                ),
            ),
        ),
        Section(
            title = "Dados e armazenamento",
            items = listOf(
                Item(
                    titleRes = MR.strings.label_data_storage,
                    icon = MaterialSymbols.Rounded.Info,
                    screen = SettingsDataScreen,
                ),
            ),
        ),
        Section(
            title = "Privacidade e segurança",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_category_security,
                    icon = MaterialSymbols.Rounded.Security,
                    screen = SettingsSecurityScreen,
                ),
            ),
        ),
        Section(
            title = "Diagnóstico",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_debug_info,
                    icon = MaterialSymbols.Rounded.Code,
                    screen = SettingsTsuzukiDiagnosticsScreen,
                ),
            ),
        ),
        Section(
            title = "Avançado",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_category_advanced,
                    subtitleRes = MR.strings.pref_advanced_summary,
                    icon = MaterialSymbols.Rounded.Code,
                    screen = SettingsAdvancedScreen,
                ),
            ),
        ),
        Section(
            title = "Sobre",
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_category_about,
                    formatSubtitle = {
                        "${stringResource(MR.strings.app_name)} ${AboutScreen.getVersionName(withBuildDate = false)}"
                    },
                    icon = MaterialSymbols.Rounded.Info,
                    screen = AboutScreen,
                ),
            ),
        ),
    )
}
