package eu.kanade.tachiyomi.data.tsuzuki.supabase

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository

class SupabaseAccountRepository(
    private val authService: SupabaseAuthService,
    private val sessionStore: SupabaseSessionStore,
    private val callbackRequests: AuthCallbackRequestStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : AccountRepository {

    private val mutableState = MutableStateFlow(
        sessionStore.load()?.toAccountState() ?: AccountState.LoggedOut,
    )

    override val state: StateFlow<AccountState> = mutableState.asStateFlow()

    override suspend fun register(email: String, password: String): Result<AccountState> =
        runCatching {
            val request = callbackRequests.start("signup")
            try {
                val result = authService.register(
                    email = email,
                    password = password,
                    redirectTo = authService.callbackRedirectUrl(request.nonce),
                    codeChallenge = request.codeChallenge,
                )
                check(result.session == null) {
                    "PKCE signup must not return bearer tokens before email verification"
                }
                val next = AccountState.EmailConfirmationRequired(email)
                mutableState.value = next
                next
            } catch (error: Throwable) {
                callbackRequests.cancel("signup", request.nonce)
                throw error
            }
        }

    override suspend fun login(email: String, password: String): Result<AccountState> =
        runCatching {
            val result = authService.login(email, password)
            val session = requireNotNull(result.session) {
                "Supabase login succeeded without a session"
            }
            sessionStore.save(session)
            session.toAccountState().also { mutableState.value = it }
        }

    override suspend fun logout(): Result<Unit> {
        val existing = sessionStore.load()
        sessionStore.clear()
        mutableState.value = AccountState.LoggedOut
        if (existing == null) return Result.success(Unit)
        return runCatching {
            authService.logout(existing.accessToken)
        }
    }

    override suspend fun sendPasswordRecovery(email: String): Result<Unit> =
        runCatching {
            val request = callbackRequests.start("recovery")
            try {
                authService.sendPasswordRecovery(
                    email = email,
                    redirectTo = authService.callbackRedirectUrl(request.nonce),
                    codeChallenge = request.codeChallenge,
                )
            } catch (error: Throwable) {
                callbackRequests.cancel("recovery", request.nonce)
                throw error
            }
        }

    override suspend fun refreshSession(): Result<AccountState> =
        runCatching {
            val existing = sessionStore.load()
                ?: return@runCatching AccountState.LoggedOut.also {
                    mutableState.value = it
                }
            val result = authService.refresh(existing.refreshToken)
            val session = requireNotNull(result.session) {
                "Supabase refresh succeeded without a session"
            }
            sessionStore.save(session)
            session.toAccountState().also { mutableState.value = it }
        }

    override suspend fun getAccessToken(): Result<String?> = runCatching {
        val activeUserId = (state.value as? AccountState.Authenticated)
            ?.account?.id ?: return@runCatching null

        val session = sessionStore.load() ?: return@runCatching null
        check(session.userId == activeUserId) {
            "Persisted Supabase session belongs to a different account"
        }
        if (session.expiresAtEpochSeconds > nowEpochSeconds() + ACCESS_TOKEN_REFRESH_SKEW_SECONDS) {
            return@runCatching session.accessToken
        }

        refreshSession().getOrThrow()
        val refreshed = sessionStore.load() ?: return@runCatching null
        check(refreshed.userId == activeUserId) {
            "Supabase token refresh changed the active account identity"
        }
        refreshed.accessToken
    }

    fun acceptSession(session: SupabaseSession): AccountState {
        sessionStore.save(session)
        return session.toAccountState().also { mutableState.value = it }
    }

    fun clearInvalidSession() {
        sessionStore.clear()
        mutableState.value = AccountState.LoggedOut
    }

    private fun SupabaseSession.toAccountState(): AccountState =
        AccountState.Authenticated(
            TsuzukiAccount(
                id = userId,
                email = email,
            ),
        )

    private companion object {
        const val ACCESS_TOKEN_REFRESH_SKEW_SECONDS = 30L
    }
}
