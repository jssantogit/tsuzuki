package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile

class TorrentInventoryEvidenceProjectorTest {
    private val providerId = ProviderId("app.tsuzuki.nyaa")

    @Test
    fun exactChapterFoldersProduceOnlyShadowReleaseObservations() {
        val torrent = candidate(
            "Tokyo Ghoul Manga/Chapter 01/001.jpg",
            "Tokyo Ghoul Manga/Chapter 01/002.png",
            "Tokyo Ghoul Manga/Chapter 05/001.jpg",
            "Tokyo Ghoul Manga/Chapter 11/001.jpg",
        )
        val batches = TorrentInventoryEvidenceProjector().project(
            canonicalTitleId = "tokyo-ghoul",
            displayTitle = "Tokyo Ghoul",
            providerId = providerId,
            candidates = listOf(torrent),
            language = "en",
            observedAt = 1234,
        )
        batches.size shouldBe 1
        val batch = batches.single()
        batch.claims.map { it.label } shouldBe listOf("1", "5", "11")
        batch.claims.all {
            it.kind == InventoryClaimKind.INDIVIDUAL &&
                it.derivation == InventoryDerivation.RELEASE_RECORD &&
                it.provenance == null
        } shouldBe true
        batch.scope.workIdentityVerified shouldBe false
        batch.scope.editionIdentityVerified shouldBe false
        batch.scope.editionKey shouldBe null
        summarizeInventory(batch.claims.map { InventoryStoredClaim(batch.scope, it) })
            .individuallyAdmissibleClaims shouldBe 0
    }

    @Test
    fun anotherWorkAndUnnumberedFilesNeverCreateChapterEvidence() {
        val torrent = candidate(
            "Tokyo Ghoul Re/Chapter 01/001.jpg",
            "Tokyo Ghoul Manga/Chapter 02/cover.jpg",
            "Tokyo Ghoul Manga/Other/003.jpg",
        )
        // An explicitly numbered page 2 under the correct work is a valid
        // provisional observation, but an unrelated work and unnumbered folder are not.
        val result = TorrentInventoryEvidenceProjector().project(
            canonicalTitleId = "tokyo-ghoul",
            displayTitle = "Tokyo Ghoul",
            providerId = providerId,
            candidates = listOf(torrent),
            language = "en",
            observedAt = 1234,
        )
        result.single().claims.map { it.label } shouldBe listOf("2")
    }

    @Test
    fun unknownMetadataNeverFabricatesChaptersFromReleaseTitle() {
        val torrent = TorrentCandidate(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = null,
            torrentUrl = null,
            displayName = "Tokyo Ghoul Manga Chapter 1 to 143 Complete",
            files = null,
        )
        TorrentInventoryEvidenceProjector().project(
            canonicalTitleId = "tokyo-ghoul",
            displayTitle = "Tokyo Ghoul",
            providerId = providerId,
            candidates = listOf(torrent),
            language = null,
            observedAt = 1,
        ) shouldBe emptyList()
    }

    private fun candidate(vararg paths: String) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = null,
        torrentUrl = null,
        displayName = "Tokyo Ghoul Manga - Complete",
        files = paths.mapIndexed { index, path ->
            TorrentCandidateFile(index, path, sizeBytes = 1024)
        },
    )
}
