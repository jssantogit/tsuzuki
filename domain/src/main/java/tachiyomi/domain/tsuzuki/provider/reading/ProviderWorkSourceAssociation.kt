package tachiyomi.domain.tsuzuki.provider.reading

/**
 * Last observed work-level assessment of one exact Provider HID.
 *
 * This is a derived, local-only source observation, NOT an editorial binding.
 * It is deliberately distinct from ProviderReadingBinding (the preferred
 * reading edition), CanonicalChapter, and the Reader's page authority.
 */
data class ProviderWorkSourceAssociation(
    val canonicalTitleId: String,
    val ref: ProviderBindingRef,
    val workVerification: ProviderWorkVerification,
    val editionQualification: ProviderSourceEditionQualification,
    val observedAt: Long,
) {
    init {
        require(canonicalTitleId.isNotBlank())
        require(observedAt >= 0L)
    }
}

interface ProviderWorkSourceAssociationRepository {
    /**
     * Write only if this HID is not already owned by another canonical title.
     * Newer observations may replace older verification status, never title identity.
     * Returns false if the HID is already associated to another title.
     */
    suspend fun observe(association: ProviderWorkSourceAssociation): Boolean

    suspend fun get(ref: ProviderBindingRef): ProviderWorkSourceAssociation?

    suspend fun getByTitle(canonicalTitleId: String): List<ProviderWorkSourceAssociation>
}
