package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.presentation.util.Screen

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
                            title = "Logs",
                            subtitle = "Capturar, limpar e compartilhar diagnósticos",
                            onClick = { navigator.push(SettingsTsuzukiLogsScreen) },
                        ),
                        DefaultSkinSettingsNavItem(
                            title = "Informações técnicas",
                            subtitle = "App, dispositivo, workers e schema de backup",
                            onClick = { navigator.push(DebugInfoScreen()) },
                        ),
                    ),
                ),
            ),
        )
    }
}
