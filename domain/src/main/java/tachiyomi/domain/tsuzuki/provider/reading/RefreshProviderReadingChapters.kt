package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence

class RefreshProviderReadingChapters internal constructor(
    private val gateway: ProviderReadingGateway,
    private val evidenceAdapter: ProviderChapterEvidenceAdapter,
    private val publishSnapshot: suspend (
        canonicalTitleId: String,
        producerId: String,
        snapshotObservedAt: Long,
        evidence: List<ChapterEvidence>,
    ) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onPageObserved: suspend (pageCount: Int, observationCount: Int) -> Unit = { _, _ -> },
    private val onPageCollected: suspend (List<ProviderChapterObservation>, Long) -> Unit = { _, _ -> },
    private val onPageStarted: suspend (Int) -> Unit = {},
    private val onPageFinished: suspend (Int, Int?, ProviderErrorCode?, Long) -> Unit = { _, _, _, _ -> },
) {

    constructor(
        gateway: ProviderReadingGateway,
        evidenceAdapter: ProviderChapterEvidenceAdapter,
        reconcileChapterEvidence: ReconcileChapterEvidence,
    ) : this(
        gateway = gateway,
        evidenceAdapter = evidenceAdapter,
        publishSnapshot = reconcileChapterEvidence::executeProviderSnapshot,
    )

    suspend fun execute(
        binding: ProviderReadingBinding,
    ): ProviderCallResult<Int> {
        if (binding.availability != ProviderBindingAvailability.AVAILABLE) {
            return failure(ProviderErrorCode.UNAVAILABLE)
        }

        // Snapshot age starts before the first page so delayed refreshes cannot overwrite or
        // detach evidence from a newer complete inventory that finished first.
        val snapshotObservedAt = clock()
        require(snapshotObservedAt >= 0L) { "Provider snapshot timestamp must not be negative" }

        val observations = ArrayList<ProviderChapterObservation>()
        val seenChapterIds = hashSetOf<String>()
        val seenCursors = hashSetOf<String>()
        var cursor: ProviderCursor? = null

        repeat(MAX_PAGES) {
            val pageIndex = it + 1
            onPageStarted(pageIndex)
            val startedNanos = System.nanoTime()
            val result = gateway.chapters(
                providerId = binding.ref.providerId,
                request = ProviderReadingChaptersRequest(
                    binding = binding.ref,
                    cursor = cursor,
                ),
            )
            val elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L
            val page = when (result) {
                is ProviderCallResult.Success -> {
                    onPageFinished(pageIndex, result.value.items.size, null, elapsedMillis)
                    result.value
                }
                is ProviderCallResult.Failure -> {
                    onPageFinished(pageIndex, null, result.error.code, elapsedMillis)
                    return result
                }
            }

            page.items.forEach { observation ->
                if (!seenChapterIds.add(observation.providerChapterId)) {
                    return failure(ProviderErrorCode.MALFORMED_RESULT)
                }
                observations += observation
                if (observations.size > MAX_OBSERVATIONS) {
                    return failure(ProviderErrorCode.RESOURCE_LIMIT)
                }
            }

            val next = page.nextCursor
            if (next != null && !seenCursors.add(next.value)) {
                return failure(ProviderErrorCode.MALFORMED_RESULT)
            }
            // These are sanitized page-level diagnostics only, not partial chapter inventory.
            // Never publish a canonical snapshot until the final page is verified, so a
            // stalled or failed later page cannot detach previously recorded chapters.
            // The incomplete result must not pretend that the inventory is complete.
            onPageObserved(it + 1, observations.size)
            // Observed chapters may be used additively if a later page stalls. This is
            // not a complete snapshot and must never detach older Provider support.
            onPageCollected(page.items, snapshotObservedAt)
            if (next == null) {
                val evidence = evidenceAdapter.toEvidence(
                    canonicalTitleId = binding.canonicalTitleId,
                    binding = binding.ref,
                    observations = observations,
                    observedAt = snapshotObservedAt,
                )
                publishSnapshot(
                    binding.canonicalTitleId,
                    binding.ref.evidenceProducerId(),
                    snapshotObservedAt,
                    evidence,
                )
                return ProviderCallResult.Success(observations.size)
            }
            cursor = next
        }

        return failure(ProviderErrorCode.RESOURCE_LIMIT)
    }

    private fun failure(code: ProviderErrorCode) =
        ProviderCallResult.Failure(
            ProviderError(
                code = code,
                retryable = false,
            ),
        )

    private companion object {
        const val MAX_PAGES = 32
        const val MAX_OBSERVATIONS = 16_000
    }
}
