package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import eu.kanade.presentation.more.settings.widget.PreferenceControlSheet
import eu.kanade.presentation.tsuzuki.components.DefaultSkinFormField
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenError
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenState
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

internal enum class SettingsTsuzukiAccountFormMode {
    LOGIN,
    REGISTER,
    RECOVERY,
}

internal val SettingsTsuzukiAccountFormMode.showsPassword: Boolean
    get() = this != SettingsTsuzukiAccountFormMode.RECOVERY

object SettingsTsuzukiAccountScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = metroViewModel<TsuzukiAccountScreenModel>()
        val state by screenModel.state.collectAsStateWithLifecycle()

        var formMode by rememberSaveable { mutableStateOf(SettingsTsuzukiAccountFormMode.LOGIN) }
        var email by rememberSaveable { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var passwordVisible by rememberSaveable { mutableStateOf(false) }
        var showSignOutConfirmation by rememberSaveable { mutableStateOf(false) }

        fun selectMode(mode: SettingsTsuzukiAccountFormMode) {
            formMode = mode
            password = ""
            passwordVisible = false
            screenModel.clearFeedback()
        }

        SettingsCanvas {
            LazyColumn(
                modifier = Modifier
                    .imePadding()
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                item(key = "account_header") {
                    SettingsPageHeader(
                        title = stringResource(MR.strings.tsuzuki_account_title),
                        navigateUp = navigator::pop,
                    )
                }

                when (val account = state.accountState) {
                    AccountState.LoggedOut -> {
                        item(key = "account_auth") {
                            AccountAuthGroup(
                                mode = formMode,
                                email = email,
                                password = password,
                                passwordVisible = passwordVisible,
                                state = state,
                                onEmailChange = {
                                    email = it
                                    screenModel.clearFeedback()
                                },
                                onPasswordChange = {
                                    password = it
                                    screenModel.clearFeedback()
                                },
                                onTogglePassword = { passwordVisible = !passwordVisible },
                                onPrimaryAction = {
                                    when (formMode) {
                                        SettingsTsuzukiAccountFormMode.LOGIN -> screenModel.login(email, password)
                                        SettingsTsuzukiAccountFormMode.REGISTER -> screenModel.register(email, password)
                                        SettingsTsuzukiAccountFormMode.RECOVERY -> {
                                            screenModel.sendPasswordRecovery(email)
                                        }
                                    }
                                },
                                onCreateAccount = { selectMode(SettingsTsuzukiAccountFormMode.REGISTER) },
                                onForgotPassword = { selectMode(SettingsTsuzukiAccountFormMode.RECOVERY) },
                                onBackToSignIn = { selectMode(SettingsTsuzukiAccountFormMode.LOGIN) },
                            )
                        }
                    }

                    is AccountState.EmailConfirmationRequired -> {
                        item(key = "account_auth") {
                            AccountAuthGroup(
                                mode = formMode,
                                email = email,
                                password = password,
                                passwordVisible = passwordVisible,
                                state = state,
                                confirmationEmail = account.email,
                                onEmailChange = {
                                    email = it
                                    screenModel.clearFeedback()
                                },
                                onPasswordChange = {
                                    password = it
                                    screenModel.clearFeedback()
                                },
                                onTogglePassword = { passwordVisible = !passwordVisible },
                                onPrimaryAction = {
                                    when (formMode) {
                                        SettingsTsuzukiAccountFormMode.LOGIN -> screenModel.login(email, password)
                                        SettingsTsuzukiAccountFormMode.REGISTER -> screenModel.register(email, password)
                                        SettingsTsuzukiAccountFormMode.RECOVERY -> {
                                            screenModel.sendPasswordRecovery(email)
                                        }
                                    }
                                },
                                onCreateAccount = { selectMode(SettingsTsuzukiAccountFormMode.REGISTER) },
                                onForgotPassword = { selectMode(SettingsTsuzukiAccountFormMode.RECOVERY) },
                                onBackToSignIn = { selectMode(SettingsTsuzukiAccountFormMode.LOGIN) },
                            )
                        }
                    }

                    is AccountState.Authenticated -> {
                        item(key = "account_connected") {
                            ConnectedAccountContent(
                                email = account.account.email,
                                state = state,
                                onSyncNow = screenModel::syncNow,
                                onSignOut = { showSignOutConfirmation = true },
                            )
                        }
                    }
                }
            }
        }

        if (showSignOutConfirmation) {
            PreferenceControlSheet(
                title = stringResource(MR.strings.logout_title, "Tsuzuki"),
                onDismissRequest = { showSignOutConfirmation = false },
                actions = {
                    AccountTextAction(
                        text = stringResource(MR.strings.action_cancel),
                        onClick = { showSignOutConfirmation = false },
                    )
                    AccountTextAction(
                        text = stringResource(MR.strings.logout),
                        onClick = {
                            showSignOutConfirmation = false
                            screenModel.logout()
                        },
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            ) {}
        }
    }
}

@Composable
private fun AccountAuthGroup(
    mode: SettingsTsuzukiAccountFormMode,
    email: String,
    password: String,
    passwordVisible: Boolean,
    state: TsuzukiAccountScreenState,
    confirmationEmail: String? = null,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onPrimaryAction: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    val colors = defaultSkinColors()
    val invalidInput = state.error == TsuzukiAccountScreenError.INVALID_INPUT
    val emailError = invalidInput && !email.isValidAccountEmail()
    val passwordError = invalidInput && mode.showsPassword && password.isBlank()

    SettingsGroup(title = stringResource(mode.groupTitleResource())) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            confirmationEmail?.let { AccountConfirmationNotice(it) }

            Text(
                text = stringResource(mode.descriptionResource()),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondary,
            )

            DefaultSkinFormField(
                label = stringResource(MR.strings.tsuzuki_entry_email),
                value = email,
                onValueChange = onEmailChange,
                keyboardType = KeyboardType.Email,
                enabled = !state.isWorking,
                error = if (emailError) stringResource(MR.strings.tsuzuki_entry_error_email) else null,
            )

            if (mode.showsPassword) {
                DefaultSkinFormField(
                    label = stringResource(MR.strings.tsuzuki_entry_password),
                    value = password,
                    onValueChange = onPasswordChange,
                    keyboardType = KeyboardType.Password,
                    enabled = !state.isWorking,
                    error = if (passwordError) {
                        stringResource(MR.strings.tsuzuki_entry_error_password)
                    } else {
                        null
                    },
                    isPassword = true,
                    passwordVisible = passwordVisible,
                    passwordToggleLabel = stringResource(
                        if (passwordVisible) {
                            MR.strings.tsuzuki_entry_hide_password
                        } else {
                            MR.strings.tsuzuki_entry_show_password
                        },
                    ),
                    onTogglePassword = onTogglePassword,
                )
            }

            AccountFeedback(state)

            DefaultSkinPrimaryButton(
                text = stringResource(if (state.isWorking) MR.strings.tsuzuki_entry_working else mode.actionResource()),
                onClick = onPrimaryAction,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isWorking,
                leadingContent = {
                    if (state.isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = colors.secondary,
                        )
                    }
                },
            )

            when (mode) {
                SettingsTsuzukiAccountFormMode.LOGIN -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(MR.strings.tsuzuki_entry_no_account),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.secondary,
                            textAlign = TextAlign.Center,
                        )
                        AccountTextAction(
                            text = stringResource(MR.strings.tsuzuki_entry_create_account),
                            onClick = onCreateAccount,
                            enabled = !state.isWorking,
                        )
                        AccountTextAction(
                            text = stringResource(MR.strings.tsuzuki_entry_forgot_password),
                            onClick = onForgotPassword,
                            enabled = !state.isWorking,
                        )
                    }
                }

                SettingsTsuzukiAccountFormMode.REGISTER,
                SettingsTsuzukiAccountFormMode.RECOVERY,
                -> AccountTextAction(
                    text = stringResource(MR.strings.tsuzuki_entry_back_to_sign_in),
                    onClick = onBackToSignIn,
                    enabled = !state.isWorking,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun AccountConfirmationNotice(email: String) {
    val colors = defaultSkinColors()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(MR.strings.tsuzuki_entry_confirmation_sent),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.foreground,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = email,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondary,
        )
    }
}

