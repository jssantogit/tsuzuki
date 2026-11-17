package tachiyomi.domain.tsuzuki.catalog.interactor

import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata

/**
 * Converts an independently verified catalog detail response to the field-level
 * provenance consumed by CollectProviderReadingEvidence.
 *
 * This is NOT a reading edition binding or proof of a chapter inventory. The
 * provenance can only be inserted into an isolated CI canonical snapshot after
 * independent catalog search and getDetails have completed successfully.
 */
fun MangaAllCatalogIdentityResult.toCanonicalReadingMetadata(): ResolvedMetadata? {
    if (status != MangaAllCatalogIdentityStatus.RESOLVED) return null
    val verified = identity ?: return null
    val providerId = verified.provider.takeIf {
        it.isNotBlank() && it.length <= 128 && !it.startsWith("app.tsuzuki.mangaall")
    } ?: return null
    val workId = verified.providerId.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
    if (verified.title.isBlank() || verified.title.length > 1024) return null
    if (verified.externalIds.any { (provider, id) ->
            provider.isBlank() || provider.startsWith("app.tsuzuki.mangaall") ||
                id.isBlank() || id.length > 512 || (provider == providerId && id != workId)
        }
    ) return null

    val observedCreators = verified.authors.isNotEmpty() || verified.artists.isNotEmpty()
    if (observedCreators != creatorsAttested) return null
    val creators = verified.authors + verified.artists
    if (creators.size > 32 || creators.any { it.isBlank() || it.length > 128 }) return null

    val provider = IntegrationId(providerId)
    val verifiedIds = (verified.externalIds + (providerId to workId)).mapKeys { (key, _) ->
        IntegrationId(key)
    }
    fun <T> credit(value: T): ProvenancedMetadata<T> = ProvenancedMetadata(
        value = value,
        providerId = provider,
        externalId = workId,
    )
    return ResolvedMetadata(
        title = credit(verified.title),
        authors = verified.authors.takeIf { it.isNotEmpty() }?.let(::credit),
        artists = verified.artists.takeIf { it.isNotEmpty() }?.let(::credit),
        externalIds = verifiedIds,
    )
}
