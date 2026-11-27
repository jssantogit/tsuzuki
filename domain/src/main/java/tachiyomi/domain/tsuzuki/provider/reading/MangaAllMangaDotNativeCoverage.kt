package tachiyomi.domain.tsuzuki.provider.reading

/**
 * Projects a genuinely observed chapter page from the experimental
 * code11 MangaDot native snapshot into bounded work-level coverage evidence.
 *
 * The caller MUST supply the result of the exact [binding] chapter request,
 * not a source lookup count, fixture in a live run, or another HID's page.
 * This is not an edition association, chapter mapping, or Reader authority.
 */
object MangaAllMangaDotNativeCoverage {
    fun fromFirstPage(
        artifactVersionCode: Long,
        binding: ProviderBindingRef,
        candidate: ProviderWorkCandidate,
        page: ProviderPage<ProviderChapterObservation>,
    ): ProviderWorkCandidate? = fromObservedPage(artifactVersionCode, binding, candidate, page)

    /** A resumed page is equally valid work-level numeric coverage, not a total. */
    fun fromObservedPage(
        artifactVersionCode: Long,
        binding: ProviderBindingRef,
        candidate: ProviderWorkCandidate,
        page: ProviderPage<ProviderChapterObservation>,
        independentlyVerifiedMangaUpdatesId: String? = null,
    ): ProviderWorkCandidate? {
        val catalogMatch = independentlyVerifiedMangaUpdatesId != null &&
            independentlyVerifiedMangaUpdatesId.matches(Regex("[1-9][0-9]{0,17}")) &&
            candidate.mangaUpdatesReferenceIds == listOf(independentlyVerifiedMangaUpdatesId)
        // Preserve the compatibility entry point and its exact catalog-only scope.
        if (!binding.externalWorkId.startsWith("mangadot:")) return null
        return MangaAllNativeChapterCoverage.project(artifactVersionCode, binding, candidate, page, catalogMatch)
    }
}
