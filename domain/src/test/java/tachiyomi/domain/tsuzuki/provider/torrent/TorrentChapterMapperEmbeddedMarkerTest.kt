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
    fun `chapter mapper accepts an explicit chapter marker embedded after the release title`() {
        val candidate = candidate(
            file(0, "pack/Secret Example Manga - Chapter 12.cbz"),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!!.single())
    }

    @Test
    fun `chapter mapper keeps arbitrary title numbers fail closed`() {
        val candidate = candidate(
            file(0, "pack/Secret Example Manga 12.cbz"),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `chapter mapper preserves volume evidence before an embedded chapter marker`() {
        val request = chapter12.copy(volume = 2)
        val candidate = candidate(
            file(0, "pack/Secret Example Manga - Vol. 1 - Chapter 12.cbz"),
            file(1, "pack/Secret Example Manga - Vol. 2 - Chapter 12.cbz"),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    private fun candidate(vararg files: TorrentCandidateFile) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = null,
        displayName = "Secret Example Manga release",
        files = files.toList(),
    )

    private fun file(index: Int, path: String) = TorrentCandidateFile(
        index = index,
        path = path,
        sizeBytes = 1024,
    )
}
