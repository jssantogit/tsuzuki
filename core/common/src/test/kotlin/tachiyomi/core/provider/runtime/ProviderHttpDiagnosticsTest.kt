package tachiyomi.core.provider.runtime

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ProviderHttpDiagnosticsTest {

    @Test
    fun `http diagnostics classify response body deadline without leaking path or query`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body("late")
                .bodyDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        server.start()

        try {
            val owner = ProviderResourceOwner("org.example.diagnostics", "http-diagnostics")
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val diagnostics = mutableListOf<ProviderHttpDiagnostic>()
            val http = DefaultProviderHttpHostService(
                owner = owner,
                resources = ProviderResourceStore(),
                policy = ProviderNetworkPolicy(
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                cookieJar = ProviderHttpSessionStore().cookieJar(owner.providerId),
                invocationTimeoutMs = 250,
                diagnosticSink = diagnostics::add,
            )

            runCatching {
                http.getText(server.url("/secret-title?q=one-piece").toString())
            }

            diagnostics.shouldHaveSize(2)
            diagnostics.first().let { started ->
                started.phase shouldBe ProviderHttpDiagnosticPhase.STARTED
                started.host shouldBe server.url("/").host
                started.timeoutMs shouldBe 250L
                started.statusCode shouldBe null
                started.failureFamily shouldBe null
            }
            diagnostics.last().let { failed ->
                failed.phase shouldBe ProviderHttpDiagnosticPhase.FAILED
                failed.host shouldBe server.url("/").host
                failed.timeoutMs shouldBe 250L
                failed.elapsedMs.shouldBeGreaterThanOrEqual(0L)
                failed.statusCode shouldBe 200
                failed.failureFamily shouldBe ProviderHttpFailureFamily.HOST_DEADLINE
            }

            diagnostics.joinToString().shouldNotContain("secret-title")
            diagnostics.joinToString().shouldNotContain("one-piece")
        } finally {
            server.close()
        }
    }
}
