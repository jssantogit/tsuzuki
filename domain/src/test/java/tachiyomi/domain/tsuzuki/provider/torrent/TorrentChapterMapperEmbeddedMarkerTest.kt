package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class TorrentChapterMapperEmbeddedMarkerTest {

    private val chapter12 = TorrentChapterRequest(
        identity = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
        ),
        volume = null,
    )

    @Test
    fun `matches an explicit chapter marker after a release title`() {
        val candidate = candidate("Series Name - Ch. 12 - Release Group.cbz")

        TorrentChapterMapper().map(chapter12, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!!.single())
    }

    @Test
    fun `keeps arbitrary title numbers fail closed`() {
        val candidate = candidate("Series Name 12 - Release Group.cbz")

        TorrentChapterMapper().map(chapter12, candidate) shouldBe TorrentChapterFileMatch.None
    }

    private fun candidate(path: String) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = null,
        displayName = "Example release",
        files = listOf(
            TorrentCandidateFile(
                index = 0,
                path = path,
                sizeBytes = 1024,
            ),
        ),
    )
}
