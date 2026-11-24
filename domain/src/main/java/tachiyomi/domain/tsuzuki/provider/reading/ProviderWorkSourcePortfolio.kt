package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.provider.ProviderId
import java.text.Normalizer

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
     * MangaFire retains its established source-specific policy. MangaDex can
     * enter the provisional portfolio only with verified source details,
     * expanded creators and observed numeric coverage for the requested facet.
     *
     * MangaDot is not trusted by this predicate. Its experimental native
     * coverage path must independently fetch and validate the exact chapter page.
     * No internal source can borrow another source's author authority.
     */
    fun trustedCandidates(
        providerId: ProviderId,
        facetId: String?,
        candidates: List<ProviderWorkCandidate>,
    ): List<ProviderWorkCandidate> = when (providerId.value) {
        MANGAFIRE_PROVIDER_ID -> candidates
        MANGAALL_PROVIDER_ID -> candidates.filter { candidate ->
            when {
                facetId == "en" && candidate.externalWorkId.startsWith(MANGAALL_MANGAFIRE_PREFIX) ->
                    MANGAFIRE_HID.matches(candidate.externalWorkId.removePrefix(MANGAALL_MANGAFIRE_PREFIX))
                facetId in setOf("en", "pt-br") &&
                    candidate.externalWorkId.startsWith(MANGAALL_MANGADEX_PREFIX) -> {
                    // Exact detail + expanded authors + observed numeric chapters
                    // from the requested facet; title-only matches never qualify.
                    MANGADEX_HID.matches(candidate.externalWorkId.removePrefix(MANGAALL_MANGADEX_PREFIX)) &&
                        candidate.detailOutcome in VERIFIED_SOURCE_DETAILS &&
                        candidate.authors.isNotEmpty() &&
                        candidate.coverageOutcome == ProviderCoverageOutcome.OBSERVED &&
                        candidate.coverageObservedCount?.let { it > 0 } == true
                }
                else -> false
            }
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
            ?.flatMap(::creatorKeys)
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }

        return candidates.distinctBy(ProviderWorkCandidate::externalWorkId).map { candidate ->
            val exactTitle = sequenceOf(candidate.title)
                .plus(candidate.aliases.asSequence())
                .any { normalizeTitle(it) == normalizedQuery }
            val detailVerified = candidate.detailOutcome in VERIFIED_SOURCE_DETAILS
            val sourceAuthors = candidate.authors.flatMap(::creatorKeys).toSet()
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

    private fun creatorKeys(name: String): Set<String> = buildSet {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        // A public creator credit may explicitly publish the Latin spelling
        // followed by its Hangul spelling in parentheses. Treat each complete
        // spelling as independently usable, not a concatenation of both.
        // Never split arbitrary Latin parentheticals, labels or partial names.
        val spellings = buildSet {
            add(normalized)
            BILINGUAL_KOREAN_CREDIT.matchEntire(normalized.trim())?.let {
                add(it.groupValues[1])
                add(it.groupValues[2])
            }
        }
        for (spelling in spellings) {
            normalizeCreator(spelling)?.let(::add)
            // Romanized full names may spell one explicitly hyphenated token
            // with or without a hyphen. Do not concatenate independent words,
            // infer a missing surname or alter single-token pseudonyms.
            if (spelling.trim().split(WHITESPACE).size >= 2) {
                val joined = EXPLICIT_NAME_HYPHEN.replace(spelling) {
                    it.groupValues[1] + it.groupValues[2]
                }
                if (joined != spelling) normalizeCreator(joined)?.let(::add)
            }
        }
    }

    private fun normalizeCreator(name: String): String? {
        val tokens = name.trim().lowercase()
            .split(CREATOR_SEPARATORS)
            .filter(String::isNotBlank)
            .takeIf { it.isNotEmpty() }
            ?: return null
        // This portfolio is only a source of *provisional* chapter evidence;
        // it cannot auto-bind editions or grant Reader access. Full Japanese
        // creator names commonly differ only by a transcribed long vowel:
        // "KUI Ryoko" vs "Ryouko Kui" / "Ryōko Kui". Compare ALL name tokens,
        // without surname-only/fuzzy matching. Do not normalize single-token
        // pseudonyms (e.g. "ONE") into a broader identity.
        val normalized = if (tokens.size >= 2) {
            tokens.map { token ->
                val withoutMacrons = Normalizer.normalize(token, Normalizer.Form.NFKD)
                    .replace(CREATOR_COMBINING_MARKS, "")
                JAPANESE_LONG_YO.replace(withoutMacrons) { match -> match.value.dropLast(1) }
            }
        } else {
            tokens
        }
        return normalized.sorted().joinToString(" ")
    }

    private fun normalizeTitle(title: String): String =
        title.replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')
            .trim().lowercase().replace(WHITESPACE, " ")

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
            MANGAALL_PROVIDER_ID ->
                candidate.externalWorkId
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
    private const val MANGAALL_MANGADEX_PREFIX = "mangadex:"
    private val MANGADEX_HID = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
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
    private val CREATOR_COMBINING_MARKS = Regex("\\p{M}+")
    private val BILINGUAL_KOREAN_CREDIT = Regex(
        """([\p{IsLatin}][\p{IsLatin}\p{M} ,.'’\-‐‑]{0,127}?)\s*\(([가-힣]{2,32}(?: [가-힣]{1,32})?)\)""",
    )
    private val EXPLICIT_NAME_HYPHEN = Regex("([A-Za-z])[-‐‑]([A-Za-z])")
    private val JAPANESE_LONG_YO = Regex("(?:ry|ky|gy|sh|ch|j|ny|hy|by|py|my)ou")
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
