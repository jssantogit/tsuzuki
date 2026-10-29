package tachiyomi.domain.tsuzuki.chapter.inventory

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import java.io.IOException
import kotlin.time.Clock

/** A source of release/evidence metadata, not a provider of readable pages. */
interface IndependentInventoryProducer {
    val producerId: String

    /** One bounded request for a known external work. The cursor is an opaque page key. */
    suspend fun fetchPage(scope: InventoryScope, pageKey: String): Result<InventoryEvidenceBatch>

    /**
     * An optional live authorization check for producers that issue remote requests.
     * Native producers recheck it at the HTTP dispatch boundary, including retries
     * or fallback requests. The default keeps local and third-party producers
     * source-compatible; the orchestrator still checks consent before saving.
     */
    suspend fun fetchPageAuthorized(
        scope: InventoryScope,
        pageKey: String,
        isEnabled: suspend () -> Boolean,
    ): Result<InventoryEvidenceBatch> {
        if (!isEnabled()) return Result.failure(IllegalStateException("Inventory integration is disabled"))
        return fetchPage(scope, pageKey)
    }
}

enum class InventoryRefreshStop {
    EXHAUSTED,
    PARTIAL,
    FAILED,
    NO_VERIFIED_WORK,
    DISABLED,
}

enum class InventoryFetchFailureReason {
    ACCESS_DENIED,
    RATE_LIMITED,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    NETWORK_ERROR,
    INVALID_RESPONSE,
    UNKNOWN,
}

/** Stable source failure classification. Never carries URLs, work IDs or source response data. */
class InventorySourceFetchException(val reason: InventoryFetchFailureReason) :
    IOException("Independent inventory request failed: ${reason.name}")

data class InventoryRefreshResult(
    val stop: InventoryRefreshStop,
    val pagesSaved: Int,
    val observationsSaved: Int,
    val failureReason: InventoryFetchFailureReason? = null,
)

/**
 * Shadow-only acquisition. Only verified work crosswalks are used. No Reader,
 * ContentProvider, canonical chapter or sync path is involved.
 */
