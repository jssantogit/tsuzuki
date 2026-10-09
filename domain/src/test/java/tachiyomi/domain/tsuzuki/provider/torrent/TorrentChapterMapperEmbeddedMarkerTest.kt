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

    @Test
    fun `chapter mapper accepts a title aware chapter token in the sole readable archive`() {
        val request = chapter12.copy(
            titles = listOf("Secret Example Manga"),
            chapterNumber = "12",
        )
        val candidate = candidate(
            file(0, "pack/Secret Example Manga 12.cbz"),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!!.single())
    }

    @Test
    fun `chapter mapper rejects a chapter token that only belongs to the title`() {
        val request = TorrentChapterRequest(
            identity = CanonicalChapterIdentity(
                type = CanonicalChapterType.REGULAR,
                baseNumber = 86,
            ),
            volume = null,
            titles = listOf("86 Eighty-Six"),
            chapterNumber = "86",
        )
        val candidate = candidate(
            file(0, "pack/86 Eighty-Six.cbz"),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `chapter mapper keeps title token fallback disabled for volume requests`() {
        val request = chapter12.copy(
            volume = 2,
            titles = listOf("Secret Example Manga"),
            chapterNumber = "12",
        )
        val candidate = candidate(
            file(0, "pack/Secret Example Manga 12.cbz"),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `chapter mapper requires exactly one readable archive for title token fallback`() {
        val request = chapter12.copy(
            titles = listOf("Secret Example Manga"),
            chapterNumber = "12",
        )
        val candidate = candidate(
            file(0, "pack/Secret Example Manga 12.cbz"),
            file(1, "pack/bonus.cbz"),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
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
