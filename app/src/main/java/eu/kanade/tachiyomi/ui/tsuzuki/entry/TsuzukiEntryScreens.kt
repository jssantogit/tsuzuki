package eu.kanade.tachiyomi.ui.tsuzuki.entry

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.tsuzuki.entry.TsuzukiEntryPage
import eu.kanade.presentation.tsuzuki.entry.TsuzukiEntryScreen
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object TsuzukiLoginScreen : Screen() {
    @Composable
    override fun Content() = AuthContent(TsuzukiEntryPage.LOGIN)
}

data object TsuzukiRegisterScreen : Screen() {
    @Composable
    override fun Content() = AuthContent(TsuzukiEntryPage.REGISTER)
}

data object TsuzukiForgotPasswordScreen : Screen() {
    @Composable
    override fun Content() = AuthContent(TsuzukiEntryPage.RECOVERY)
}

data object TsuzukiResetPasswordScreen : Screen() {
    @Composable
    override fun Content() = AuthContent(TsuzukiEntryPage.RESET_PASSWORD)
}

@Composable
private fun AuthContent(page: TsuzukiEntryPage) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val screenModel = metroViewModel<TsuzukiEntryScreenModel>()
    val state = screenModel.state.collectAsStateWithLifecycle().value
    val passwordUpdatedMessage = stringResource(MR.strings.tsuzuki_entry_password_updated)

    TsuzukiEntryScreen(
        page = page,
        state = state,
        onBack = {
            screenModel.clearFeedback()
            navigator.pop()
        },
        onCreateAccount = {
            screenModel.clearFeedback()
            navigator.push(TsuzukiRegisterScreen)
        },
        onSignIn = {
            screenModel.clearFeedback()
            navigator.push(TsuzukiLoginScreen)
        },
        onForgotPassword = {
            screenModel.clearFeedback()
            navigator.push(TsuzukiForgotPasswordScreen)
        },
        onLogin = screenModel::login,
        onRegister = screenModel::register,
        onSendRecovery = screenModel::sendPasswordRecovery,
        onUpdatePassword = screenModel::updatePassword,
    )

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            screenModel.clearFeedback()
            navigator.popUntilRoot()
        }
    }
    LaunchedEffect(state.passwordUpdated) {
        if (state.passwordUpdated) {
            Toast.makeText(context, passwordUpdatedMessage, Toast.LENGTH_SHORT).show()
            screenModel.clearFeedback()
            navigator.popUntilRoot()
        }
    }
}
