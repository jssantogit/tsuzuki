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
    /** Legacy aggregate timestamp across stored claim and page observations. */
    val latestObservedAt: Long?,
    /** Latest recorded fetch attempt; explicit NOT_ATTEMPTED rows are not attempts. */
    val latestAttemptAt: Long? = null,
    /** Outcomes tied at the latest attempt timestamp, retaining ambiguity across pages. */
    val latestAttemptOutcomes: Set<InventoryFetchOutcome> = emptySet(),
    /** Latest page observation from a successful PARTIAL or EXHAUSTED fetch. */
    val latestSuccessfulFetchAt: Long? = null,
    /** Latest timestamp carried by a stored claim; it does not imply a successful page outcome. */
    val latestClaimObservedAt: Long? = null,
    /** Bounded examples of verified primary editorial units, not Reader targets. */
    val knownEditorialLabels: List<String> = emptyList(),
    /** Bounded raw release labels, never automatically promoted to chapters. */
    val releaseExampleLabels: List<String> = emptyList(),
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
    private companion object {
        const val EXAMPLE_LIMIT = 8
    }

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
            val attemptPages = sourcePages.filter { it.outcome != InventoryFetchOutcome.NOT_ATTEMPTED }
            val latestAttemptAt = attemptPages.maxOfOrNull { it.observedAt }
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
                latestAttemptAt = latestAttemptAt,
                latestAttemptOutcomes = latestAttemptAt?.let { at ->
                    attemptPages.filter { it.observedAt == at }.map { it.outcome }.toSet()
                }.orEmpty(),
                latestSuccessfulFetchAt = sourcePages
                    .filter {
                        it.outcome == InventoryFetchOutcome.PARTIAL ||
                            it.outcome == InventoryFetchOutcome.EXHAUSTED
                    }
                    .maxOfOrNull { it.observedAt },
                latestClaimObservedAt = sourceClaims.maxOfOrNull { it.claim.observedAt },
                knownEditorialLabels = sourceClaims
                    .filter { InventoryClaimPolicy.mayPromote(scope, it.claim) }
                    .mapNotNull { it.claim.label?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(EXAMPLE_LIMIT),
                releaseExampleLabels = sourceClaims
                    .filter { it.claim.derivation == InventoryDerivation.RELEASE_RECORD }
                    .mapNotNull { it.claim.label?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                    .take(EXAMPLE_LIMIT),
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
