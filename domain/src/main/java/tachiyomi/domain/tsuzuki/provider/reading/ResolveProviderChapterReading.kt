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

/**
 * Causal, count-only audit for a Reader option lookup that returned no source.
 * Counts describe persisted evidence, not a promise that upstream still serves
 * every previously observed chapter.
 */
data class ProviderReadingNoMatchAudit(
    val eligibleSourceCount: Int,
    val providerEvidenceCount: Int,
    val matchingNumberEvidenceCount: Int,
    val mappedEvidenceCount: Int,
    val mappedEligibleSourceEvidenceCount: Int,
    val reason: ProviderReadingNoMatchReason,
)

/** Closed structural chapter metadata for an explicitly selected source, never source-written text. */
data class ProviderReadingSourceIdentityAudit(
    val sourceNumberMilli: Long?,
    val sourceVolume: Int?,
    val labelHasExplicitVolumeMarker: Boolean,
    val sourceTitleIsVolumeContainer: Boolean,
    val sourceTitlePresent: Boolean,
)

enum class ProviderReadingNoMatchReason {
    NO_ELIGIBLE_SOURCE,
    SOURCE_EDITION_EXCLUDED,
    NO_PROVIDER_EVIDENCE,
    NO_MATCHING_SOURCE_CHAPTER,
    NO_CANONICAL_MAPPING,
    NO_ELIGIBLE_MAPPED_SOURCE,
    UNVERIFIED_EARLY_TITLE,
    IDENTITY_REJECTED,
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
        val associations = workSourceAssociationRepository.getByTitle(chapter.canonicalTitleId)
        // An editorial variant is negative evidence for automatic matching,
        // never proof that an alternate source is the original edition.
        // A later verified source lookup may reveal that a previously auto-bound
        // HID is a colored/omnibus variant. Never silently keep serving it as
        // the base edition. An explicit user-confirmed edition remains intact.
        val attestedVariantProducers = associations.asSequence()
            // Source-attested negative edition evidence does not need author
            // corroboration: it cannot prove an alternate HID, only disqualify
            // an automatic base-edition assumption for this exact source.
            .filter { it.editionQualification == ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT }
            .map { it.ref.evidenceProducerId() }
            .toSet()
        val bindings = bindingRepository.getByTitle(chapter.canonicalTitleId)
            .asSequence()
            .filter { it.availability == ProviderBindingAvailability.AVAILABLE }
            .filter { binding ->
                binding.verification == ProviderBindingVerification.USER_CONFIRMED ||
                    binding.ref.evidenceProducerId() !in attestedVariantProducers
            }
            .filter { binding ->
                registrations[binding.ref.providerId]?.isEligibleForReading(binding.ref.facetId) == true
            }
            .associateBy { it.ref.evidenceProducerId() }
        val sourceAssociations = associations.asSequence()
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
                // MangaFire can report a complete volume as numbered chapter 0.
                // A positive, source-authored "Volume N [Extra]" title is a
                // disqualifier even if the source and canonical volume agree.
                val sourceIsVolumeBundle = chapter.type == CanonicalChapterType.REGULAR &&
                    chapter.baseNumber == 0 &&
                    evidence.title?.trim()?.matches(SOURCE_VOLUME_TITLE) == true
                // A provider can attach an arbitrary scene title to a numeric
                // zero/zero-fraction. A synthetic "Ch. 0" prefix created by the
                // provider is NOT source-authored evidence that this scene is
                // a canonical prologue. Require an explicit source-title number
                // or prologue marker when the chapter has no qualified volume.
                val unqualifiedEarlyScene = isUnqualifiedEarlyScene(chapter, evidence)
                observedIdentity?.isSpecific == true &&
                    !unqualifiedEarlyScene &&
                    parsed.confidence >= MIN_READING_IDENTITY_CONFIDENCE &&
                    observedIdentity == chapter.identity &&
                    !sourceIsVolumeBundle &&
                    !hasConflictingIntegerChapterHint(parsed, evidence.rawNumber) &&
                    // The source's volume may be present only in the label;
                    // reject conflicts or malformed explicit volume markers.
                    (
                        !volumeParser.hasExplicitVolumeMarker(evidence.rawLabel) ||
                            explicitLabelVolume != null
                        ) &&
                    (
                        evidence.volume == null ||
                            explicitLabelVolume == null ||
                            evidence.volume == explicitLabelVolume
                        ) &&
                    // Unknown canonical volume is not a wildcard for source-attested volume 112.
                    (
                        observedVolume == null ||
                            observedVolume == chapter.volume ||
                            // Vol. 1 Ch. 1 is a defensible first-volume
                            // identity even if the catalogue has not learned
                            // its volume yet. This does NOT license Vol. 112
                            // Ch. 1 or arbitrary source volume mismatches.
                            (
                                chapter.type == CanonicalChapterType.REGULAR &&
                                    chapter.baseNumber == 1 &&
                                    chapter.part == null &&
                                    chapter.volume == null &&
                                    observedVolume == 1
                                )
                        )
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

