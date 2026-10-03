package tachiyomi.core.provider.transport

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TorrentTransportConvergenceTest {

    @Test
    fun `debrid and direct p2p converge on the same readable resource contract`() = runTest {
        val candidate = TorrentCandidate(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            files = listOf(
                TorrentCandidateFile(index = 0, path = "pack/chapter-001.cbz", sizeBytes = 1024),
                TorrentCandidateFile(index = 1, path = "pack/chapter-002.cbz", sizeBytes = 2048),
            ),
        )
        val selected = candidate.files[1]

        val debrid = RecordingTorrentBackend(
            ProviderReadableResource.HttpFile(
                url = "https://cdn.example/chapter-002.cbz",
                headers = mapOf("Authorization" to "Bearer opaque"),
            ),
        )
        val direct = RecordingTorrentBackend(
            ProviderReadableResource.LocalFile(
                absolutePath = "/data/user/0/app.tsuzuki.dev/cache/chapter-002.cbz",
            ),
        )
        val router = TorrentAcquisitionRouter(
            debrid = debrid,
            directP2p = direct,
        )

        val debridResult = router.resolve(
            candidate = candidate,
            fileIndex = selected.index,
            route = TorrentAcquisitionRoute.DEBRID,
        )
        val directResult = router.resolve(
            candidate = candidate,
            fileIndex = selected.index,
            route = TorrentAcquisitionRoute.DIRECT_P2P,
        )

        debridResult.file shouldBe selected
        directResult.file shouldBe selected
        debridResult.resource shouldBe ProviderReadableResource.HttpFile(
            url = "https://cdn.example/chapter-002.cbz",
            headers = mapOf("Authorization" to "Bearer opaque"),
        )
        directResult.resource shouldBe ProviderReadableResource.LocalFile(
            absolutePath = "/data/user/0/app.tsuzuki.dev/cache/chapter-002.cbz",
        )
        debrid.requests shouldBe listOf(candidate to selected)
        direct.requests shouldBe listOf(candidate to selected)
    }

    @Test
    fun `fails closed when requested torrent file is not present`() = runTest {
        val candidate = TorrentCandidate(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            files = listOf(
                TorrentCandidateFile(index = 0, path = "chapter.cbz", sizeBytes = 1024),
            ),
        )
        val backend = RecordingTorrentBackend(
            ProviderReadableResource.LocalFile("/tmp/chapter.cbz"),
        )
        val router = TorrentAcquisitionRouter(
            debrid = backend,
            directP2p = backend,
        )

        val result = runCatching {
            router.resolve(
                candidate = candidate,
                fileIndex = 99,
                route = TorrentAcquisitionRoute.DIRECT_P2P,
            )
        }

        result.isFailure shouldBe true
        backend.requests shouldBe emptyList()
    }

    private class RecordingTorrentBackend(
        private val resource: ProviderReadableResource,
    ) : TorrentAcquisitionBackend {
        val requests = mutableListOf<Pair<TorrentCandidate, TorrentCandidateFile>>()

        override suspend fun acquire(
            candidate: TorrentCandidate,
            file: TorrentCandidateFile,
        ): ProviderReadableResource {
            requests += candidate to file
            return resource
        }
    }
}
