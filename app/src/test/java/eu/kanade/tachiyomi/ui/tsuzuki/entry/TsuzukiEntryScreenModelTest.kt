package eu.kanade.tachiyomi.ui.tsuzuki.entry

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository

@OptIn(ExperimentalCoroutinesApi::class)
class TsuzukiEntryScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `login trims email and delegates credentials to the account repository`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = model(repository)

        model.login(" reader@example.com ", "my password")
        advanceUntilIdle()

        repository.lastLoginEmail shouldBe "reader@example.com"
        repository.lastLoginPassword shouldBe "my password"
        model.state.value.signedIn shouldBe true
    }

    @Test
    fun `invalid login is rejected before contacting account repository`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = model(repository)

        model.login("not-an-email", "secret")
        advanceUntilIdle()

        repository.loginCalls shouldBe 0
        model.state.value.error shouldBe TsuzukiEntryError.INVALID_INPUT
    }

    @Test
    fun `registration without a session waits for email confirmation`() = runTest(dispatcher) {
        val model = model(FakeAccountRepository())

        model.register("reader@example.com", "secret")
        advanceUntilIdle()

        model.state.value.completion shouldBe TsuzukiEntryCompletion.ACCOUNT_CREATED
        model.state.value.signedIn shouldBe false
    }

    @Test
    fun `password recovery reports a generic completion without the submitted email`() = runTest(dispatcher) {
        val model = model(FakeAccountRepository())

        model.sendPasswordRecovery("reader@example.com")
        advanceUntilIdle()

        model.state.value.completion shouldBe TsuzukiEntryCompletion.PASSWORD_RECOVERY_SENT
        model.state.value.toString().contains("reader@example.com") shouldBe false
    }

    @Test
    fun `password update requires confirmation before calling backend`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = model(repository)

        model.updatePassword("new secret", "different")
        advanceUntilIdle()

        repository.passwordUpdateCalls shouldBe 0
        model.state.value.error shouldBe TsuzukiEntryError.PASSWORDS_DO_NOT_MATCH
    }

    @Test
    fun `password update rejects blank values before calling backend`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = model(repository)

        model.updatePassword("", "")
        advanceUntilIdle()

        repository.passwordUpdateCalls shouldBe 0
        model.state.value.error shouldBe TsuzukiEntryError.INVALID_INPUT
    }

    @Test
    fun `confirmed password update uses account repository and completes recovery`() = runTest(dispatcher) {
        val repository = FakeAccountRepository()
        val model = model(repository)

        model.updatePassword("new secret", "new secret")
        advanceUntilIdle()

        repository.passwordUpdateCalls shouldBe 1
        repository.lastUpdatedPassword shouldBe "new secret"
        model.state.value.passwordUpdated shouldBe true
    }

    @Test
    fun `recovery error exposes only a safe category`() = runTest(dispatcher) {
        val repository = FakeAccountRepository().apply {
            recoveryFailure = IllegalStateException("secret token=private-value")
        }
        val model = model(repository)

        model.sendPasswordRecovery("reader@example.com")
        advanceUntilIdle()

        model.state.value.error shouldBe TsuzukiEntryError.UNKNOWN
        model.state.value.toString().contains("private-value") shouldBe false
    }

    @Test
    fun `authentication backend failure maps to safe category without exposing response`() = runTest(dispatcher) {
        val repository = FakeAccountRepository().apply {
            recoveryFailure = IllegalStateException("HTTP 401 token=private-value")
        }
        val model = model(repository)

        model.sendPasswordRecovery("reader@example.com")
        advanceUntilIdle()

        model.state.value.error shouldBe TsuzukiEntryError.AUTHENTICATION_FAILED
        model.state.value.toString().contains("private-value") shouldBe false
    }

    private class FakeAccountRepository : AccountRepository {
        private val mutableState = MutableStateFlow<AccountState>(AccountState.LoggedOut)
        override val state: StateFlow<AccountState> = mutableState

        var loginCalls = 0
        var lastLoginEmail: String? = null
        var lastLoginPassword: String? = null
        var passwordUpdateCalls = 0
        var lastUpdatedPassword: String? = null
        var recoveryFailure: Throwable? = null

        override suspend fun register(email: String, password: String): Result<AccountState> =
            Result.success(AccountState.EmailConfirmationRequired(email))

        override suspend fun login(email: String, password: String): Result<AccountState> {
            loginCalls += 1
            lastLoginEmail = email
            lastLoginPassword = password
            return Result.success(
                AccountState.Authenticated(TsuzukiAccount("user-1", email)).also {
                    mutableState.value = it
                },
            )
        }

        override suspend fun logout(): Result<Unit> = Result.success(Unit)

        override suspend fun sendPasswordRecovery(email: String): Result<Unit> =
            recoveryFailure?.let { Result.failure(it) } ?: Result.success(Unit)

        override suspend fun updatePassword(password: String): Result<Unit> {
            passwordUpdateCalls += 1
            lastUpdatedPassword = password
            return Result.success(Unit)
        }

        override suspend fun refreshSession(): Result<AccountState> = Result.success(mutableState.value)

        override suspend fun getAccessToken(): Result<String?> = Result.success(null)
    }

    private fun model(repository: AccountRepository) =
        TsuzukiEntryScreenModel(repository).apply { ioDispatcher = dispatcher }
}
