package tachiyomi.domain.tsuzuki.chapter.model

import java.util.Locale

/**
 * Structured identity/order components for a canonical chapter.
 *
 * A chapter's identity never uses a floating-point number. In particular,
 * `12`, `12.5`, and `12a` have different structured identities.
 */
@ConsistentCopyVisibility
data class CanonicalChapterIdentity private constructor(
    val type: CanonicalChapterType = CanonicalChapterType.UNKNOWN,
    val baseNumber: Int? = null,
    val part: Int? = null,
    private val normalizedAlphaSuffix: String? = null,
    val numberingKind: CanonicalChapterNumberingKind? = null,
    val decimalFraction: CanonicalChapterDecimalFraction? = null,
) : Comparable<CanonicalChapterIdentity> {

    init {
        require(decimalFraction == null || numberingKind == CanonicalChapterNumberingKind.DECIMAL) {
            "Only exact decimal chapters may have a decimal fraction"
        }
        if (numberingKind != null) {
            require(type == CanonicalChapterType.REGULAR && baseNumber != null) {
                "Exact numbering requires a regular numbered chapter"
            }
            when (numberingKind) {
                CanonicalChapterNumberingKind.DECIMAL -> require(
                    decimalFraction != null && part == decimalFraction.numerator && alphaSuffix == null,
                ) { "Decimal numerator must match the legacy part field" }
                CanonicalChapterNumberingKind.ORDINAL_PART -> require(part != null && decimalFraction == null) {
                    "Ordinal numbering requires a part and no decimal fraction"
                }
            }
        }
    }

    /**
     * Public construction normalizes the suffix before it participates in
     * equality, hashing, comparison, or sorting.
     */
    constructor(
        type: CanonicalChapterType = CanonicalChapterType.UNKNOWN,
        baseNumber: Int? = null,
        part: Int? = null,
        alphaSuffix: String? = null,
        numberingKind: CanonicalChapterNumberingKind? = null,
        decimalFraction: CanonicalChapterDecimalFraction? = null,
        @Suppress("UNUSED_PARAMETER") normalizationMarker: Unit = Unit,
    ) : this(
        type = type,
        baseNumber = baseNumber,
        part = part,
        normalizedAlphaSuffix = alphaSuffix?.lowercase(Locale.ROOT),
        numberingKind = numberingKind,
        decimalFraction = decimalFraction,
    )

    val alphaSuffix: String?
        get() = normalizedAlphaSuffix

    /** Whether this identity contains a number that can safely be reconciled. */
    val isNumbered: Boolean
        get() = baseNumber != null

    /** Unknown identities must not be treated as a cross-source match. */
    val isSpecific: Boolean
        get() = type != CanonicalChapterType.UNKNOWN && (baseNumber != null || type != CanonicalChapterType.REGULAR)

    /**
     * A stable, non-floating-point sort key.
     *
     * Fixed-width numeric fields make lexicographic ordering numeric for the
     * supported non-negative chapter range. The structured fields still remain
     * the source of truth for equality and reconciliation.
     */
    val sortKey: String
        get() = buildString {
            append(type.sortRank.toString().padStart(2, '0'))
            append('|')
            append(numberKey(baseNumber))
            append('|')
            // An unsuffixed chapter must precede its fractional installments (0 before 0.5).
            // The explicit prefix prevents null from colliding with a real part zero.
            append(
                if (numberingKind == CanonicalChapterNumberingKind.DECIMAL) {
                    "1" + decimalFraction!!.asNormalizedDigits().padEnd(NULL_NUMBER_KEY.length, '0')
                } else {
                    part?.let { "1" + numberKey(it) } ?: "0"
                },
            )
            append('|')
            when (val suffix = alphaSuffix?.lowercase()) {
                null -> append("00|")
                else -> append("01|").append(suffix)
            }
            if (numberingKind != null) {
                // Existing v1 sort/sync keys remain byte-for-byte unchanged.
                append("|v2:").append(numberingKind.name)
                decimalFraction?.let {
                    append(':').append(it.numerator).append(':').append(it.scale)
                }
            }
        }

    override fun compareTo(other: CanonicalChapterIdentity): Int {
        // Do not change the legacy comparator: some historical rows may contain
        // signed numbers, for which the legacy sort key was not a numeric order.
        if (numberingKind != null || other.numberingKind != null) {
            return sortKey.compareTo(other.sortKey)
        }
        return when {
            type.sortRank != other.type.sortRank -> type.sortRank.compareTo(other.type.sortRank)
            baseNumber != other.baseNumber -> compareNullableNumbers(baseNumber, other.baseNumber)
            part != other.part -> compareNullableParts(part, other.part)
            alphaSuffix != other.alphaSuffix -> compareNullableStrings(alphaSuffix, other.alphaSuffix)
            else -> 0
        }
    }

    private companion object {
        private const val NULL_NUMBER_KEY = "9999999999"

        fun numberKey(value: Int?): String {
            if (value == null) return NULL_NUMBER_KEY
            // Chapter labels are non-negative in the initial parser. Prefixing
            // a sign keeps an accidental negative value deterministic as well.
            return if (value >= 0) {
                value.toString().padStart(NULL_NUMBER_KEY.length, '0')
            } else {
                "-" + value.toString().removePrefix("-").padStart(NULL_NUMBER_KEY.length - 1, '0')
            }
        }

        fun compareNullableNumbers(left: Int?, right: Int?): Int = when {
            left == null && right == null -> 0
            left == null -> 1
            right == null -> -1
            else -> left.compareTo(right)
        }

        fun compareNullableParts(left: Int?, right: Int?): Int = when {
            left == null && right == null -> 0
            left == null -> -1
            right == null -> 1
            else -> left.compareTo(right)
        }

        fun compareNullableStrings(left: String?, right: String?): Int = when {
            left == null && right == null -> 0
            left == null -> -1
            right == null -> 1
            else -> left.lowercase().compareTo(right.lowercase())
        }
    }
}
