package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import java.text.Normalizer
import java.util.Locale

/** Extracts bounded volume evidence from explicit, consistent source chapter labels. */
@Inject
class ParseCanonicalChapterVolume {

    operator fun invoke(rawLabel: String?): Int? = execute(rawLabel)

    fun execute(rawLabel: String?): Int? {
        val normalized = normalize(rawLabel.orEmpty())
        val volumes = VOLUME_MARKERS.findAll(normalized).take(3).map { marker ->
            marker.value.filter(Char::isDigit).toIntOrNull()
        }.toList()
        if (volumes.isEmpty() || volumes.size > 2 || volumes.any { it == null }) return null

        // Some providers repeat the same volume in "Vol. 1 Ch. 1 - Chapter 1 - Volume 1".
        val leadingVolume = LEADING_VOLUME_CHAPTER_PREFIX.find(normalized)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (leadingVolume != null) {
            return leadingVolume.takeIf { volume -> volumes.all { it == volume } }
        }

        // MangaFire may supply no structured volume but title the chapter
        // "Chapter 1 - Volume 1". Its Host label becomes
        // "Ch. 1 - Chapter 1 - Volume 1". Permit the single suffix volume
        // only for positive numbered chapters with consistent chapter markers.
        // Ch. 0 - Volume 112 and conflicting chapter numbers stay unqualified.
        val leadingChapter = LEADING_NUMBERED_CHAPTER.find(normalized)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (volumes.size != 1) return null
        val chapterNumbers = CHAPTER_MARKERS.findAll(normalized).take(3).map { marker ->
            marker.groupValues[1].toIntOrNull()
        }.toList()
        if (
            chapterNumbers.isEmpty() ||
            chapterNumbers.size > 2 ||
            chapterNumbers.any { it != leadingChapter }
        ) {
            return null
        }
        return volumes.single()
    }

    /** True when a label starts with a volume marker, even if its value is unusable. */
    fun hasExplicitVolumePrefix(rawLabel: String?): Boolean =
        VOLUME_PREFIX_START.containsMatchIn(normalize(rawLabel.orEmpty()))

    /**
     * A numeric volume marker anywhere in a chapter label must be accounted for.
     * "Ch. 0 - Volume 112" is a volume container, not an unqualified chapter 0.
     * [execute] also accepts one consistent suffix volume on a positive chapter,
     * never a bare volume container on chapter zero.
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
        val LEADING_NUMBERED_CHAPTER = Regex("^ch(?:apter)?\\.?\\s*([1-9]\\d*)\\b")
        val CHAPTER_MARKERS = Regex("\\bch(?:apter)?\\.?\\s*(\\d+)\\b")
        val LEADING_VOLUME_CHAPTER_PREFIX = Regex(
            "^vol(?:ume)?\\.?\\s*(\\d+)\\s*(?:[-:|/]\\s*|\\s+)" +
                "(?=(?:ch(?:apter)?|capitulo)\\s*\\.?\\s*\\d)",
        )
    }
}
