package tachiyomi.domain.tsuzuki.chapter.inventory

/** A single, explicitly selected EPUB3 nav XHTML document and edition snapshot. */
data class SelectedEpub3Navigation(
    val scope: InventoryScope,
    val editionReference: String,
    val snapshotRevision: String,
    val snapshotDigest: String,
    val navXhtml: String,
    val observedAt: Long,
)

/**
 * Deterministic adapter for caller-selected EPUB3 navigation XHTML only.
 * It does not open archives, scan files, access page content, or bypass DRM.
 */
object SelectedEpub3TocAdapter {
    const val MAX_DOCUMENT_BYTES = 512 * 1024
    const val MAX_ENTRIES = 512
    const val MAX_DEPTH = 32

    fun adapt(selected: SelectedEpub3Navigation): Result<InventoryEvidenceBatch> =
        Result.failure(UnsupportedOperationException("Selected EPUB3 navigation parsing is not available yet"))
}
