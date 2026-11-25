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
    maxVariants: Int = MAX_QUERY_VARIANTS,
    researchBatch: Int = 0,
): List<String> {
    require(maxVariants in 1..6)
    require(researchBatch in 0..3 && (researchBatch == 0 || maxVariants == 6))
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
        .let { ordered ->
            // Only explicit research refreshes may consume another bounded batch.
            // Short lists retry the primary batch, preserving normal cursor recovery.
            ordered.drop(researchBatch * maxVariants).take(maxVariants).ifEmpty { ordered.take(maxVariants) }
        }
}

private const val MAX_QUERY_VARIANTS = 3
private const val MAX_QUERY_CHARS = 1024
