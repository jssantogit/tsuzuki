package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen

object SettingsTsuzukiReadingHubScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        DefaultSkinSettingsNavigationScreen(
            title = "Leitura",
            navigateUp = navigator::pop,
            groups = listOf(
                DefaultSkinSettingsNavGroup(
                    items = listOf(
                        DefaultSkinSettingsNavItem(
                            title = "Leitor",
                            subtitle = "Modo de leitura, exibição e navegação",
                            onClick = { navigator.push(SettingsReaderScreen) },
                        ),
                        DefaultSkinSettingsNavItem(
                            title = "Comportamento da biblioteca",
                            subtitle = "Categorias, atualização e ações de capítulos",
                            onClick = { navigator.push(SettingsLibraryScreen) },
                        ),
                    ),
                ),
            ),
        )
    }
}
