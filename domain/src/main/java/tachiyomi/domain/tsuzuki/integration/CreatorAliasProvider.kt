package tachiyomi.domain.tsuzuki.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata

/**
 * An independently published creator name, obtained by following the exact
 * creator ID from the work's own author/artist credit to its detail endpoint.
 * It is not a new work identity, a source HID, or a display credit.
 */
enum class CreatorCreditRole { AUTHOR, ARTIST }

data class CatalogCreatorAliasEvidence(
    val workExternalId: String,
    val creatorExternalId: String,
    val role: CreatorCreditRole,
    val creditedName: String,
    val primaryName: String,
    val aliases: List<String>,
)

/** Optional capability on the owning metadata integration, never a reading provider. */
interface CreatorAliasProvider : MetadataProvider {
    suspend fun creatorAliases(externalId: String): Result<List<CatalogCreatorAliasEvidence>>
}

/**
 * Expand staff names for work corroboration only. The canonical repository
 * must confirm the exact catalog work ID independently of the cached field
 * provenance. Alias groups cannot be inferred across different catalog IDs.
 *
 * Failure is best-effort: it adds no aliases and leaves existing exact credit
 * matching untouched. Never use this to claim an editorial edition.
 */
suspend fun resolveVerifiedCreatorAliases(
    metadata: ResolvedMetadata,
    verifiedWorkIds: Map<IntegrationId, String>,
    providers: List<MetadataProvider>,
): Set<String> {
    val credits = listOfNotNull(
        metadata.authors?.let { CreatorCreditRole.AUTHOR to it },
        metadata.artists?.let { CreatorCreditRole.ARTIST to it },
    ).filter { (_, field) ->
        val id = field.externalId
        !field.providerId.value.startsWith("app.tsuzuki.mangaall") &&
            id != null && metadata.externalIds[field.providerId] == id &&
            verifiedWorkIds[field.providerId] == id
    }
    val expanded = linkedSetOf<String>()
    // At most the two independently proven staff field owners, with a shared
    // deadline. Catalog outages must not stall the entire chapter refresh.
    withTimeoutOrNull(8_000L) {
        for ((providerId, fields) in credits.groupBy { it.second.providerId }) {
            val provider = providers.singleOrNull { it.integrationId == providerId } as? CreatorAliasProvider
                ?: continue
            val workId = verifiedWorkIds[providerId] ?: continue
            val result = try {
                provider.creatorAliases(workId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                continue
            }
            val failure = result.exceptionOrNull()
            if (failure is CancellationException) throw failure
            val evidence = result.getOrNull() ?: continue
            if (evidence.size > 32) continue
            for (entry in evidence) {
                if (entry.workExternalId != workId ||
                    entry.creatorExternalId.isBlank() || entry.creatorExternalId.length > 128 ||
                    entry.aliases.size > 32
                ) {
                    continue
                }
                val names = listOf(entry.primaryName) + entry.aliases
                if (names.any { it.isBlank() || it.length > 128 }) continue
                val creditedKey = creditKey(entry.creditedName)
                if (creditedKey.isEmpty() || names.none { creditKey(it) == creditedKey }) continue
                val credited = fields.any { (role, field) ->
                    role == entry.role && field.value.any { creditKey(it) == creditedKey }
                }
                if (credited) expanded += names.map(String::trim)
            }
        }
    }
    return expanded
}

private fun creditKey(name: String): String =
    name.trim().lowercase().replace(Regex("""\s+"""), " ")
