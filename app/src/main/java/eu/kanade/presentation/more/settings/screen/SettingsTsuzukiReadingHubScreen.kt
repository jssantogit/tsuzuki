package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiReadingHubScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        DefaultSkinSettingsNavigationScreen(
            title = stringResource(MR.strings.tsuzuki_reading_settings_title),
            navigateUp = navigator::pop,
            groups = listOf(
                DefaultSkinSettingsNavGroup(
                    items = listOf(
                        DefaultSkinSettingsNavItem(
                            title = stringResource(MR.strings.pref_category_reader),
                            subtitle = stringResource(MR.strings.pref_reader_summary),
                            onClick = { navigator.push(SettingsReaderScreen) },
                        ),
                        DefaultSkinSettingsNavItem(
                            title = stringResource(MR.strings.pref_category_library),
                            subtitle = stringResource(MR.strings.pref_library_summary),
                            onClick = { navigator.push(SettingsLibraryScreen) },
                        ),
                    ),
                ),
            ),
        )
    }
}