    /**
     * A source number is not independent editorial evidence when a Provider
     * constructs the chapter prefix. Accept a source-authored title that
     * explicitly names the same early chapter, or a genuine prologue heading.
     * Keep title-free labels and volume-qualified editions unchanged.
     */
    private fun isUnqualifiedEarlyScene(
        chapter: tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter,
        evidence: tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence,
    ): Boolean = chapter.type == CanonicalChapterType.REGULAR &&
        chapter.baseNumber == 0 &&
        chapter.volume == null &&
        !evidence.title.isNullOrBlank() &&
        !hasEarlyChapterTitleAttestation(chapter, evidence.title)

    private fun hasEarlyChapterTitleAttestation(
        chapter: tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter,
        title: String,
    ): Boolean {
        if (chapter.part == null && chapter.decimalFraction == null &&
            PROLOGUE_TITLE.matches(title.trim())
        ) {
            return true
        }
        val sourceIdentity = if (chapter.numberingKind == null) {
            chapterLabelParser.execute(title).identity
        } else {
            chapterLabelParser.previewExactNumbering(title)
        }
        return sourceIdentity?.isSpecific == true && sourceIdentity == chapter.identity
    }

    /**
     * Prefer exactly one source from an edition that the user explicitly
     * confirmed. A source found automatically or a corroborated work-level HID
     * does not acquire this authority. Multiple chapter variants even inside
     * that confirmed edition remain ambiguous.
     *
     * [options] must be the eligible, identity-checked options for this exact
     * chapter. Delivery independently revalidates the chosen option.
     */
    suspend fun uniqueUserConfirmedOption(
        canonicalChapterId: String,
        options: List<ProviderChapterReadingOption>,
    ): ProviderChapterReadingOption? {
        if (options.size < 2) return null
        val chapter = canonicalChapterRepository.getById(canonicalChapterId) ?: return null
        val confirmed = bindingRepository.getByTitle(chapter.canonicalTitleId)
            .asSequence()
            .filter { it.verification == ProviderBindingVerification.USER_CONFIRMED }
            .filter { it.availability == ProviderBindingAvailability.AVAILABLE }
            .associateBy { it.id }
        return options.filter { option ->
            val binding = confirmed[option.bindingId]
            option.canonicalChapterId == canonicalChapterId &&
                binding != null &&
                binding.canonicalTitleId == chapter.canonicalTitleId &&
                binding.ref == option.bindingRef()
        }.singleOrNull()
    }

    /**
     * Explain why a canonical chapter has no Reader source without exporting
     * raw labels, URLs, or IDs. This is invoked only after options() was empty.
     * It never selects a different HID or mutates canonical evidence.
     */
    suspend fun auditNoMatch(canonicalChapterId: String): ProviderReadingNoMatchAudit {
        val chapter = canonicalChapterRepository.getById(canonicalChapterId)
            ?: return ProviderReadingNoMatchAudit(
                eligibleSourceCount = 0,
                providerEvidenceCount = 0,
                matchingNumberEvidenceCount = 0,
                mappedEvidenceCount = 0,
                mappedEligibleSourceEvidenceCount = 0,
                reason = ProviderReadingNoMatchReason.NO_ELIGIBLE_SOURCE,
            )

        providerRegistry.awaitReady()
        val registrations = providerRegistry.providers().associateBy { it.descriptor.id }
        val associations = workSourceAssociationRepository.getByTitle(chapter.canonicalTitleId)
        val attestedVariantProducers = associations.asSequence()
            // Source-attested negative edition evidence does not need author
            // corroboration: it cannot prove an alternate HID, only disqualify
            // an automatic base-edition assumption for this exact source.
            .filter { it.editionQualification == ProviderSourceEditionQualification.SOURCE_ATTESTED_VARIANT }
            .map { it.ref.evidenceProducerId() }
            .toSet()
        val eligibleProducers = buildSet {
            bindingRepository.getByTitle(chapter.canonicalTitleId)
                .filter { it.availability == ProviderBindingAvailability.AVAILABLE }
                .filter { binding ->
                    binding.verification == ProviderBindingVerification.USER_CONFIRMED ||
                        binding.ref.evidenceProducerId() !in attestedVariantProducers
                }
                .filter { registrations[it.ref.providerId]?.isEligibleForReading(it.ref.facetId) == true }
                .forEach { add(it.ref.evidenceProducerId()) }

            associations
                .filter { it.workVerification == ProviderWorkVerification.AUTHOR_CORROBORATED }
                .filter { it.editionQualification == ProviderSourceEditionQualification.UNRESOLVED }
                .filter { registrations[it.ref.providerId]?.isEligibleForReading(it.ref.facetId) == true }
                .forEach { add(it.ref.evidenceProducerId()) }
        }

        val observations = evidenceRepository.getByCanonicalTitleId(chapter.canonicalTitleId)
            .filter { it.evidence.producerKind == ProducerKind.PROVIDER }
            .filter { it.evidence.authority == ChapterEvidenceAuthority.PROVIDER_PROVISIONAL }

        val matchingNumberCount = observations.count { persisted ->
            val evidence = persisted.evidence
            val parsed = chapterLabelParser.execute(evidence.rawLabel, evidence.rawNumber)
            val identity = if (chapter.numberingKind == null) {
                parsed.identity
            } else {
                chapterLabelParser.previewExactNumbering(evidence.rawLabel)
            }
            identity?.isSpecific == true &&
                parsed.confidence >= MIN_READING_IDENTITY_CONFIDENCE &&
                identity == chapter.identity &&
                !hasConflictingIntegerChapterHint(parsed, evidence.rawNumber)
        }
        val mapped = observations.filter { it.mappedCanonicalChapterId == canonicalChapterId }
        val mappedEligible = mapped.count { it.evidence.producerId in eligibleProducers }
        val reason = when {
            eligibleProducers.isEmpty() && attestedVariantProducers.isNotEmpty() ->
                ProviderReadingNoMatchReason.SOURCE_EDITION_EXCLUDED
            eligibleProducers.isEmpty() -> ProviderReadingNoMatchReason.NO_ELIGIBLE_SOURCE
            observations.isEmpty() -> ProviderReadingNoMatchReason.NO_PROVIDER_EVIDENCE
            matchingNumberCount == 0 -> ProviderReadingNoMatchReason.NO_MATCHING_SOURCE_CHAPTER
            mapped.isEmpty() -> ProviderReadingNoMatchReason.NO_CANONICAL_MAPPING
            mappedEligible == 0 -> ProviderReadingNoMatchReason.NO_ELIGIBLE_MAPPED_SOURCE
            mapped.any { it.evidence.producerId in eligibleProducers &&
                isUnqualifiedEarlyScene(chapter, it.evidence)
            } -> ProviderReadingNoMatchReason.UNVERIFIED_EARLY_TITLE
            else -> ProviderReadingNoMatchReason.IDENTITY_REJECTED
        }

        return ProviderReadingNoMatchAudit(
            eligibleSourceCount = eligibleProducers.size,
            providerEvidenceCount = observations.size,
            matchingNumberEvidenceCount = matchingNumberCount,
            mappedEvidenceCount = mapped.size,
            mappedEligibleSourceEvidenceCount = mappedEligible,
            reason = reason,
        )
    }

