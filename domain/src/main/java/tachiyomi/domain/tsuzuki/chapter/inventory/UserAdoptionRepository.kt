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
    suspend fun preview(request: UserAdoptionReviewRequest): UserAdoptionPreview

    suspend fun adopt(confirmation: UserAdoptionConfirmation): UserAdoptionCommitResult
}
