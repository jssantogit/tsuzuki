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
    fun crossHostRedirectsAreRejectedBeforeFollowingThem() = runBlocking<Unit> {
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
    fun rateLimitRetryAfterBlocksAnotherRequestWithoutAutomaticRetry() = runBlocking<Unit> {
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
    fun forbiddenSourceIsLockedForThisApplicationSession() = runBlocking<Unit> {
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
    fun cachedResponseKeepsOriginalFetchTimeAndAvoidsDuplicateRequest() = runBlocking<Unit> {
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
    fun explicitFreshFetchBypassesCachedMangaDexWorkMetadata() = runBlocking<Unit> {
        var requests = 0
        val url = "https://api.mangadex.org/manga/12345678-1234-1234-1234-123456789abc"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("""{"revision":$requests}""".toResponseBody()).build()
        }.build()
        val transport = transport(client)

        transport.fetch(url, null).content shouldBe """{"revision":1}"""
        transport.fetch(url, null).content shouldBe """{"revision":1}"""
        transport.fetchFresh(url, null).content shouldBe """{"revision":2}"""
        requests shouldBe 2
    }

    @Test
    fun malformedMangaDexUUIDsAreRejectedAtTheNativeHttpBoundary() = runBlocking<Unit> {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("{}".toResponseBody()).build()
        }.build()
        val transport = transport(client)
        for (path in listOf("/manga/${"-".repeat(36)}", "/manga/${"a".repeat(36)}/feed")) {
            shouldThrow<IllegalArgumentException> {
                transport.fetch("https://api.mangadex.org$path", null)
            }
        }
        requests shouldBe 0
    }

    @Test
    fun mangaDexReadOnlyAllowlistRejectsUnknownMethodsAndTrailingRoutes() = runBlocking<Unit> {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body("{}".toResponseBody()).build()
        }.build()
        val transport = transport(client)
        val uuid = "12345678-1234-1234-1234-123456789abc"
        val rejected = listOf(
            "https://api.mangadex.org/manga/$uuid/statistics",
            "https://api.mangadex.org/manga/$uuid/feed/extra",
            "https://api.mangadex.org/manga/$uuid/",
        )
        rejected.forEach { url ->
            shouldThrow<IllegalArgumentException> { transport.fetch(url, null) }
        }
        shouldThrow<IllegalArgumentException> {
            transport.fetch("https://api.mangadex.org/manga/$uuid/feed", """{"unexpected":"post"}""")
        }
        requests shouldBe 0
    }

    @Test
    fun oversizedResponseIsNotCachedAndInvalidEndpointsNeverReachHttp() = runBlocking<Unit> {
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
