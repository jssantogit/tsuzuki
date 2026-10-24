package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.AssessLegacyExactChapterPromotion
import tachiyomi.domain.tsuzuki.chapter.interactor.LegacyExactPromotionReview
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class AssessLegacyExactChapterPromotionTest {
    private val assessor = AssessLegacyExactChapterPromotion()

    @Test
    fun `independent explicit labels can yield a read only review candidate`() {
        val legacy = chapter()
        val candidate = assessor.assess(
            chapter = legacy,
            mappedEvidence = listOf(
                evidence("source-a", "Chapter 12.5", key = "/12.5"),
                evidence("source-b", "Chapter 12.50", key = "/chapter/12-50"),
            ),
            titleChapters = listOf(legacy),
        )

        candidate shouldBe LegacyExactPromotionReview.Candidate(
            identity = CanonicalChapterIdentity(
                type = CanonicalChapterType.REGULAR,
                baseNumber = 12,
                part = 5,
                numberingKind = CanonicalChapterNumberingKind.DECIMAL,
                decimalFraction = CanonicalChapterDecimalFraction(5, 1),
            ),
            supportingProducers = 2,
        )
        legacy.numberingKind shouldBe null
    }

    @Test
    fun `single provider cannot assert exact history without independent confirmation`() {
        val legacy = chapter()
        assessor.assess(
            chapter = legacy,
            mappedEvidence = listOf(evidence("source-a", "Chapter 12.5")),
            titleChapters = listOf(legacy),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.INSUFFICIENT_PROVENANCE)
    }

    @Test
    fun `editorial evidence with stable key is sufficient for review only`() {
        val legacy = chapter()
        val proof = evidence("editorial", "Chapter 12.05", authority = ChapterEvidenceAuthority.EDITORIAL)
        val original = legacy.copy(part = 5)
        val result = assessor.assess(original, listOf(proof), listOf(original))

        result shouldBe LegacyExactPromotionReview.Candidate(
            identity = CanonicalChapterIdentity(
                type = CanonicalChapterType.REGULAR,
                baseNumber = 12,
                part = 5,
                numberingKind = CanonicalChapterNumberingKind.DECIMAL,
                decimalFraction = CanonicalChapterDecimalFraction(5, 2),
            ),
            supportingProducers = 1,
        )
    }

    @Test
    fun `conflicting exact scales and ordinal parts block review`() {
        val legacy = chapter()
        for (other in listOf("Chapter 12.05", "Chapter 12 Part 5", "12.5")) {
            assessor.assess(
                legacy,
                listOf(evidence("source-a", "Chapter 12.5"), evidence("source-b", other)),
                listOf(legacy),
            ) shouldBe blocked(LegacyExactPromotionReview.Reason.CONFLICTING_EVIDENCE)
        }
    }

    @Test
    fun `contradictory numeric hints block a historical precision review`() {
        val legacy = chapter()
        val first = evidence("source-a", "Chapter 12.5")
        val second = evidence("source-b", "Chapter 12.50")
        val contradictory = second.copy(evidence = second.evidence.copy(rawNumber = 46.0))

        assessor.assess(
            legacy,
            listOf(first, contradictory),
            listOf(legacy),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.CONFLICTING_EVIDENCE)
    }

    @Test
    fun `legacy integer part and precise decimal must agree without rewriting legacy rows`() {
        val legacy = chapter().copy(part = 50, displayNumber = "12.50")
        assessor.assess(
            legacy,
            listOf(
                evidence("source-a", "Chapter 12.50"),
                evidence("source-b", "Chapter 12.5"),
            ),
            listOf(legacy),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.LEGACY_IDENTITY_MISMATCH)
    }

    @Test
    fun `volume resets and ambiguous volume evidence cannot assert exact chapter`() {
        val legacy = chapter().copy(volume = 1)
        assessor.assess(
            legacy,
            listOf(
                evidence("source-a", "Vol. 1 Ch. 12.5", volume = 1),
                evidence("source-b", "Vol. 2 Ch. 12.5", volume = 2),
            ),
            listOf(legacy),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.VOLUME_AMBIGUITY)

        assessor.assess(
            chapter(),
            listOf(evidence("source-a", "Vol. 1 Ch. 12.5", volume = 1)),
            listOf(chapter()),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.VOLUME_AMBIGUITY)
    }

    @Test
    fun `existing competing exact or legacy identities block historical promotion`() {
        val legacy = chapter()
        val proof = listOf(evidence("source-a", "Chapter 12.5"), evidence("source-b", "Chapter 12.50"))
        val competingExact = legacy.copy(
            id = "already-exact",
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 1),
        )
        assessor.assess(legacy, proof, listOf(legacy, competingExact)) shouldBe
            blocked(LegacyExactPromotionReview.Reason.COMPETING_CHAPTER)
        assessor.assess(legacy, proof, listOf(legacy, legacy.copy(id = "duplicate-v1"))) shouldBe
            blocked(LegacyExactPromotionReview.Reason.COMPETING_CHAPTER)
    }

    @Test
    fun `protected reading and portable sync state block an otherwise consistent candidate`() {
        val legacy = chapter()
        val proof = listOf(evidence("source-a", "Chapter 12.5"), evidence("source-b", "Chapter 12.50"))
        assessor.assess(legacy, proof, listOf(legacy), hasProtectedState = true) shouldBe
            blocked(LegacyExactPromotionReview.Reason.PROTECTED_STATE)
        assessor.assess(legacy, proof, listOf(legacy), hasPortableSyncState = true) shouldBe
            blocked(LegacyExactPromotionReview.Reason.PROTECTED_STATE)
    }

    @Test
    fun `already precise IDs and mismapped or unstable evidence are not migration candidates`() {
        val legacy = chapter()
        assessor.assess(
            legacy.copy(
                numberingKind = CanonicalChapterNumberingKind.DECIMAL,
                decimalFraction = CanonicalChapterDecimalFraction(5, 1),
            ),
            emptyList(),
            listOf(legacy),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.ALREADY_EXACT)

        assessor.assess(legacy, emptyList(), listOf(legacy)) shouldBe
            blocked(LegacyExactPromotionReview.Reason.NO_EVIDENCE)
        assessor.assess(
            legacy,
            listOf(evidence("source-a", "Chapter 12.5").copy(mappedCanonicalChapterId = "wrong-id")),
            listOf(legacy),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.CONFLICTING_EVIDENCE)
        assessor.assess(
            legacy,
            listOf(evidence("source-a", "Chapter 12.5", key = null)),
            listOf(legacy),
        ) shouldBe blocked(LegacyExactPromotionReview.Reason.INSUFFICIENT_PROVENANCE)
    }

    private fun blocked(reason: LegacyExactPromotionReview.Reason) = LegacyExactPromotionReview.Blocked(reason)

    private fun chapter() = CanonicalChapter(
        id = "legacy-id",
        canonicalTitleId = "title",
        displayNumber = "12.5",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 12,
        part = 5,
    )

    private fun evidence(
        producer: String,
        label: String,
        key: String? = "/chapter",
        volume: Int? = null,
        authority: ChapterEvidenceAuthority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
    ): PersistedChapterEvidence = PersistedChapterEvidence(
        evidence = ChapterEvidence(
            id = "$producer-$label",
            canonicalTitleId = "title",
            producerKind = if (authority == ChapterEvidenceAuthority.EDITORIAL) {
                ProducerKind.INTEGRATION
            } else {
                ProducerKind.ADDON
            },
            producerId = producer,
            externalChapterKey = key,
            rawLabel = label,
            rawNumber = null,
            volume = volume,
            title = null,
            observedAt = 100L,
            confidence = 1.0,
            authority = authority,
        ),
        mappedCanonicalChapterId = "legacy-id",
    )
}
