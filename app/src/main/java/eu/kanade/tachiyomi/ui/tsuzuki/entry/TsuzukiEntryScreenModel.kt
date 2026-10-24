package eu.kanade.tachiyomi.ui.tsuzuki.entry

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import java.io.IOException

@Immutable
data class TsuzukiEntryState(
    val isWorking: Boolean = false,
    val error: TsuzukiEntryError? = null,
    val completion: TsuzukiEntryCompletion? = null,
    val signedIn: Boolean = false,
    val passwordUpdated: Boolean = false,
)

enum class TsuzukiEntryError {
    INVALID_INPUT,
    PASSWORDS_DO_NOT_MATCH,
    AUTHENTICATION_FAILED,
    NETWORK_UNAVAILABLE,
    CONFIGURATION_UNAVAILABLE,
    UNKNOWN,
}

enum class TsuzukiEntryCompletion {
    ACCOUNT_CREATED,
    PASSWORD_RECOVERY_SENT,
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class TsuzukiEntryScreenModel(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val mutableState = MutableStateFlow(TsuzukiEntryState())
    val state: StateFlow<TsuzukiEntryState> = mutableState.asStateFlow()

    fun login(email: String, password: String) {
        val normalizedEmail = email.trim()
        if (!validCredentials(normalizedEmail, password)) {
            fail(TsuzukiEntryError.INVALID_INPUT)
            return
        }
        launchOperation {
            accountRepository.login(normalizedEmail, password).getOrThrow()
            TsuzukiEntrySuccess.SIGNED_IN
        }
    }

    fun register(email: String, password: String) {
        val normalizedEmail = email.trim()
        if (!validCredentials(normalizedEmail, password)) {
            fail(TsuzukiEntryError.INVALID_INPUT)
            return
        }
        launchOperation {
            val result = accountRepository.register(normalizedEmail, password).getOrThrow()
            when (result) {
                is AccountState.EmailConfirmationRequired -> TsuzukiEntrySuccess.ACCOUNT_CREATED
                is AccountState.Authenticated -> TsuzukiEntrySuccess.SIGNED_IN
                AccountState.LoggedOut -> TsuzukiEntrySuccess.NONE
            }
        }
    }

    fun sendPasswordRecovery(email: String) {
        val normalizedEmail = email.trim()
        if (!validEmail(normalizedEmail)) {
            fail(TsuzukiEntryError.INVALID_INPUT)
            return
        }
        launchOperation {
            accountRepository.sendPasswordRecovery(normalizedEmail).getOrThrow()
            TsuzukiEntrySuccess.PASSWORD_RECOVERY_SENT
        }
    }

    fun updatePassword(password: String, confirmation: String) {
        if (password.isBlank() || confirmation.isBlank()) {
            fail(TsuzukiEntryError.INVALID_INPUT)
            return
        }
        if (password != confirmation) {
            fail(TsuzukiEntryError.PASSWORDS_DO_NOT_MATCH)
            return
        }
        launchOperation {
            accountRepository.updatePassword(password).getOrThrow()
            TsuzukiEntrySuccess.PASSWORD_UPDATED
        }
    }

    fun clearFeedback() {
        mutableState.value = mutableState.value.copy(
            error = null,
            completion = null,
            signedIn = false,
            passwordUpdated = false,
        )
    }

    private fun launchOperation(
        block: suspend () -> TsuzukiEntrySuccess,
    ) {
        if (mutableState.value.isWorking) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isWorking = true,
                error = null,
                completion = null,
            )
            try {
                applySuccess(withContext(ioDispatcher) { block() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableState.value = mutableState.value.copy(error = failure.toEntryError())
            } finally {
                mutableState.value = mutableState.value.copy(isWorking = false)
            }
        }
    }

    private fun applySuccess(success: TsuzukiEntrySuccess) {
        val current = mutableState.value
        mutableState.value = when (success) {
            TsuzukiEntrySuccess.SIGNED_IN -> current.copy(signedIn = true)
            TsuzukiEntrySuccess.ACCOUNT_CREATED -> current.copy(
                completion = TsuzukiEntryCompletion.ACCOUNT_CREATED,
            )
            TsuzukiEntrySuccess.PASSWORD_RECOVERY_SENT -> current.copy(
                completion = TsuzukiEntryCompletion.PASSWORD_RECOVERY_SENT,
            )
            TsuzukiEntrySuccess.PASSWORD_UPDATED -> current.copy(passwordUpdated = true)
            TsuzukiEntrySuccess.NONE -> current
        }
    }

    private fun fail(error: TsuzukiEntryError) {
        mutableState.value = mutableState.value.copy(error = error, completion = null)
    }

    private fun validCredentials(email: String, password: String): Boolean =
        validEmail(email) && password.isNotBlank()

    private fun validEmail(email: String): Boolean {
        val separator = email.indexOf('@')
        return separator > 0 && separator < email.lastIndex
    }

    private fun Throwable.toEntryError(): TsuzukiEntryError {
        if (this is IOException) return TsuzukiEntryError.NETWORK_UNAVAILABLE
        val message = message.orEmpty().lowercase()
        return when {
            "not configured" in message || "supabase url" in message || "publishable" in message ->
                TsuzukiEntryError.CONFIGURATION_UNAVAILABLE
            "http 400" in message || "http 401" in message || "http 403" in message || "http 422" in message ->
                TsuzukiEntryError.AUTHENTICATION_FAILED
            "network" in message || "timeout" in message || "connection" in message ->
                TsuzukiEntryError.NETWORK_UNAVAILABLE
            else -> TsuzukiEntryError.UNKNOWN
        }
    }
}

private enum class TsuzukiEntrySuccess {
    SIGNED_IN,
    ACCOUNT_CREATED,
    PASSWORD_RECOVERY_SENT,
    PASSWORD_UPDATED,
    NONE,
}
