package eu.kanade.tachiyomi.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile

class HttpProviderTorrentMetadataInspectorTest {

    @Test
    fun `downloads bounded Provider authorized torrent metadata`() = runTest {
        val server = MockWebServer()
        val metadata = "torrent-metadata".encodeToByteArray()
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(metadata))
                .build(),
        )
        server.start()

        try {
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val expectedFiles = listOf(
                TorrentCandidateFile(0, "pack/Chapter 11.cbz", 11L),
                TorrentCandidateFile(1, "pack/Chapter 12.cbz", 12L),
            )
            var decodedBytes: ByteArray? = null
            val inspector = HttpProviderTorrentMetadataInspector(
                baseClient = OkHttpClient(),
                maxMetadataBytes = 1024,
                decoder = { bytes, candidate ->
                    decodedBytes = bytes
                    candidate.copy(files = expectedFiles)
                },
            )
            val candidate = candidate(server.url("/123.torrent").toString())

            val hydrated = inspector.inspect(
                descriptor = descriptor(origin, allowLocalNetwork = true),
                candidate = candidate,
            )

            decodedBytes?.contentEquals(metadata) shouldBe true
            hydrated?.files shouldBe expectedFiles
            server.requestCount shouldBe 1
        } finally {
            server.close()
        }
    }

    @Test
    fun `redirect cannot escape Provider network authority`() = runTest {
        val allowed = MockWebServer()
        val blocked = MockWebServer()
        allowed.start()
        blocked.start()
        allowed.enqueue(
            MockResponse(
                code = 302,
                headers = headersOf(
                    "Location",
                    blocked.url("/leak.torrent").toString(),
                ),
            ),
        )
        blocked.enqueue(
            MockResponse.Builder()
                .body("should-not-be-read")
                .build(),
        )

        try {
            val origin = allowed.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            val inspector = HttpProviderTorrentMetadataInspector(
                baseClient = OkHttpClient(),
                decoder = { _, candidate -> candidate },
            )

            val hydrated = inspector.inspect(
                descriptor = descriptor(origin, allowLocalNetwork = true),
                candidate = candidate(allowed.url("/start").toString()),
            )

            hydrated shouldBe null
            blocked.requestCount shouldBe 0
        } finally {
            allowed.close()
            blocked.close()
        }
    }

    @Test
    fun `metadata byte quota fails closed`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(ByteArray(2048) { 1 }))
                .build(),
        )
        server.start()

        try {
            val origin = server.url("/").let { "${it.scheme}://${it.host}:${it.port}" }
            var decoded = false
            val inspector = HttpProviderTorrentMetadataInspector(
                baseClient = OkHttpClient(),
                maxMetadataBytes = 1024,
                decoder = { _, candidate ->
                    decoded = true
                    candidate
                },
            )

            val hydrated = inspector.inspect(
                descriptor = descriptor(origin, allowLocalNetwork = true),
                candidate = candidate(server.url("/large.torrent").toString()),
            )

            hydrated shouldBe null
            decoded shouldBe false
        } finally {
            server.close()
        }
    }

    private fun candidate(url: String) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = url,
        displayName = "Example Manga Chapter 12",
    )

    private fun descriptor(
        origin: String,
        allowLocalNetwork: Boolean,
    ) = ProviderDescriptor(
        id = ProviderId("app.tsuzuki.nyaa"),
        name = "Nyaa",
        version = ProviderVersion("test", 1),
        origin = ProviderOrigin.Repository("test"),
        runtime = ProviderRuntimeKind.SCRIPT,
        capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
        permissions = ProviderPermissionSet(
            network = ProviderNetworkPermission(
                origins = setOf(origin),
                localNetwork = allowLocalNetwork,
            ),
        ),
        settings = emptyList(),
        contentLanguages = emptySet(),
    )
}
