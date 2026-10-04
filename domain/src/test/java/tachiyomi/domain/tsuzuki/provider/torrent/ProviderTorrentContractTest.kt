package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class ProviderTorrentContractTest {

    private val chapter12 = TorrentChapterRequest(
        identity = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
        ),
        volume = null,
        preferredLanguages = emptySet(),
    )

    @Test
    fun `torrent candidate requires a resolvable identity and safe file metadata`() {
        shouldThrow<IllegalArgumentException> {
            TorrentCandidate(
                infoHash = null,
                magnetUri = null,
                torrentUrl = null,
                displayName = "Pack",
            )
        }

        shouldThrow<IllegalArgumentException> {
            TorrentCandidateFile(
                index = 0,
                path = "../chapter-012.cbz",
                sizeBytes = 1024,
            )
        }

        TorrentCandidate(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = null,
            torrentUrl = null,
            displayName = "Pack",
            files = listOf(
                TorrentCandidateFile(
                    index = 0,
                    path = "pack/chapter-012.cbz",
                    sizeBytes = 1024,
                ),
            ),
        ).files?.single()?.path shouldBe "pack/chapter-012.cbz"
    }

    @Test
    fun `chapter mapper selects one explicit matching chapter file`() {
        val candidate = candidate(
            files = listOf(
                file(0, "pack/chapter-011.cbz"),
                file(1, "pack/chapter-012.cbz"),
                file(2, "pack/chapter-013.cbz"),
            ),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    @Test
    fun `chapter mapper uses explicit volume evidence to disambiguate equal chapter numbers`() {
        val request = chapter12.copy(volume = 2)
        val candidate = candidate(
            files = listOf(
                file(0, "pack/Vol. 1 Ch. 12.cbz"),
                file(1, "pack/Vol. 2 Ch. 12.cbz"),
            ),
        )

        TorrentChapterMapper().map(request, candidate) shouldBe
            TorrentChapterFileMatch.Exact(candidate.files!![1])
    }

    @Test
    fun `chapter mapper fails closed when more than one file remains plausible`() {
        val candidate = candidate(
            files = listOf(
                file(0, "release-a/chapter-012.cbz"),
                file(1, "release-b/chapter-012.zip"),
            ),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe
            TorrentChapterFileMatch.Ambiguous(candidate.files!!)
    }

    @Test
    fun `chapter mapper does not treat arbitrary title numbers as chapter identity`() {
        val candidate = candidate(
            files = listOf(
                file(0, "Series Name 12.cbz"),
            ),
        )

        TorrentChapterMapper().map(chapter12, candidate) shouldBe TorrentChapterFileMatch.None
    }

    @Test
    fun `acquisition policy keeps debrid preference separate from direct p2p consent`() {
        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            hasUsableDebrid = true,
            directP2pAllowed = true,
        ) shouldBe TorrentAcquisitionDecision.Routes(
            listOf(
                TorrentAcquisitionRoute.DEBRID,
                TorrentAcquisitionRoute.DIRECT_P2P,
            ),
        )

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            hasUsableDebrid = true,
            directP2pAllowed = false,
        ) shouldBe TorrentAcquisitionDecision.Routes(
            listOf(TorrentAcquisitionRoute.DEBRID),
        )

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            hasUsableDebrid = false,
            directP2pAllowed = false,
        ) shouldBe TorrentAcquisitionDecision.DirectP2pConsentRequired

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.DEBRID_ONLY,
            hasUsableDebrid = false,
            directP2pAllowed = true,
        ) shouldBe TorrentAcquisitionDecision.Unavailable
    }

    @Test
    fun `p2p only never bypasses the explicit direct p2p preference`() {
        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.P2P_ONLY,
            hasUsableDebrid = true,
            directP2pAllowed = false,
        ) shouldBe TorrentAcquisitionDecision.DirectP2pConsentRequired

        TorrentAcquisitionPolicy.resolve(
            preference = TorrentAcquisitionPreference.P2P_ONLY,
            hasUsableDebrid = false,
            directP2pAllowed = true,
        ) shouldBe TorrentAcquisitionDecision.Routes(
            listOf(TorrentAcquisitionRoute.DIRECT_P2P),
        )
    }

    private fun candidate(files: List<TorrentCandidateFile>) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        torrentUrl = null,
        displayName = "Example pack",
        languages = setOf("en"),
        files = files,
    )

    private fun file(index: Int, path: String) = TorrentCandidateFile(
        index = index,
        path = path,
        sizeBytes = 1024,
    )
}
