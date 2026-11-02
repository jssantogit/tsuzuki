package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.hasConflictingIntegerChapterHint
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.model.PreparedHttpPage

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

@Inject
class ResolveProviderChapterReading(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val evidenceRepository: ChapterEvidenceRepository,
    private val bindingRepository: ProviderReadingBindingRepository,
    private val workSourceAssociationRepository: ProviderWorkSourceAssociationRepository =
        ProviderWorkSourceAssociationRepository.NoOp,
    private val providerRegistry: ProviderRegistry,
    private val gateway: ProviderReadingGateway,
    private val managedResources: ProviderManagedResourceResolver = ProviderManagedResourceResolver.DenyAll,
    private val chapterLabelParser: ParseCanonicalChapterLabel = ParseCanonicalChapterLabel(),
) {

    suspend fun options(canonicalChapterId: String): List<ProviderChapterReadingOption> {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId) ?: return emptyList()
        providerRegistry.awaitReady()
        val registrations = providerRegistry.providers().associateBy { it.descriptor.id }
        val bindings = bindingRepository.getByTitle(chapter.canonicalTitleId)
            .asSequence()
            .filter { it.availability == ProviderBindingAvailability.AVAILABLE }
            .filter { binding ->
                registrations[binding.ref.providerId]?.isEligibleForReading(binding.ref.facetId) == true
            }
            .associateBy { it.ref.evidenceProducerId() }
        val sourceAssociations = workSourceAssociationRepository.getByTitle(chapter.canonicalTitleId)
            .asSequence()
            // Matching canonical *work* alone never licenses editorial variants.
            // For a provisional Reader option, require an independently
            // corroborated author, the exact source HID, and a persisted
            // source chapter whose number/volume matches below.
            .filter { it.workVerification == ProviderWorkVerification.AUTHOR_CORROBORATED }
            .filter { it.editionQualification == ProviderSourceEditionQualification.UNRESOLVED }
            .filter { source ->
                registrations[source.ref.providerId]?.isEligibleForReading(source.ref.facetId) == true
            }
            .toList()
        val sources = buildMap<String, Pair<String, ProviderBindingRef>> {
            bindings.forEach { (producerId, binding) ->
                put(producerId, binding.id to binding.ref)
            }
            sourceAssociations.forEach { source ->
                val producerId = source.ref.evidenceProducerId()
                if (producerId !in this) {
                    put(producerId, "work-source:$producerId" to source.ref)
                }
            }
        }
        if (sources.isEmpty()) return emptyList()

        return evidenceRepository.getByCanonicalTitleId(chapter.canonicalTitleId)
            .asSequence()
            .filter { persisted ->
                persisted.mappedCanonicalChapterId == canonicalChapterId &&
                    persisted.evidence.producerKind == ProducerKind.PROVIDER &&
                    persisted.evidence.authority == ChapterEvidenceAuthority.PROVIDER_PROVISIONAL
            }
            .filter { persisted ->
                // A persisted mapping can outlive a source correction or legacy
                // migration. Recheck chapter semantics at the Reader boundary:
                // a source chapter 84 must not open canonical chapter 1.
                val evidence = persisted.evidence
                val parsed = chapterLabelParser.execute(evidence.rawLabel, evidence.rawNumber)
                // Exact decimal/part v2 identities contain additional precision
                // that the legacy parser deliberately cannot represent.
                val observedIdentity = if (chapter.numberingKind == null) {
                    parsed.identity
                } else {
                    chapterLabelParser.previewExactNumbering(evidence.rawLabel)
                }
                observedIdentity?.isSpecific == true &&
                    parsed.confidence >= MIN_READING_IDENTITY_CONFIDENCE &&
                    observedIdentity == chapter.identity &&
                    !hasConflictingIntegerChapterHint(parsed, evidence.rawNumber) &&
                    // A source-attested volume cannot be treated as a wildcard
                    // when the canonical volume is unknown. Otherwise chapter
                    // zero from volume 112 could serve an unrelated chapter zero.
                    (evidence.volume == null || evidence.volume == chapter.volume)
            }
            .mapNotNull { persisted ->
                val providerChapterId = persisted.evidence.externalChapterKey ?: return@mapNotNull null
                val (bindingId, ref) = sources[persisted.evidence.producerId] ?: return@mapNotNull null
                ProviderChapterReadingOption(
                    canonicalChapterId = canonicalChapterId,
                    bindingId = bindingId,
                    providerId = ref.providerId,
                    facetId = ref.facetId,
                    externalWorkId = ref.externalWorkId,
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

    suspend fun preparedContent(
        option: ProviderChapterReadingOption,
    ): ProviderCallResult<PreparedChapterContent> {
        return when (val result = delivery(option)) {
            is ProviderCallResult.Failure -> result
            is ProviderCallResult.Success -> when (val delivery = result.value) {
                is ProviderReadingDelivery.PageList -> ProviderCallResult.Success(
                    PreparedChapterContent.HttpPages(
                        delivery.pages.map { page ->
                            PreparedHttpPage(
                                url = page.url,
                                headers = page.headers,
                                allowedOrigins = page.allowedOrigins,
                                allowLocalNetwork = page.allowLocalNetwork,
                            )
                        },
                    ),
                )
                is ProviderReadingDelivery.ManagedFile -> {
                    val uri = managedResources.resolve(
                        providerId = option.providerId,
                        resource = delivery.resource,
                        format = delivery.format,
                    )
                    if (uri == null) {
                        ProviderCallResult.Failure(
                            ProviderError(
                                code = ProviderErrorCode.MALFORMED_RESULT,
                                retryable = false,
                            ),
                        )
                    } else {
                        ProviderCallResult.Success(
                            PreparedChapterContent.CanonicalDownload(
                                uri = uri,
                                format = delivery.format.name,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun ProviderRegistration.isEligibleForReading(facetId: String?): Boolean =
        lifecycleStatus == ProviderLifecycleStatus.ENABLED &&
            descriptor.runtime == ProviderRuntimeKind.SCRIPT &&
            REQUIRED_READING_CAPABILITIES.all { it in enabledCapabilities } &&
            if (facets.isEmpty()) {
                facetId == null
            } else {
                facets.any { it.facetId == facetId }
            }

    private companion object {
        const val MIN_READING_IDENTITY_CONFIDENCE = 0.90

        val REQUIRED_READING_CAPABILITIES = setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
            ProviderCapabilities.ReadingPagesV1,
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
