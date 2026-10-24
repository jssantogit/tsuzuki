package eu.kanade.tachiyomi.data.tsuzuki.supabase

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState

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
            nowEpochSeconds = { 100 },
        )

        repository.getAccessToken().getOrThrow() shouldBe "access-2"

        val request = server.takeRequest()
        request.url.encodedPath shouldBe "/auth/v1/token"
        request.url.queryParameter("grant_type") shouldBe "refresh_token"
    }

    @Test
    fun `password recovery uses recover endpoint`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val repository = repository(InMemorySessionStore())

        repository.sendPasswordRecovery("reader@example.com").getOrThrow()

        server.takeRequest().url.encodedPath shouldBe "/auth/v1/recover"
    }

    @Test
    fun `password update uses accepted session bearer and only sends new password`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val store = InMemorySessionStore()
        val repository = repository(store)
        repository.acceptSession(recoverySession())

        repository.updatePassword("replacement123").getOrThrow()

        val request = server.takeRequest()
        request.method shouldBe "PUT"
        request.url.encodedPath shouldBe "/auth/v1/user"
        request.url.query shouldBe null
        request.headers["apikey"] shouldBe "publishable-test-key"
        request.headers["Authorization"] shouldBe "Bearer recovery-access"
        request.body!!.utf8() shouldBe """{"password":"replacement123"}"""
        store.load()?.refreshToken shouldBe "recovery-refresh"
        repository.state.value shouldBe AccountState.Authenticated(
            tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount("user-1", "reader@example.com"),
        )
    }

    @Test
    fun `password update observes recovery accepted after repository initialization`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val store = InMemorySessionStore()
        val repository = repository(store)
        repository(store).acceptSession(recoverySession())

        repository.updatePassword("replacement123").getOrThrow()

        server.takeRequest().headers["Authorization"] shouldBe "Bearer recovery-access"
        (repository.state.value is AccountState.Authenticated) shouldBe true
    }

    @Test
    fun `password update refreshes expired accepted session before updating user`() = runTest {
        server.enqueue(sessionResponse(accessToken = "refreshed-access"))
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val repository = repository(InMemorySessionStore())
        repository.acceptSession(recoverySession().copy(expiresAtEpochSeconds = 1))

        repository.updatePassword("replacement123").getOrThrow()

        val refresh = server.takeRequest()
        refresh.url.queryParameter("grant_type") shouldBe "refresh_token"
        refresh.body!!.utf8() shouldBe """{"refresh_token":"recovery-refresh"}"""
        val update = server.takeRequest()
        update.method shouldBe "PUT"
        update.headers["Authorization"] shouldBe "Bearer refreshed-access"
    }

    @Test
    fun `password update fails without session and sends no request`() = runTest {
        val repository = repository(InMemorySessionStore())

        repository.updatePassword("replacement123").isFailure shouldBe true

        server.requestCount shouldBe 0
        repository.state.value shouldBe AccountState.LoggedOut
    }

    @Test
    fun `blank password update fails before network`() = runTest {
        val repository = repository(InMemorySessionStore())
        repository.acceptSession(recoverySession())

        repository.updatePassword("   ").isFailure shouldBe true

        server.requestCount shouldBe 0
    }

    @Test
    fun `password update backend failure excludes response credentials from error`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(422)
                .body("""{"message":"replacement123 recovery-access recovery-refresh"}""")
                .build(),
        )
        val repository = repository(InMemorySessionStore())
        repository.acceptSession(recoverySession())

        val result = repository.updatePassword("replacement123")

        result.isFailure shouldBe true
        val error = result.exceptionOrNull()!!.message.orEmpty()
        error.contains("422") shouldBe true
        listOf("replacement123", "recovery-access", "recovery-refresh").forEach {
            error.contains(it) shouldBe false
        }
    }

    @Test
    fun `password update does not pass credentials to HTTP logging interceptors`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val logs = mutableListOf<String>()
        val logger = HttpLoggingInterceptor { logs += it }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder().addInterceptor(logger).addNetworkInterceptor(logger).build()
        val service = SupabaseAuthService(
            client = supabaseSafeClient(client),
            configuration = SupabaseConfiguration(
                server.url("/").toString().removeSuffix("/"),
                "publishable-test-key",
            ),
        )

        service.updatePassword("recovery-access", "replacement123")

        logs shouldBe emptyList()
        server.takeRequest().headers["Authorization"] shouldBe "Bearer recovery-access"
    }

    private fun recoverySession() = SupabaseSession(
        accessToken = "recovery-access",
        refreshToken = "recovery-refresh",
        expiresAtEpochSeconds = Long.MAX_VALUE,
        userId = "user-1",
        email = "reader@example.com",
    )

    private fun repository(store: SupabaseSessionStore): SupabaseAccountRepository {
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
        )
    }

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
