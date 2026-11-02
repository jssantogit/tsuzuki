package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import java.text.Normalizer
import java.util.Locale

/** Extracts volume evidence only from an explicit leading volume and chapter label. */
@Inject
class ParseCanonicalChapterVolume {

    operator fun invoke(rawLabel: String?): Int? = execute(rawLabel)

    fun execute(rawLabel: String?): Int? {
        val normalized = normalize(rawLabel.orEmpty())
        val match = LEADING_VOLUME_CHAPTER_PREFIX.find(normalized) ?: return null
        val leadingVolume = match.groupValues[1].toIntOrNull() ?: return null
        // Some providers return "Vol. 1 Ch. 1 - Chapter 1 - Volume 1".
        // The repeated, identical volume is not a second edition. A second
        // different, invalid or missing volume is still disqualifying.
        val volumes = VOLUME_MARKERS.findAll(normalized).take(3).map { marker ->
            marker.value.filter(Char::isDigit).toIntOrNull()
        }.toList()
        if (volumes.isEmpty() || volumes.size > 2 || volumes.any { it != leadingVolume }) return null
        return leadingVolume
    }

    /** True when a label starts with a volume marker, even if its value is unusable. */
    fun hasExplicitVolumePrefix(rawLabel: String?): Boolean =
        VOLUME_PREFIX_START.containsMatchIn(normalize(rawLabel.orEmpty()))

    /**
     * A numeric volume marker anywhere in a chapter label must be accounted for.
     * "Ch. 0 - Volume 112" is a volume container, not an unqualified chapter 0.
     * Only [execute] may recognize a qualified leading "Vol. N Ch. M" identity.
     */
    fun hasExplicitVolumeMarker(rawLabel: String?): Boolean =
        VOLUME_MARKERS.containsMatchIn(normalize(rawLabel.orEmpty()))

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace('–', '-')
            .replace('—', '-')
            .replace(WHITESPACE, " ")
            .trim()

    private companion object {
        val COMBINING_MARKS = Regex("\\p{M}+")
        val WHITESPACE = Regex("\\s+")
        val VOLUME_PREFIX_START = Regex("^vol(?:ume)?(?:\\b|\\.)")
        val VOLUME_MARKERS = Regex("\\bvol(?:ume)?\\.?\\s*(?:\\d+|none)\\b")
        val LEADING_VOLUME_CHAPTER_PREFIX = Regex(
            "^vol(?:ume)?\\.?\\s*(\\d+)\\s*(?:[-:|/]\\s*|\\s+)" +
                "(?=(?:ch(?:apter)?|capitulo)\\s*\\.?\\s*\\d)",
        )
    }
}
