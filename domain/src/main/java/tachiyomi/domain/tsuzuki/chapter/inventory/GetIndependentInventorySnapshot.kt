package tachiyomi.domain.tsuzuki.chapter.inventory

import dev.zacsweers.metro.Inject

/**
 * A read-only, offline view of what independent inventory producers actually observed.
 * Neither release records nor exhausted HTTP pagination prove editorial completeness.
 */
enum class InventoryKnowledge {
    UNKNOWN,
    PARTIAL,
}

data class IndependentInventorySourceSnapshot(
    val scope: InventoryScope,
    val knownIndividualClaims: Int,
    val releaseObservations: Int,
    val declaredTotals: Set<Int>,
    val outcomes: Set<InventoryFetchOutcome>,
    val latestObservedAt: Long?,
)

data class IndependentInventorySnapshot(
    val knowledge: InventoryKnowledge,
    val sources: List<IndependentInventorySourceSnapshot>,
    val editorialCompletenessConfirmed: Boolean = false,
)

@Inject
class GetIndependentInventorySnapshot(
    private val ledger: InventoryEvidenceRepository,
) {
    suspend fun execute(canonicalTitleId: String): IndependentInventorySnapshot {
        require(canonicalTitleId.isNotBlank())

        // Stored evidence belongs to the requested canonical title, and edition
        // is part of the identity boundary, never an implicit wildcard.
        val claims = ledger.byTitle(canonicalTitleId)
            .filter { it.scope.canonicalTitleId == canonicalTitleId }
        val pages = ledger.batchesByTitle(canonicalTitleId)
            .filter { it.scope.canonicalTitleId == canonicalTitleId }
        val scopes = (claims.map { it.scope } + pages.map { it.scope })
            .distinct()
            .sortedWith(
                compareBy<InventoryScope>(
                    { it.producerId },
                    { it.externalWorkId },
                    { it.editionKey.orEmpty() },
                    { it.language.orEmpty() },
                ),
            )

        val sources = scopes.map { scope ->
            val sourceClaims = claims.filter { it.scope == scope }
                .sortedByDescending { it.claim.observedAt }
                .distinctBy { it.claim.sourceRecordId }
            val sourcePages = pages.filter { it.scope == scope }
            val summary = summarizeInventory(sourceClaims)
            IndependentInventorySourceSnapshot(
                scope = scope,
                knownIndividualClaims = summary.individuallyAdmissibleClaims,
                releaseObservations = summary.releaseObservations,
                declaredTotals = sourcePages.mapNotNull { it.declaredTotal }.toSet(),
                outcomes = sourcePages.map { it.outcome }.toSet(),
                latestObservedAt = (
                    sourceClaims.map { it.claim.observedAt } +
                        sourcePages.map { it.observedAt }
                    ).maxOrNull(),
            )
        }

        // This ledger currently cannot certify editorial completeness. In
        // particular EXHAUSTED applies only to its technical page request.
        val hasKnowledge = sources.any { source ->
            source.knownIndividualClaims > 0 ||
                source.releaseObservations > 0 ||
                source.declaredTotals.isNotEmpty() ||
                source.outcomes.any {
                    it == InventoryFetchOutcome.PARTIAL || it == InventoryFetchOutcome.EXHAUSTED
                }
        }
        return IndependentInventorySnapshot(
            knowledge = if (hasKnowledge) InventoryKnowledge.PARTIAL else InventoryKnowledge.UNKNOWN,
            sources = sources,
        )
    }
}
