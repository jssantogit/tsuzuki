package tachiyomi.domain.tsuzuki.provider.reading

/**
 * Durable continuation of an incomplete, independently corroborated source HID.
 * Never grants editorial or Reader authority and never represents a full inventory.
 */
data class ProviderProvisionalCursorCheckpoint(
    val canonicalTitleId: String,
    val ref: ProviderBindingRef,
    val providerVersionCode: Int,
    val configurationFingerprint: String,
    val nextCursor: ProviderCursor,
    val completedPages: Int,
) {
    init {
        require(canonicalTitleId.isNotBlank())
        require(providerVersionCode >= 0)
        require(completedPages in 1..MAX_PROVISIONAL_CURSOR_PAGES)
    }
}

/**
 * Pending cursor advance. The Host must first reconcile all chapter evidence from
 * this round durably; if it crashes before committing the cursor, replay is safe.
 */
data class ProviderProvisionalCursorProgress(
    val canonicalTitleId: String,
    val ref: ProviderBindingRef,
    val providerVersionCode: Int,
    val configurationFingerprint: String,
    val expectedCursor: ProviderCursor?,
    val nextCursor: ProviderCursor?,
    val completedPages: Int,
    val recordedAt: Long,
) {
    init {
        require(canonicalTitleId.isNotBlank())
        require(providerVersionCode >= 0)
        require(completedPages in 1..MAX_PROVISIONAL_CURSOR_PAGES)
        require(recordedAt >= 0)
        require(expectedCursor != nextCursor || nextCursor == null)
    }
}

const val MAX_PROVISIONAL_CURSOR_PAGES: Int = 256

interface ProviderProvisionalCursorRepository {
    /** Returns a cursor only for the exact work, facet, installed version and active configuration. */
    suspend fun get(
        canonicalTitleId: String,
        ref: ProviderBindingRef,
        providerVersionCode: Int,
        configurationFingerprint: String,
    ): ProviderProvisionalCursorCheckpoint?

    /**
     * Compare-and-swap on the previous cursor. A concurrent refresh cannot
     * overwrite a newer cursor, and a finished source only removes its own state.
     */
    suspend fun commit(progress: ProviderProvisionalCursorProgress)
}
