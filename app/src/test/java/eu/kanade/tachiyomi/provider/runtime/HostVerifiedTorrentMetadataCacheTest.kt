package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile

class HostVerifiedTorrentMetadataCacheTest {

    private val hash = "0123456789abcdef0123456789abcdef01234567"
    private val url = "https://example.invalid/download/test.torrent"
    private val archive = "Example Chapter 012.cbz"
    private var now = 1_000L

    private fun store(maxBytes: Int = 1024): HostVerifiedTorrentMetadataCache =
        HostVerifiedTorrentMetadataCache(
            clock = { now },
            maxMetadataBytes = maxBytes,
            maxEntries = 2,
            ttlMillis = 1_000L,
            describe = { bytes ->
                if (bytes.contentEquals(byteArrayOf(1, 2, 3))) {
                    VerifiedTorrentDescription(
                        infoHash = hash,
                        files = listOf(VerifiedTorrentFile(0, archive, 3L)),
                    )
                } else {
                    null
                }
            },
        )

    private fun candidate(path: String = archive) = TorrentCandidate(
        infoHash = hash,
        magnetUri = "magnet:?xt=urn:btih:$hash",
        torrentUrl = url,
        displayName = "Example Chapter",
        files = listOf(TorrentCandidateFile(index = 0, path = path, sizeBytes = 3L)),
    )

    private fun request(
        magnet: String? = "magnet:?xt=urn:btih:$hash",
        filePath: String = archive,
        fileSize: Long? = 3L,
    ) = ProviderP2pAcquireRequest(
        operationId = "verified-metadata-test",
        magnetUri = magnet,
        torrentUrl = url,
        infoHash = hash,
        selectedFileIndex = 0,
        selectedFilePath = filePath,
        selectedFileSizeBytes = fileSize,
    )

    @Test
    fun `accepted Host bytes can only be reused by their provider and exact selection`() {
        val cache = store()
        val source = byteArrayOf(1, 2, 3)
        cache.offer("app.tsuzuki.alpha", candidate(), source) shouldBe true
        source[0] = 7

        val first = cache.find("app.tsuzuki.alpha", request())
        first?.toList() shouldBe listOf<Byte>(1, 2, 3)
        first!![0] = 8
        cache.find("app.tsuzuki.alpha", request())?.toList() shouldBe listOf<Byte>(1, 2, 3)
        cache.find("app.tsuzuki.beta", request()) shouldBe null
        cache.find("app.tsuzuki.alpha", request(filePath = "Other.cbz")) shouldBe null
        cache.find("app.tsuzuki.alpha", request(fileSize = 4)) shouldBe null
    }

    @Test
    fun `unverified hash and Provider fabricated file evidence never populate cache`() {
        val cache = store()
        val mismatchedHash = candidate().copy(infoHash = "f".repeat(40))
        cache.offer("app.tsuzuki.alpha", mismatchedHash, byteArrayOf(1, 2, 3)) shouldBe false
        cache.offer("app.tsuzuki.alpha", candidate(path = "Other.cbz"), byteArrayOf(1, 2, 3)) shouldBe false
        cache.offer("app.tsuzuki.alpha", candidate(), byteArrayOf(1, 2, 4)) shouldBe false
        cache.find("app.tsuzuki.alpha", request()) shouldBe null
    }

    @Test
    fun `different URL or magnet info hash does not reuse trusted bytes`() {
        val cache = store()
        cache.offer("app.tsuzuki.alpha", candidate(), byteArrayOf(1, 2, 3)) shouldBe true
        cache.find("app.tsuzuki.alpha", request().copy(torrentUrl = "https://other.invalid/t.torrent")) shouldBe null
        cache.find("app.tsuzuki.alpha", request().copy(infoHash = "f".repeat(40))) shouldBe null
        cache.find(
            "app.tsuzuki.alpha",
            request(magnet = "magnet:?xt=urn:btih:${"f".repeat(40)}"),
        ) shouldBe null
    }

    @Test
    fun `expired and oversized inspected metadata always falls back to magnet`() {
        val cache = store(maxBytes = 3)
        cache.offer("app.tsuzuki.alpha", candidate(), byteArrayOf(1, 2, 3)) shouldBe true
        now += 1_001L
        cache.find("app.tsuzuki.alpha", request()) shouldBe null
        val tooSmall = store(maxBytes = 2)
        tooSmall.offer("app.tsuzuki.alpha", candidate(), byteArrayOf(1, 2, 3)) shouldBe false
        tooSmall.find("app.tsuzuki.alpha", request()) shouldBe null
    }
}
