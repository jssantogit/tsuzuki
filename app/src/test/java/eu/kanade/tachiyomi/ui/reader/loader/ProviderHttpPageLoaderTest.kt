package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import java.nio.file.Path

class ProviderHttpPageLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `loads validated Provider page with declared headers and no redirects`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder()
                .body("image-bytes")
                .build(),
        )
        val request = PreparedHttpPage(
            url = server.url("/001.jpg").toString(),
            headers = mapOf("Referer" to "https://reader.example/"),
        )
        val loader = ProviderHttpPageLoader(
            requests = listOf(request),
            cacheRoot = tempDir.toFile(),
            client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )

        try {
            val page = loader.getPages().single()
            loader.loadPage(page)

            page.status shouldBe Page.State.Ready
            page.stream!!.invoke().use { input ->
                input.readBytes().decodeToString() shouldBe "image-bytes"
            }
            server.takeRequest().headers["Referer"] shouldBe "https://reader.example/"
        } finally {
            loader.recycle()
            server.close()
        }
    }

    @Test
    fun `redirect response fails closed instead of escaping validated URL`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", "https://evil.example/page.jpg")
                .build(),
        )
        val loader = ProviderHttpPageLoader(
            requests = listOf(PreparedHttpPage(server.url("/redirect").toString())),
            cacheRoot = tempDir.toFile(),
            client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )

        try {
            val page = loader.getPages().single()
            shouldThrow<ProviderHttpPageException> {
                loader.loadPage(page)
            }
            (page.status is Page.State.Error) shouldBe true
            server.requestCount shouldBe 1
        } finally {
            loader.recycle()
            server.close()
        }
    }

    @Test
    fun `oversized Provider page is rejected and temporary bytes are removed`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder()
                .body("12345")
                .build(),
        )
        val loader = ProviderHttpPageLoader(
            requests = listOf(PreparedHttpPage(server.url("/large").toString())),
            cacheRoot = tempDir.toFile(),
            maxImageBytes = 4,
        )

        try {
            val page = loader.getPages().single()
            shouldThrow<ProviderHttpPageException> {
                loader.loadPage(page)
            }
            tempDir.toFile().walkTopDown()
                .filter { it.isFile }
                .toList()
                .isEmpty() shouldBe true
        } finally {
            loader.recycle()
            server.close()
        }
    }
}
