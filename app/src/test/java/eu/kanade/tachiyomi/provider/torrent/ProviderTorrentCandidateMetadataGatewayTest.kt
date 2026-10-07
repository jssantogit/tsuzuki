package eu.kanade.tachiyomi.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ProviderTorrentCandidateMetadataGatewayTest {

    private val providerId = ProviderId("app.tsuzuki.nyaa")

    @Test
    fun `hydrates authoritative files from Provider authorized torrent metadata`() = runTest {
        val server = MockWebServer()
        val fixture = singleFileTorrent("Chapter 12.cbz", 12L)
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(fixture.bytes))
                .build(),
        )
        server.start()

        try {
            val origin = server.origin()
            val candidate = candidate(
                torrentUrl = server.url("/chapter-12.torrent").toString(),
                infoHash = fixture.infoHash,
            )
            val gateway = gateway(origin)

            val result = gateway.hydrate(providerId, candidate)

            val hydrated = (result as ProviderCallResult.Success).value
            hydrated.infoHash shouldBe fixture.infoHash
            hydrated.files shouldBe listOf(
                TorrentCandidateFile(
                    index = 0,
                    path = "Chapter 12.cbz",
                    sizeBytes = 12L,
                    languages = setOf("en"),
                ),
            )
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
            val gateway = gateway(allowed.origin())
            val candidate = candidate(
                torrentUrl = allowed.url("/start").toString(),
                infoHash = "0".repeat(40),
            )

            val result = gateway.hydrate(providerId, candidate)

            val failure = result as ProviderCallResult.Failure
            failure.error.code shouldBe ProviderErrorCode.NETWORK_POLICY
            failure.error.retryable shouldBe false
            blocked.requestCount shouldBe 0
        } finally {
            allowed.close()
            blocked.close()
        }
    }

    @Test
    fun `metadata byte quota fails closed before decoding`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(ByteArray(2048) { 1 }))
                .build(),
        )
        server.start()

        try {
            val gateway = gateway(
                origin = server.origin(),
                maxMetadataBytes = 1024,
            )
            val candidate = candidate(
                torrentUrl = server.url("/large.torrent").toString(),
                infoHash = "0".repeat(40),
            )

            val result = gateway.hydrate(providerId, candidate)

            val failure = result as ProviderCallResult.Failure
            failure.error.code shouldBe ProviderErrorCode.RESOURCE_LIMIT
            failure.error.retryable shouldBe false
            server.requestCount shouldBe 1
        } finally {
            server.close()
        }
    }

    @Test
    fun `torrent info hash mismatch fails closed`() = runTest {
        val server = MockWebServer()
        val fixture = singleFileTorrent("Chapter 12.cbz", 12L)
        server.enqueue(
            MockResponse.Builder()
                .body(okio.Buffer().write(fixture.bytes))
                .build(),
        )
        server.start()

        try {
            val gateway = gateway(server.origin())
            val candidate = candidate(
                torrentUrl = server.url("/chapter-12.torrent").toString(),
                infoHash = "0".repeat(40),
            )

            val result = gateway.hydrate(providerId, candidate)

            val failure = result as ProviderCallResult.Failure
            failure.error.code shouldBe ProviderErrorCode.MALFORMED_RESULT
            failure.error.retryable shouldBe false
        } finally {
            server.close()
        }
    }

    private fun gateway(
        origin: String,
        maxMetadataBytes: Long = 8L * 1024L * 1024L,
    ) = ProviderTorrentCandidateMetadataGateway(
        baseClient = OkHttpClient(),
        registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = ProviderDescriptor(
                            id = providerId,
                            name = "Nyaa",
                            version = ProviderVersion("test", 1),
                            origin = ProviderOrigin.Repository("test"),
                            runtime = ProviderRuntimeKind.SCRIPT,
                            capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                            permissions = ProviderPermissionSet(
                                network = ProviderNetworkPermission(
                                    origins = setOf(origin),
                                    localNetwork = true,
                                ),
                            ),
                            settings = emptyList(),
                            contentLanguages = emptySet(),
                        ),
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                    ),
                )
            },
        ),
        maxMetadataBytes = maxMetadataBytes,
    )

    private fun candidate(
        torrentUrl: String,
        infoHash: String,
    ) = TorrentCandidate(
        infoHash = infoHash,
        magnetUri = "magnet:?xt=urn:btih:$infoHash",
        torrentUrl = torrentUrl,
        displayName = "Example Manga Chapter 12",
        languages = setOf("en"),
    )

    private fun MockWebServer.origin(): String =
        url("/").let { "${it.scheme}://${it.host}:${it.port}" }

    private fun singleFileTorrent(
        fileName: String,
        size: Long,
    ): TorrentFixture {
        val info = ByteArrayOutputStream().use { output ->
            output.write('d'.code)
            output.write(bencodedString("length"))
            output.write("i${size}e".encodeToByteArray())
            output.write(bencodedString("name"))
            output.write(bencodedString(fileName))
            output.write(bencodedString("piece length"))
            output.write("i16384e".encodeToByteArray())
            output.write(bencodedString("pieces"))
            output.write("20:".encodeToByteArray())
            output.write(ByteArray(20))
            output.write('e'.code)
            output.toByteArray()
        }
        val metadata = ByteArrayOutputStream().use { output ->
            output.write('d'.code)
            output.write(bencodedString("announce"))
            output.write(bencodedString("http://tracker.invalid/announce"))
            output.write(bencodedString("info"))
            output.write(info)
            output.write('e'.code)
            output.toByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-1")
            .digest(info)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return TorrentFixture(metadata, hash)
    }

    private fun bencodedString(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        return "${bytes.size}:".encodeToByteArray() + bytes
    }

    private data class TorrentFixture(
        val bytes: ByteArray,
        val infoHash: String,
    )
}
