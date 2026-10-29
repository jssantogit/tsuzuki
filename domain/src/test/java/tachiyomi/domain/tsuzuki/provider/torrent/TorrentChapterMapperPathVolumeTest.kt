package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class TorrentChapterMapperPathVolumeTest {

    private val request = TorrentChapterRequest(
        identity = CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = 12),
        volume = 2,
        titles = listOf("Example Manga"),
        chapterNumber = "12",
    )

    @Test
    fun `explicit chapter inside a volume folder matches the requested volume`() {
        val candidate = candidate(
            "Example Manga/Volume 1/Chapter 12.cbz",
            "Example Manga/Volume 2/Chapter 12.cbz",
            "Example Manga/Volume 3/Chapter 12.cbz",
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    @Test
    fun `explicit volume folder can disambiguate title aware chapter files`() {
        val candidate = candidate(
            "Example Manga/Vol. 1/Example Manga 012.cbz",
            "Example Manga/Vol. 2/Example Manga 012.cbz",
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    @Test
    fun `conflicting explicit volumes in filename and directory fail closed`() {
        val candidate = candidate("Example Manga/Volume 2/Vol. 3 Ch. 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `nested contradictory volume directories fail closed`() {
        val candidate = candidate("Example Manga/Volume 2/Volume 3/Chapter 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `volume only archives never become individual chapters`() {
        val candidate = candidate("Example Manga/Volume 2.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `unmarked volume and arbitrary numbered file remain unproven`() {
        val candidate = candidate("Example Manga/2/Unrelated 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `chapter directory with numbered images is an exact readable chapter selection`() {
        val chapter1 = request.copy(
            identity = CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = 1),
            volume = null,
            titles = listOf("Tokyo Ghoul"),
            chapterNumber = "1",
        )
        val candidate = candidate(
            "Tokyo Ghoul Manga/Chapter 01/001.jpg",
            "Tokyo Ghoul Manga/Chapter 01/002.png",
            "Tokyo Ghoul Manga/Chapter 05/001.jpg",
            "Tokyo Ghoul Manga/Chapter 11/001.jpg",
        )

        TorrentChapterMapper().select(chapter1, candidate) shouldBe
            TorrentChapterPayloadMatch.Exact(
                TorrentChapterPayload.ImageDirectory(
                    prefix = "Tokyo Ghoul Manga/Chapter 01",
                    files = candidate.files!!.take(2),
                ),
            )
    }

    @Test
    fun `chapter directory cannot borrow the identity of a different manga`() {
        val chapter1 = request.copy(
            identity = CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = 1),
            volume = null,
            titles = listOf("Tokyo Ghoul"),
            chapterNumber = "1",
        )
        val candidate = candidate("Tokyo Ghoul Re/Chapter 01/001.jpg")
        TorrentChapterMapper().select(chapter1, candidate) shouldBe TorrentChapterPayloadMatch.None
    }

    @Test
    fun `alternate release folder cannot be silently treated as primary chapter`() {
        val chapter1 = request.copy(
            identity = CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = 1),
            volume = null,
            titles = listOf("Tokyo Ghoul"),
            chapterNumber = "1",
        )
        val candidate = candidate("Tokyo Ghoul Manga/Alternate Chapter Releases/Chapter 01/001.jpg")
        TorrentChapterMapper().select(chapter1, candidate) shouldBe TorrentChapterPayloadMatch.None
    }

    @Test
    fun `a directory request never selects files belonging to a different chapter`() {
        val chapter1 = request.copy(
            identity = CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = 1),
            volume = null,
            titles = listOf("Tokyo Ghoul"),
            chapterNumber = "1",
        )
        val candidate = candidate("Tokyo Ghoul Manga/Chapter 11/001.jpg")
        TorrentChapterMapper().select(chapter1, candidate) shouldBe TorrentChapterPayloadMatch.None
    }

    @Test
    fun `unsafe image paths are rejected before chapter-directory selection`() {
        val chapter1 = request.copy(
            identity = CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = 1),
            volume = null,
            titles = listOf("Tokyo Ghoul"),
            chapterNumber = "1",
        )
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            candidate(
                "Tokyo Ghoul Manga/Chapter 01/001.jpg",
                "Tokyo Ghoul Manga/Chapter 01/../002.png",
            )
        }
    }

    private fun candidate(vararg paths: String) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = null,
        displayName = "Example Manga release",
        files = paths.mapIndexed { index, path ->
            TorrentCandidateFile(index = index, path = path, sizeBytes = 1024)
        },
    )
}
