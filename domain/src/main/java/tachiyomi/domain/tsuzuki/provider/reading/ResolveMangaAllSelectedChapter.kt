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
        return selectedSource.select(
            canonicalTitleId = chapter.canonicalTitleId,
            canonicalChapterId = canonicalChapterId,
            requestedLanguage = requestedLanguage,
            verifiedOptions = chapterSources.options(canonicalChapterId),
        )
    }

    /** Only an explicit Reader picker tap can record a release preference. */
    suspend fun rememberRelease(option: ProviderChapterReadingOption, at: Long): Boolean {
        val chapter = canonicalChapters.getById(option.canonicalChapterId) ?: return false
        return selectedSource.remember(
            canonicalTitleId = chapter.canonicalTitleId,
            option = option,
            verifiedOptions = chapterSources.options(chapter.id),
            at = at,
        )
    }

    suspend fun options(canonicalChapterId: String, requestedLanguage: String): List<ProviderChapterReadingOption> {
        if (canonicalChapterId.isBlank() || requestedLanguage.isBlank()) return emptyList()
        val chapter = canonicalChapters.getById(canonicalChapterId) ?: return emptyList()
        val validated = chapterSources.options(canonicalChapterId)
        return selectedSource.options(
            canonicalTitleId = chapter.canonicalTitleId,
            canonicalChapterId = canonicalChapterId,
            requestedLanguage = requestedLanguage,
            verifiedOptions = validated,
        )
    }
}
