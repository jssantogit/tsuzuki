package tachiyomi.domain.tsuzuki.chapter.model

/**
 * Orders chapters by their observed numbering scheme without changing canonical identity.
 *
 * Chapters usually have globally increasing numbers, so their structured chapter identity
 * remains the primary sort. A repeated regular identity in different explicit volumes is
 * evidence of volume-local numbering; only then does volume lead the regular chapter order.
 * Unqualified volumes sort after known volumes in this mode rather than being guessed.
 */
fun hasVolumeLocalChapterNumbering(chapters: List<CanonicalChapter>): Boolean {
    val volumeByIdentity = mutableMapOf<CanonicalChapterIdentity, Int>()
    return chapters.any { chapter ->
        if (
            chapter.type != CanonicalChapterType.REGULAR ||
            chapter.baseNumber == null ||
            chapter.volume == null
        ) {
            false
        } else {
            val previousVolume = volumeByIdentity.putIfAbsent(chapter.identity, chapter.volume)
            previousVolume != null && previousVolume != chapter.volume
        }
    }
}

/** See [hasVolumeLocalChapterNumbering] for the conservative reset detection rule. */
fun orderCanonicalChapters(chapters: List<CanonicalChapter>): List<CanonicalChapter> {
    val comparator = if (hasVolumeLocalChapterNumbering(chapters)) {
        compareBy<CanonicalChapter>({ it.type.sortRank }, { it.volume ?: Int.MAX_VALUE }, { it.identity }, { it.id })
    } else {
        compareBy<CanonicalChapter>({ it.identity }, { it.volume ?: Int.MAX_VALUE }, { it.id })
    }
    return chapters.sortedWith(comparator)
}
