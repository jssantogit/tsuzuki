package tachiyomi.domain.tsuzuki.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import java.net.URI

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
            val exactCredits = fields.flatMap { (role, field) ->
                field.value.map { role to it }
            }
            val people = verifiedCatalogCreatorAliasGroups(workId, exactCredits, evidence)
            people.forEach { expanded += it.names }
        }
    }
    return expanded
}

/**
 * A verified alias group anchored to one catalog person and one or more
 * actual credits on the exact work. Keep the person identity available to
 * callers that need to distinguish two adaptation creators.
 */
internal data class VerifiedCatalogCreatorAliasGroup(
    val creatorExternalId: String,
    val names: Set<String>,
    val credits: Set<Pair<CreatorCreditRole, String>>,
)

/**
 * Never expand a name claimed by competing person IDs or allow contradictory
 * rows of a single person to donate aliases. Raw detail credits remain intact
 * even when these optional aliases are rejected.
 */
internal fun verifiedCatalogCreatorAliasGroups(
    workId: String,
    credits: List<Pair<CreatorCreditRole, String>>,
    evidence: List<CatalogCreatorAliasEvidence>,
): List<VerifiedCatalogCreatorAliasGroup> {
    if (workId.isBlank() || evidence.size > 32 || credits.size > 32) return emptyList()
    val anchored = credits.map { (role, name) -> role to creditKey(name) }.toSet()
    val valid = evidence.filter { entry ->
        val names = listOf(entry.primaryName) + entry.aliases
        val credited = creditKey(entry.creditedName)
        entry.workExternalId == workId &&
            entry.creatorExternalId.isNotBlank() && entry.creatorExternalId.length <= 128 &&
            entry.aliases.size <= 32 &&
            names.none { it.isBlank() || it.length > 128 } &&
            credited.isNotEmpty() && names.any { creditKey(it) == credited } &&
            (entry.role to credited) in anchored
    }
    val byPerson = valid.groupBy { it.creatorExternalId }
    // Include all valid anchored claims in the collision index, even if one
    // of those people later fails its own consistency check.
    val ownersByName = mutableMapOf<String, MutableSet<String>>()
    for (entry in valid) {
        for (name in listOf(entry.primaryName) + entry.aliases) {
            ownersByName.getOrPut(creditKey(name)) { mutableSetOf() }.add(entry.creatorExternalId)
        }
    }
    return byPerson.mapNotNull { (id, rows) ->
        val nameSets = rows.map { row ->
            (listOf(row.primaryName) + row.aliases).map(::creditKey).toSet()
        }
        val shared = nameSets.reduce { left, right -> left intersect right }
        val names = rows.flatMap { listOf(it.primaryName) + it.aliases }.map(String::trim).toSet()
        if (shared.isEmpty() || names.any { ownersByName[creditKey(it)]?.size != 1 }) {
            return@mapNotNull null
        }
        VerifiedCatalogCreatorAliasGroup(
            creatorExternalId = id,
            names = names,
            credits = rows.map { it.role to creditKey(it.creditedName) }.toSet(),
        )
    }
}

/**
 * Independently reviewed bibliographic credit evidence, with its own
 * citations. Only research callers supply it. Never establishes an edition
 * or substitutes a missing source author with a catalog name.
 */
data class ReviewedCreatorAliasReference(
    val catalogOwner: IntegrationId,
    val catalogWorkId: String,
    val role: CreatorCreditRole,
    val creditedName: String,
    val creditUrl: String,
    val aliases: Map<String, String>,
)

fun resolveReviewedCreatorAliases(
    metadata: ResolvedMetadata,
    verifiedWorkIds: Map<IntegrationId, String>,
    references: List<ReviewedCreatorAliasReference>,
): Set<String> {
    if (references.size > 8) return emptySet()
    return buildSet {
        for (reference in references) {
            val field = when (reference.role) {
                CreatorCreditRole.AUTHOR -> metadata.authors
                CreatorCreditRole.ARTIST -> metadata.artists
            } ?: continue
            val owner = reference.catalogOwner
            if (owner.value.startsWith("app.tsuzuki.mangaall") ||
                field.providerId != owner || field.externalId != reference.catalogWorkId ||
                metadata.externalIds[owner] != reference.catalogWorkId ||
                verifiedWorkIds[owner] != reference.catalogWorkId ||
                reference.catalogWorkId.isBlank() || reference.creditedName.isBlank() ||
                field.value.none { creditKey(it) == creditKey(reference.creditedName) } ||
                reference.aliases.size !in 1..8
            ) {
                continue
            }
            val creditHost = publicReferenceHost(reference.creditUrl) ?: continue
            for ((name, url) in reference.aliases) {
                val aliasHost = publicReferenceHost(url) ?: continue
                if (aliasHost != creditHost && name.isNotBlank() && name.length <= 128) add(name.trim())
            }
        }
    }
}

private fun publicReferenceHost(value: String): String? = runCatching {
    require(value.length in 1..2048)
    val uri = URI(value)
    require(uri.scheme == "https" && uri.userInfo == null && uri.host != null)
    uri.host.lowercase().removePrefix("www.")
}.getOrNull()

private fun creditKey(name: String): String =
    name.trim().lowercase().replace(Regex("""\s+"""), " ")
