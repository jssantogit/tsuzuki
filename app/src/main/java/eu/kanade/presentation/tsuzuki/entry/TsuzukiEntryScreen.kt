package eu.kanade.presentation.tsuzuki.entry

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.tsuzuki.entry.TsuzukiEntryCompletion
import eu.kanade.tachiyomi.ui.tsuzuki.entry.TsuzukiEntryError
import eu.kanade.tachiyomi.ui.tsuzuki.entry.TsuzukiEntryState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

enum class TsuzukiEntryPage {
    WELCOME,
    LOGIN,
    REGISTER,
    RECOVERY,
    RESET_PASSWORD,
}

@Composable
fun TsuzukiEntryScreen(
    page: TsuzukiEntryPage,
    state: TsuzukiEntryState = TsuzukiEntryState(),
    onBack: () -> Unit = {},
    onCreateAccount: () -> Unit = {},
    onSignIn: () -> Unit = {},
    onGuest: () -> Unit = {},
    onForgotPassword: () -> Unit = {},
    onLogin: (String, String) -> Unit = { _, _ -> },
    onRegister: (String, String) -> Unit = { _, _ -> },
    onSendRecovery: (String) -> Unit = {},
    onUpdatePassword: (String, String) -> Unit = { _, _ -> },
) {
    val colors = rememberEntryColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (page == TsuzukiEntryPage.WELCOME) {
            WelcomeContent(
                colors = colors,
                onCreateAccount = onCreateAccount,
                onSignIn = onSignIn,
                onGuest = onGuest,
            )
        } else {
            AuthContent(
                page = page,
                state = state,
                colors = colors,
                onBack = onBack,
                onSignIn = onSignIn,
                onCreateAccount = onCreateAccount,
                onForgotPassword = onForgotPassword,
                onLogin = onLogin,
                onRegister = onRegister,
                onSendRecovery = onSendRecovery,
                onUpdatePassword = onUpdatePassword,
            )
        }
    }
}

@Composable
private fun WelcomeContent(
    colors: EntryColors,
    onCreateAccount: () -> Unit,
    onSignIn: () -> Unit,
    onGuest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(max = 440.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_tsuzuki_lockup),
            contentDescription = stringResource(MR.strings.tsuzuki_entry_brand_description),
            tint = colors.foreground,
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .height(72.dp),
        )
        Spacer(Modifier.height(30.dp))
        Text(
            text = stringResource(MR.strings.tsuzuki_entry_welcome_message),
            style = MaterialTheme.typography.titleMedium,
            color = colors.secondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onCreateAccount,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = primaryButtonColors(colors),
        ) {
            Text(stringResource(MR.strings.tsuzuki_entry_create_account))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onSignIn,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.divider),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = colors.surface,
                contentColor = colors.foreground,
            ),
        ) {
            Text(stringResource(MR.strings.tsuzuki_entry_sign_in))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onGuest,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
        ) {
            Text(stringResource(MR.strings.tsuzuki_entry_continue_as_guest))
        }
    }
}

