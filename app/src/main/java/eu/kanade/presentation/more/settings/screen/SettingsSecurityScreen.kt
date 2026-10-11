package eu.kanade.presentation.more.settings.screen

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.core.security.PrivacyPreferences
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.provider.torrent.ProviderTorrentPreferences
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.authenticate
import eu.kanade.tachiyomi.util.system.AuthenticatorUtil.isAuthenticationSupported
import eu.kanade.tachiyomi.util.system.telemetryIncluded
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsSecurityScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_security

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val graph = remember { context.appGraph }
        val securityPreferences = remember { graph.securityPreferences }
        val privacyPreferences = remember { graph.privacyPreferences }
        val providerTorrentPreferences = remember { graph.providerTorrentPreferences }
        val directP2pPreference = providerTorrentPreferences.directP2pAllowed
        val scope = rememberCoroutineScope()
        var showDirectP2pConfirmation by rememberSaveable { mutableStateOf(false) }

        if (showDirectP2pConfirmation) {
            AlertDialog(
                onDismissRequest = { showDirectP2pConfirmation = false },
                title = { Text("Permitir P2P direto?") },
                text = {
                    Text(
                        "Conexões P2P diretas podem expor seu endereço IP para outros participantes da rede. " +
                            "Permita apenas se você entende e aceita essa exposição.",
                    )
                },
                dismissButton = {
                    TextButton(onClick = { showDirectP2pConfirmation = false }) {
                        Text("Cancelar")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDirectP2pConfirmation = false
                            scope.launch { directP2pPreference.set(true) }
                        },
                    ) {
                        Text("Permitir")
                    }
                },
            )
        }

        return buildList(3) {
            add(getSecurityGroup(securityPreferences))
            add(
                getNetworkPrivacyGroup(
                    providerTorrentPreferences = providerTorrentPreferences,
                    onDirectP2pEnableRequested = { showDirectP2pConfirmation = true },
                ),
            )
            if (telemetryIncluded) {
                add(getTelemetryGroup(privacyPreferences))
            }
        }
    }

    @Composable
    private fun getSecurityGroup(
        securityPreferences: SecurityPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        val authSupported = remember { context.isAuthenticationSupported() }
        val useAuthPref = securityPreferences.useAuthenticator
        val useAuth by useAuthPref.collectAsState()

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_security),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = useAuthPref,
                    title = stringResource(MR.strings.lock_with_biometrics),
                    enabled = authSupported,
                    onValueChanged = {
                        (context as FragmentActivity).authenticate(
                            title = context.stringResource(MR.strings.lock_with_biometrics),
                        )
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = securityPreferences.lockAppAfter,
                    entries = LockAfterValues
                        .associateWith {
                            when (it) {
                                -1 -> stringResource(MR.strings.lock_never)
                                0 -> stringResource(MR.strings.lock_always)
                                else -> pluralStringResource(MR.plurals.lock_after_mins, count = it, it)
                            }
                        },
                    title = stringResource(MR.strings.lock_when_idle),
                    enabled = authSupported && useAuth,
                    onValueChanged = {
                        (context as FragmentActivity).authenticate(
                            title = context.stringResource(MR.strings.lock_when_idle),
                        )
                    },
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = securityPreferences.secureScreen,
                    entries = SecurityPreferences.SecureScreenMode.entries
                        .associateWith { stringResource(it.titleRes) },
                    title = stringResource(MR.strings.secure_screen),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = securityPreferences.hideNotificationContent,
                    title = stringResource(MR.strings.hide_notification_content),
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.secure_screen_summary)),
            ),
        )
    }

    @Composable
    private fun getNetworkPrivacyGroup(
        providerTorrentPreferences: ProviderTorrentPreferences,
        onDirectP2pEnableRequested: () -> Unit,
    ): Preference.PreferenceGroup {
        val directP2pPreference = providerTorrentPreferences.directP2pAllowed

        return Preference.PreferenceGroup(
            title = "Privacidade de rede",
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = directP2pPreference,
                    title = "Permitir P2P direto",
                    subtitle = "Permite conexões P2P diretas quando um Provider precisar. " +
                        "Seu endereço IP pode ficar visível para outros participantes da rede.",
                    onValueChanged = { enabled ->
                        if (!enabled) {
                            true
                        } else {
                            onDirectP2pEnableRequested()
                            false
                        }
                    },
                ),
            ),
        )
    }

    @Composable
    private fun getTelemetryGroup(
        privacyPreferences: PrivacyPreferences,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = "Dados e telemetria",
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = privacyPreferences.crashlytics,
                    title = stringResource(MR.strings.onboarding_permission_crashlytics),
                    subtitle = stringResource(MR.strings.onboarding_permission_crashlytics_description),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = privacyPreferences.analytics,
                    title = stringResource(MR.strings.onboarding_permission_analytics),
                    subtitle = stringResource(MR.strings.onboarding_permission_analytics_description),
                ),
            ),
        )
    }
}

private val LockAfterValues = listOf(
    0, // Always
    1,
    2,
    5,
    10,
    -1, // Never
)
