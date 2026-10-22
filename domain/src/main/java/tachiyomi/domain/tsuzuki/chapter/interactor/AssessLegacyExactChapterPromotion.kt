package tachiyomi.domain.tsuzuki.chapter.interactor

import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import kotlin.math.abs

/**
 * Read-only historical audit. A [LegacyExactPromotionReview.Candidate] is NOT an
 * instruction to migrate, rekey evidence, or overwrite reading/sync state.
 * Callers must not use this as a shortcut around the v1-v2 reconciliation gate.
 */
class AssessLegacyExactChapterPromotion(
    private val parser: ParseCanonicalChapterLabel = ParseCanonicalChapterLabel(),
    private val volumeParser: ParseCanonicalChapterVolume = ParseCanonicalChapterVolume(),
) {

    fun assess(
        chapter: CanonicalChapter,
        mappedEvidence: List<PersistedChapterEvidence>,
        titleChapters: List<CanonicalChapter>,
        hasProtectedState: Boolean = false,
        hasPortableSyncState: Boolean = false,
    ): LegacyExactPromotionReview {
        fun blocked(reason: LegacyExactPromotionReview.Reason) = LegacyExactPromotionReview.Blocked(reason)

        if (chapter.numberingKind != null) return blocked(LegacyExactPromotionReview.Reason.ALREADY_EXACT)
        if (
            chapter.type != CanonicalChapterType.REGULAR ||
            chapter.baseNumber == null ||
            chapter.part == null ||
            chapter.alphaSuffix != null ||
            chapter.confirmation == CanonicalChapterConfirmation.CONFLICTED
        ) {
            return blocked(LegacyExactPromotionReview.Reason.LEGACY_IDENTITY_MISMATCH)
        }
        if (hasProtectedState || hasPortableSyncState) {
            return blocked(LegacyExactPromotionReview.Reason.PROTECTED_STATE)
        }
        if (mappedEvidence.isEmpty()) return blocked(LegacyExactPromotionReview.Reason.NO_EVIDENCE)

        val exactIdentities = mutableSetOf<CanonicalChapterIdentity>()
        val independentProducers = mutableSetOf<Pair<String, String>>()
        var editorialProvenance = false

        for (persisted in mappedEvidence) {
            val evidence = persisted.evidence
            if (
                persisted.mappedCanonicalChapterId != chapter.id ||
                evidence.canonicalTitleId != chapter.canonicalTitleId
            ) {
                return blocked(LegacyExactPromotionReview.Reason.CONFLICTING_EVIDENCE)
            }
            if (evidence.externalChapterKey.isNullOrBlank() || evidence.confidence < MIN_CONFIDENCE) {
                return blocked(LegacyExactPromotionReview.Reason.INSUFFICIENT_PROVENANCE)
            }
            val labelVolume = volumeParser.execute(evidence.rawLabel)
            if (
                (evidence.volume != null && labelVolume != null && evidence.volume != labelVolume) ||
                (volumeParser.hasExplicitVolumePrefix(evidence.rawLabel) && labelVolume == null) ||
                (chapter.volume != (evidence.volume ?: labelVolume))
            ) {
                return blocked(LegacyExactPromotionReview.Reason.VOLUME_AMBIGUITY)
            }

            val exact = parser.previewExactNumbering(evidence.rawLabel)
                ?: return blocked(LegacyExactPromotionReview.Reason.CONFLICTING_EVIDENCE)
            if (exact.baseNumber != chapter.baseNumber || exact.part != chapter.part) {
                return blocked(LegacyExactPromotionReview.Reason.LEGACY_IDENTITY_MISMATCH)
            }
            // A raw numeric hint can contradict the explicit label, but must
            // NEVER become a canonical identity or supply missing precision.
            // Floating point is used only for this conservative contradiction test.
            if (exact.decimalFraction != null && evidence.rawNumber != null) {
                val hint = evidence.rawNumber
                val expected = "${exact.baseNumber}.${exact.decimalFraction.asNormalizedDigits()}".toDouble()
                if (!hint.isFinite() || hint < 0.0 || abs(hint - expected) > HINT_EPSILON) {
                    return blocked(LegacyExactPromotionReview.Reason.CONFLICTING_EVIDENCE)
                }
            }
            exactIdentities += exact
            independentProducers += evidence.producerKind.name to evidence.producerId
            if (evidence.authority == ChapterEvidenceAuthority.EDITORIAL) {
                editorialProvenance = true
            }
        }

        if (exactIdentities.size != 1) {
            return blocked(LegacyExactPromotionReview.Reason.CONFLICTING_EVIDENCE)
        }
        val exact = exactIdentities.single()
        if (titleChapters.any { peer ->
                peer.id != chapter.id &&
                    peer.canonicalTitleId == chapter.canonicalTitleId &&
                    (
                        (peer.identity == exact && peer.volume == chapter.volume) ||
                            (
                                peer.type == chapter.type &&
                                    peer.baseNumber == chapter.baseNumber &&
                                    peer.part == chapter.part &&
                                    peer.alphaSuffix == chapter.alphaSuffix &&
                                    (peer.volume == chapter.volume || peer.volume == null || chapter.volume == null)
                                )
                        )
            }
        ) {
            return blocked(LegacyExactPromotionReview.Reason.COMPETING_CHAPTER)
        }
        if (!editorialProvenance && independentProducers.size < 2) {
            return blocked(LegacyExactPromotionReview.Reason.INSUFFICIENT_PROVENANCE)
        }
        return LegacyExactPromotionReview.Candidate(
            identity = exact,
            supportingProducers = independentProducers.size,
        )
    }

    private companion object {
        private const val MIN_CONFIDENCE = 0.95
        private const val HINT_EPSILON = 1e-10
    }
}

sealed interface LegacyExactPromotionReview {
    /**
     * Evidence-consistent only; does NOT authorize automatic promotion.
     * A separate, versioned migration must audit progress, variants and sync.
     */
    data class Candidate(
        val identity: CanonicalChapterIdentity,
        val supportingProducers: Int,
    ) : LegacyExactPromotionReview

    data class Blocked(val reason: Reason) : LegacyExactPromotionReview

    enum class Reason {
        ALREADY_EXACT,
        LEGACY_IDENTITY_MISMATCH,
        PROTECTED_STATE,
        NO_EVIDENCE,
        INSUFFICIENT_PROVENANCE,
        VOLUME_AMBIGUITY,
        CONFLICTING_EVIDENCE,
        COMPETING_CHAPTER,
    }
}
