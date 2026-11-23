package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import okhttp3.Request
import org.junit.jupiter.api.Test

class MangaAllMangaFireUserAgentTest {

    private val browserUa =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .build()

    @Test
    fun `alignment requires both exact signed provider grants`() {
        val network = setOf("https://mangafire.to", "https://api.mangadex.org")
        val browser = setOf("https://mangafire.to", "https://*.mangafire.to")
        MangaAllMangaFireUserAgent.enabled("app.tsuzuki.mangaall", network, browser) shouldBe true
        MangaAllMangaFireUserAgent.enabled("app.tsuzuki.mangafire", network, browser) shouldBe false
        MangaAllMangaFireUserAgent.enabled("app.tsuzuki.mangaall.extra", network, browser) shouldBe false
        MangaAllMangaFireUserAgent.enabled(
            "app.tsuzuki.mangaall",
            setOf("https://*.mangafire.to"),
            browser,
        ) shouldBe false
        MangaAllMangaFireUserAgent.enabled("app.tsuzuki.mangaall", network, emptySet()) shouldBe false
    }

    @Test
    fun `exact HTTPS MangaFire request inherits only browser user agent`() {
        val original = request("https://mangafire.to/api/titles?keyword=fixture")
        val aligned = MangaAllMangaFireUserAgent.align(original, browserUa)
        aligned.header("User-Agent") shouldBe browserUa
        aligned.header("Accept") shouldBe "application/json"
        aligned.header("Cookie") shouldBe null
        aligned.header("Authorization") shouldBe null
        aligned.url shouldBe original.url
        original.header("User-Agent") shouldBe null
    }

    @Test
    fun `explicit source user agent is never overwritten`() {
        val original = request("https://mangafire.to/api/titles")
            .newBuilder()
            .header("user-agent", "ProviderCustom/1")
            .build()
        MangaAllMangaFireUserAgent.align(original, browserUa).header("User-Agent") shouldBe "ProviderCustom/1"
    }

    @Test
    fun `foreign hosts and insecure schemes remain unchanged`() {
        for (url in listOf(
            "https://evil-mangafire.to/api/titles",
            "https://mangafire.to.example.org/api/titles",
            "https://sub.mangafire.to/api/titles",
            "http://mangafire.to/api/titles",
            "https://mangafire.to:8443/api/titles",
            "https://api.mangadex.org/manga",
            "https://mangadot.net/search",
        )) {
            val original = request(url)
            MangaAllMangaFireUserAgent.align(original, browserUa) shouldBe original
        }
    }

    @Test
    fun `malformed and unavailable browser identities never modify requests`() {
        val original = request("https://mangafire.to/api/titles")
        for (ua in listOf("", "okhttp/5.0", "Mozilla/5.0\r\nX-Leak: yes", "Mozilla/5.0" + "a".repeat(520))) {
            MangaAllMangaFireUserAgent.align(original, ua) shouldBe original
        }
    }
}
