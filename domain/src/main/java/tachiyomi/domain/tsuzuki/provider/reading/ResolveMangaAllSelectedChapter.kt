package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository

/**
 * The safe Reader entrypoint for the user's stored MangaAll source choice.
 *
 * Always obtains chapter options from the existing canonical/evidence resolver,
 * then intersects them with the exact selected work + language. No alternative
 * source is silently chosen when the saved source lacks this chapter.
 */
@Inject
class ResolveMangaAllSelectedChapter(
    private val canonicalChapters: CanonicalChapterRepository,
    private val chapterSources: ResolveProviderChapterReading,
    private val selectedSource: ResolveMangaAllSelectedReadingOption,
) {
    suspend fun execute(canonicalChapterId: String, requestedLanguage: String): ProviderChapterReadingOption? {
        if (canonicalChapterId.isBlank() || requestedLanguage.isBlank()) return null
        val chapter = canonicalChapters.getById(canonicalChapterId) ?: return null
        val validated = chapterSources.options(canonicalChapterId)
        return selectedSource.select(
            canonicalTitleId = chapter.canonicalTitleId,
            requestedLanguage = requestedLanguage,
            verifiedOptions = validated,
        )
    }
}
