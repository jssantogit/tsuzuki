package tachiyomi.domain.tsuzuki.chapter.inventory

/** Local, append-or-update-only shadow ledger. Never exports to sync v1. */
interface InventoryEvidenceRepository {
    suspend fun byTitle(canonicalTitleId: String): List<InventoryStoredClaim>

    suspend fun batchesByTitle(canonicalTitleId: String): List<InventoryStoredBatch>

    /** Must be atomic; no response may delete claims from a prior successful page. */
    suspend fun saveBatch(batch: InventoryEvidenceBatch)
}

data class InventoryStoredClaim(
    val scope: InventoryScope,
    val claim: InventoryClaim,
)

data class InventoryStoredBatch(
    val scope: InventoryScope,
    val pageKey: String,
    val observedAt: Long,
    val outcome: InventoryFetchOutcome,
    val itemCount: Int,
    val declaredTotal: Int?,
    val nextCursor: String?,
)

data class InventoryKnowledgeSummary(
    val individuallyAdmissibleClaims: Int,
    val releaseObservations: Int,
    val ambiguousOrAggregateClaims: Int,
    val editorialCompletenessConfirmed: Boolean = false,
    val candidateObservations: Int = 0,
)

/** Same upstream release may be observed by several pages, but not a new chapter each time. */
fun summarizeInventory(claims: List<InventoryStoredClaim>): InventoryKnowledgeSummary {
    val distinct = claims.distinctBy {
        listOf(
            it.scope.canonicalTitleId,
            it.scope.producerId,
            it.scope.externalWorkId,
            it.scope.editionKey.orEmpty(),
            it.scope.language.orEmpty(),
            it.claim.sourceRecordId,
        )
    }
    return InventoryKnowledgeSummary(
        individuallyAdmissibleClaims = distinct.count {
            InventoryClaimPolicy.mayPromote(it.scope, it.claim)
        },
        releaseObservations = distinct.count { it.claim.derivation == InventoryDerivation.RELEASE_RECORD },
        candidateObservations = distinct.count { InventoryClaimPolicy.isNonEditorialCandidate(it.claim) },
        ambiguousOrAggregateClaims = distinct.count {
            it.claim.kind != InventoryClaimKind.INDIVIDUAL
        },
    )
}
