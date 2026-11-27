package tachiyomi.domain.tsuzuki.provider.reading

import java.security.MessageDigest

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
    /** Scope-bound independent bibliographic attestation, not an author or edition credential. */
    val catalogIdentityFingerprint: String? = null,
) {
    init {
        require(canonicalTitleId.isNotBlank())
        require(observedAt >= 0L)
        if (catalogIdentityFingerprint != null) {
            require(workVerification == ProviderWorkVerification.CATALOG_CORROBORATED)
            require(catalogIdentityFingerprint.matches(Regex("catalog-work-v1:[0-9a-f]{64}")))
        }
    }
}

interface ProviderWorkSourceAssociationRepository {
    /**
     * Write only if this HID is not already owned by another canonical title.
     * Newer observations may replace older verification status, never title identity.
     * Catalog conflict persists until a newer catalog assessment, not missing metadata
     * or author-only corroboration. Author conflict persists until fresh author
     * verification or a stronger catalog conflict. Conflict wins timestamp ties.
     * Returns false if the HID is already associated to another title.
     */
    suspend fun observe(association: ProviderWorkSourceAssociation): Boolean

    suspend fun get(ref: ProviderBindingRef): ProviderWorkSourceAssociation?

    suspend fun getByTitle(canonicalTitleId: String): List<ProviderWorkSourceAssociation>

    object NoOp : ProviderWorkSourceAssociationRepository {
        override suspend fun observe(association: ProviderWorkSourceAssociation): Boolean = false
        override suspend fun get(ref: ProviderBindingRef): ProviderWorkSourceAssociation? = null
        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderWorkSourceAssociation> = emptyList()
    }
}

/**
 * The enum alone cannot prove which independently verified catalog identity
 * was compared. Bind that observation to the exact work, facet, version and
 * active configuration. Never treat this fingerprint as Reader permission.
 */
object MangaAllCatalogWorkIdentityProof {
    fun fingerprint(
        canonicalTitleId: String,
        ref: ProviderBindingRef,
        independentMangaUpdatesId: String,
        versionCode: Long,
        configurationFingerprint: String,
    ): String? {
        if (canonicalTitleId.isBlank() || configurationFingerprint.isBlank() ||
            versionCode != 11L || ref.providerId.value != "app.tsuzuki.mangaall" ||
            ref.facetId !in setOf("en", "pt-br") ||
            !ref.externalWorkId.matches(Regex("mangadot:[1-9][0-9]{0,15}")) ||
            !independentMangaUpdatesId.matches(Regex("[1-9][0-9]{0,17}"))
        ) {
            return null
        }
        val fields = listOf(
            canonicalTitleId,
            ref.providerId.value,
            requireNotNull(ref.facetId),
            ref.externalWorkId,
            independentMangaUpdatesId,
            versionCode.toString(),
            configurationFingerprint,
        )
        // Length framing avoids delimiter collisions with arbitrary local IDs.
        val material = fields.joinToString("") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "catalog-work-v1:$digest"
    }
}
