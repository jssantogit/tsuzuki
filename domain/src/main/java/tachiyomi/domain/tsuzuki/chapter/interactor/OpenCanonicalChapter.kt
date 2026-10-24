package tachiyomi.domain.tsuzuki.chapter.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter

/** The shared Details/Hero boundary before passing a canonical chapter identity to Reader. */
@Inject
class OpenCanonicalChapter(private val materializeInferredChapter: MaterializeInferredChapter) {
    suspend fun execute(chapter: CanonicalChapter): CanonicalChapter =
        if (isInferredChapter(chapter)) materializeInferredChapter.execute(chapter) else chapter
}

fun isVisibleCanonicalChapter(
    chapter: CanonicalChapter,
    hasProgress: Boolean,
    supported: Boolean,
    downloaded: Boolean,
): Boolean = chapter.confirmation == CanonicalChapterConfirmation.CONFIRMED || hasProgress || supported || downloaded
