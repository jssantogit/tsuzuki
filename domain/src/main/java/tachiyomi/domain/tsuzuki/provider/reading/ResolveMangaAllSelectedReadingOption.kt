package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject

/**
 * Apply an explicit MangaAll source preference to Reader-safe options only.
 *
 * The input must come from ResolveProviderChapterReading.options() which has
 * already checked canonical chapter mapping, edition, language, provider
 * readiness, and source chapter identity. Never fabricate a chapter based on
 * a saved work ID. No automatic cross-source or cross-language fallback.
 */
@Inject
class ResolveMangaAllSelectedReadingOption(
    private val selections: MangaAllSourceSelectionRepository,
) {
    suspend fun select(
        canonicalTitleId: String,
        canonicalChapterId: String,
        requestedLanguage: String,
        verifiedOptions: List<ProviderChapterReadingOption>,
    ): ProviderChapterReadingOption? {
        if (canonicalTitleId.isBlank() || canonicalChapterId.isBlank() || requestedLanguage.isBlank()) return null
        val language = requestedLanguage.lowercase()
        val saved = selections.get(canonicalTitleId, language) ?: return null
        if (saved.canonicalTitleId != canonicalTitleId ||
            saved.ref.facetId != language ||
            !MangaAllReadingIdentity.supportsRequestedLanguage(saved.ref)
        ) {
            return null
        }
        return verifiedOptions.filter { option ->
            val source = option.contentSourceId
            option.canonicalChapterId == canonicalChapterId &&
                option.bindingRef() == saved.ref &&
                source != null &&
                option.providerChapterId.startsWith("$source:") &&
                option.providerChapterId.substringAfter(':').isNotBlank()
        }.singleOrNull()
    }
}
