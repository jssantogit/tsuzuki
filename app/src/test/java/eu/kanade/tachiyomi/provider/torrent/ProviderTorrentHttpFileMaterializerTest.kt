package eu.kanade.tachiyomi.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentArchiveFormat
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProviderTorrentHttpFileMaterializerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `debrid HTTP archive streams into provider scoped managed Reader content`() = runTest {
        val server = MockWebServer()
        val archive = zip("page-1.jpg", "chapter")
        server.enqueue(MockResponse(body = okio.Buffer().write(archive)))
        server.start()

        try {
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val managed = testManagedStore()
            val materializer = ProviderTorrentHttpFileMaterializer(
                baseClient = OkHttpClient(),
                managedFiles = managed,
                tempRoot = tempDir.resolve("http").toFile(),
                maxFileBytes = 1024 * 1024L,
            )

            val result = materializer.materialize(
                providerId = ProviderId("org.example.debrid"),
                request = request(),
                resource = TorrentReadableResource.HttpFile(
                    url = server.url("/chapter.cbz").toString(),
                    headers = mapOf("Authorization" to "Bearer opaque"),
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
            ) as ProviderCallResult.Success

            result.value.format shouldBe "CBZ"
            result.value.uri.startsWith("managed-uri:managed:") shouldBe true
            server.takeRequest().headers["Authorization"] shouldBe "Bearer opaque"
        } finally {
            server.close()
        }
    }

    @Test
    fun `debrid redirects are revalidated before blocked network access`() = runTest {
        val allowed = MockWebServer()
        val blocked = MockWebServer()
        allowed.start()
        blocked.start()
        allowed.enqueue(
            MockResponse(
                code = 302,
                headers = okhttp3.Headers.headersOf(
                    "Location",
                    blocked.url("/leak.cbz").toString(),
                ),
            ),
        )
        blocked.enqueue(MockResponse(body = okio.Buffer().write(zip("page.jpg", "leak"))))

        try {
            val origin = allowed.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val materializer = ProviderTorrentHttpFileMaterializer(
                baseClient = OkHttpClient(),
                managedFiles = testManagedStore(),
                tempRoot = tempDir.resolve("blocked").toFile(),
            )

            val result = materializer.materialize(
                providerId = ProviderId("org.example.debrid"),
                request = request(),
                resource = TorrentReadableResource.HttpFile(
                    url = allowed.url("/start").toString(),
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
            ) as ProviderCallResult.Failure

            result.error.code shouldBe ProviderErrorCode.NETWORK_POLICY
            blocked.requestCount shouldBe 0
        } finally {
            allowed.close()
            blocked.close()
        }
    }

    @Test
    fun `debrid archive streaming fails closed at byte quota`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse(
                body = okio.Buffer().write(ByteArray(4096) { 1 }),
            ),
        )
        server.start()

        try {
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val materializer = ProviderTorrentHttpFileMaterializer(
                baseClient = OkHttpClient(),
                managedFiles = testManagedStore(),
                tempRoot = tempDir.resolve("quota").toFile(),
                maxFileBytes = 1024L,
            )

            val result = materializer.materialize(
                providerId = ProviderId("org.example.debrid"),
                request = request(),
                resource = TorrentReadableResource.HttpFile(
                    url = server.url("/huge.cbz").toString(),
                    allowedOrigins = setOf(origin),
                    allowLocalNetwork = true,
                ),
            ) as ProviderCallResult.Failure

            result.error.code shouldBe ProviderErrorCode.RESOURCE_LIMIT
        } finally {
            server.close()
        }
    }

    private fun request(): TorrentAcquisitionRequest {
        val file = TorrentCandidateFile(
            index = 1,
            path = "pack/chapter-012.cbz",
            sizeBytes = null,
        )
        return TorrentAcquisitionRequest(
            operationId = "read:canonical-12",
            candidate = TorrentCandidate(
                infoHash = "0123456789abcdef0123456789abcdef01234567",
                magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                torrentUrl = null,
                displayName = "Example",
                files = listOf(file),
            ),
            selectedFile = file,
        )
    }

    private fun testManagedStore() =
        eu.kanade.tachiyomi.provider.runtime.ProviderManagedFileStore(
            root = tempDir.resolve("managed").toFile(),
            uriFactory = { file ->
                "managed-uri:managed:${file.name.substringBefore('.') }.${file.extension}"
            },
            maxFileBytes = 1024 * 1024L,
            maxTotalBytesPerProvider = 2 * 1024 * 1024L,
        )

    private fun zip(
        name: String,
        content: String,
    ): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.encodeToByteArray())
            zip.closeEntry()
        }
        output.toByteArray()
    }
}
