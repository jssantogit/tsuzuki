package tachiyomi.domain.tsuzuki.chapter.model

/**
 * An exact, canonical fraction of a chapter number without floating-point conversions.
 *
 * Leading fractional zeroes are significant: ".05" is (5, 2), not (5, 1).
 * Trailing zeroes are normalized: ".50" and ".5" both become (5, 1).
 *
 * Zero fractions and more than nine fractional digits are deliberately unsupported
 * until the migration can distinguish historical "12" and "12.0" safely.
 */
data class CanonicalChapterDecimalFraction(
    val numerator: Int,
    val scale: Int,
) : Comparable<CanonicalChapterDecimalFraction> {

    init {
        require(scale in 1..MAX_DECIMAL_SCALE) { "Fractional scale must be between 1 and $MAX_DECIMAL_SCALE" }
        require(numerator > 0 && numerator.toString().length <= scale) {
            "Numerator must fit within its fractional scale"
        }
        require(numerator % 10 != 0) { "Trailing zeroes must be normalized" }
    }

    fun asNormalizedDigits(): String = numerator.toString().padStart(scale, '0')

    override fun compareTo(other: CanonicalChapterDecimalFraction): Int =
        asNormalizedDigits().padEnd(MAX_DECIMAL_SCALE, '0')
            .compareTo(other.asNormalizedDigits().padEnd(MAX_DECIMAL_SCALE, '0'))

    companion object {
        private const val MAX_DECIMAL_SCALE = 9

        fun parse(fractionalDigits: String): CanonicalChapterDecimalFraction? {
            if (fractionalDigits.isEmpty() ||
                fractionalDigits.length > MAX_DECIMAL_SCALE ||
                !fractionalDigits.all { it in '0'..'9' }
            ) {
                return null
            }
            val normalized = fractionalDigits.trimEnd('0')
            if (normalized.isEmpty()) return null

            val numerator = normalized.toIntOrNull() ?: return null
            return CanonicalChapterDecimalFraction(numerator, normalized.length)
        }
    }
}
