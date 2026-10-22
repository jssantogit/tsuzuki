package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsOne
import tachiyomi.data.Database
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.interactor.AssessLegacyExactChapterPromotion
import tachiyomi.domain.tsuzuki.chapter.interactor.LegacyExactPromotionReview

/**
 * Transactional, read-only audit of current v1 history.
 *
 * Not a migration endpoint: this object has no UPDATE and cannot authorize a
 * future update on the basis of a stale snapshot. In particular, successful
 * inspection does not establish cross-device sync compatibility.
 *
 * No production caller is registered until portable chapter sync v2 and
 * independently audited user-state migrations exist.
 */
internal class LegacyExactChapterMigrationPreflight(
    private val database: Database,
    private val assessor: AssessLegacyExactChapterPromotion = AssessLegacyExactChapterPromotion(),
) {
    suspend fun inspect(
        chapterId: String,
        remoteSyncStateAuditedAbsent: Boolean = false,
    ): LegacyExactMigrationPreflight {
        // There is no local database query which proves that another device
        // never published a v1 chapter key. Require an explicit external audit;
        // even a successful result is for human/diagnostic review ONLY.
        if (!remoteSyncStateAuditedAbsent) {
            return LegacyExactMigrationPreflight.Blocked(
                LegacyExactMigrationPreflight.Reason.REMOTE_SYNC_UNVERIFIED,
            )
        }
        return database.transactionWithResult {
            val chapterRepository = CanonicalChapterRepositoryImpl(database)
            val evidenceRepository = ChapterEvidenceRepositoryImpl(database)
            val chapter = chapterRepository.getById(chapterId)
                ?: return@transactionWithResult LegacyExactMigrationPreflight.Blocked(
                    LegacyExactMigrationPreflight.Reason.MISSING_CHAPTER,
                )

            val protectedStateCount = database.tsuzuki_canonical_chaptersQueries
                .countTsuzukiCanonicalChapterProtectedState(chapterId)
                .awaitAsOne()
            if (protectedStateCount > 0L) {
                return@transactionWithResult LegacyExactMigrationPreflight.Blocked(
                    LegacyExactMigrationPreflight.Reason.PROTECTED_STATE,
                )
            }
            // Variants have their own raw chapter labels and source order. A
            // complete migration would need to compare them independently.
            if (chapterRepository.getVariantsByCanonicalChapterId(chapterId).isNotEmpty()) {
                return@transactionWithResult LegacyExactMigrationPreflight.Blocked(
                    LegacyExactMigrationPreflight.Reason.VARIANTS_REQUIRE_REVIEW,
                )
            }

            val titleChapters = chapterRepository.getByCanonicalTitleId(chapter.canonicalTitleId)
            val mappedEvidence = evidenceRepository.getByCanonicalTitleId(chapter.canonicalTitleId)
                .filter { it.mappedCanonicalChapterId == chapterId }
            when (
                val review = assessor.assess(
                    chapter = chapter,
                    mappedEvidence = mappedEvidence,
                    titleChapters = titleChapters,
                    hasProtectedState = false,
                    hasPortableSyncState = false,
                )
            ) {
                is LegacyExactPromotionReview.Candidate -> LegacyExactMigrationPreflight.ReviewOnly(
                    chapterId = chapter.id,
                    evidenceReview = review,
                )
                is LegacyExactPromotionReview.Blocked -> LegacyExactMigrationPreflight.Blocked(
                    reason = LegacyExactMigrationPreflight.Reason.EVIDENCE_BLOCKED,
                    evidenceReason = review.reason,
                )
            }
        }
    }
}

internal sealed interface LegacyExactMigrationPreflight {
    /**
     * Diagnostic evidence consistency only. Never execute an identity update
     * from this result: the transaction and its validation have already ended.
     */
    data class ReviewOnly(
        val chapterId: String,
        val evidenceReview: LegacyExactPromotionReview.Candidate,
    ) : LegacyExactMigrationPreflight

    data class Blocked(
        val reason: Reason,
        val evidenceReason: LegacyExactPromotionReview.Reason? = null,
    ) : LegacyExactMigrationPreflight

    enum class Reason {
        REMOTE_SYNC_UNVERIFIED,
        MISSING_CHAPTER,
        PROTECTED_STATE,
        VARIANTS_REQUIRE_REVIEW,
        EVIDENCE_BLOCKED,
    }
}
