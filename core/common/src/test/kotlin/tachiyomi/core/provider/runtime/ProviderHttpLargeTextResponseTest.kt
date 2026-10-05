package tachiyomi.core.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test

class ProviderHttpLargeTextResponseTest {

    @Test
    fun `http broker carries bounded feed responses above legacy 64 KiB ceiling`() = runBlocking {
        val server = MockWebServer()
        val body = "<rss>" + "x".repeat(96 * 1024) + "</rss>"
        server.enqueue(MockResponse(body = body))
        server.start()

        try {
            val owner = ProviderResourceOwner("org.example.feed", "invocation-feed")
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

            http.request(
                ProviderHttpRequest(
                    method = ProviderHttpMethod.GET,
                    url = server.url("/feed").toString(),
                ),
            ) shouldBe ProviderHttpResponse(
                statusCode = 200,
                body = body,
            )
        } finally {
            server.close()
        }
    }
}