@Inject
class RefreshIndependentInventory(
    private val canonicalTitles: CanonicalTitleRepository,
    private val ledger: InventoryEvidenceRepository,
) {
    suspend fun execute(
        canonicalTitleId: String,
        producer: IndependentInventoryProducer,
        isEnabled: suspend () -> Boolean,
        maxPages: Int = 3,
        clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ): InventoryRefreshResult {
        require(maxPages in 1..10) { "Inventory fetch must have a bounded page budget" }
        require(canonicalTitleId.isNotBlank())
        require(producer.producerId.isNotBlank())

        if (!isEnabled()) return InventoryRefreshResult(InventoryRefreshStop.DISABLED, 0, 0)
        val identity = verifiedIdentity(canonicalTitleId, producer.producerId)
            ?: return InventoryRefreshResult(InventoryRefreshStop.NO_VERIFIED_WORK, 0, 0)

        // Work-verified != edition-verified. Never infer edition from an external
        // series ID, and never promote a release while edition scope is unknown.
        val scope = InventoryScope(
            canonicalTitleId = canonicalTitleId,
            producerId = producer.producerId,
            externalWorkId = identity.externalId,
            editionKey = null,
            language = null,
            workIdentityVerified = true,
            editionIdentityVerified = false,
        )

        var pageKey = resumePageKey(ledger.batchesByTitle(canonicalTitleId), scope)
        var saved = 0
        var observations = 0
        val visited = hashSetOf<String>()
        repeat(maxPages) {
            if (!isEnabled()) return InventoryRefreshResult(InventoryRefreshStop.DISABLED, saved, observations)
            if (verifiedIdentity(canonicalTitleId, producer.producerId) != identity) {
                return InventoryRefreshResult(InventoryRefreshStop.NO_VERIFIED_WORK, saved, observations)
            }
            check(visited.add(pageKey)) { "Inventory cursor loop detected" }

            val page = try {
                producer.fetchPageAuthorized(scope, pageKey, isEnabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            val failureReason = page.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                when (error) {
                    is InventorySourceFetchException -> error.reason
                    is IOException -> InventoryFetchFailureReason.NETWORK_ERROR
                    is IllegalArgumentException, is IllegalStateException ->
                        InventoryFetchFailureReason.INVALID_RESPONSE
                    else -> InventoryFetchFailureReason.UNKNOWN
                }
            }
            currentCoroutineContext().ensureActive()
            // Revocation, logout and remapping during fetch must discard the late
            // response, even if the HTTP request has already succeeded.
            if (!isEnabled()) return InventoryRefreshResult(InventoryRefreshStop.DISABLED, saved, observations)
            if (verifiedIdentity(canonicalTitleId, producer.producerId) != identity) {
                return InventoryRefreshResult(InventoryRefreshStop.NO_VERIFIED_WORK, saved, observations)
            }

            val batch = page.getOrNull()
                ?: InventoryEvidenceBatch(
                    scope = scope,
                    pageKey = pageKey,
                    observedAt = clock(),
                    outcome = InventoryFetchOutcome.FAILED,
                    claims = emptyList(),
                )
            require(batch.scope == scope && batch.pageKey == pageKey) {
                "Inventory response changed its verified work, edition, language or cursor"
            }

            ledger.saveBatch(batch)
            saved += 1
            observations += batch.claims.size
            when (batch.outcome) {
                InventoryFetchOutcome.EXHAUSTED ->
                    return InventoryRefreshResult(InventoryRefreshStop.EXHAUSTED, saved, observations)
                InventoryFetchOutcome.FAILED ->
                    return InventoryRefreshResult(
                        InventoryRefreshStop.FAILED,
                        saved,
                        observations,
                        failureReason ?: InventoryFetchFailureReason.UNKNOWN,
                    )
                InventoryFetchOutcome.NOT_ATTEMPTED ->
                    return InventoryRefreshResult(InventoryRefreshStop.PARTIAL, saved, observations)
                InventoryFetchOutcome.PARTIAL -> {
                    val next = batch.nextCursor?.takeIf(String::isNotBlank)
                        ?: return InventoryRefreshResult(InventoryRefreshStop.PARTIAL, saved, observations)
                    check(next !in visited) { "Inventory cursor did not advance" }
                    pageKey = next
                }
            }
        }
        return InventoryRefreshResult(InventoryRefreshStop.PARTIAL, saved, observations)
    }

    /**
     * Follow only previously persisted pages of this exact work/edition scope.
     * A partial page with a cursor is a checkpoint, not an editorial completeness
     * claim. Revisit failed pages, and restart only after an exhausted traversal
     * or an invalid/stale cursor chain.
     */
    private fun resumePageKey(
        pages: List<InventoryStoredBatch>,
        scope: InventoryScope,
    ): String {
        val known = pages.filter { it.scope == scope }.associateBy { it.pageKey }
        val visited = hashSetOf<String>()
        var cursor = FIRST_PAGE
        repeat(MAX_RESUME_DEPTH) {
            if (!visited.add(cursor)) return FIRST_PAGE
            val batch = known[cursor] ?: return cursor
            when (batch.outcome) {
                InventoryFetchOutcome.EXHAUSTED -> return FIRST_PAGE
                InventoryFetchOutcome.FAILED, InventoryFetchOutcome.NOT_ATTEMPTED -> return cursor
                InventoryFetchOutcome.PARTIAL -> {
                    cursor = batch.nextCursor?.takeIf(String::isNotBlank) ?: return FIRST_PAGE
                }
            }
        }
        return FIRST_PAGE
    }

    private suspend fun verifiedIdentity(
        canonicalTitleId: String,
        producerId: String,
    ): ExternalIdentity? = canonicalTitles.getExternalIdentities(canonicalTitleId)
        .filter { it.provider == producerId && it.verified && it.canonicalTitleId == canonicalTitleId }
        .singleOrNull()

    private companion object {
        const val FIRST_PAGE = "start"
        const val MAX_RESUME_DEPTH = 100
    }
}
