package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import java.nio.file.Files

class SupabaseAccountRepositoryTest {

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `repository without stored session starts logged out without network`() {
        val store = InMemorySessionStore()
        val repository = repository(store)

        repository.state.value shouldBe AccountState.LoggedOut
        server.requestCount shouldBe 0
    }

    @Test
    fun `signup uses auth signup with publishable key only`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""{"user":{"id":"user-1","email":"reader@example.com"},"session":null}""")
                .build(),
        )
        val repository = repository(InMemorySessionStore())

        repository.register("reader@example.com", "secret123").getOrThrow() shouldBe
            AccountState.EmailConfirmationRequired("reader@example.com")

        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/auth/v1/signup"
        request.headers["apikey"] shouldBe "publishable-test-key"
        request.headers["Authorization"] shouldBe "Bearer publishable-test-key"
        request.body!!.utf8().contains("secret123") shouldBe true
    }

    @Test
    fun `password login uses password grant and persists authenticated session`() = runTest {
        server.enqueue(sessionResponse())
        val store = InMemorySessionStore()
        val repository = repository(store)

        val result = repository.login("reader@example.com", "secret123").getOrThrow()

        result shouldBe AccountState.Authenticated(
            account = tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount(
                id = "user-1",
                email = "reader@example.com",
            ),
        )
        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/auth/v1/token"
        request.url.queryParameter("grant_type") shouldBe "password"
        store.load()?.refreshToken shouldBe "refresh-1"
    }

    @Test
    fun `refresh uses refresh token grant`() = runTest {
        server.enqueue(sessionResponse(accessToken = "access-2", refreshToken = "refresh-2"))
        val store = InMemorySessionStore().apply {
            save(
                SupabaseSession(
                    accessToken = "expired",
                    refreshToken = "refresh-1",
                    expiresAtEpochSeconds = 1,
                    userId = "user-1",
                    email = "reader@example.com",
                ),
            )
        }
        val repository = repository(store)

        repository.refreshSession().getOrThrow()

        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/auth/v1/token"
        request.url.queryParameter("grant_type") shouldBe "refresh_token"
        request.body!!.utf8().contains("refresh-1") shouldBe true
        store.load()?.accessToken shouldBe "access-2"
    }

    @Test
    fun `expired access token is refreshed before use`() = runTest {
        server.enqueue(sessionResponse(accessToken = "access-2", refreshToken = "refresh-2"))
        val store = InMemorySessionStore().apply {
            save(
                SupabaseSession(
                    accessToken = "old-access",
                    refreshToken = "refresh-1",
                    expiresAtEpochSeconds = 100,
                    userId = "user-1",
                    email = "reader@example.com",
                ),
            )
        }
        val config = SupabaseConfiguration(
            url = server.url("/").toString().removeSuffix("/"),
            publishableKey = "publishable-test-key",
        )
        val repository = SupabaseAccountRepository(
            authService = SupabaseAuthService(
                client = OkHttpClient(),
                configuration = config,
            ),
            sessionStore = store,
            callbackRequests = freshCallbackStore(),
            nowEpochSeconds = { 100 },
        )

        repository.getAccessToken().getOrThrow() shouldBe "access-2"

        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/auth/v1/token"
        request.url.queryParameter("grant_type") shouldBe "refresh_token"
    }

    @Test
    fun `access token is refused when callback replaced session behind live account state`() = runTest {
        val store = InMemorySessionStore().apply {
            save(
                SupabaseSession(
                    accessToken = "access-A",
                    refreshToken = "refresh-A",
                    expiresAtEpochSeconds = Long.MAX_VALUE,
                    userId = "account-A",
                    email = "a@example.com",
                ),
            )
        }
        val repository = repository(store)
        // A separate callback Activity currently writes to the same persisted
        // store without updating this live repository instance.
        store.save(
            SupabaseSession(
                accessToken = "access-B",
                refreshToken = "refresh-B",
                expiresAtEpochSeconds = Long.MAX_VALUE,
                userId = "account-B",
                email = "b@example.com",
            ),
        )

        repository.getAccessToken().isFailure shouldBe true
        server.requestCount shouldBe 0
    }

    @Test
    fun `password recovery uses recover endpoint`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val repository = repository(InMemorySessionStore())

        repository.sendPasswordRecovery("reader@example.com").getOrThrow()

        server.takeRequest().url.encodedPath shouldBe "/auth/v1/recover"
    }

    private fun repository(
        store: SupabaseSessionStore,
        callbacks: AuthCallbackRequestStore = freshCallbackStore(),
    ): SupabaseAccountRepository {
        val config = SupabaseConfiguration(
            url = server.url("/").toString().removeSuffix("/"),
            publishableKey = "publishable-test-key",
        )
        val service = SupabaseAuthService(
            client = OkHttpClient(),
            configuration = config,
        )
        return SupabaseAccountRepository(
            authService = service,
            sessionStore = store,
            callbackRequests = callbacks,
        )
    }

    @Test
    fun `signup redirects contain a valid pending request nonce`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"user":{"id":"user-1","email":"reader@example.com"},"session":null}""")
                .build(),
        )
        val nonce = "unique-signup-nonce-1234567890"
        val callbackStore = PersistentAuthCallbackRequestStore(
            directory = Files.createTempDirectory("tsuzuki-auth-flow").toFile(),
            nonceSource = { nonce },
        )

        repository(InMemorySessionStore(), callbackStore)
            .register("reader@example.com", "password").getOrThrow()

        val request = server.takeRequest()
        val redirectTo = request.url.queryParameter("redirect_to")
        redirectTo shouldBe "tsuzuki://auth?tsz_state=$nonce"
        request.body!!.utf8().contains("\"code_challenge_method\":\"s256\"") shouldBe true
        request.body!!.utf8().contains("\"code_challenge\":") shouldBe true
        (callbackStore.consume(nonce, "signup") != null) shouldBe true
        callbackStore.consume(nonce, "signup") shouldBe null
    }

    @Test
    fun `failed signup never leaves a callback request authorized`() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("{}").build())
        val nonce = "failed-signup-nonce-123456789"
        val callbackStore = PersistentAuthCallbackRequestStore(
            directory = Files.createTempDirectory("tsuzuki-auth-failed").toFile(),
            nonceSource = { nonce },
        )

        repository(InMemorySessionStore(), callbackStore)
            .register("reader@example.com", "password").isFailure shouldBe true

        callbackStore.consume(nonce, "signup") shouldBe null
    }

    @Test
    fun `password recovery callback is bound only to recovery request`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val nonce = "unique-recovery-nonce-123456789"
        val callbackStore = PersistentAuthCallbackRequestStore(
            directory = Files.createTempDirectory("tsuzuki-auth-recovery").toFile(),
            nonceSource = { nonce },
        )

        repository(InMemorySessionStore(), callbackStore)
            .sendPasswordRecovery("reader@example.com").getOrThrow()

        val request = server.takeRequest()
        request.url.queryParameter("redirect_to") shouldBe
            "tsuzuki://auth?tsz_state=$nonce"
        request.body!!.utf8().contains("\"code_challenge_method\":\"s256\"") shouldBe true
        callbackStore.consume(nonce, "signup") shouldBe null
        (callbackStore.consume(nonce, "recovery") != null) shouldBe true
    }

    private fun freshCallbackStore() = PersistentAuthCallbackRequestStore(
        directory = Files.createTempDirectory("tsuzuki-auth-repository").toFile(),
    )

    private fun sessionResponse(
        accessToken: String = "access-1",
        refreshToken: String = "refresh-1",
    ) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(
            """
                {
                  "access_token":"$accessToken",
                  "refresh_token":"$refreshToken",
                  "expires_in":3600,
                  "user":{"id":"user-1","email":"reader@example.com"}
                }
            """.trimIndent(),
        )
        .build()

    private class InMemorySessionStore : SupabaseSessionStore {
        private var session: SupabaseSession? = null

        override fun load(): SupabaseSession? = session

        override fun save(session: SupabaseSession) {
            this.session = session
        }

        override fun clear() {
            session = null
        }
    }
}
