package tachiyomi.domain.tsuzuki.chapter.inventory

/**
 * A user, not an editor or provider, explicitly accepts selected candidates
 * for a named local publication. Source data is reloaded during the commit.
 */
data class UserAdoptionReviewRequest(
    val scope: InventoryScope,
    val sourceRecordIds: List<String>,
)

data class UserAdoptionConfirmation(
    val request: UserAdoptionReviewRequest,
    val reviewedFingerprint: String,
    val acceptedAt: Long,
    /** Only a UI confirmation after presenting every selected unit may set this true. */
    val explicitlyConfirmed: Boolean,
)

/** Bounded offline keyset page, independent of Detail's eight summary examples. */
data class UserAdoptionCandidatePage(
    val candidates: List<InventoryCandidateExample>,
    val nextCursor: String?,
)

sealed interface UserAdoptionCommitResult {
    data class Adopted(
        val receiptId: String,
        val canonicalChapterIds: List<String>,
    ) : UserAdoptionCommitResult
    data object Refused : UserAdoptionCommitResult
}

/**
 * Distinct from PRIMARY_EDITORIAL and Host authority.
 * Must not be invoked from automatic inventory refresh.
 */
interface UserAdoptionRepository {
    /** True even after restart or backup restoration; never publisher authority. */
    suspend fun adoptedChapterIds(canonicalTitleId: String): Set<String>

    /** Exact edition, local-only browsing; never invokes a producer or writes chapters. */
    suspend fun candidatePage(scope: InventoryScope, afterRecordId: String? = null): UserAdoptionCandidatePage

    suspend fun preview(request: UserAdoptionReviewRequest): UserAdoptionPreview

    suspend fun adopt(confirmation: UserAdoptionConfirmation): UserAdoptionCommitResult
}
