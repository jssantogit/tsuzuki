package tachiyomi.domain.tsuzuki.chapter.inventory

/** Explicitly selected page/revision context plus a bounded Action API formatversion=2 response. */
data class SelectedWikimediaRevision(
    val scope: InventoryScope,
    val selectedPageId: Long,
    val selectedRevisionId: Long,
    val pageReference: String,
    val responseJson: String,
    val observedAt: Long,
    val snapshotDigest: String,
    val attributionReference: String?,
    val licenseReference: String?,
)

/**
 * Deterministic local adapter for a user-selected Wikimedia revision fixture.
 * It does not perform network access and never treats a page as editorial authority.
 */
object SelectedWikimediaChapterIndexAdapter {
    const val MAX_SOURCE_BYTES = 256 * 1024
    const val MAX_ENTRIES = 256

    fun adapt(selected: SelectedWikimediaRevision): Result<InventoryEvidenceBatch> =
        Result.failure(UnsupportedOperationException("Selected Wikimedia revision parsing is not available yet"))
}
