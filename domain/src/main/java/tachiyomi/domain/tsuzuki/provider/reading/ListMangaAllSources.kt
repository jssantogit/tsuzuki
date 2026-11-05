package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject

/**
 * Read-only source portfolio for a single installed MangaAll provider.
 *
 * Work associations are hints for a future explicit edition/translation picker,
 * NOT Reader authority. Neither a facet nor a corroborated work proves the
 * availability or language of individual chapters or pages.
 */
data class MangaAllSourceAlternative(
    val ref: ProviderBindingRef,
    val sourceId: String,
    val requestedLanguage: String,
    val workVerification: ProviderWorkVerification,
    val editionQualification: ProviderSourceEditionQualification,
) {
    /** Observing a work does not certify a readable chapter or translation. */
    val isReaderVerified: Boolean get() = false
}

@Inject
class ListMangaAllSources(
    private val associations: ProviderWorkSourceAssociationRepository,
) {
    suspend fun execute(canonicalTitleId: String): List<MangaAllSourceAlternative> {
        if (canonicalTitleId.isBlank()) return emptyList()
        return associations.getByTitle(canonicalTitleId).mapNotNull { association ->
            val ref = association.ref
            val source = MangaAllReadingIdentity.sourceId(ref) ?: return@mapNotNull null
            val language = ref.facetId?.lowercase() ?: return@mapNotNull null
            if (!MangaAllReadingIdentity.supportsRequestedLanguage(ref)) return@mapNotNull null
            MangaAllSourceAlternative(
                ref = ref,
                sourceId = source,
                requestedLanguage = language,
                workVerification = association.workVerification,
                editionQualification = association.editionQualification,
            )
        }.distinctBy { it.ref }
    }
}
