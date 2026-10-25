package tachiyomi.domain.tsuzuki.chapter.inventory

import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterIdentity
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

/**
 * Implemented by the trusted Host using its independent, verified edition registry.
 * The producer's own [InventoryScope] must never be accepted as an authorization grant.
 */
fun interface HostEditorialEditionAuthorization {
    fun approvedScope(producerId: String, canonicalTitleId: String): InventoryScope?
}

enum class EditorialEditionBlockReason {
    HOST_AUTHORIZATION,
    NON_EDITORIAL_EVIDENCE,
    UNSUPPORTED_STRUCTURE,
    DUPLICATE_OR_COLLIDING_UNIT,
    UNSCOPED_CANONICAL_COLLISION,
    SNAPSHOT_MISMATCH,
}

data class EditorialEditionPreviewUnit(
    val scope: InventoryScope,
    val sourceRecordId: String,
    val upstreamSourceId: String,
    val label: String,
    val volume: Int?,
    val identity: CanonicalChapterIdentity,
)

sealed interface EditorialEditionPreflight {
    data class Blocked(val reason: EditorialEditionBlockReason) : EditorialEditionPreflight

    /**
     * An inert dry-run result. It is NOT a canonical chapter, adoption receipt,
     * Reader binding, Host authority grant, transaction or permission to commit.
     */
    data class Ready(
        val scope: InventoryScope,
        val units: List<EditorialEditionPreviewUnit>,
        val editorialCompletenessConfirmed: Boolean = false,
    ) : EditorialEditionPreflight
}

/**
 * Fail-closed planning boundary for a synthetic or explicitly authorized
 * primary editorial inventory. No persistence or read provider is needed.
 *
 * The active canonical schema has no edition key. A matching existing regular
 * chapter therefore blocks a plan instead of being silently reused. Any future
 * writer MUST revalidate Host authority, exact edition scope, old chapter state,
 * and persisted edition ownership inside its own transaction; this preview is
 * never sufficient to authorize a write.
 */
class PreviewEditorialEditionInventory(
    private val hostAuthorization: HostEditorialEditionAuthorization,
) {
    private companion object {
        const val MAX_PREVIEW_UNITS = 2048
        private val individual = Regex("""^[0-9]{1,10}(?:\.[0-9]{1,9})?$""")
        private val volumeNumber = Regex("""^[1-9][0-9]*$""")
    }

    fun execute(
        batch: InventoryEvidenceBatch,
        existingCanonicalChapters: List<CanonicalChapter>,
    ): EditorialEditionPreflight {
        val scope = batch.scope
        val independentlyAuthorized = hostAuthorization.approvedScope(
            scope.producerId,
            scope.canonicalTitleId,
        )
        if (
            independentlyAuthorized != scope ||
            !scope.workIdentityVerified ||
            !scope.editionIdentityVerified ||
            scope.editionKey.isNullOrBlank() ||
            scope.language.isNullOrBlank()
        ) {
            return EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.HOST_AUTHORIZATION)
        }

        if (existingCanonicalChapters.any { it.canonicalTitleId != scope.canonicalTitleId }) {
            return EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.SNAPSHOT_MISMATCH)
        }

        if (
            batch.claims.isEmpty() ||
            batch.claims.size > MAX_PREVIEW_UNITS ||
            (batch.outcome != InventoryFetchOutcome.PARTIAL && batch.outcome != InventoryFetchOutcome.EXHAUSTED)
        ) {
            return EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.NON_EDITORIAL_EVIDENCE)
        }

        val seenUpstream = hashSetOf<String>()
        val seenChapterIdentities = hashSetOf<Pair<CanonicalChapterIdentity, Int?>>()
        val planned = ArrayList<EditorialEditionPreviewUnit>(batch.claims.size)
        for (claim in batch.claims) {
            if (!InventoryClaimPolicy.mayPromote(scope, claim)) {
                return EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.NON_EDITORIAL_EVIDENCE)
            }
            val raw = claim.label?.trim().orEmpty()
            val number = parseExactIndividualNumber(raw)
                ?: return EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.UNSUPPORTED_STRUCTURE)
            val volume = claim.volumeLabel?.let { rawVolume ->
                rawVolume.takeIf { volumeNumber.matches(it) }
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: return EditorialEditionPreflight.Blocked(EditorialEditionBlockReason.UNSUPPORTED_STRUCTURE)
            }
            val (identity, normalizedLabel) = number
            if (
                !seenUpstream.add(claim.sourceRecordId) ||
                !seenChapterIdentities.add(identity to volume)
            ) {
                return EditorialEditionPreflight.Blocked(
                    EditorialEditionBlockReason.DUPLICATE_OR_COLLIDING_UNIT,
                )
            }

            // Existing canonical rows predate edition isolation. Even a seeming
            // exact number match cannot prove the same publication or language.
            // Include decimal/legacy representations with the same base number,
            // not just equality of a lossy legacy `part`.
            if (
                existingCanonicalChapters.any {
                    it.type == CanonicalChapterType.REGULAR &&
                        it.baseNumber == identity.baseNumber
                }
            ) {
                return EditorialEditionPreflight.Blocked(
                    EditorialEditionBlockReason.UNSCOPED_CANONICAL_COLLISION,
                )
            }
            planned += EditorialEditionPreviewUnit(
                scope = scope,
                sourceRecordId = claim.sourceRecordId,
                upstreamSourceId = requireNotNull(claim.upstreamSourceId),
                label = normalizedLabel,
                volume = volume,
                identity = identity,
            )
        }
        return EditorialEditionPreflight.Ready(scope = scope, units = planned)
    }

    private fun parseExactIndividualNumber(rawLabel: String): Pair<CanonicalChapterIdentity, String>? {
        if (!individual.matches(rawLabel)) return null
        val baseDigits = rawLabel.substringBefore('.')
        val base = baseDigits.toIntOrNull() ?: return null
        val fractionDigits = rawLabel.substringAfter('.', missingDelimiterValue = "")
        val fraction = if ('.' in rawLabel) {
            CanonicalChapterDecimalFraction.parse(fractionDigits) ?: return null
        } else {
            null
        }
        val identity = CanonicalChapterIdentity(
            type = CanonicalChapterType.REGULAR,
            baseNumber = base,
            part = fraction?.numerator,
            numberingKind = if (fraction == null) null else CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = fraction,
        )
        val canonicalLabel = buildString {
            append(base)
            fraction?.let { append('.').append(it.asNormalizedDigits()) }
        }
        return identity to canonicalLabel
    }
}
