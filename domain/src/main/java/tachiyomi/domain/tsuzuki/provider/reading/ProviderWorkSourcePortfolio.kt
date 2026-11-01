package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.provider.ProviderId

/**
 * Read-only work-level assessment for competing source HIDs.
 *
 * The same canonical work can have multiple legitimate Provider source records.
 * Independent author corroboration may establish a probable *work* association,
 * but never proves that two source editions, translations or chapters coincide.
 *
 * This intentionally does not write chapter evidence or override the existing
 * single preferred Provider binding. It is a prerequisite to a separately
 * reviewed, versioned source association store and Reader eligibility policy.
 */
object ProviderWorkSourcePortfolio {

    fun assess(
        providerId: ProviderId,
        facetId: String?,
        queryTitle: String,
        independentlyVerifiedAuthors: Set<String>?,
        candidates: List<ProviderWorkCandidate>,
    ): List<ProviderWorkSourceAssessment> {
        val normalizedQuery = normalizeTitle(queryTitle)
        require(normalizedQuery.isNotEmpty()) { "Portfolio query title cannot be empty" }
        val expectedAuthors = independentlyVerifiedAuthors
            ?.mapNotNull(::normalizeCreator)
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

        return candidates.distinctBy(ProviderWorkCandidate::externalWorkId).map { candidate ->
            val exactTitle = sequenceOf(candidate.title)
                .plus(candidate.aliases.asSequence())
                .any { normalizeTitle(it) == normalizedQuery }
            val detailVerified = candidate.detailOutcome in VERIFIED_SOURCE_DETAILS
            val sourceAuthors = candidate.authors.mapNotNull(::normalizeCreator).toSet()
            val verification = when {
                !exactTitle -> ProviderWorkVerification.TITLE_UNVERIFIED
                expectedAuthors == null -> ProviderWorkVerification.CANONICAL_METADATA_UNVERIFIED
                !detailVerified -> ProviderWorkVerification.SOURCE_DETAIL_UNVERIFIED
                sourceAuthors.isEmpty() -> ProviderWorkVerification.MISSING_AUTHOR_EVIDENCE
                sourceAuthors.none { it in expectedAuthors } -> ProviderWorkVerification.AUTHOR_CONFLICT
                else -> ProviderWorkVerification.AUTHOR_CORROBORATED
            }
            val qualified = sequenceOf(candidate.title)
                .plus(candidate.aliases.asSequence())
                .any { explicitlyQualifiedEdition(it, normalizedQuery) }
            ProviderWorkSourceAssessment(
                ref = ProviderBindingRef(
                    providerId = providerId,
                    facetId = facetId,
                    externalWorkId = candidate.externalWorkId,
                ),
                workVerification = verification,
                sourceChapterPageAttested = candidate.coverageOutcome == ProviderCoverageOutcome.OBSERVED &&
                    candidate.coverageObservedCount?.let { it > 0 } == true,
                editionQualification = if (qualified) {
                    ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT
                } else {
                    ProviderSourceEditionQualification.UNRESOLVED
                },
            )
        }
    }

    private fun normalizeCreator(name: String): String? =
        name.trim().lowercase()
            .split(CREATOR_SEPARATORS)
            .filter(String::isNotBlank)
            .takeIf { it.isNotEmpty() }
            ?.sorted()
            ?.joinToString(" ")

    private fun normalizeTitle(title: String): String =
        title.trim().lowercase().replace(WHITESPACE, " ")

    private fun explicitlyQualifiedEdition(sourceTitle: String, normalizedBase: String): Boolean {
        val name = normalizeTitle(sourceTitle)
        if (!name.startsWith(normalizedBase) || name == normalizedBase) return false
        val suffix = name.removePrefix(normalizedBase).trim()
        if (suffix.none(Char::isLetterOrDigit)) return false
        if (suffix.firstOrNull() !in listOf('(', '[', '-', ':')) return false
        return EDITION_MARKERS.any(suffix::contains)
    }

    private val VERIFIED_SOURCE_DETAILS = setOf(
        ProviderWorkDetailOutcome.VERIFIED_WITH_ALIASES,
        ProviderWorkDetailOutcome.VERIFIED_NO_ALIASES,
    )
    private val WHITESPACE = Regex("\\s+")
    private val CREATOR_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
    private val EDITION_MARKERS = listOf(
        "colored",
        "coloured",
        "full color",
        "full colour",
        "omnibus",
        "one shot",
        "oneshot",
        "special edition",
    )
}

enum class ProviderWorkVerification {
    AUTHOR_CORROBORATED,
    CANONICAL_METADATA_UNVERIFIED,
    SOURCE_DETAIL_UNVERIFIED,
    MISSING_AUTHOR_EVIDENCE,
    AUTHOR_CONFLICT,
    TITLE_UNVERIFIED,
}

enum class ProviderSourceEditionQualification {
    /** No source-attested variant label; this is NOT confirmation of original edition. */
    UNRESOLVED,
    SOURCE_ATTESTED_VARIANT,
}

data class ProviderWorkSourceAssessment(
    val ref: ProviderBindingRef,
    val workVerification: ProviderWorkVerification,
    val editionQualification: ProviderSourceEditionQualification,
    val sourceChapterPageAttested: Boolean = false,
) {
    /**
     * A first page with source IDs and numerically valid chapter labels permits
     * *provisional, per-HID* observation only. No editorial equivalence is asserted.
     */
    val canCollectProvisionalChapters: Boolean
        get() = workVerification == ProviderWorkVerification.AUTHOR_CORROBORATED &&
            editionQualification == ProviderSourceEditionQualification.UNRESOLVED &&
            sourceChapterPageAttested

    /**
     * Explicitly fail closed: work identity is not the authority to create
     * canonical chapters or open Reader pages. Those require independently
     * validated source chapter and editorial identities.
     */
    val canPromoteChapters: Boolean get() = false
    val canOpenReader: Boolean get() = false
}
