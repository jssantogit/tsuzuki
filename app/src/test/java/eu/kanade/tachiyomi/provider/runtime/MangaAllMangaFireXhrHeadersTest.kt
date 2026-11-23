package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import okhttp3.Request
import org.junit.jupiter.api.Test

class MangaAllMangaFireXhrHeadersTest {
    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .build()

    @Test
    fun `signed MangaFire API GET receives same origin XHR metadata only`() {
        val original = request("https://mangafire.to/api/titles?keyword=punpun&vrf=fixture")
        val aligned = MangaAllMangaFireXhrHeaders.align(original)
        aligned.url shouldBe original.url
        aligned.header("Accept") shouldBe "application/json"
        aligned.header("Referer") shouldBe "https://mangafire.to/"
        aligned.header("Sec-Fetch-Site") shouldBe "same-origin"
        aligned.header("Sec-Fetch-Mode") shouldBe "cors"
        aligned.header("Sec-Fetch-Dest") shouldBe "empty"
        aligned.header("Cookie") shouldBe null
        aligned.header("Authorization") shouldBe null
        aligned.header("Origin") shouldBe null
        original.header("Referer") shouldBe null
    }

    @Test
    fun `provider explicit browser headers take precedence and result is idempotent`() {
        val original = request("https://mangafire.to/api/titles").newBuilder()
            .header("Referer", "https://mangafire.to/title/test/")
            .header("Sec-Fetch-Mode", "custom")
            .build()
        val aligned = MangaAllMangaFireXhrHeaders.align(original)
        aligned.header("Referer") shouldBe "https://mangafire.to/title/test/"
        aligned.header("Sec-Fetch-Mode") shouldBe "custom"
        MangaAllMangaFireXhrHeaders.align(aligned) shouldBe aligned
    }

    @Test
    fun `non API and foreign origins cannot gain source browser headers`() {
        for (url in listOf(
            "http://mangafire.to/api/titles",
            "https://mangafire.to:8443/api/titles",
            "https://sub.mangafire.to/api/titles",
            "https://mangafire.to.example.org/api/titles",
            "https://api.mangadex.org/api/titles",
            "https://mangadot.net/api/titles",
            "https://mangafire.to/title/test/",
            "https://mangafire.to/apifake/titles",
        )) {
            val original = request(url)
            MangaAllMangaFireXhrHeaders.align(original) shouldBe original
        }
        val post = request("https://mangafire.to/api/titles").newBuilder()
            .post("{}".toByteArray().let { okhttp3.RequestBody.create(null, it) })
            .build()
        MangaAllMangaFireXhrHeaders.align(post) shouldBe post
    }
}