@Composable
private fun AuthContent(
    page: TsuzukiEntryPage,
    state: TsuzukiEntryState,
    colors: EntryColors,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onForgotPassword: () -> Unit,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
    onSendRecovery: (String) -> Unit,
    onUpdatePassword: (String, String) -> Unit,
) {
    var email by rememberSaveable(page) { mutableStateOf("") }
    var password by remember(page) { mutableStateOf("") }
    var confirmation by remember(page) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(page) { mutableStateOf(false) }
    var confirmationVisible by rememberSaveable(page) { mutableStateOf(false) }
    val isCompleted = when (page) {
        TsuzukiEntryPage.REGISTER -> state.completion == TsuzukiEntryCompletion.ACCOUNT_CREATED
        TsuzukiEntryPage.RECOVERY -> state.completion == TsuzukiEntryCompletion.PASSWORD_RECOVERY_SENT
        else -> false
    }

    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 28.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp),
        ) {
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                contentDescription = stringResource(MR.strings.tsuzuki_navigation_back),
                tint = colors.foreground,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(page.titleResource()),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 36.sp,
                lineHeight = 43.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = colors.foreground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(page.descriptionResource()),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondary,
        )
        Spacer(Modifier.height(30.dp))

        if (isCompleted) {
            Text(
                text = stringResource(page.completionResource()),
                style = MaterialTheme.typography.bodyLarge,
                color = colors.foreground,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(vertical = 12.dp),
            )
            if (page == TsuzukiEntryPage.REGISTER) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground,
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = primaryButtonColors(colors),
            ) {
                Text(stringResource(MR.strings.tsuzuki_entry_back_to_sign_in))
            }
            return@Column
        }

        if (page == TsuzukiEntryPage.LOGIN || page == TsuzukiEntryPage.REGISTER) {
            EntryField(
                colors = colors,
                label = stringResource(MR.strings.tsuzuki_entry_email),
                value = email,
                onValueChange = { email = it },
                keyboardType = KeyboardType.Email,
                error = if (state.error == TsuzukiEntryError.INVALID_INPUT && !email.isValidEntryEmail()) {
                    stringResource(MR.strings.tsuzuki_entry_error_email)
                } else {
                    null
                },
            )
            Spacer(Modifier.height(18.dp))
        }

        when (page) {
            TsuzukiEntryPage.LOGIN -> {
                EntryField(
                    colors = colors,
                    label = stringResource(MR.strings.tsuzuki_entry_password),
                    value = password,
                    onValueChange = { password = it },
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    error = if (state.error == TsuzukiEntryError.INVALID_INPUT && password.isBlank()) {
                        stringResource(MR.strings.tsuzuki_entry_error_password)
                    } else {
                        null
                    },
                )
                TextButton(
                    onClick = onForgotPassword,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) {
                    Text(stringResource(MR.strings.tsuzuki_entry_forgot_password))
                }
            }

            TsuzukiEntryPage.REGISTER -> EntryField(
                colors = colors,
                label = stringResource(MR.strings.tsuzuki_entry_password),
                value = password,
                onValueChange = { password = it },
                keyboardType = KeyboardType.Password,
                isPassword = true,
                passwordVisible = passwordVisible,
                onTogglePassword = { passwordVisible = !passwordVisible },
                error = if (state.error == TsuzukiEntryError.INVALID_INPUT && password.isBlank()) {
                    stringResource(MR.strings.tsuzuki_entry_error_password)
                } else {
                    null
                },
            )

            TsuzukiEntryPage.RECOVERY -> EntryField(
                colors = colors,
                label = stringResource(MR.strings.tsuzuki_entry_email),
                value = email,
                onValueChange = { email = it },
                keyboardType = KeyboardType.Email,
                error = if (state.error == TsuzukiEntryError.INVALID_INPUT && !email.isValidEntryEmail()) {
                    stringResource(MR.strings.tsuzuki_entry_error_email)
                } else {
                    null
                },
            )

            TsuzukiEntryPage.RESET_PASSWORD -> {
                EntryField(
                    colors = colors,
                    label = stringResource(MR.strings.tsuzuki_entry_new_password),
                    value = password,
                    onValueChange = { password = it },
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    passwordVisible = passwordVisible,
                    onTogglePassword = { passwordVisible = !passwordVisible },
                    error = if (state.error == TsuzukiEntryError.INVALID_INPUT && password.isBlank()) {
                        stringResource(MR.strings.tsuzuki_entry_error_password)
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(18.dp))
                EntryField(
                    colors = colors,
                    label = stringResource(MR.strings.tsuzuki_entry_confirm_new_password),
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    keyboardType = KeyboardType.Password,
                    isPassword = true,
                    passwordVisible = confirmationVisible,
                    onTogglePassword = { confirmationVisible = !confirmationVisible },
                    error = when (state.error) {
                        TsuzukiEntryError.PASSWORDS_DO_NOT_MATCH ->
                            stringResource(MR.strings.tsuzuki_entry_error_password_mismatch)
                        TsuzukiEntryError.INVALID_INPUT ->
                            stringResource(MR.strings.tsuzuki_entry_error_password)
                        else -> null
                    },
                )
            }

            TsuzukiEntryPage.WELCOME -> Unit
        }

        state.error?.takeIf {
            it !in setOf(TsuzukiEntryError.INVALID_INPUT, TsuzukiEntryError.PASSWORDS_DO_NOT_MATCH)
        }?.let {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(it.messageResource()),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                when (page) {
                    TsuzukiEntryPage.LOGIN -> onLogin(email, password)
                    TsuzukiEntryPage.REGISTER -> onRegister(email, password)
                    TsuzukiEntryPage.RECOVERY -> onSendRecovery(email)
                    TsuzukiEntryPage.RESET_PASSWORD -> onUpdatePassword(password, confirmation)
                    TsuzukiEntryPage.WELCOME -> Unit
                }
            },
            enabled = !state.isWorking,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = primaryButtonColors(colors),
        ) {
            if (state.isWorking) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
            }
            Text(stringResource(page.actionResource(state.isWorking)))
        }

        if (page == TsuzukiEntryPage.LOGIN || page == TsuzukiEntryPage.REGISTER) {
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (page == TsuzukiEntryPage.LOGIN) {
                            MR.strings.tsuzuki_entry_no_account
                        } else {
                            MR.strings.tsuzuki_entry_have_account
                        },
                    ),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(
                    onClick = if (page == TsuzukiEntryPage.LOGIN) onCreateAccount else onSignIn,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                ) {
                    Text(
                        stringResource(
                            if (page == TsuzukiEntryPage.LOGIN) {
                                MR.strings.tsuzuki_entry_create_account
                            } else {
                                MR.strings.tsuzuki_entry_sign_in
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryField(
    colors: EntryColors,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    error: String? = null,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: () -> Unit = {},
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                focusedLabelColor = colors.accent,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                errorContainerColor = colors.surface,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (isPassword && !passwordVisible) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            trailingIcon = if (isPassword) {
                {
                    TextButton(
                        onClick = onTogglePassword,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
                    ) {
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
                }
            } else {
                null
            },
        )
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun rememberEntryColors(): EntryColors {
    val scheme = MaterialTheme.colorScheme
    val lightSurface = scheme.surface.luminance() > 0.5f
    return EntryColors(
        background = if (lightSurface) Color(0xFFF7F7F8) else scheme.background,
        surface = if (lightSurface) Color.White else scheme.surface,
        foreground = if (lightSurface) Color(0xFF17181A) else scheme.onBackground,
        secondary = if (lightSurface) Color(0xFF50565C) else scheme.onSurfaceVariant,
        accent = if (lightSurface) Color(0xFF275A80) else scheme.primary,
        divider = if (lightSurface) Color(0xFFD6D9DC) else scheme.outlineVariant,
    )
}

private data class EntryColors(
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val secondary: Color,
    val accent: Color,
    val divider: Color,
)

@Composable
private fun primaryButtonColors(colors: EntryColors) =
    ButtonDefaults.buttonColors(
        containerColor = colors.foreground,
        contentColor = colors.background,
    )

private fun TsuzukiEntryPage.titleResource() = when (this) {
    TsuzukiEntryPage.WELCOME -> MR.strings.tsuzuki_entry_welcome_title
    TsuzukiEntryPage.LOGIN -> MR.strings.tsuzuki_entry_sign_in
    TsuzukiEntryPage.REGISTER -> MR.strings.tsuzuki_entry_create_account
    TsuzukiEntryPage.RECOVERY -> MR.strings.tsuzuki_entry_forgot_password
    TsuzukiEntryPage.RESET_PASSWORD -> MR.strings.tsuzuki_entry_reset_password
}

private fun TsuzukiEntryPage.descriptionResource() = when (this) {
    TsuzukiEntryPage.WELCOME -> MR.strings.tsuzuki_entry_welcome_message
    TsuzukiEntryPage.LOGIN -> MR.strings.tsuzuki_entry_login_description
    TsuzukiEntryPage.REGISTER -> MR.strings.tsuzuki_entry_register_description
    TsuzukiEntryPage.RECOVERY -> MR.strings.tsuzuki_entry_recovery_description
    TsuzukiEntryPage.RESET_PASSWORD -> MR.strings.tsuzuki_entry_reset_description
}

private fun TsuzukiEntryPage.actionResource(isWorking: Boolean) = when {
    isWorking && this == TsuzukiEntryPage.RESET_PASSWORD -> MR.strings.tsuzuki_entry_saving_password
    isWorking -> MR.strings.tsuzuki_entry_working
    this == TsuzukiEntryPage.LOGIN -> MR.strings.tsuzuki_entry_sign_in
    this == TsuzukiEntryPage.REGISTER -> MR.strings.tsuzuki_entry_create_account
    this == TsuzukiEntryPage.RECOVERY -> MR.strings.tsuzuki_entry_send_recovery
    this == TsuzukiEntryPage.RESET_PASSWORD -> MR.strings.tsuzuki_entry_save_password
    else -> MR.strings.tsuzuki_entry_create_account
}

private fun TsuzukiEntryPage.completionResource() = when (this) {
    TsuzukiEntryPage.REGISTER -> MR.strings.tsuzuki_entry_confirmation_sent
    TsuzukiEntryPage.RECOVERY -> MR.strings.tsuzuki_entry_recovery_sent
    else -> MR.strings.tsuzuki_entry_recovery_sent
}

private fun TsuzukiEntryError.messageResource() = when (this) {
    TsuzukiEntryError.INVALID_INPUT -> MR.strings.tsuzuki_entry_error_invalid
    TsuzukiEntryError.PASSWORDS_DO_NOT_MATCH -> MR.strings.tsuzuki_entry_error_password_mismatch
    TsuzukiEntryError.AUTHENTICATION_FAILED -> MR.strings.tsuzuki_entry_error_authentication
    TsuzukiEntryError.NETWORK_UNAVAILABLE -> MR.strings.tsuzuki_entry_error_network
    TsuzukiEntryError.CONFIGURATION_UNAVAILABLE -> MR.strings.tsuzuki_entry_error_configuration
    TsuzukiEntryError.UNKNOWN -> MR.strings.tsuzuki_entry_error_unknown
}

private fun String.isValidEntryEmail(): Boolean {
    val normalized = trim()
    val separator = normalized.indexOf('@')
    return separator > 0 && separator < normalized.lastIndex
}
