package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence

class RefreshProviderReadingChapters internal constructor(
    private val gateway: ProviderReadingGateway,
    private val evidenceAdapter: ProviderChapterEvidenceAdapter,
    private val publishEvidence: suspend (canonicalTitleId: String, evidence: List<ChapterEvidence>) -> Unit,
) {

    constructor(
        gateway: ProviderReadingGateway,
        evidenceAdapter: ProviderChapterEvidenceAdapter,
        reconcileChapterEvidence: ReconcileChapterEvidence,
    ) : this(
        gateway = gateway,
        evidenceAdapter = evidenceAdapter,
        publishEvidence = reconcileChapterEvidence::execute,
    )

    suspend fun execute(
        binding: ProviderReadingBinding,
    ): ProviderCallResult<Int> {
        if (binding.availability != ProviderBindingAvailability.AVAILABLE) {
            return failure(ProviderErrorCode.UNAVAILABLE)
        }

        val observations = ArrayList<ProviderChapterObservation>()
        val seenChapterIds = hashSetOf<String>()
        val seenCursors = hashSetOf<String>()
        var cursor: ProviderCursor? = null

        repeat(MAX_PAGES) {
            val result = gateway.chapters(
                providerId = binding.ref.providerId,
                request = ProviderReadingChaptersRequest(
                    binding = binding.ref,
                    cursor = cursor,
                ),
            )
            val page = when (result) {
                is ProviderCallResult.Success -> result.value
                is ProviderCallResult.Failure -> return result
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
            if (next == null) {
                val evidence = evidenceAdapter.toEvidence(
                    canonicalTitleId = binding.canonicalTitleId,
                    binding = binding.ref,
                    observations = observations,
                )
                publishEvidence(binding.canonicalTitleId, evidence)
                return ProviderCallResult.Success(observations.size)
            }
            if (!seenCursors.add(next.value)) {
                return failure(ProviderErrorCode.MALFORMED_RESULT)
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
