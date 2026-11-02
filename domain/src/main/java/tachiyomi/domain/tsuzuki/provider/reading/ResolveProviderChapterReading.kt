package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.interactor.hasConflictingIntegerChapterHint
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
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
    private val volumeParser: ParseCanonicalChapterVolume = ParseCanonicalChapterVolume(),
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
                val explicitLabelVolume = volumeParser.execute(evidence.rawLabel)
                val observedVolume = evidence.volume ?: explicitLabelVolume
                observedIdentity?.isSpecific == true &&
                    parsed.confidence >= MIN_READING_IDENTITY_CONFIDENCE &&
                    observedIdentity == chapter.identity &&
                    !hasConflictingIntegerChapterHint(parsed, evidence.rawNumber) &&
                    // The source's volume may be present only in the label;
                    // reject conflicts or malformed explicit volume markers.
                    (
                        !volumeParser.hasExplicitVolumePrefix(evidence.rawLabel) ||
                            explicitLabelVolume != null
                        ) &&
                    (
                        evidence.volume == null ||
                            explicitLabelVolume == null ||
                            evidence.volume == explicitLabelVolume
                        ) &&
                    // Unknown canonical volume is not a wildcard for source-attested volume 112.
                    (observedVolume == null || observedVolume == chapter.volume)
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

        val result = gateway.pages(
            providerId = current.providerId,
            request = ProviderReadingPagesRequest(
                binding = current.bindingRef(),
                providerChapterId = current.providerChapterId,
            ),
        )
        // A bare numeric 'Ch. 0' can be an incorrectly numbered full-volume
        // bundle at the source. The 2026 physical smoke observed 182, 186 and
        // 191 pages on different works. Preserve valid chapter zeros and
        // fractions; reject only implausibly volume-sized page deliveries when
        // the canonical regular zero has no independent volume qualification.
        val chapter = canonicalChapterRepository.getById(current.canonicalChapterId)
        val pageList = (result as? ProviderCallResult.Success)?.value as? ProviderReadingDelivery.PageList
        if (
            chapter != null &&
            chapter.type == CanonicalChapterType.REGULAR &&
            chapter.baseNumber == 0 &&
            chapter.part == null &&
            chapter.alphaSuffix == null &&
            chapter.decimalFraction == null &&
            chapter.volume == null &&
            pageList != null &&
            pageList.pages.size >= MIN_SUSPECT_VOLUME_PAGE_COUNT
        ) {
            return ProviderCallResult.Failure(
                ProviderError(
                    code = ProviderErrorCode.CHAPTER_IDENTITY_UNVERIFIED,
                    retryable = false,
                    observedPageCount = pageList.pages.size,
                ),
            )
        }
        return result
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

        // Only a delivery shape safety limit, not a chapter-count/identity inference.
        const val MIN_SUSPECT_VOLUME_PAGE_COUNT = 120

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
