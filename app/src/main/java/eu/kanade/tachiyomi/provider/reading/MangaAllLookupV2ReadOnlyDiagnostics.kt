package eu.kanade.tachiyomi.provider.reading

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Explicitly UNPUBLISHED, read-only future contract used by the Host's
 * offline V2 parser tests. It is not wired to any production gateway.
 *
 * Important: this is intentionally NOT a ProviderPage<ProviderWorkCandidate>.
 * A partially observed lookup must never grant a canonical binding, chapter
 * mapping, preferred edition or Reader source.
 */
internal object MangaAllLookupV2ReadOnlyDiagnostics {
    private const val PROVIDER_ID = "app.tsuzuki.mangaall"
    private const val UNSIGNED = "UNSIGNED_CANDIDATE_NOT_LIVE"
    private val sources = listOf("mangadex", "mangadot", "mangafire")
    private val statuses = setOf("SUCCESS_PAGE", "FAILED", "NOT_QUERIED")
    private val failureCodes = setOf(
        "CHALLENGE_BLOCKED",
        "HTTP_ERROR",
        "INVALID_RESPONSE",
        "UNCLASSIFIED_ERROR",
    )
    private val id = Regex("[A-Za-z0-9._:-]{1,160}")
    private val unsafe = Regex("(?i)https?://|(?:cookie|authorization|bearer)\\s*:")
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    @Serializable
    private data class FailureDto(
        val code: String,
        val httpStatus: Int? = null,
        val retryable: Boolean,
    )

    @Serializable
    private data class SourceDto(
        val source: String,
        val attempts: Int,
        val status: String,
        val pageComplete: Boolean? = null,
        val observedCandidates: Int,
        val observationsTruncated: Boolean,
        val unsafeCandidate: Boolean,
        val failure: FailureDto? = null,
    )

    @Serializable
    private data class CandidateDto(
        val source: String,
        val externalWorkId: String,
        val title: String,
        val aliases: List<String>,
        val authors: List<String>,
        val editorialIdentityVerified: Boolean,
        val bindingEligible: Boolean,
    )

    @Serializable
    private data class EnvelopeDto(
        val contractVersion: Int,
        val providerId: String,
        val state: String,
        val lookupComplete: Boolean,
        val canContinue: Boolean,
        val continuationCursor: String? = null,
        val sourceStatuses: List<SourceDto>,
        val observedCandidates: List<CandidateDto>,
        val eligibleForCanonicalBinding: Boolean,
        val eligibleForReader: Boolean,
        val provenance: String,
    )

    /** Typed observations, not production work IDs or Reader option tokens. */
    data class SourceObservation(
        val source: String,
        val status: String,
        val attempts: Int,
        val observedCount: Int,
        val pageComplete: Boolean?,
        val observationsTruncated: Boolean,
        val unsafeCandidate: Boolean,
        val failureCode: String?,
        val httpStatus: Int?,
        val retryable: Boolean?,
    )

    data class ObservedWork(
        val source: String,
        val sourceWorkId: String,
        val title: String,
        val aliases: List<String>,
        val authors: List<String>,
    )

    data class ReadOnlyAudit(
        val state: String,
        val lookupComplete: Boolean,
        val canContinue: Boolean,
        /** Opaque Host continuation: diagnostics only, never a work binding. */
        val continuationCursor: String?,
        val sources: List<SourceObservation>,
        val observedWorks: List<ObservedWork>,
    )

    private fun safeText(value: String): Boolean =
        value.length in 1..160 &&
            value.none { it.isISOControl() || it == '<' || it == '>' } &&
            !unsafe.containsMatchIn(value)

