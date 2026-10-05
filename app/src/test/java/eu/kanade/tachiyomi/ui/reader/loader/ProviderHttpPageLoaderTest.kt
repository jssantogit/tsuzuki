package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            allowedOrigins = setOf(server.origin()),
            allowLocalNetwork = true,
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
    fun `reader cancellation remains cancellation instead of becoming page failure`() = runTest {
        val server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse.Builder()
                .body("image-bytes")
                .build(),
        )
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestStarted.countDown()
                check(releaseRequest.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release Provider page request" }
                chain.proceed(chain.request())
            }
            .build()
        val loader = ProviderHttpPageLoader(
            requests = listOf(
                PreparedHttpPage(
                    url = server.url("/cancel.jpg").toString(),
                    allowedOrigins = setOf(server.origin()),
                    allowLocalNetwork = true,
                ),
            ),
            cacheRoot = tempDir.toFile(),
            client = client,
        )
        val failure = CompletableDeferred<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        try {
            val page = loader.getPages().single()
            val job = scope.launch {
                try {
                    loader.loadPage(page)
                } catch (error: Throwable) {
                    failure.complete(error)
                }
            }
            check(requestStarted.await(5, TimeUnit.SECONDS)) { "Provider page request did not start" }

            job.cancel(CancellationException("Reader moved away from page"))
            val error = failure.await()

            (error is CancellationException) shouldBe true
            (error is ProviderHttpPageException) shouldBe false
            (page.status is Page.State.Error) shouldBe false
        } finally {
            releaseRequest.countDown()
            scope.cancel()
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
            requests = listOf(
                PreparedHttpPage(
                    url = server.url("/redirect").toString(),
                    allowedOrigins = setOf(server.origin()),
                    allowLocalNetwork = true,
                ),
            ),
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
    fun `private-network address is rejected at Reader fetch time when local access is not allowed`() = runTest {
        val server = MockWebServer()
        server.start()
        val loader = ProviderHttpPageLoader(
            requests = listOf(
                PreparedHttpPage(
                    url = server.url("/private.jpg").toString(),
                    allowedOrigins = setOf(server.origin()),
                    allowLocalNetwork = false,
                ),
            ),
            cacheRoot = tempDir.toFile(),
        )

        try {
            val page = loader.getPages().single()
            shouldThrow<ProviderHttpPageException> {
                loader.loadPage(page)
            }
            server.requestCount shouldBe 0
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
            requests = listOf(
                PreparedHttpPage(
                    url = server.url("/large").toString(),
                    allowedOrigins = setOf(server.origin()),
                    allowLocalNetwork = true,
                ),
            ),
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

    private fun MockWebServer.origin(): String =
        url("/").let { "${it.scheme}://${it.host}:${it.port}" }
}
