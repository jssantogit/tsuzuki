package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

/**
 * The chapter ordinal and the work identity are independent assertions.
 * Nyaa search relevance or a release's self-declared title cannot override an
 * incompatible work name embedded in the selected archive's own path.
 */
class TorrentChapterMapperWorkIdentityTest {

    private val request = TorrentChapterRequest(
        identity = CanonicalChapterIdentity(type = CanonicalChapterType.REGULAR, baseNumber = 12),
        volume = null,
        titles = listOf("Example Manga", "Alternative Example"),
        chapterNumber = "12",
    )

    @Test
    fun `rejects a different work with the same explicit chapter number even when release claims requested title`() {
        val candidate = candidate("Example Manga release", "pack/Other Series - Chapter 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `rejects contradictory work folder even when release claims requested title`() {
        val candidate = candidate("Example Manga release", "Other Series/Chapter 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `rejects a numbered file without any matching work evidence`() {
        val candidate = candidate("Other Series release", "pack/Chapter 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `rejects other work prefix hiding requested name later in archive label`() {
        val candidate = candidate("Example Manga release", "Other Series Example Manga Chapter 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `accepts chapter explicitly attributed to a canonical alias in its archive name`() {
        val candidate = candidate("Unstructured release", "pack/Alternative Example - Chapter 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!!.single())
    }

    @Test
    fun `accepts chapter in an exact work directory with explicit volume evidence`() {
        val candidate = candidate("Unstructured release", "Example Manga/Volume 2/Chapter 12.cbz")
        val byVolume = request.copy(volume = 2)
        TorrentChapterMapper().map(byVolume, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!!.single())
    }

    @Test
    fun `keeps generic explicit chapter with matching release work as separate evidence`() {
        val candidate = candidate("Example Manga release", "pack/Chapter 12.cbz")
        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!!.single())
    }

    @Test
    fun `rejects full-volume archive even when release declares work and chapter`() {
        val candidate = candidate("Example Manga - Chapter 12", "Example Manga/Volume 2.cbz")
        TorrentChapterMapper().map(request.copy(volume = 2), candidate) shouldBe TorrentChapterFileMatch.None
    }

    private fun candidate(name: String, path: String) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = null,
        displayName = name,
        files = listOf(TorrentCandidateFile(0, path, sizeBytes = 1024)),
    )
}
