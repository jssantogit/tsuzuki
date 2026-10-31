package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.provider.ProviderId

enum class ProviderBindingVerification {
    EXACT,
    USER_CONFIRMED,
}

enum class ProviderBindingAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

data class ProviderReadingBinding(
    val id: String,
    val canonicalTitleId: String,
    val ref: ProviderBindingRef,
    val verification: ProviderBindingVerification,
    val availability: ProviderBindingAvailability,
    val createdAt: Long,
    val updatedAt: Long,
) {
    init {
        require(id.isNotBlank()) { "Provider reading binding ID must not be blank" }
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }
        require(createdAt >= 0L) { "Provider binding creation time must not be negative" }
        require(updatedAt >= createdAt) { "Provider binding update time cannot precede creation" }
    }
}

interface ProviderReadingBindingRepository {
    suspend fun get(
        canonicalTitleId: String,
        providerId: ProviderId,
        facetId: String? = null,
    ): ProviderReadingBinding?

    suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding>

    /**
     * Atomically reserves an empty canonical-title / Provider / facet slot.
     *
     * A user-confirmed edition and background exact-match discovery can race after both
     * observe an empty slot. Implementations backed by a database must make the check
     * and insert a single atomic operation; never overwrite or append another edition.
     */
    suspend fun insertIfAbsent(binding: ProviderReadingBinding): Boolean {
        if (get(binding.canonicalTitleId, binding.ref.providerId, binding.ref.facetId) != null) return false
        upsert(binding)
        return true
    }

    suspend fun upsert(binding: ProviderReadingBinding)

    suspend fun markUnavailable(bindingId: String, updatedAt: Long)
}
