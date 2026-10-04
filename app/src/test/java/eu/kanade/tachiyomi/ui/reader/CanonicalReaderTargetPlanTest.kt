package eu.kanade.tachiyomi.ui.reader

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage

class CanonicalReaderTargetPlanTest {

    @Test
    fun `local archive plan does not require Mihon coordinates`() {
        val plan = planCanonicalReaderTarget(
            canonicalChapterId = "chapter-1",
            target = PreparedChapterContent.LocalArchive("content://downloads/chapter-1.cbz"),
        ).shouldBeInstanceOf<CanonicalReaderTargetPlan.Local>()

        plan.canonicalChapterId shouldBe "chapter-1"
        plan.uri shouldBe "content://downloads/chapter-1.cbz"
        plan.format shouldBe CanonicalLocalReaderFormat.ARCHIVE
    }

    @Test
    fun `local directory plan stays provider neutral`() {
        val plan = planCanonicalReaderTarget(
            canonicalChapterId = "chapter-2",
            target = PreparedChapterContent.LocalDirectory("content://downloads/chapter-2"),
        ).shouldBeInstanceOf<CanonicalReaderTargetPlan.Local>()

        plan.uri shouldBe "content://downloads/chapter-2"
        plan.format shouldBe CanonicalLocalReaderFormat.DIRECTORY
    }

    @Test
    fun `provider HTTP pages remain provider neutral`() {
        val pages = listOf(
            PreparedHttpPage(
                url = "https://cdn.example/001.jpg",
                headers = mapOf("Referer" to "https://reader.example/"),
                allowedOrigins = setOf("https://cdn.example"),
            ),
        )

        val plan = planCanonicalReaderTarget(
            canonicalChapterId = "chapter-http",
            target = PreparedChapterContent.HttpPages(pages),
        ).shouldBeInstanceOf<CanonicalReaderTargetPlan.HttpPages>()

        plan.canonicalChapterId shouldBe "chapter-http"
        plan.pages shouldBe pages
    }

    @Test
    fun `prepared Reader HTTP boundary rejects routing headers`() {
        listOf(
            "Host",
            "Connection",
            "Content-Length",
            "Keep-Alive",
            "Proxy-Authenticate",
            "Proxy-Authorization",
            "Proxy-Connection",
            "TE",
            "Trailer",
            "Transfer-Encoding",
            "Upgrade",
        ).forEach { header ->
            shouldThrow<IllegalArgumentException> {
                PreparedHttpPage(
                    url = "https://cdn.example/001.jpg",
                    headers = mapOf(header to "unsafe"),
                    allowedOrigins = setOf("https://cdn.example"),
                )
            }
        }

        PreparedHttpPage(
            url = "https://cdn.example/001.jpg",
            headers = mapOf(
                "Referer" to "https://reader.example/",
                "User-Agent" to "Tsuzuki",
                "Cookie" to "session=value",
                "Authorization" to "Bearer token",
            ),
            allowedOrigins = setOf("https://cdn.example"),
        ).headers.keys shouldBe setOf("Referer", "User-Agent", "Cookie", "Authorization")
    }

    @Test
    fun `canonical EPUB download preserves explicit format`() {
        val plan = planCanonicalReaderTarget(
            canonicalChapterId = "chapter-3",
            target = PreparedChapterContent.CanonicalDownload(
                uri = "content://downloads/chapter-3",
                format = "EPUB",
            ),
        ).shouldBeInstanceOf<CanonicalReaderTargetPlan.Local>()

        plan.format shouldBe CanonicalLocalReaderFormat.EPUB
    }
}