@Composable
private fun ConnectedAccountContent(
    email: String,
    state: TsuzukiAccountScreenState,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
) {
    val colors = defaultSkinColors()

    SettingsGroup(title = stringResource(MR.strings.tsuzuki_account_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(MR.strings.tsuzuki_entry_email),
                style = MaterialTheme.typography.labelLarge,
                color = colors.secondary,
            )
            Text(
                text = email,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.foreground,
            )
        }
    }

    SettingsGroup(title = stringResource(MR.strings.tsuzuki_account_sync_section)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(
                    if (state.isSyncing) MR.strings.drive_sync_running else MR.strings.drive_sync_ready,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.foreground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            AccountFeedback(state)

            DefaultSkinPrimaryButton(
                text = stringResource(
                    if (state.isSyncing) MR.strings.drive_sync_running else MR.strings.drive_sync_now,
                ),
                onClick = onSyncNow,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isWorking && !state.isSyncing,
                leadingContent = {
                    if (state.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = colors.secondary,
                        )
                    }
                },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        HorizontalDivider(color = colors.outline.copy(alpha = 0.6f))
        AccountTextAction(
            text = stringResource(MR.strings.logout),
            onClick = onSignOut,
            enabled = !state.isWorking && !state.isSyncing,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun AccountFeedback(state: TsuzukiAccountScreenState) {
    val colors = defaultSkinColors()
    state.error?.takeIf { it != TsuzukiAccountScreenError.INVALID_INPUT }?.let { error ->
        Text(
            text = error.messageText(),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (state.error == null && state.recoverySentTo != null) {
        Text(
            text = stringResource(MR.strings.tsuzuki_entry_recovery_sent),
            color = colors.accent,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun AccountTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color? = null,
    textAlign: TextAlign = TextAlign.Center,
) {
    val colors = defaultSkinColors()
    Text(
        text = text,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        color = if (enabled) color ?: colors.foreground else colors.secondary,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = textAlign,
    )
}

@Composable
private fun TsuzukiAccountScreenError.messageText(): String = when (this) {
    TsuzukiAccountScreenError.INVALID_INPUT -> stringResource(MR.strings.tsuzuki_entry_error_invalid)
    TsuzukiAccountScreenError.AUTHENTICATION_FAILED -> stringResource(MR.strings.tsuzuki_entry_error_authentication)
    TsuzukiAccountScreenError.NETWORK_UNAVAILABLE -> stringResource(MR.strings.tsuzuki_entry_error_network)
    TsuzukiAccountScreenError.CONFIGURATION_UNAVAILABLE -> {
        stringResource(MR.strings.tsuzuki_entry_error_configuration)
    }
    TsuzukiAccountScreenError.SYNC_FAILED -> stringResource(MR.strings.drive_sync_failed)
    TsuzukiAccountScreenError.UNKNOWN -> stringResource(MR.strings.tsuzuki_entry_error_unknown)
}

private fun SettingsTsuzukiAccountFormMode.groupTitleResource() = when (this) {
    SettingsTsuzukiAccountFormMode.LOGIN -> MR.strings.tsuzuki_entry_sign_in
    SettingsTsuzukiAccountFormMode.REGISTER -> MR.strings.tsuzuki_entry_create_account
    SettingsTsuzukiAccountFormMode.RECOVERY -> MR.strings.tsuzuki_entry_reset_password
}

private fun SettingsTsuzukiAccountFormMode.descriptionResource() = when (this) {
    SettingsTsuzukiAccountFormMode.LOGIN -> MR.strings.tsuzuki_entry_login_description
    SettingsTsuzukiAccountFormMode.REGISTER -> MR.strings.tsuzuki_entry_register_description
    SettingsTsuzukiAccountFormMode.RECOVERY -> MR.strings.tsuzuki_entry_recovery_description
}

private fun SettingsTsuzukiAccountFormMode.actionResource() = when (this) {
    SettingsTsuzukiAccountFormMode.LOGIN -> MR.strings.tsuzuki_entry_sign_in
    SettingsTsuzukiAccountFormMode.REGISTER -> MR.strings.tsuzuki_entry_create_account
    SettingsTsuzukiAccountFormMode.RECOVERY -> MR.strings.tsuzuki_entry_send_recovery
}

private fun String.isValidAccountEmail(): Boolean {
    val normalized = trim()
    val separator = normalized.indexOf('@')
    return separator > 0 && separator < normalized.lastIndex
}
