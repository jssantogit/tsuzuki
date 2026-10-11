package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.presentation.util.Screen

object SettingsTsuzukiDiagnosticsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Diagnóstico") },
                    navigationIcon = {
                        TextButton(onClick = navigator::pop) { Text("Voltar") }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                ListItem(
                    headlineContent = { Text("Logs") },
                    supportingContent = { Text("Capturar, limpar e compartilhar diagnósticos") },
                    modifier = Modifier.clickable { navigator.push(SettingsTsuzukiLogsScreen) },
                )
                ListItem(
                    headlineContent = { Text("Informações técnicas") },
                    supportingContent = { Text("App, dispositivo, workers e schema de backup") },
                    modifier = Modifier.clickable { navigator.push(DebugInfoScreen()) },
                )
            }
        }
    }
}
