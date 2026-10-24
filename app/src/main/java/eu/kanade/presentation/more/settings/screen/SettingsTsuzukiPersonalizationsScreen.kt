package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiPersonalizationsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        DefaultSkinSettingsNavigationScreen(
            title = stringResource(MR.strings.tsuzuki_personalizations_title),
            navigateUp = navigator::pop,
            groups = listOf(
                DefaultSkinSettingsNavGroup(
                    items = listOf(
                        DefaultSkinSettingsNavItem(
                            title = stringResource(MR.strings.tsuzuki_visual_preset_title),
                            subtitle = stringResource(MR.strings.tsuzuki_visual_preset_summary),
                            onClick = { navigator.push(SettingsTsuzukiVisualPresetScreen) },
                        ),
                        DefaultSkinSettingsNavItem(
                            title = stringResource(MR.strings.pref_category_appearance),
                            subtitle = stringResource(MR.strings.pref_appearance_summary),
                            onClick = { navigator.push(SettingsAppearanceScreen) },
                        ),
                        DefaultSkinSettingsNavItem(
                            title = stringResource(MR.strings.tsuzuki_collections_settings_title),
                            subtitle = stringResource(MR.strings.tsuzuki_collections_settings_summary),
                            onClick = { navigator.push(CollectionsScreen()) },
                        ),
                    ),
                ),
            ),
        )
    }
}
