package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class ProviderHttpHostServiceTest {

    @Test
    fun `http broker preserves provider scoped cookies across invocations`() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.url.encodedPath) {
                    "/set" -> MockResponse(
                        headers = headersOf("Set-Cookie", "session=alpha; Path=/"),
                        body = "set",
                    )
                    "/read" -> MockResponse(body = request.headers["Cookie"] ?: "empty")
                    else -> MockResponse(code = 404)
                }
        }
        server.start()

        try {
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val sessions = ProviderHttpSessionStore()
            val resources = ProviderResourceStore()
            val policy = ProviderNetworkPolicy(
                allowedOrigins = setOf(origin),
                allowLocalNetwork = true,
            )

            val first = DefaultProviderHttpHostService(
                owner = ProviderResourceOwner("org.example.a", "invocation-1"),
                resources = resources,
                policy = policy,
                cookieJar = sessions.cookieJar("org.example.a"),
            )
            first.getText(server.url("/set").toString()) shouldBe "set"

            val second = DefaultProviderHttpHostService(
                owner = ProviderResourceOwner("org.example.a", "invocation-2"),
                resources = resources,
                policy = policy,
                cookieJar = sessions.cookieJar("org.example.a"),
            )
            second.getText(server.url("/read").toString()) shouldBe "session=alpha"

            val otherProvider = DefaultProviderHttpHostService(
                owner = ProviderResourceOwner("org.example.b", "invocation-1"),
                resources = resources,
                policy = policy,
                cookieJar = sessions.cookieJar("org.example.b"),
            )
            otherProvider.getText(server.url("/read").toString()) shouldBe "empty"
        } finally {
            server.close()
        }
    }

    @Test
    fun `closing http broker cancels in flight request`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body("late")
                .bodyDelay(30, TimeUnit.SECONDS)
                .build(),
        )
        server.start()

        try {
            val owner = ProviderResourceOwner("org.example.reader", "invocation-cancel")
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val http = DefaultProviderHttpHostService(
                owner = owner,
                resources = ProviderResourceStore(),
                policy = ProviderNetworkPolicy(
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                cookieJar = ProviderHttpSessionStore().cookieJar(owner.providerId),
            )

            val pending = async { runCatching { http.getText(server.url("/slow").toString()) } }
            server.takeRequest()

            http.close()

            withTimeout(2_000) {
                pending.await().isFailure shouldBe true
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `closed http broker rejects new work`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse(body = "must-not-run"))
        server.start()

        try {
            val owner = ProviderResourceOwner("org.example.reader", "invocation-closed")
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val http = DefaultProviderHttpHostService(
                owner = owner,
                resources = ProviderResourceStore(),
                policy = ProviderNetworkPolicy(
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                cookieJar = ProviderHttpSessionStore().cookieJar(owner.providerId),
            )

            http.close()

            runCatching { http.getText(server.url("/after-close").toString()) }.isFailure shouldBe true
            server.requestCount shouldBe 0
        } finally {
            server.close()
        }
    }

    @Test
    fun `http resource responses remain host side for DOM processing`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/html; charset=utf-8"),
                body = "<html><body><div id=\"probe\">host-side</div></body></html>",
            ),
        )
        server.start()

        try {
            val owner = ProviderResourceOwner("org.example.reader", "invocation-1")
            val resources = ProviderResourceStore()
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val http = DefaultProviderHttpHostService(
                owner = owner,
                resources = resources,
                policy = ProviderNetworkPolicy(
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                cookieJar = ProviderHttpSessionStore().cookieJar(owner.providerId),
            )
            val dom = DefaultProviderDomHostService(owner, resources)

            val handle = http.getResource(server.url("/page").toString())

            dom.selectText(handle, "#probe") shouldBe "host-side"
        } finally {
            server.close()
        }
    }

    @Test
    fun `redirect targets are revalidated before any blocked origin request`() = runBlocking {
        val allowed = MockWebServer()
        val blocked = MockWebServer()
        blocked.enqueue(MockResponse(body = "leak"))
        allowed.start()
        blocked.start()
        allowed.enqueue(
            MockResponse(
                code = 302,
                headers = headersOf("Location", blocked.url("/leak").toString()),
            ),
        )

        try {
            val owner = ProviderResourceOwner("org.example.reader", "invocation-1")
            val origin = allowed.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val http = DefaultProviderHttpHostService(
                owner = owner,
                resources = ProviderResourceStore(),
                policy = ProviderNetworkPolicy(
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
                cookieJar = ProviderHttpSessionStore().cookieJar(owner.providerId),
            )

            (
                runCatching { http.getText(allowed.url("/redirect").toString()) }
                    .exceptionOrNull() is ProviderNetworkPolicyException
                ) shouldBe true
            blocked.requestCount shouldBe 0
        } finally {
            allowed.close()
            blocked.close()
        }
    }
}
