package tachiyomi.core.provider.runtime

import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class ProviderHttpInvocationDeadlineTest {

    @Test
    fun `http broker stops an in flight request at the invocation deadline`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body("late")
                .bodyDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        server.start()

        try {
            val owner = ProviderResourceOwner("org.example.deadline", "invocation-deadline")
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val http = DefaultProviderHttpHostService(
                owner = owner,
                resources = ProviderResourceStore(),
                policy = ProviderNetworkPolicy(
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                cookieJar = ProviderHttpSessionStore().cookieJar(owner.providerId),
                invocationTimeoutMs = 250,
            )

            val started = TimeSource.Monotonic.markNow()
            val failed = runCatching {
                http.getText(server.url("/slow").toString())
            }.isFailure

            failed.shouldBeTrue()
            (started.elapsedNow() < 1_000.milliseconds).shouldBeTrue()
        } finally {
            server.close()
        }
    }
}
