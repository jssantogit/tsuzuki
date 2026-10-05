package tachiyomi.domain.tsuzuki.model

/**
 * A provider-published name observed for one canonical title.
 *
 * Observed names are discovery metadata only. They never prove canonical identity and must not
 * replace [CanonicalTitle.displayTitle] implicitly.
 */
data class TitleNameObservation(
    val canonicalTitleId: String,
    val provider: String,
    val sourceKey: String,
    val value: String,
    val updatedAt: Long,
) {
    init {
        require(canonicalTitleId.isNotBlank()) { "Canonical title id cannot be blank" }
        require(provider.isNotBlank()) { "Name provider cannot be blank" }
        require(sourceKey.isNotBlank()) { "Name source key cannot be blank" }
        require(value.isNotBlank()) { "Observed title name cannot be blank" }
    }
}
