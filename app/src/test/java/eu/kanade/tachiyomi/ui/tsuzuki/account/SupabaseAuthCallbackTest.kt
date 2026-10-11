package eu.kanade.tachiyomi.ui.tsuzuki.account

import android.content.Intent
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAccountRepository
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseAuthService
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseConfiguration
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseSession
import eu.kanade.tachiyomi.data.tsuzuki.supabase.SupabaseSessionStore
import eu.kanade.tachiyomi.ui.main.MainActivity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState

class SupabaseAuthCallbackTest {
    private val server = MockWebServer()
    private val store = MemorySessionStore()
    private lateinit var service: SupabaseAuthService
    private lateinit var repository: SupabaseAccountRepository

    @BeforeEach
    fun setUp() {
        server.start()
        service = SupabaseAuthService(
            OkHttpClient(),
            SupabaseConfiguration(server.url("/").toString().removeSuffix("/"), "publishable-test-key"),
        )
        repository = SupabaseAccountRepository(service, store)
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `verified recovery accepts session before returning explicit password reset action`() = runTest {
        server.enqueue(sessionResponse())

        acceptSupabaseAuthCallback(
            mapOf("type" to "recovery", "token_hash" to "test-hash"),
            service,
            repository,
        ) shouldBe MainActivity.ACTION_TSUZUKI_PASSWORD_RECOVERY

        store.load()?.accessToken shouldBe "accepted-access"
        (repository.state.value is AccountState.Authenticated) shouldBe true
        val verify = server.takeRequest()
        verify.url.encodedPath shouldBe "/auth/v1/verify"
        verify.body!!.utf8() shouldBe """{"token_hash":"test-hash","type":"recovery"}"""
    }

    @Test
    fun `signup confirmation returns existing generic action`() = runTest {
        server.enqueue(sessionResponse())

        acceptSupabaseAuthCallback(
            mapOf("type" to "signup", "token_hash" to "test-hash"),
            service,
            repository,
        ) shouldBe Intent.ACTION_APPLICATION_PREFERENCES
    }

    @Test
    fun `email change returns existing generic action`() = runTest {
        server.enqueue(sessionResponse())

        acceptSupabaseAuthCallback(
            mapOf("type" to "email_change", "token_hash" to "test-hash"),
            service,
            repository,
        ) shouldBe Intent.ACTION_APPLICATION_PREFERENCES
    }

    @Test
    fun `recovery access token is verified against user before accepting session`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"id":"user-1","email":"reader@example.com"}""").build(),
        )

        acceptSupabaseAuthCallback(
            mapOf(
                "type" to "recovery",
                "access_token" to "accepted-access",
                "refresh_token" to "accepted-refresh",
                "expires_in" to "3600",
            ),
            service,
            repository,
        ) shouldBe MainActivity.ACTION_TSUZUKI_PASSWORD_RECOVERY

        val verify = server.takeRequest()
        verify.method shouldBe "GET"
        verify.url.encodedPath shouldBe "/auth/v1/user"
        verify.headers["Authorization"] shouldBe "Bearer accepted-access"
        store.load()?.refreshToken shouldBe "accepted-refresh"
    }

    @Test
    fun `failed recovery verification does not return reset action or accept session`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())

        val result = runCatching {
            acceptSupabaseAuthCallback(
                mapOf("type" to "recovery", "token_hash" to "bad-hash"),
                service,
                repository,
            )
        }

        result.isFailure shouldBe true
        result.getOrNull() shouldBe null
        store.load() shouldBe null
    }

    @Test
    fun `recovery response without session cannot return reset action`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body("""{"user":{"id":"user-1","email":"reader@example.com"}}""")
                .build(),
        )

        runCatching {
            acceptSupabaseAuthCallback(
                mapOf("type" to "recovery", "token_hash" to "test-hash"),
                service,
                repository,
            )
        }.getOrNull() shouldBe null
        store.load() shouldBe null
    }

    @Test
    fun `unsupported callback type and missing token fail before network`() = runTest {
        listOf(
            mapOf("type" to "invalid", "token_hash" to "test-hash"),
            mapOf("type" to "recovery"),
            mapOf("token_hash" to "test-hash"),
        ).forEach { values ->
            runCatching { acceptSupabaseAuthCallback(values, service, repository) }.getOrNull() shouldBe null
        }
        server.requestCount shouldBe 0
        store.load() shouldBe null
    }

    private fun sessionResponse() = MockResponse.Builder().code(200)
        .body(
            """{"access_token":"accepted-access","refresh_token":"accepted-refresh","expires_in":3600,"user":{"id":"user-1","email":"reader@example.com"}}""",
        ).build()

    private class MemorySessionStore : SupabaseSessionStore {
        private var session: SupabaseSession? = null
        override fun load() = session
        override fun save(session: SupabaseSession) {
            this.session = session
        }
        override fun clear() {
            session = null
        }
    }
}
