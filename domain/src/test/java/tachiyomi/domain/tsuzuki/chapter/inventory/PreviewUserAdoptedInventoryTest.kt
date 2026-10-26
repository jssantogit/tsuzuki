package tachiyomi.domain.tsuzuki.chapter.inventory

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class PreviewUserAdoptedInventoryTest {
    private val source = InventoryCandidateProvenance(
        sourceKind = InventoryCandidateSourceKind.USER_SELECTED_EPUB3_TOC,
        originReference = "user-selected:edition",
        exactRevision = "r1",
        snapshotDigest = "sha256:" + "a".repeat(64),
        sourceUnitId = "chapter-1.xhtml",
        structuralCategory = "toc-chapter",
    )
    private val scope = InventoryScope(
        canonicalTitleId = "title-1",
        producerId = "epub3-user-selected",
        externalWorkId = "selected-book",
        editionKey = "edition-1",
        language = "ja",
        workIdentityVerified = false,
        editionIdentityVerified = false,
    )
    private fun claim(id: String = "chapter-one", label: String = "Chapter 1") = InventoryStoredClaim(
        scope = scope,
        claim = InventoryClaim(
            sourceRecordId = id,
            label = label,
            volumeLabel = null,
            language = "ja",
            kind = InventoryClaimKind.INDIVIDUAL,
            derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
            upstreamSourceId = "r1",
            observedAt = 10,
            provenance = source.copy(sourceUnitId = "$id.xhtml"),
        ),
    )

    @Test
    fun selectedEditionProducesReviewWithExactIdentityAndSourceDigest() {
        val result = PreviewUserAdoptedInventory().execute(
            scope = scope,
            storedClaims = listOf(claim()),
            selectedSourceRecordIds = listOf("chapter-one"),
            existingChapters = emptyList(),
        )
        (result is UserAdoptionPreview.Ready) shouldBe true
        val ready = result as UserAdoptionPreview.Ready
        ready.units.single().identity.baseNumber shouldBe 1
        ready.units.single().provenance.snapshotDigest shouldBe source.snapshotDigest
        ready.fingerprint.startsWith("sha256:") shouldBe true
        ready.fingerprint.length shouldBe 71
    }

    @Test
    fun updatedSourceRevisionOrLabelInvalidatesReviewFingerprint() {
        val preview = PreviewUserAdoptedInventory()
        fun checked(stored: InventoryStoredClaim) = preview.execute(
            scope,
            listOf(stored),
            listOf("chapter-one"),
            emptyList(),
        ) as UserAdoptionPreview.Ready
        val original = checked(claim())
        val claimWithNewRevision = claim().claim.copy(
            provenance = source.copy(exactRevision = "r2"),
        )
        val changed = checked(claim().copy(claim = claimWithNewRevision))
        val relabeled = checked(claim(label = "Chapter 2"))
        (original.fingerprint == changed.fingerprint) shouldBe false
        (original.fingerprint == relabeled.fingerprint) shouldBe false
    }

    @Test
    fun releaseRecordsTotalsRangesAndMismatchedLanguageAreRejected() {
        val preview = PreviewUserAdoptedInventory()
        val sourceClaim = claim()
        val rejected = listOf(
            sourceClaim.copy(
                claim = sourceClaim.claim.copy(
                    derivation = InventoryDerivation.RELEASE_RECORD,
                    provenance = null,
                ),
            ),
            sourceClaim.copy(claim = sourceClaim.claim.copy(kind = InventoryClaimKind.DECLARED_TOTAL)),
            claim(label = "Chapter 1-5"),
            claim(label = "Chapter 1v2"),
            sourceClaim.copy(claim = sourceClaim.claim.copy(language = "en")),
        )
        rejected.forEach { stored ->
            val result = preview.execute(scope, listOf(stored), listOf("chapter-one"), emptyList())
            (result is UserAdoptionPreview.Blocked) shouldBe true
        }
    }

    @Test
    fun differentSelectedOriginsCannotShareOneUserAdoptionReview() {
        val first = claim(id = "chapter-one", label = "Chapter 1")
        val second = claim(id = "chapter-two", label = "Chapter 2")
        val otherOrigin = second.copy(
            claim = second.claim.copy(
                provenance = second.claim.provenance!!.copy(originReference = "user-selected:other-edition"),
            ),
        )
        val result = PreviewUserAdoptedInventory().execute(
            scope = scope,
            storedClaims = listOf(first, otherOrigin),
            selectedSourceRecordIds = listOf("chapter-one", "chapter-two"),
            existingChapters = emptyList(),
        )
        result shouldBe UserAdoptionPreview.Blocked(UserAdoptionBlockReason.SOURCE_SNAPSHOT_MISMATCH)
    }

    @Test
    fun duplicatesWrongEditionAndLegacyNumbersAreRejected() {
        val preview = PreviewUserAdoptedInventory()
        val canonical = CanonicalChapter(
            id = "legacy-count",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
        )
        val duplicate = preview.execute(
            scope,
            listOf(claim()),
            listOf("chapter-one", "chapter-one"),
            emptyList(),
        )
        val wrongEdition = preview.execute(
            scope.copy(editionKey = "edition-2"),
            listOf(claim()),
            listOf("chapter-one"),
            emptyList(),
        )
        val collision = preview.execute(
            scope,
            listOf(claim()),
            listOf("chapter-one"),
            listOf(canonical),
        )
        (duplicate is UserAdoptionPreview.Blocked) shouldBe true
        (wrongEdition is UserAdoptionPreview.Blocked) shouldBe true
        (collision is UserAdoptionPreview.Blocked) shouldBe true
    }
}
