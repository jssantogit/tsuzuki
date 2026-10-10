package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class TorrentChapterMapperZeroPaddingTest {

    private val request = TorrentChapterRequest(
        identity = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
        ),
        volume = null,
        titles = listOf("Acceptance Series"),
        chapterNumber = "12",
    )

    @Test
    fun `title aware chapter token accepts equivalent zero padding`() {
        val candidate = candidate(
            file(0, "pack/Acceptance Series 011.cbz"),
            file(1, "pack/Acceptance Series 012.cbz"),
            file(2, "pack/Acceptance Series 013.cbz"),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    @Test
    fun `zero padded title token remains ambiguous with another exact chapter archive`() {
        val candidate = candidate(
            file(0, "pack/Acceptance Series 012.cbz"),
            file(1, "alternate/chapter-012.zip"),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Ambiguous(candidate.files!!)
    }

    private fun candidate(vararg files: TorrentCandidateFile) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = null,
        displayName = "Acceptance Series release",
        files = files.toList(),
    )

    private fun file(index: Int, path: String) = TorrentCandidateFile(
        index = index,
        path = path,
        sizeBytes = 1024,
        languages = setOf("en"),
    )
}
