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
        val eligible = options(canonicalTitleId, canonicalChapterId, requestedLanguage, verifiedOptions)
        val ref = eligible.firstOrNull()?.bindingRef() ?: return null
        val savedRelease = selections.getRelease(canonicalChapterId, ref)
        return if (savedRelease == null) {
            eligible.singleOrNull()
        } else {
            // An unavailable saved release is not permission to choose a different one.
            eligible.singleOrNull { it.providerChapterId == savedRelease }
        }
    }

    suspend fun remember(
        canonicalTitleId: String,
        option: ProviderChapterReadingOption,
        verifiedOptions: List<ProviderChapterReadingOption>,
        at: Long,
    ): Boolean {
        val language = option.facetId ?: return false
        if (at < 0) return false
        val eligible = options(canonicalTitleId, option.canonicalChapterId, language, verifiedOptions)
        val exact = eligible.singleOrNull { it.readerSourceKey == option.readerSourceKey } ?: return false
        return selections.selectRelease(canonicalTitleId, exact, at)
    }

    /** Only eligible releases of the exact saved work and language, for explicit confirmation. */
    suspend fun options(
        canonicalTitleId: String,
        canonicalChapterId: String,
        requestedLanguage: String,
        verifiedOptions: List<ProviderChapterReadingOption>,
    ): List<ProviderChapterReadingOption> {
        if (canonicalTitleId.isBlank() || canonicalChapterId.isBlank() ||
            requestedLanguage.isBlank()
        ) {
            return emptyList()
        }
        val language = requestedLanguage.lowercase()
        val saved = selections.get(canonicalTitleId, language) ?: return emptyList()
        if (saved.canonicalTitleId != canonicalTitleId ||
            saved.ref.facetId != language ||
            !MangaAllReadingIdentity.supportsRequestedLanguage(saved.ref)
        ) {
            return emptyList()
        }
        return verifiedOptions.filter { option ->
            val source = option.contentSourceId
            option.canonicalChapterId == canonicalChapterId &&
                option.bindingRef() == saved.ref &&
                source != null &&
                option.providerChapterId.startsWith("$source:") &&
                option.providerChapterId.substringAfter(':').isNotBlank()
        }
    }
}
