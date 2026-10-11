package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenError
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenModel
import eu.kanade.tachiyomi.ui.tsuzuki.account.TsuzukiAccountScreenState
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
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

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "account_title") {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_account_title),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                when (val account = state.accountState) {
                    AccountState.LoggedOut -> {
                        item(key = "account_auth") {
                            AccountAuthSurface(
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
                        item(key = "account_confirmation") {
                            AccountConfirmationSurface(email = account.email)
                        }
                        item(key = "account_auth") {
                            AccountAuthSurface(
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

                    is AccountState.Authenticated -> {
                        item(key = "account_connected") {
                            ConnectedAccountSurface(
                                email = account.account.email,
                                state = state,
                                onSyncNow = screenModel::syncNow,
                            )
                        }
                        item(key = "account_sign_out") {
                            SignOutSurface(
                                enabled = !state.isWorking && !state.isSyncing,
                                onSignOut = { showSignOutConfirmation = true },
                            )
                        }
                    }
                }
            }
        }

        if (showSignOutConfirmation) {
            AlertDialog(
                onDismissRequest = { showSignOutConfirmation = false },
                title = { Text(stringResource(MR.strings.logout_title, "Tsuzuki")) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSignOutConfirmation = false
                            screenModel.logout()
                        },
                    ) {
                        Text(
                            text = stringResource(MR.strings.logout),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutConfirmation = false }) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun AccountAuthSurface(
    mode: SettingsTsuzukiAccountFormMode,
    email: String,
    password: String,
    passwordVisible: Boolean,
    state: TsuzukiAccountScreenState,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onPrimaryAction: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    onBackToSignIn: () -> Unit,
) {
    val invalidInput = state.error == TsuzukiAccountScreenError.INVALID_INPUT
    val emailError = invalidInput && !email.isValidAccountEmail()
    val passwordError = invalidInput && mode.showsPassword && password.isBlank()

    AccountSurface {
        Text(
            text = stringResource(mode.titleResource()),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(mode.descriptionResource()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text(stringResource(MR.strings.tsuzuki_entry_email)) },
            singleLine = true,
            enabled = !state.isWorking,
            isError = emailError,
            supportingText = if (emailError) {
                { Text(stringResource(MR.strings.tsuzuki_entry_error_email)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (mode.showsPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(MR.strings.tsuzuki_entry_password)) },
                singleLine = true,
                enabled = !state.isWorking,
                isError = passwordError,
                supportingText = if (passwordError) {
                    { Text(stringResource(MR.strings.tsuzuki_entry_error_password)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = onTogglePassword, enabled = !state.isWorking) {
                        Text(
                            stringResource(
                                if (passwordVisible) {
                                    MR.strings.tsuzuki_entry_hide_password
                                } else {
                                    MR.strings.tsuzuki_entry_show_password
                                },
                            ),
                        )
                    }
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (mode == SettingsTsuzukiAccountFormMode.LOGIN) {
            TextButton(
                onClick = onForgotPassword,
                enabled = !state.isWorking,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(MR.strings.tsuzuki_entry_forgot_password))
            }
        }

        AccountFeedback(state)

        Button(
            onClick = onPrimaryAction,
            enabled = !state.isWorking,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            if (state.isWorking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                stringResource(
                    if (state.isWorking) MR.strings.tsuzuki_entry_working else mode.actionResource(),
                ),
            )
        }

        when (mode) {
            SettingsTsuzukiAccountFormMode.LOGIN -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_entry_no_account),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onCreateAccount, enabled = !state.isWorking) {
                        Text(stringResource(MR.strings.tsuzuki_entry_create_account))
                    }
                }
            }

            SettingsTsuzukiAccountFormMode.REGISTER,
            SettingsTsuzukiAccountFormMode.RECOVERY,
            -> TextButton(
                onClick = onBackToSignIn,
                enabled = !state.isWorking,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(stringResource(MR.strings.tsuzuki_entry_back_to_sign_in))
            }
        }
    }
}

@Composable
private fun AccountConfirmationSurface(email: String) {
    AccountSurface {
        Text(
            text = stringResource(MR.strings.tsuzuki_entry_confirmation_sent),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            text = email,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectedAccountSurface(
    email: String,
    state: TsuzukiAccountScreenState,
    onSyncNow: () -> Unit,
) {
    AccountSurface {
        Text(
            text = stringResource(MR.strings.tsuzuki_entry_email),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = email,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = stringResource(
                if (state.isSyncing) MR.strings.drive_sync_running else MR.strings.drive_sync_ready,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )

        AccountFeedback(state)

        Button(
            onClick = onSyncNow,
            enabled = !state.isWorking && !state.isSyncing,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            if (state.isSyncing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                stringResource(
                    if (state.isSyncing) MR.strings.drive_sync_running else MR.strings.drive_sync_now,
                ),
            )
        }
    }
}

@Composable
private fun SignOutSurface(
    enabled: Boolean,
    onSignOut: () -> Unit,
) {
    AccountSurface {
        OutlinedButton(
            onClick = onSignOut,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text(
                text = stringResource(MR.strings.logout),
                color = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(
                        alpha = 0.38f,
                    )
                },
            )
        }
    }
}

@Composable
private fun AccountSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun AccountFeedback(state: TsuzukiAccountScreenState) {
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
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun TsuzukiAccountScreenError.messageText(): String = when (this) {
    TsuzukiAccountScreenError.INVALID_INPUT -> stringResource(MR.strings.tsuzuki_entry_error_invalid)
    TsuzukiAccountScreenError.AUTHENTICATION_FAILED -> stringResource(MR.strings.tsuzuki_entry_error_authentication)
    TsuzukiAccountScreenError.NETWORK_UNAVAILABLE -> stringResource(MR.strings.tsuzuki_entry_error_network)
    TsuzukiAccountScreenError.CONFIGURATION_UNAVAILABLE -> stringResource(MR.strings.tsuzuki_entry_error_configuration)
    TsuzukiAccountScreenError.SYNC_FAILED -> stringResource(MR.strings.drive_sync_failed)
    TsuzukiAccountScreenError.UNKNOWN -> stringResource(MR.strings.tsuzuki_entry_error_unknown)
}

private fun SettingsTsuzukiAccountFormMode.titleResource() = when (this) {
    SettingsTsuzukiAccountFormMode.LOGIN -> MR.strings.tsuzuki_entry_sign_in
    SettingsTsuzukiAccountFormMode.REGISTER -> MR.strings.tsuzuki_entry_create_account
    SettingsTsuzukiAccountFormMode.RECOVERY -> MR.strings.tsuzuki_entry_forgot_password
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
