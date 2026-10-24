package tachiyomi.data.tsuzuki

import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind

/**
 * Explicit, internal-only v1 -> exact-decimal migration experiment.
 *
 * No application entry point invokes this class. It must remain disabled
 * until a versioned cross-device sync rollout has been independently audited.
 * The caller's remote-state flag is an external prerequisite, NOT proof
 * inferred from this local database.
 */
internal class LegacyExactChapterMigrationWriter(
    private val database: Database,
    private val preflight: LegacyExactChapterMigrationPreflight = LegacyExactChapterMigrationPreflight(database),
) {
    suspend fun inspect(
        chapterId: String,
        remoteSyncStateAuditedAbsent: Boolean = false,
    ): LegacyExactMigrationPreflight = preflight.inspect(chapterId, remoteSyncStateAuditedAbsent)

    suspend fun promote(
        chapterId: String,
        reviewerApproved: Boolean = false,
        remoteSyncStateAuditedAbsent: Boolean = false,
    ): LegacyExactMigrationWriteResult {
        if (!remoteSyncStateAuditedAbsent) {
            return LegacyExactMigrationWriteResult.Refused(
                LegacyExactMigrationWriteResult.Reason.REMOTE_SYNC_UNVERIFIED,
            )
        }
        if (!reviewerApproved) {
            return LegacyExactMigrationWriteResult.Refused(
                LegacyExactMigrationWriteResult.Reason.REVIEW_REQUIRED,
            )
        }

        return database.transactionWithResult {
            // Re-read current evidence and user-owned state INSIDE the same
            // transaction as the compare-and-swap write. A prior ReviewOnly
            // diagnostic must never serve as the authority to mutate a row.
            val review = preflight.inspectInTransaction(
                chapterId = chapterId,
                remoteSyncStateAuditedAbsent = true,
            ) as? LegacyExactMigrationPreflight.ReviewOnly
                ?: return@transactionWithResult LegacyExactMigrationWriteResult.Refused(
                    LegacyExactMigrationWriteResult.Reason.PREFLIGHT_BLOCKED,
                )

            val exact = review.evidenceReview.identity
            if (exact.numberingKind != CanonicalChapterNumberingKind.DECIMAL) {
                return@transactionWithResult LegacyExactMigrationWriteResult.Refused(
                    LegacyExactMigrationWriteResult.Reason.UNSUPPORTED_NUMBERING_KIND,
                )
            }
            val fraction = requireNotNull(exact.decimalFraction)
            val original = CanonicalChapterRepositoryImpl(database).getById(chapterId)
                ?: return@transactionWithResult LegacyExactMigrationWriteResult.Refused(
                    LegacyExactMigrationWriteResult.Reason.PREFLIGHT_BLOCKED,
                )

            database.tsuzuki_canonical_chaptersQueries.promoteLegacyChapterNumberingIfSafe(
                numberingKind = CanonicalChapterNumberingKind.DECIMAL.name,
                decimalNumerator = fraction.numerator.toLong(),
                decimalScale = fraction.scale.toLong(),
                chapterId = original.id,
                canonicalTitleId = original.canonicalTitleId,
                baseNumber = original.baseNumber?.toLong(),
                part = original.part?.toLong(),
                volume = original.volume?.toLong(),
                updatedAt = original.updatedAt,
            )

            val persisted = CanonicalChapterRepositoryImpl(database).getById(chapterId)
            if (persisted?.identity != exact || persisted.volume != original.volume) {
                return@transactionWithResult LegacyExactMigrationWriteResult.Refused(
                    LegacyExactMigrationWriteResult.Reason.CONCURRENT_CHANGE,
                )
            }
            LegacyExactMigrationWriteResult.Promoted(chapterId)
        }
    }
}

internal sealed interface LegacyExactMigrationWriteResult {
    data class Promoted(val chapterId: String) : LegacyExactMigrationWriteResult
    data class Refused(val reason: Reason) : LegacyExactMigrationWriteResult

    enum class Reason {
        REMOTE_SYNC_UNVERIFIED,
        REVIEW_REQUIRED,
        PREFLIGHT_BLOCKED,
        UNSUPPORTED_NUMBERING_KIND,
        CONCURRENT_CHANGE,
    }
}
