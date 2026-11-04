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
) {
    init {
        require(canonicalTitleId.isNotBlank())
        require(selectedAt >= 0)
        require(MangaAllReadingIdentity.supportsRequestedLanguage(ref))
    }
}

interface MangaAllSourceSelectionRepository {
    suspend fun get(canonicalTitleId: String, language: String): MangaAllSourceSelection?
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
@Inject
class SelectMangaAllSource(
    private val associations: ProviderWorkSourceAssociationRepository,
    private val selections: MangaAllSourceSelectionRepository,
) {
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
        if (
            association.workVerification == ProviderWorkVerification.AUTHOR_CONFLICT ||
            association.workVerification == ProviderWorkVerification.TITLE_UNVERIFIED
        ) {
            return MangaAllSourceSelectionResult.BLOCKED
        }
        val language = ref.facetId!!.lowercase()
        if (selections.get(canonicalTitleId, language)?.ref == ref) {
            return MangaAllSourceSelectionResult.ALREADY_SELECTED
        }
        val selected = selections.select(
            MangaAllSourceSelection(canonicalTitleId, ref.copy(facetId = language), at),
        )
        return if (selected) MangaAllSourceSelectionResult.SELECTED else MangaAllSourceSelectionResult.UNAVAILABLE
    }
}
