package eu.kanade.tachiyomi.data.tsuzuki.inventory

import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import java.io.IOException

class NativeInventoryReleaseTransportTest {
    private val url = "https://api.mangaupdates.com/v1/releases/search"

    private fun transport(client: OkHttpClient): NativeInventoryReleaseTransport {
        val network = mockk<NetworkHelper>()
        every { network.client } returns client
        return NativeInventoryReleaseTransport(network)
    }

    @Test
    fun crossHostRedirectsAreRejectedBeforeFollowingThem() = runBlocking {
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requested += chain.request().url.host
            if (chain.request().url.host == "api.mangaupdates.com") {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302)
                    .message("Found").header("Location", "https://untrusted.example/inventory")
                    .body("".toResponseBody()).build()
            } else {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("OK").body("{}".toResponseBody()).build()
            }
        }.build()
        shouldThrow<IOException> {
            transport(client).fetch(url, """{"search":"123"}""")
        }
        requested shouldBe listOf("api.mangaupdates.com")
    }

    @Test
    fun rateLimitRetryAfterBlocksAnotherRequestWithoutAutomaticRetry() = runBlocking {
        var requested = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requested++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429)
                .message("Too Many Requests").header("Retry-After", "120")
                .body("rate limited".toResponseBody()).build()
        }.build()
        val transport = transport(client)
        shouldThrow<IOException> { transport.fetch(url, """{"search":"123"}""") }
        shouldThrow<IOException> { transport.fetch(url, """{"search":"456"}""") }
        requested shouldBe 1
    }

    @Test
    fun forbiddenSourceIsLockedForThisApplicationSession() = runBlocking {
        var requested = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requested++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403)
                .message("Forbidden").body("".toResponseBody()).build()
        }.build()
        val transport = transport(client)
        shouldThrow<IOException> { transport.fetch(url, "{}") }
        shouldThrow<IOException> { transport.fetch(url, """{"page":2}""") }
        requested shouldBe 1
    }

    @Test
    fun cachedResponseKeepsOriginalFetchTimeAndAvoidsDuplicateRequest() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""{"results":[]}""".toResponseBody()).build()
        }.build()
        val transport = transport(client)
        val first = transport.fetch(url, """{"search":"123"}""")
        val cached = transport.fetch(url, """{"search":"123"}""")

        requests shouldBe 1
        cached shouldBe first
        cached.content shouldBe """{"results":[]}"""
    }

    @Test
    fun oversizedResponseIsNotCachedAndInvalidEndpointsNeverReachHttp() = runBlocking {
        var requested = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requested++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("x".repeat(513_000).toResponseBody()).build()
        }.build()
        val transport = transport(client)
        shouldThrow<IllegalArgumentException> {
            transport.fetch("https://api.mangaupdates.com:9443/v1/releases/search", "{}")
        }
        shouldThrow<IllegalArgumentException> {
            transport.fetch("https://evil.example/v1/releases/search", "{}")
        }
        requested shouldBe 0
        shouldThrow<IOException> { transport.fetch(url, "{}") }
        requested shouldBe 1
    }
}