    /**
     * Returns null on ANY incompatible/unsafe V2 response, including V1 pages.
     * The caller may use only diagnostic evidence; no binding API is provided.
     */
    fun decodeReadOnly(payload: String): ReadOnlyAudit? {
        if (payload.toByteArray(Charsets.UTF_8).size > 48 * 1024) return null
        val envelope = try {
            json.decodeFromString<EnvelopeDto>(payload)
        } catch (_: Exception) {
            return null
        }
        if (
            envelope.contractVersion != 2 ||
            envelope.providerId != PROVIDER_ID ||
            envelope.provenance != UNSIGNED ||
            envelope.eligibleForCanonicalBinding ||
            envelope.eligibleForReader ||
            envelope.sourceStatuses.map { it.source } != sources ||
            envelope.observedCandidates.size > 96
        ) {
            return null
        }
        if (
            envelope.canContinue != (envelope.continuationCursor != null) ||
            envelope.continuationCursor?.let {
                it.length !in 1..512 || it.any(Char::isISOControl) || unsafe.containsMatchIn(it)
            } == true
        ) {
            return null
        }
        val counts = envelope.observedCandidates.groupingBy { it.source }.eachCount()
        for (source in envelope.sourceStatuses) {
            if (source.status !in statuses ||
                source.attempts !in 0..1 ||
                source.observedCandidates !in 0..32 ||
                counts.getOrDefault(source.source, 0) != source.observedCandidates
            ) {
                return null
            }
            when (source.status) {
                "NOT_QUERIED" -> {
                    if (
                        source.attempts != 0 ||
                        source.pageComplete != null ||
                        source.failure != null ||
                        source.observedCandidates != 0 ||
                        source.observationsTruncated ||
                        source.unsafeCandidate
                    ) {
                        return null
                    }
                }

                "SUCCESS_PAGE" -> {
                    if (
                        source.attempts != 1 ||
                        source.pageComplete == null ||
                        source.failure != null
                    ) {
                        return null
                    }
                }

                "FAILED" -> {
                    if (
                        source.attempts != 1 ||
                        source.pageComplete != null ||
                        source.failure == null ||
                        source.observedCandidates != 0
                    ) {
                        return null
                    }
                }
            }
            source.failure?.let {
                if (
                    it.code !in failureCodes ||
                    it.httpStatus?.let { http -> http !in 100..599 } == true ||
                    (it.code == "CHALLENGE_BLOCKED" && it.httpStatus?.let { http -> http in 200..299 } == true)
                ) {
                    return null
                }
            }
        }
        val complete = envelope.sourceStatuses.all {
            it.status == "SUCCESS_PAGE" &&
                it.pageComplete == true &&
                !it.observationsTruncated &&
                !it.unsafeCandidate
        } && !envelope.canContinue
        if (
            (envelope.lookupComplete && !complete) ||
            ((envelope.state == "COMPLETE_SINGLE_PAGE") != envelope.lookupComplete) ||
            envelope.state !in setOf("COMPLETE_SINGLE_PAGE", "PARTIAL_EVIDENCE", "INCONCLUSIVE") ||
            (
                (envelope.state == "PARTIAL_EVIDENCE") !=
                    (envelope.observedCandidates.isNotEmpty() && !envelope.lookupComplete)
                ) ||
            (envelope.state == "INCONCLUSIVE" && envelope.observedCandidates.isNotEmpty())
        ) {
            return null
        }
        val seen = hashSetOf<String>()
        val works = envelope.observedCandidates.map { candidate ->
            if (
                candidate.source !in sources ||
                !candidate.externalWorkId.startsWith(candidate.source + ":") ||
                !id.matches(candidate.externalWorkId) ||
                !seen.add(candidate.externalWorkId) ||
                candidate.bindingEligible ||
                candidate.editorialIdentityVerified ||
                !safeText(candidate.title) ||
                candidate.aliases.size > 20 ||
                candidate.authors.size > 20 ||
                !candidate.aliases.all(::safeText) ||
                !candidate.authors.all(::safeText)
            ) {
                return null
            }
            ObservedWork(
                candidate.source,
                candidate.externalWorkId,
                candidate.title,
                candidate.aliases,
                candidate.authors,
            )
        }
        return ReadOnlyAudit(
            envelope.state,
            envelope.lookupComplete,
            envelope.canContinue,
            envelope.continuationCursor,
            envelope.sourceStatuses.map {
                SourceObservation(
                    it.source,
                    it.status,
                    it.attempts,
                    it.observedCandidates,
                    it.pageComplete,
                    it.observationsTruncated,
                    it.unsafeCandidate,
                    it.failure?.code,
                    it.failure?.httpStatus,
                    it.failure?.retryable,
                )
            },
            works,
        )
    }
}
