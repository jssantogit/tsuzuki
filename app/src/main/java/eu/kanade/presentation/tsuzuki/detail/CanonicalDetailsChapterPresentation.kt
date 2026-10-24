package eu.kanade.presentation.tsuzuki.detail

import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalChapterDetailItem
import kotlin.math.roundToInt

private const val CONTEXTUAL_CHAPTER_WINDOW_SIZE = 5
private const val LARGE_WORK_SCRUBBER_THRESHOLD = 80

enum class CanonicalDetailsLowerMode {
    COMPACT,
    FULL,
    EDITORIAL,
}

enum class CanonicalDetailsLowerAction {
    VIEW_ALL,
    EDITORIAL,
    CHAPTERS,
    BACK,
}

enum class CanonicalChapterSort {
    ORIGINAL,
    REVERSED,
}

enum class CanonicalChapterProgressState {
    UNREAD,
    PARTIAL,
    READ,
}

fun availableDetailChapters(chapters: List<CanonicalChapterDetailItem>): List<CanonicalChapterDetailItem> =
    chapters.filterNot(CanonicalChapterDetailItem::inferredFromCount)

/** Presentation context only: the canonical reading owner supplies [activeChapterId]. */
fun contextualChapters(
    chapters: List<CanonicalChapterDetailItem>,
    activeChapterId: String? = null,
    windowSize: Int = CONTEXTUAL_CHAPTER_WINDOW_SIZE,
): List<CanonicalChapterDetailItem> {
    val available = availableDetailChapters(chapters)
    val size = windowSize.coerceAtLeast(1)
    if (available.size <= size) return available

    val activeIndex = available.indexOfFirst { it.chapter.id == activeChapterId }
    val anchor = when {
        available.all { it.progress?.read == true } -> available.lastIndex
        activeIndex >= 0 -> activeIndex
        else -> 0
    }
    val start = (anchor - size / 2).coerceIn(0, available.size - size)
    return available.subList(start, start + size)
}

fun sortDetailChapters(
    chapters: List<CanonicalChapterDetailItem>,
    sort: CanonicalChapterSort,
): List<CanonicalChapterDetailItem> {
    val available = availableDetailChapters(chapters)
    return when (sort) {
        CanonicalChapterSort.ORIGINAL -> available
        CanonicalChapterSort.REVERSED -> available.reversed()
    }
}

fun resolveChapterJump(
    chapters: List<CanonicalChapterDetailItem>,
    input: String,
): CanonicalChapterDetailItem? {
    val query = input.trim()
    if (query.isEmpty()) return null
    val available = availableDetailChapters(chapters)
    return available.firstOrNull { it.chapter.id == query }
        ?: available.firstOrNull {
            it.chapter.displayNumber.equals(query, ignoreCase = true) ||
                it.chapter.title?.equals(query, ignoreCase = true) == true
        }
}

/** The fraction indexes real rows; chapter labels are never interpolated. */
fun chapterAtScrubberPosition(
    chapters: List<CanonicalChapterDetailItem>,
    fraction: Float,
): CanonicalChapterDetailItem? {
    val available = availableDetailChapters(chapters)
    if (available.isEmpty()) return null
    val index = (fraction.coerceIn(0f, 1f) * available.lastIndex).roundToInt()
    return available[index]
}

fun showChapterScrubber(
    chapterCount: Int,
    threshold: Int = LARGE_WORK_SCRUBBER_THRESHOLD,
): Boolean = chapterCount >= threshold

/** Page count is not part of canonical progress, so this exposes no invented percentage. */
fun chapterProgressState(item: CanonicalChapterDetailItem): CanonicalChapterProgressState {
    val progress = item.progress
    return when {
        progress?.read == true -> CanonicalChapterProgressState.READ
        progress != null && (progress.lastPageRead > 0L || !progress.lastVariantId.isNullOrBlank()) ->
            CanonicalChapterProgressState.PARTIAL
        else -> CanonicalChapterProgressState.UNREAD
    }
}

fun restoreDetailsMode(mode: CanonicalDetailsLowerMode): CanonicalDetailsLowerMode? = when (mode) {
    CanonicalDetailsLowerMode.COMPACT -> null
    CanonicalDetailsLowerMode.FULL, CanonicalDetailsLowerMode.EDITORIAL -> CanonicalDetailsLowerMode.COMPACT
}

fun detailsModeAfterAction(
    mode: CanonicalDetailsLowerMode,
    action: CanonicalDetailsLowerAction,
): CanonicalDetailsLowerMode? = when (action) {
    CanonicalDetailsLowerAction.VIEW_ALL -> CanonicalDetailsLowerMode.FULL
    CanonicalDetailsLowerAction.EDITORIAL -> CanonicalDetailsLowerMode.EDITORIAL
    CanonicalDetailsLowerAction.CHAPTERS -> CanonicalDetailsLowerMode.COMPACT
    CanonicalDetailsLowerAction.BACK -> restoreDetailsMode(mode)
}
