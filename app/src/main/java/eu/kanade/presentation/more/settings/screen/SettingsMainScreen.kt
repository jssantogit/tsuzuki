package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.tsuzuki.components.DefaultSkinNavigationRow
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPageTitle
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowRight
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
        Content(twoPane = false, navigateUp = backPress)
    }

    @Composable
    fun Content(twoPane: Boolean) {
        val backPress = LocalBackPress.currentOrThrow
        Content(twoPane = twoPane, navigateUp = backPress)
    }

    @Composable
    fun Content(
        twoPane: Boolean,
        navigateUp: (() -> Unit)?,
    ) {
        val navigator = LocalNavigator.currentOrThrow
        val colors = defaultSkinColors()
        val topBarState = rememberTopAppBarState()
        val selectedClass = if (twoPane) navigator.items.firstOrNull()?.let { it::class } else null
        val state = rememberLazyListState()

        LaunchedEffect(selectedClass) {
            if (selectedClass == null) return@LaunchedEffect
            val selectedSection = sections.indexOfFirst { section ->
                section.items.any { it.screen::class == selectedClass }
            }
            if (selectedSection >= 0) state.animateScrollToItem(1 + selectedSection * 2)
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
            containerColor = colors.canvas,
            content = { contentPadding ->
                LazyColumn(
                    state = state,
                    modifier = Modifier.padding(contentPadding),
                ) {
                    item(key = "settings_title") {
                        DefaultSkinPageTitle(
                            text = stringResource(MR.strings.label_settings),
                            modifier = Modifier.padding(
                                start = DefaultSkinSpacing.PageHorizontal,
                                end = DefaultSkinSpacing.PageHorizontal,
                                top = 8.dp,
                                bottom = 32.dp,
                            ),
                        )
                    }

                    sections.forEach { section ->
                        item(key = "section_label_${section.key}") {
                            DefaultSkinSectionLabel(
                                text = stringResource(section.titleRes),
                                modifier = Modifier.padding(
                                    start = DefaultSkinSpacing.PageHorizontal,
                                    end = DefaultSkinSpacing.PageHorizontal,
                                    bottom = 10.dp,
                                ),
                            )
                        }
                        item(key = "section_card_${section.key}") {
                            Column(
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    bottom = DefaultSkinSpacing.Section,
                                ),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                section.items.forEach { item ->
                                    val selected = selectedClass != null && item.screen::class == selectedClass
                                    DefaultSkinNavigationRow(
                                        title = stringResource(item.titleRes),
                                        subtitle = item.formatSubtitle(),
                                        icon = item.icon,
                                        selected = selected,
                                        onClick = { navigator.navigate(item.screen, twoPane) },
                                        trailing = {
                                            Icon(
                                                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowRight,
                                                contentDescription = null,
                                                tint = colors.secondary,
                                                modifier = Modifier
                                                    .size(19.dp)
                                                    .clearAndSetSemantics { },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
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
        val key: String,
        val titleRes: StringResource,
        val items: List<Item>,
    )

    private val sections = listOf(
        Section(
            key = "account",
            titleRes = MR.strings.tsuzuki_account_title,
            items = listOf(
                Item(
                    titleRes = MR.strings.tsuzuki_account_title,
                    icon = MaterialSymbols.Rounded.Security,
                    screen = SettingsTsuzukiAccountScreen,
                ),
            ),
        ),
        Section(
            key = "general",
            titleRes = MR.strings.pref_category_general,
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
            key = "content",
            titleRes = MR.strings.tsuzuki_settings_section_content,
            items = listOf(
                Item(
                    titleRes = MR.strings.tsuzuki_integrations_title,
                    icon = MaterialSymbols.Rounded.Explore,
                    screen = SettingsTsuzukiIntegrationsScreen(),
                ),
                Item(
                    titleRes = MR.strings.tsuzuki_providers_title,
                    icon = MaterialSymbols.Rounded.Explore,
                    screen = SettingsTsuzukiProvidersDefaultSkinScreen(),
                ),
            ),
        ),
        Section(
            key = "downloads",
            titleRes = MR.strings.pref_category_downloads,
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
            key = "data_storage",
            titleRes = MR.strings.label_data_storage,
            items = listOf(
                Item(
                    titleRes = MR.strings.label_data_storage,
                    icon = MaterialSymbols.Rounded.Info,
                    screen = SettingsDataScreen,
                ),
            ),
        ),
        Section(
            key = "privacy_security",
            titleRes = MR.strings.tsuzuki_settings_section_privacy_security,
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_category_security,
                    icon = MaterialSymbols.Rounded.Security,
                    screen = SettingsSecurityScreen,
                ),
            ),
        ),
        Section(
            key = "diagnostics",
            titleRes = MR.strings.tsuzuki_settings_section_diagnostics,
            items = listOf(
                Item(
                    titleRes = MR.strings.pref_debug_info,
                    icon = MaterialSymbols.Rounded.Code,
                    screen = SettingsTsuzukiDiagnosticsScreen,
                ),
            ),
        ),
        Section(
            key = "advanced",
            titleRes = MR.strings.pref_category_advanced,
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
            key = "about",
            titleRes = MR.strings.pref_category_about,
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
