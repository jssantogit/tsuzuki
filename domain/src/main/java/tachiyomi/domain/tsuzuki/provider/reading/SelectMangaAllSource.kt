package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject

/**
 * The user's explicit source preference for one canonical work and language.
 * This is NOT a chapter mapping, provider binding or Reader authorization.
 * A change must never clear user progress or the established preferred edition.
 */
data class MangaAllSourceSelection(
    val canonicalTitleId: String,
    val ref: ProviderBindingRef,
    val selectedAt: Long,
    val catalogIdentityFingerprint: String? = null,
) {
    init {
        require(canonicalTitleId.isNotBlank())
        require(selectedAt >= 0)
        require(MangaAllReadingIdentity.supportsRequestedLanguage(ref))
        if (catalogIdentityFingerprint != null) {
            require(catalogIdentityFingerprint.matches(Regex("catalog-work-v1:[0-9a-f]{64}")))
        }
    }
}

interface MangaAllSourceSelectionRepository {
    suspend fun get(canonicalTitleId: String, language: String): MangaAllSourceSelection?

    /** Most recent explicit choice, used only to restore the user's active language. */
    suspend fun getLatest(canonicalTitleId: String): MangaAllSourceSelection? = null

    /** Chapter-scoped release choice; never changes the title-wide source preference. */
    suspend fun getRelease(canonicalTitleId: String, canonicalChapterId: String, ref: ProviderBindingRef): String? =
        null

    /** Persist only an already validated release within the exact saved work and facet. */
    suspend fun selectRelease(
        canonicalTitleId: String,
        option: ProviderChapterReadingOption,
        at: Long,
    ): Boolean = false

    /** Stores an explicit preference only while the selected work is still associated to this title. */
    suspend fun select(choice: MangaAllSourceSelection): Boolean
}

enum class MangaAllSourceSelectionResult {
    SELECTED,
    ALREADY_SELECTED,
    UNSUPPORTED,
    NOT_ASSOCIATED,
    BLOCKED,
    UNAVAILABLE,
}

/** Persist source choice separately from the one preferred Provider binding. */
class SelectMangaAllSource(
    private val associations: ProviderWorkSourceAssociationRepository,
    private val selections: MangaAllSourceSelectionRepository,
    private val catalogResearch: MangaAllCatalogReaderResearch?,
) {
    @Inject
    constructor(
        associations: ProviderWorkSourceAssociationRepository,
        selections: MangaAllSourceSelectionRepository,
    ) : this(associations, selections, null)

    suspend fun select(
        canonicalTitleId: String,
        ref: ProviderBindingRef,
        at: Long,
    ): MangaAllSourceSelectionResult {
        if (
            canonicalTitleId.isBlank() ||
            at < 0 ||
            !MangaAllReadingIdentity.supportsRequestedLanguage(ref)
        ) {
            return MangaAllSourceSelectionResult.UNSUPPORTED
        }
        val association = associations.get(ref)
        if (association?.canonicalTitleId != canonicalTitleId) {
            return MangaAllSourceSelectionResult.NOT_ASSOCIATED
        }
        val proof = if (association.workVerification == ProviderWorkVerification.CATALOG_CORROBORATED) {
            catalogResearch?.proof(association) ?: return MangaAllSourceSelectionResult.BLOCKED
        } else {
            if (association.workVerification.blocksReadingSource()) return MangaAllSourceSelectionResult.BLOCKED
            null
        }
        val language = ref.facetId!!.lowercase()
        val previous = selections.get(canonicalTitleId, language)
        if (previous?.ref == ref && previous.catalogIdentityFingerprint == proof) {
            return MangaAllSourceSelectionResult.ALREADY_SELECTED
        }
        val selected = selections.select(
            MangaAllSourceSelection(canonicalTitleId, ref.copy(facetId = language), at, proof),
        )
        return if (selected) MangaAllSourceSelectionResult.SELECTED else MangaAllSourceSelectionResult.UNAVAILABLE
    }
}
