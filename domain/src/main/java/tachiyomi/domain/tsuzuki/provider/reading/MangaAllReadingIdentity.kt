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

    // The MangaAll package advertises reading.pages globally, but as of v0.1.0
    // MangaFire and MangaDot now route source-scoped page metadata. MangaDex
    // remains blocked until attribution/removal and real image delivery gates.
    // A ready source still requires a verified chapter and attested language;
    // a package-wide pages capability alone never establishes readability.
    private val readerReadySources = setOf("mangafire", "mangadot")

    /** Content source for UI/diagnostics, distinct from the one Provider ID. */
    fun sourceId(ref: ProviderBindingRef): String? {
        if (ref.providerId.value != PROVIDER_ID) return null
        val source = ref.externalWorkId.substringBefore(':', missingDelimiterValue = "")
        val localId = ref.externalWorkId.substringAfter(':', missingDelimiterValue = "")
        return source.takeIf { it in supportedLanguages && localId.isNotBlank() }
    }

    /** A language facet is requested, not evidence that translated pages exist. */
    fun supportsRequestedLanguage(ref: ProviderBindingRef): Boolean {
        val source = sourceId(ref) ?: return false
        val language = ref.facetId?.lowercase() ?: return false
        return language in supportedLanguages[source].orEmpty()
    }

    fun accepts(
        ref: ProviderBindingRef,
        providerChapterId: String,
        reportedLanguage: String?,
    ): Boolean {
        if (ref.providerId.value != PROVIDER_ID) return true
        val workSource = sourceId(ref) ?: return false
        if (workSource !in readerReadySources) return false
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
        if (!supportsRequestedLanguage(ref)) return false
        // MangaDot IDs encode the upstream image endpoint to prevent accidental
        // uploads/chapters ambiguity and malformed IDs reaching the Reader.
        if (
            workSource == "mangadot" &&
            !Regex("^(uploads|chapters):[1-9][0-9]{0,15}$").matches(chapterLocalId)
        ) return false
        return reportedLanguage?.lowercase() == language
    }
}
