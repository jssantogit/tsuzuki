package tachiyomi.domain.tsuzuki.provider.reading

import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.provider.ProviderId

data class ProviderChapterReadingOption(
    val canonicalChapterId: String,
    val bindingId: String,
    val providerId: ProviderId,
    val facetId: String?,
    val externalWorkId: String,
    val providerChapterId: String,
) {
    init {
        require(canonicalChapterId.isNotBlank()) { "Canonical chapter ID must not be blank" }
        require(bindingId.isNotBlank()) { "Provider binding ID must not be blank" }
        require(externalWorkId.isNotBlank()) { "Provider external work ID must not be blank" }
        require(providerChapterId.isNotBlank()) { "Provider chapter ID must not be blank" }
    }

    fun bindingRef() = ProviderBindingRef(
        providerId = providerId,
        facetId = facetId,
        externalWorkId = externalWorkId,
    )
}

class ResolveProviderChapterReading(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val evidenceRepository: ChapterEvidenceRepository,
    private val bindingRepository: ProviderReadingBindingRepository,
    private val gateway: ProviderReadingGateway,
) {

    suspend fun options(canonicalChapterId: String): List<ProviderChapterReadingOption> {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId) ?: return emptyList()
        val bindings = bindingRepository.getByTitle(chapter.canonicalTitleId)
            .asSequence()
            .filter { it.availability == ProviderBindingAvailability.AVAILABLE }
            .associateBy { it.ref.evidenceProducerId() }
        if (bindings.isEmpty()) return emptyList()

        return evidenceRepository.getByCanonicalTitleId(chapter.canonicalTitleId)
            .asSequence()
            .filter { persisted ->
                persisted.mappedCanonicalChapterId == canonicalChapterId &&
                    persisted.evidence.producerKind == ProducerKind.PROVIDER &&
                    persisted.evidence.authority == ChapterEvidenceAuthority.PROVIDER_PROVISIONAL
            }
            .mapNotNull { persisted ->
                val providerChapterId = persisted.evidence.externalChapterKey ?: return@mapNotNull null
                val binding = bindings[persisted.evidence.producerId] ?: return@mapNotNull null
                ProviderChapterReadingOption(
                    canonicalChapterId = canonicalChapterId,
                    bindingId = binding.id,
                    providerId = binding.ref.providerId,
                    facetId = binding.ref.facetId,
                    externalWorkId = binding.ref.externalWorkId,
                    providerChapterId = providerChapterId,
                )
            }
            .distinct()
            .sortedWith(
                compareBy<ProviderChapterReadingOption>(
                    { it.providerId.value },
                    { it.facetId.orEmpty() },
                    { it.bindingId },
                    { it.providerChapterId },
                ),
            )
            .toList()
    }

    suspend fun delivery(
        option: ProviderChapterReadingOption,
    ): ProviderCallResult<ProviderReadingDelivery> {
        val current = options(option.canonicalChapterId)
            .firstOrNull { it == option }
            ?: return unavailable()

        return gateway.pages(
            providerId = current.providerId,
            request = ProviderReadingPagesRequest(
                binding = current.bindingRef(),
                providerChapterId = current.providerChapterId,
            ),
        )
    }

    private fun unavailable() =
        ProviderCallResult.Failure(
            ProviderError(
                code = ProviderErrorCode.UNAVAILABLE,
                retryable = false,
            ),
        )
}