    /**
     * Read a single exact persisted Provider chapter for opt-in Reader diagnostics.
     * The source HID and mapped chapter must match the already selected option.
     * Never include a raw title, label, source URL, or provider payload in logs.
     */
    suspend fun auditSourceIdentity(
        option: ProviderChapterReadingOption,
    ): ProviderReadingSourceIdentityAudit? {
        val persisted = evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.PROVIDER,
            producerId = option.bindingRef().evidenceProducerId(),
            externalChapterKey = option.providerChapterId,
        ) ?: return null
        val evidence = persisted.evidence
        if (
            persisted.mappedCanonicalChapterId != option.canonicalChapterId ||
            evidence.producerKind != ProducerKind.PROVIDER ||
            evidence.authority != ChapterEvidenceAuthority.PROVIDER_PROVISIONAL ||
            evidence.producerId != option.bindingRef().evidenceProducerId() ||
            evidence.externalChapterKey != option.providerChapterId
        ) {
            return null
        }

        val sourceNumberMilli = evidence.rawNumber
            ?.takeIf { it.isFinite() && it >= 0.0 && it <= 1_000_000.0 }
            ?.let { kotlin.math.round(it * 1000).toLong() }
        return ProviderReadingSourceIdentityAudit(
            sourceNumberMilli = sourceNumberMilli,
            sourceVolume = evidence.volume?.takeIf { it in 0..10_000 },
            labelHasExplicitVolumeMarker = volumeParser.hasExplicitVolumeMarker(evidence.rawLabel),
            sourceTitleIsVolumeContainer = evidence.title?.trim()?.matches(SOURCE_VOLUME_TITLE) == true,
            sourceTitlePresent = !evidence.title.isNullOrBlank(),
        )
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
        val PROLOGUE_TITLE = Regex(
            "^(?:prologue|prologo|intro|introduction|preface|pilot)(?:\\b|[\\s:.-]).*",
            RegexOption.IGNORE_CASE,
        )
        val SOURCE_VOLUME_TITLE = Regex("^vol(?:ume)?\\.?\\s*[0-9]+(?:$|[\\s:.-].*)$", RegexOption.IGNORE_CASE)
        const val MIN_READING_IDENTITY_CONFIDENCE = 0.90

        // Only a delivery shape safety limit, not a chapter-count/identity inference.
        // A54 code11 physically exposed a 102-page false Chapter 0 (One Punch-Man).
        // This is still a defensive heuristic, not positive proof of source identity.
        const val MIN_SUSPECT_VOLUME_PAGE_COUNT = 100

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
