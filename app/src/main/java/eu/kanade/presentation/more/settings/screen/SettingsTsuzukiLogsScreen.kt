package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiLogsScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.tsuzuki_logs_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val graph = remember { context.appGraph }
        val captureSession = remember { graph.diagnosticCaptureSession }
        val crashLogUtil = remember { graph.crashLogUtil }

        var captureActive by remember {
            mutableStateOf(captureSession.current() != null)
        }
        var showClearConfirmation by remember { mutableStateOf(false) }

        LaunchedEffect(captureActive) {
            if (captureActive) {
                while (captureSession.current() != null) {
                    delay(1_000)
                }
                captureActive = false
            }
        }

        if (showClearConfirmation) {
            AlertDialog(
                onDismissRequest = { showClearConfirmation = false },
                title = { Text(stringResource(MR.strings.tsuzuki_logs_clear_confirm_title)) },
                text = { Text(stringResource(MR.strings.tsuzuki_logs_clear_confirm_message)) },
                dismissButton = {
                    TextButton(onClick = { showClearConfirmation = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showClearConfirmation = false
                            scope.launch {
                                val cleared = crashLogUtil.clearLogs()
                                captureActive = false
                                context.toast(
                                    if (cleared) {
                                        MR.strings.tsuzuki_logs_cleared
                                    } else {
                                        MR.strings.tsuzuki_logs_clear_failed
                                    },
                                )
                            }
                        },
                    ) {
                        Text(stringResource(MR.strings.tsuzuki_action_clear))
                    }
                },
            )
        }

        return listOf(
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(
                    if (captureActive) {
                        MR.strings.tsuzuki_logs_status_recording
                    } else {
                        MR.strings.tsuzuki_logs_status_stopped
                    },
                ),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_start),
                subtitle = stringResource(MR.strings.tsuzuki_logs_start_summary),
                enabled = !captureActive,
                onClick = {
                    captureSession.start()
                    captureActive = true
                    context.toast(MR.strings.tsuzuki_logs_started)
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_stop),
                subtitle = stringResource(MR.strings.tsuzuki_logs_stop_summary),
                enabled = captureActive,
                onClick = {
                    captureSession.stop()
                    captureActive = false
                    context.toast(MR.strings.tsuzuki_logs_stopped)
                },
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_clear),
                subtitle = stringResource(MR.strings.tsuzuki_logs_clear_summary),
                onClick = { showClearConfirmation = true },
            ),
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(MR.strings.tsuzuki_logs_share_privacy),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.tsuzuki_logs_share),
                subtitle = stringResource(MR.strings.tsuzuki_logs_share_summary),
                onClick = {
                    scope.launch {
                        crashLogUtil.dumpLogs()
                    }
                },
            ),
        )
    }
}
