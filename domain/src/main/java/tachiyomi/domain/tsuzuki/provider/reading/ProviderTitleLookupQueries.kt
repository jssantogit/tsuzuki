package tachiyomi.domain.tsuzuki.provider.reading

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.model.IntegrationCapability
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

/**
 * Select consented source-observed title spellings for Provider lookup.
 * Names are search hints, never an attestation of work/edition identity.
 */
internal suspend fun providerTitleLookupQueries(
    canonicalTitleId: String,
    primaryTitle: String,
    observations: TitleNameObservationRepository?,
    integrations: IntegrationRegistry?,
): List<String> {
    val variants = buildList {
        add(primaryTitle.trim())
        if (observations != null && integrations != null) {
            val observed = try {
                integrations.awaitReady()
                observations.getByTitle(canonicalTitleId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
            observed.asSequence()
                .filter { observation ->
                    integrations.isGlobalCapabilityActive(
                        IntegrationId(observation.provider),
                        IntegrationCapability.METADATA_BASIC,
                    )
                }
                .sortedWith(
                    compareBy(
                        { if (it.sourceKey.lowercase() in setOf("en", "english")) 0 else 1 },
                        { if (it.sourceKey.lowercase() in setOf("romaji", "romanized")) 0 else 1 },
                    ),
                )
                .map { it.value.trim() }
                .filter { it.isNotBlank() && it.length <= MAX_QUERY_CHARS }
                .forEach { add(it) }
        }
    }
    return variants
        .filter { it.isNotBlank() && it.length <= MAX_QUERY_CHARS }
        .distinctBy { it.lowercase().replace(Regex("\\s+"), " ") }
        .take(MAX_QUERY_VARIANTS)
}

private const val MAX_QUERY_VARIANTS = 3
private const val MAX_QUERY_CHARS = 1024
