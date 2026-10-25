package tachiyomi.domain.tsuzuki.chapter.inventory

/**
 * An inventory is a set of sourced assertions, not a set of readable releases.
 * A verified *work* crosswalk does not imply a verified *edition* crosswalk.
 */
data class InventoryScope(
    val canonicalTitleId: String,
    val producerId: String,
    val externalWorkId: String,
    val editionKey: String?,
    val language: String?,
    val workIdentityVerified: Boolean,
    val editionIdentityVerified: Boolean,
) {
    init {
        require(canonicalTitleId.isNotBlank())
        require(producerId.isNotBlank())
        require(externalWorkId.isNotBlank())
        require(!editionIdentityVerified || !editionKey.isNullOrBlank()) {
            "A verified edition requires an exact edition key"
        }
    }
}

enum class InventoryClaimKind {
    INDIVIDUAL,
    RELEASE_RANGE,
    DECLARED_TOTAL,
    DERIVED_PLACEHOLDER,
    AMBIGUOUS,
    CORRECTION,
    CONTENT_AVAILABILITY,
}

enum class InventoryDerivation {
    /** Source directly identifies the editorial unit, not merely its release. */
    PRIMARY_EDITORIAL,

    /** Explicitly indexed by a community source, not direct publisher authority. */
    COMMUNITY_CURATED,

    /** Navigation from a user-selected local edition, not a verified editorial chapter. */
    USER_SUPPLIED_EDITION_TOC,

    /** Distribution, translation, revision or upload of a unit; not editorial authority. */
    RELEASE_RECORD,
    COUNT_DERIVED,
    AGGREGATED,
    UNKNOWN,
}

enum class InventoryFetchOutcome {
    /** A bounded page was obtained; later pages may still be missing. */
    PARTIAL,

    /** Endpoint pagination ended, not editorial completeness. */
    EXHAUSTED,
    FAILED,
    NOT_ATTEMPTED,
}

data class InventoryClaim(
    val sourceRecordId: String,
    val label: String?,
    val volumeLabel: String?,
    val language: String?,
    val kind: InventoryClaimKind,
    val derivation: InventoryDerivation,
    val upstreamSourceId: String?,
    val observedAt: Long,
) {
    init {
        require(sourceRecordId.isNotBlank())
        require(observedAt >= 0L)
    }
}

data class InventoryEvidenceBatch(
    val scope: InventoryScope,
    val pageKey: String,
    val observedAt: Long,
    val outcome: InventoryFetchOutcome,
    val claims: List<InventoryClaim>,
    val declaredTotal: Int? = null,
    val nextCursor: String? = null,
) {
    init {
        require(pageKey.isNotBlank())
        require(observedAt >= 0L)
        require(declaredTotal == null || declaredTotal >= 0)
        require(outcome != InventoryFetchOutcome.FAILED || claims.isEmpty()) {
            "A failed page cannot assert newly obtained observations"
        }
        require(claims.all { it.observedAt <= observedAt })
    }
}

/**
 * Parsing a release label only classifies its shape. The release remains a
 * RELEASE_RECORD, even if it mentions a single number. No direct promotion.
 */
object InventoryClaimPolicy {
    private val individualNumber = Regex("""^(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$""")
    private val range = Regex("""^[0-9]+(?:\.[0-9]+)?\s*[-–—]\s*[0-9]+(?:\.[0-9]+)?$""")

    fun classifyReleaseLabel(raw: String?): InventoryClaimKind {
        val label = raw?.trim().orEmpty()
        return when {
            range.matches(label) -> InventoryClaimKind.RELEASE_RANGE
            individualNumber.matches(label) -> InventoryClaimKind.INDIVIDUAL
            else -> InventoryClaimKind.AMBIGUOUS
        }
    }

    /**
     * Strict *necessary*, not sufficient, conditions before the existing
     * exact-numbering reconciler may even consider a new canonical row.
     */
    fun isNonEditorialCandidate(claim: InventoryClaim): Boolean =
        claim.kind == InventoryClaimKind.INDIVIDUAL &&
            (
                claim.derivation == InventoryDerivation.COMMUNITY_CURATED ||
                    claim.derivation == InventoryDerivation.USER_SUPPLIED_EDITION_TOC
                )

    fun mayPromote(scope: InventoryScope, claim: InventoryClaim): Boolean =
        scope.workIdentityVerified &&
            scope.editionIdentityVerified &&
            !scope.editionKey.isNullOrBlank() &&
            claim.kind == InventoryClaimKind.INDIVIDUAL &&
            claim.derivation == InventoryDerivation.PRIMARY_EDITORIAL &&
            !claim.upstreamSourceId.isNullOrBlank() &&
            !claim.label.isNullOrBlank() &&
            scope.language == claim.language
}
