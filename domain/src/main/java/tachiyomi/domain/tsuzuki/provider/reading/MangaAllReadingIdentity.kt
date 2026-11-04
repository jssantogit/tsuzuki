package tachiyomi.domain.tsuzuki.provider.reading

/**
 * Closed provenance gate for Reader evidence inside the one MangaAll Provider.
 *
 * MangaDex, MangaDot and MangaFire are internal content sources, not Provider IDs.
 * A work, chapter and attested language must all point to the same source/facet
 * before a chapter is offered for reading. An unknown language is not an
 * invitation to silently serve the wrong translation.
 *
 * Other Providers keep their existing identity and Reader behavior.
 */
object MangaAllReadingIdentity {
    private const val PROVIDER_ID = "app.tsuzuki.mangaall"
    private val supportedLanguages = mapOf(
        "mangadex" to setOf("en", "pt-br"),
        "mangadot" to setOf("en", "pt-br"),
        "mangafire" to setOf("en"),
    )

    fun accepts(
        ref: ProviderBindingRef,
        providerChapterId: String,
        reportedLanguage: String?,
    ): Boolean {
        if (ref.providerId.value != PROVIDER_ID) return true
        val workSource = ref.externalWorkId.substringBefore(':', missingDelimiterValue = "")
        val workLocalId = ref.externalWorkId.substringAfter(':', missingDelimiterValue = "")
        val chapterSource = providerChapterId.substringBefore(':', missingDelimiterValue = "")
        val chapterLocalId = providerChapterId.substringAfter(':', missingDelimiterValue = "")
        if (
            workLocalId.isBlank() ||
            chapterLocalId.isBlank() ||
            chapterSource != workSource
        ) {
            return false
        }
        val language = ref.facetId?.lowercase() ?: return false
        if (language !in (supportedLanguages[workSource] ?: return false)) return false
        return reportedLanguage?.lowercase() == language
    }
}
