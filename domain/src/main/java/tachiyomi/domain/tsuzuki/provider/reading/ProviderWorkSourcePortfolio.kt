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

    /**
     * Only the actual signed MangaFire implementation, standalone or embedded
     * inside MangaAll, currently supplies detail/coverage evidence strong enough
     * for the established provisional work-source portfolio.
     *
     * MangaDex/MangaDot still contribute lookup/chapter evidence, but their
     * similarly named records must not borrow MangaFire's author authority.
     * MangaFire's standalone facet stays English in this version.
     */
    fun trustedCandidates(
        providerId: ProviderId,
        facetId: String?,
        candidates: List<ProviderWorkCandidate>,
    ): List<ProviderWorkCandidate> = when (providerId.value) {
        MANGAFIRE_PROVIDER_ID -> candidates
        MANGAALL_PROVIDER_ID -> if (facetId == "en") {
            candidates.filter { candidate ->
                val hid = candidate.externalWorkId.removePrefix(MANGAALL_MANGAFIRE_PREFIX)
                candidate.externalWorkId.startsWith(MANGAALL_MANGAFIRE_PREFIX) &&
                    MANGAFIRE_HID.matches(hid)
            }
        } else {
            emptyList()
        }
        else -> emptyList()
    }

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
            val qualified = hasSourceAttestedAlternateEdition(
                providerId = providerId,
                candidate = candidate,
                baseTitle = queryTitle,
            )
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

    /**
     * One closed, shared edition-variant predicate for discovery, shadow source
     * portfolios and Reader eligibility. Source URL evidence may only exclude
     * a variant; it must never promote a competing HID by popularity or count.
     */
    fun hasSourceAttestedAlternateEdition(
        providerId: ProviderId,
        candidate: ProviderWorkCandidate,
        baseTitle: String,
    ): Boolean {
        val normalizedBase = normalizeTitle(baseTitle)
        if (normalizedBase.isEmpty()) return false
        return sequenceOf(candidate.title)
            .plus(candidate.aliases.asSequence())
            .any { explicitlyQualifiedEdition(it, normalizedBase) } ||
            mangaFireUrlAttestsEditionVariant(providerId, candidate, normalizedBase)
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

    /**
     * A verified MangaFire title URL may expose an explicit editorial variant
     * even when the source's primary title and aliases omit the qualifier.
     * This is only negative edition evidence: never infer that another HID is
     * the preferred/canonical edition or use a source URL to prove authors.
     *
     * Reject foreign hosts, schemes, routes, HIDs, query strings, and slug
     * translations that do not match the normalized canonical lookup title.
     */
    private fun mangaFireUrlAttestsEditionVariant(
        providerId: ProviderId,
        candidate: ProviderWorkCandidate,
        normalizedQuery: String,
    ): Boolean {
        val hid = when (providerId.value) {
            MANGAFIRE_PROVIDER_ID -> candidate.externalWorkId
            MANGAALL_PROVIDER_ID -> candidate.externalWorkId
                .takeIf { it.startsWith(MANGAALL_MANGAFIRE_PREFIX) }
                ?.removePrefix(MANGAALL_MANGAFIRE_PREFIX)
                ?: return false
            else -> return false
        }
        if (!MANGAFIRE_HID.matches(hid)) return false
        val canonicalSlug = normalizedQuery
            .replace(NON_SLUG_CHARACTERS, "-")
            .trim('-')
        if (canonicalSlug.isEmpty()) return false
        val url = candidate.url ?: return false
        val exactTitleUrl = Regex(
            "^https://mangafire\\.to/title/${Regex.escape(hid)}-([a-z0-9-]+)/?$",
            RegexOption.IGNORE_CASE,
        )
        val pathSlug = exactTitleUrl.matchEntire(url)?.groupValues?.getOrNull(1)?.lowercase()
            ?: return false
        val qualifier = pathSlug.removePrefix("$canonicalSlug-")
        if (qualifier == pathSlug || qualifier.isEmpty()) return false
        return EDITION_SLUG_MARKERS.any { token ->
            qualifier == token || qualifier.startsWith("$token-") ||
                qualifier.endsWith("-$token") || qualifier.contains("-$token-")
        }
    }

    private const val MANGAFIRE_PROVIDER_ID = "app.tsuzuki.mangafire"
    private const val MANGAALL_PROVIDER_ID = "app.tsuzuki.mangaall"
    private const val MANGAALL_MANGAFIRE_PREFIX = "mangafire:"
    private val MANGAFIRE_HID = Regex("[a-zA-Z0-9_-]{1,128}")
    private val NON_SLUG_CHARACTERS = Regex("[^a-z0-9]+")
    private val EDITION_SLUG_MARKERS = listOf(
        "fan-colored", "fan-coloredd", "fan-coloured",
        "colored", "coloredd", "coloured",
        "full-color", "full-colour", "omnibus", "one-shot", "oneshot",
        "special-edition",
    )

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
