package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiDiagnosticsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        DefaultSkinSettingsNavigationScreen(
            title = "Diagnóstico",
            navigateUp = navigator::pop,
            groups = listOf(
                DefaultSkinSettingsNavGroup(
                    items = listOf(
                        DefaultSkinSettingsNavItem(
                            title = stringResource(MR.strings.tsuzuki_logs_title),
                            subtitle = stringResource(MR.strings.tsuzuki_logs_summary),
                            onClick = { navigator.push(SettingsTsuzukiLogsScreen) },
                        ),
                        DefaultSkinSettingsNavItem(
                            title = stringResource(MR.strings.pref_debug_info),
                            onClick = { navigator.push(DebugInfoScreen()) },
                        ),
                    ),
                ),
            ),
        )
    }
}
