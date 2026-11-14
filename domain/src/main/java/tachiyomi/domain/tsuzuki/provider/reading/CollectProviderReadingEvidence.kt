package tachiyomi.domain.tsuzuki.provider.reading

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticErrorCategory
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticMangaAllNetworkSource
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderCandidateReason
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderDetailOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderEditionQualification
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderLookupDecision
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderMalformedResultStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderWorkVerification
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.interactor.ResolveCanonicalMetadata
import tachiyomi.domain.tsuzuki.integration.repository.CanonicalMetadataSnapshotRepository
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository
import java.security.MessageDigest
import kotlin.math.roundToLong

/** One complete chapter inventory emitted by one exact Provider binding. */
data class ProviderReadingEvidenceSnapshot(
    val producerId: String,
    val observedAt: Long,
    val evidence: List<ChapterEvidence>,
) {
    init {
        require(producerId.isNotBlank()) { "Provider evidence producer ID must not be blank" }
        require(observedAt >= 0L) { "Provider evidence timestamp must not be negative" }
        require(
            evidence.all { observation ->
                observation.producerKind == ProducerKind.PROVIDER &&
                    observation.producerId == producerId &&
                    observation.authority == ChapterEvidenceAuthority.PROVIDER_PROVISIONAL &&
                    observation.observedAt == observedAt
            },
        ) {
            "Provider evidence snapshot must contain one exact Provider binding and timestamp"
        }
    }
}

/**
 * Result of collecting every enabled SCRIPT reading Provider for one canonical title.
 *
 * Snapshots stay separated by exact Provider binding so reconciliation can detach stale support
 * when a later complete inventory becomes empty or drops chapters.
 */
data class ProviderReadingEvidenceCollection(
    val snapshots: List<ProviderReadingEvidenceSnapshot> = emptyList(),
    val bindingCount: Int = 0,
    val complete: Boolean = true,
    /** Source-attested pages from an incomplete inventory; additive only, never a snapshot. */
    val partialEvidence: List<ChapterEvidence> = emptyList(),
) {
    val evidence: List<ChapterEvidence>
        get() = snapshots.flatMap(ProviderReadingEvidenceSnapshot::evidence)
}

/**
 * Connects enabled SCRIPT chapter-inventory Providers to Tsuzuki's canonical chapter-evidence pipeline.
 *
 * Bindings are deliberately conservative: a Provider work is auto-bound only when exactly one
 * candidate is an exact normalized title/alias match for the canonical title and active facet.
 * Ambiguous results remain unbound rather than leaking fuzzy Provider identity into canonical state.
 */
class CollectProviderReadingEvidence private constructor(
    private val canonicalTitleRepository: CanonicalTitleRepository,
    private val providerRegistry: ProviderRegistry,
    private val gateway: ProviderReadingGateway,
    private val bindingRepository: ProviderReadingBindingRepository,
    private val evidenceAdapter: ProviderChapterEvidenceAdapter,
    private val clock: () -> Long,
    private val diagnostics: StructuredDiagnosticRecorder,
    private val titleNameObservationRepository: TitleNameObservationRepository?,
    private val integrationRegistry: IntegrationRegistry?,
    private val canonicalMetadataSnapshotRepository: CanonicalMetadataSnapshotRepository?,
    private val canonicalMetadataResolver: ResolveCanonicalMetadata?,
    private val workSourceAssociationRepository: ProviderWorkSourceAssociationRepository?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit,
) {

    @Inject
    constructor(
        canonicalTitleRepository: CanonicalTitleRepository,
        providerRegistry: ProviderRegistry,
        gateway: ProviderReadingGateway,
        bindingRepository: ProviderReadingBindingRepository,
        diagnostics: StructuredDiagnosticRecorder,
        titleNameObservationRepository: TitleNameObservationRepository,
        integrationRegistry: IntegrationRegistry,
        canonicalMetadataSnapshotRepository: CanonicalMetadataSnapshotRepository,
        canonicalMetadataResolver: ResolveCanonicalMetadata,
        workSourceAssociationRepository: ProviderWorkSourceAssociationRepository,
    ) : this(
        canonicalTitleRepository = canonicalTitleRepository,
        providerRegistry = providerRegistry,
        gateway = gateway,
        bindingRepository = bindingRepository,
        evidenceAdapter = ProviderChapterEvidenceAdapter(),
        clock = System::currentTimeMillis,
        diagnostics = diagnostics,
        titleNameObservationRepository = titleNameObservationRepository,
        integrationRegistry = integrationRegistry,
        canonicalMetadataSnapshotRepository = canonicalMetadataSnapshotRepository,
        canonicalMetadataResolver = canonicalMetadataResolver,
        workSourceAssociationRepository = workSourceAssociationRepository,
        constructorMarker = Unit,
    )

    constructor(
        canonicalTitleRepository: CanonicalTitleRepository,
        providerRegistry: ProviderRegistry,
        gateway: ProviderReadingGateway,
        bindingRepository: ProviderReadingBindingRepository,
        evidenceAdapter: ProviderChapterEvidenceAdapter,
        clock: () -> Long,
        diagnostics: StructuredDiagnosticRecorder = NoOpStructuredDiagnosticRecorder,
        titleNameObservationRepository: TitleNameObservationRepository? = null,
        integrationRegistry: IntegrationRegistry? = null,
        canonicalMetadataSnapshotRepository: CanonicalMetadataSnapshotRepository? = null,
        canonicalMetadataResolver: ResolveCanonicalMetadata? = null,
        workSourceAssociationRepository: ProviderWorkSourceAssociationRepository? = null,
    ) : this(
        canonicalTitleRepository = canonicalTitleRepository,
        providerRegistry = providerRegistry,
        gateway = gateway,
        bindingRepository = bindingRepository,
        evidenceAdapter = evidenceAdapter,
        clock = clock,
        diagnostics = diagnostics,
        titleNameObservationRepository = titleNameObservationRepository,
        integrationRegistry = integrationRegistry,
        canonicalMetadataSnapshotRepository = canonicalMetadataSnapshotRepository,
        canonicalMetadataResolver = canonicalMetadataResolver,
        workSourceAssociationRepository = workSourceAssociationRepository,
        constructorMarker = Unit,
    )

    suspend fun execute(canonicalTitleId: String): ProviderReadingEvidenceCollection {
        require(canonicalTitleId.isNotBlank()) { "Canonical title ID must not be blank" }
        providerRegistry.awaitReady()
        val canonicalTitle = canonicalTitleRepository.getById(canonicalTitleId)
            ?: return ProviderReadingEvidenceCollection(complete = false)
        val targets = readingTargets()
        val queryTitles = providerTitleLookupQueries(
            canonicalTitleId,
            canonicalTitle.displayTitle,
            titleNameObservationRepository,
            integrationRegistry,
        )
        // A cold metadata cache must not permanently block corroborated
        // work-source evidence while chapter refresh is already running.
        // Resolve through ICE's verified canonical IDs, not a Provider title
        // search. The resolver checks its active integration fingerprint,
        // coalesces in-flight requests, and never returns unverified IDs.
        // Tests without the injected resolver retain their snapshot fixtures.
        val canonicalAuthors = try {
            val metadata = if (canonicalMetadataResolver != null) {
                canonicalMetadataResolver.cached(canonicalTitleId)
                    ?: withTimeoutOrNull(MAX_CANONICAL_AUTHOR_BOOTSTRAP_MILLIS) {
                        canonicalMetadataResolver.execute(canonicalTitleId).getOrNull()
                    }
            } else {
                canonicalMetadataSnapshotRepository?.get(canonicalTitleId)?.metadata
            }
            metadata?.authors
                ?.takeIf { author ->
                    // Never let a search title or a different provider ID
                    // authorize an author's editorial identity.
                    author.externalId != null &&
                        metadata.externalIds[author.providerId] == author.externalId
                }
                ?.value
                ?.mapNotNull(::normalizedCreator)
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Metadata is best-effort; chapter refresh and existing bindings
            // must remain usable when a catalog integration is unavailable.
            null
        }
        trace(canonicalTitleId, null, DiagnosticStage.SUMMARY, DiagnosticOutcome.STARTED, targets = targets.size)

        val results = coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT_PROVIDERS)
            targets.map { target ->
                async {
                    gate.withPermit {
                        // Keep accepted pages across the bounded coroutine cancellation. They
                        // remain additive evidence only, not an authoritative full inventory.
                        val partial = mutableListOf<ChapterEvidence>()
                        var activeStage = DiagnosticStage.LOOKUP
                        var preferredBindingSelected = false
                        val finished = withTimeoutOrNull(MAX_PROVIDER_COLLECTION_MILLIS) {
                            collectTarget(
                                canonicalTitleId = canonicalTitleId,
                                titles = queryTitles,
                                target = target,
                                canonicalAuthors = canonicalAuthors,
                                onStageChanged = { activeStage = it },
                                onPreferredBindingSelected = { preferredBindingSelected = true },
                                onPageCollected = { binding, page, observedAt ->
                                    evidenceAdapter.toEvidence(
                                        canonicalTitleId = canonicalTitleId,
                                        binding = binding,
                                        observations = page,
                                        observedAt = observedAt,
                                    ).also { partial += it }
                                },
                            )
                        }
                        if (finished == null) {
                            trace(
                                canonicalTitleId,
                                target,
                                activeStage,
                                DiagnosticOutcome.TIMEOUT,
                            )
                        }
                        // Preserve valid pages only for a genuine timeout. A malformed result,
                        // identity error or resource violation must not salvage untrusted pages.
                        val stillEnabled = providerRegistry.registration(target.registration.descriptor.id) ==
                            target.registration
                        (
                            finished ?: ProviderReadingEvidenceCollection(
                                // READ is entered only after a verified binding was selected
                                // and persisted. A later timeout cannot erase that fact.
                                bindingCount = if (preferredBindingSelected) 1 else 0,
                                complete = false,
                            )
                            ).copy(
                            partialEvidence = when {
                                !stillEnabled -> emptyList()
                                finished == null -> partial.toList()
                                else -> finished.partialEvidence
                            },
                        )
                    }
                }
            }.awaitAll()
        }

        val collection = ProviderReadingEvidenceCollection(
            snapshots = results.flatMap(ProviderReadingEvidenceCollection::snapshots),
            bindingCount = results.sumOf(ProviderReadingEvidenceCollection::bindingCount),
            partialEvidence = results.flatMap(ProviderReadingEvidenceCollection::partialEvidence),
            complete = results.all(ProviderReadingEvidenceCollection::complete),
        )
        val acceptedChapters = collection.snapshots.sumOf { it.evidence.size }
        trace(
            canonicalTitleId,
            null,
            DiagnosticStage.SUMMARY,
            when {
                !collection.complete -> DiagnosticOutcome.PARTIAL
                acceptedChapters == 0 -> DiagnosticOutcome.EMPTY
                else -> DiagnosticOutcome.SUCCEEDED
            },
            targets = targets.size,
            bindings = collection.bindingCount,
            accepted = acceptedChapters,
            partialCount = collection.partialEvidence.size.takeIf { it > 0 },
            completed = collection.complete,
        )
        return collection
    }

    suspend fun configurationTokens(): List<String> {
        providerRegistry.awaitReady()
        return readingTargets().map { target ->
            buildString {
                append("provider:")
                append(target.registration.descriptor.id.value)
                append(':')
                append(target.registration.descriptor.version.code)
                append(':')
                append(target.facetId.orEmpty())
                append(':')
                append(target.registration.configurationFingerprint)
            }
        }
    }

    private suspend fun collectTarget(
        canonicalTitleId: String,
        titles: List<String>,
        target: ReadingTarget,
        canonicalAuthors: Set<String>?,
        onStageChanged: (DiagnosticStage) -> Unit,
        onPreferredBindingSelected: () -> Unit,
        onPageCollected: suspend (ProviderBindingRef, List<ProviderChapterObservation>, Long) -> List<ChapterEvidence>,
    ): ProviderReadingEvidenceCollection {
        return try {
            val existing = bindingRepository.get(
                canonicalTitleId = canonicalTitleId,
                providerId = target.registration.descriptor.id,
                facetId = target.facetId,
            )?.takeIf { it.availability == ProviderBindingAvailability.AVAILABLE }
            if (existing != null) {
                trace(canonicalTitleId, target, DiagnosticStage.BINDING, DiagnosticOutcome.REUSED, bindings = 1)
                traceBoundWork(canonicalTitleId, target, existing, DiagnosticOutcome.REUSED)
            }
            val binding = existing ?: when (
                val discovery = discoverBinding(
                    canonicalTitleId = canonicalTitleId,
                    titles = titles,
                    target = target,
                    canonicalAuthors = canonicalAuthors,
                )
            ) {
                is ProviderCallResult.Failure -> return ProviderReadingEvidenceCollection(complete = false)
                is ProviderCallResult.Success ->
                    discovery.value.binding ?: return if (discovery.value.workSources.isNotEmpty()) {
                        onStageChanged(DiagnosticStage.READ)
                        collectUnresolvedWorkSources(
                            canonicalTitleId = canonicalTitleId,
                            target = target,
                            sources = discovery.value.workSources,
                            onPageCollected = onPageCollected,
                        )
                    } else {
                        ProviderReadingEvidenceCollection(complete = !discovery.value.unresolved)
                    }
            }

            if (existing == null) {
                traceBoundWork(canonicalTitleId, target, binding, DiagnosticOutcome.ACCEPTED)
            }
            onPreferredBindingSelected()
            onStageChanged(DiagnosticStage.READ)
            var published: ProviderReadingEvidenceSnapshot? = null
            var lastPageNumber = 0
            val refresher = RefreshProviderReadingChapters(
                gateway = gateway,
                evidenceAdapter = evidenceAdapter,
                publishSnapshot = { titleId, producerId, observedAt, evidence ->
                    require(titleId == canonicalTitleId) {
                        "Provider reading snapshot belongs to another canonical title"
                    }
                    published = ProviderReadingEvidenceSnapshot(
                        producerId = producerId,
                        observedAt = observedAt,
                        evidence = evidence,
                    )
                },
                clock = clock,
                onPageStarted = { pageIndex ->
                    traceChapterRequest(canonicalTitleId, target, binding.ref, pageIndex, DiagnosticOutcome.STARTED)
                },
                onPageFinished = { pageIndex, count, errorCode, elapsedMillis ->
                    traceChapterRequest(
                        canonicalTitleId,
                        target,
                        binding.ref,
                        pageIndex,
                        if (errorCode == null) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.FAILED,
                        itemCount = count,
                        errorCode = errorCode,
                        elapsedMillis = elapsedMillis,
                    )
                },
                onPageCollected = { page, observedAt ->
                    if (diagnostics.detailedCaptureActive) {
                        traceReadingPageDetail(canonicalTitleId, target, binding.ref, lastPageNumber, page)
                    }
                    onPageCollected(binding.ref, page, observedAt)
                },
                onPageObserved = { pages, observations ->
                    lastPageNumber = pages
                    trace(
                        canonicalTitleId,
                        target,
                        DiagnosticStage.READ,
                        DiagnosticOutcome.PARTIAL,
                        observed = observations,
                        pageCount = pages,
                        name = DiagnosticEventName.PROVIDER_READING_PAGE,
                    )
                },
            )
            when (val result = refresher.execute(binding)) {
                is ProviderCallResult.Failure -> {
                    trace(
                        canonicalTitleId,
                        target,
                        DiagnosticStage.READ,
                        DiagnosticOutcome.FAILED,
                        bindings = 1,
                        errorCode = result.error.code,
                    )
                    ProviderReadingEvidenceCollection(bindingCount = 1, complete = false)
                }
                is ProviderCallResult.Success -> {
                    val snapshot = published ?: return ProviderReadingEvidenceCollection(
                        bindingCount = 1,
                        complete = false,
                    ).also {
                        trace(canonicalTitleId, target, DiagnosticStage.READ, DiagnosticOutcome.FAILED, bindings = 1)
                    }
                    trace(
                        canonicalTitleId,
                        target,
                        DiagnosticStage.READ,
                        if (result.value == 0) DiagnosticOutcome.EMPTY else DiagnosticOutcome.SUCCEEDED,
                        bindings = 1,
                        observed = result.value,
                        accepted = snapshot.evidence.size,
                    )
                    // A complete-empty preferred MangaAll facet does not prove that
                    // other *internal* sources lack source-attested chapters.
                    // Probe independently verified work sources without changing
                    // the saved edition, User progress or Reader authority.
                    val fallback = if (
                        result.value == 0 &&
                        target.registration.descriptor.id.value == "app.tsuzuki.mangaall" &&
                        target.facetId == "en" &&
                        canonicalAuthors != null &&
                        workSourceAssociationRepository != null
                    ) {
                        collectEmptyBindingAlternatives(
                            canonicalTitleId = canonicalTitleId,
                            titles = titles,
                            target = target,
                            canonicalAuthors = canonicalAuthors,
                            preferredRef = binding.ref,
                            onPageCollected = onPageCollected,
                        )
                    } else {
                        null
                    }
                    ProviderReadingEvidenceCollection(
                        snapshots = listOf(snapshot),
                        bindingCount = 1,
                        // Alternate-source search is intentionally bounded and
                        // non-exhaustive, even if the preferred snapshot is complete.
                        complete = fallback?.complete ?: true,
                        partialEvidence = fallback?.partialEvidence.orEmpty(),
                    )
                }
            }
        } catch (error: CancellationException) {
            // The caller records a timed-out Provider separately. Do not label
            // that deadline as a user/navigation cancellation in diagnostics.
            if (error !is kotlinx.coroutines.TimeoutCancellationException) {
                trace(canonicalTitleId, target, DiagnosticStage.READ, DiagnosticOutcome.CANCELLED)
            }
            throw error
        } catch (_: Throwable) {
            trace(canonicalTitleId, target, DiagnosticStage.READ, DiagnosticOutcome.THREW)
            ProviderReadingEvidenceCollection(complete = false)
        }
    }

    private suspend fun discoverBinding(
        canonicalTitleId: String,
        titles: List<String>,
        target: ReadingTarget,
        canonicalAuthors: Set<String>?,
    ): ProviderCallResult<BindingDiscovery> {
        // Work IDs are private to this Provider and are only used to narrow
        // earlier matches. They never cross a source, title, edition or facet.
        var ambiguousCandidates: List<ProviderWorkCandidate>? = null
        var unresolved = false
        val collectedWorkSources = linkedMapOf<ProviderBindingRef, ProviderWorkSourceAssessment>()
        for ((queryIndex, title) in titles.withIndex()) {
            val queryStarted = System.nanoTime()
            traceLookupAttempt(canonicalTitleId, target, queryIndex, title, DiagnosticOutcome.STARTED)
            val lookup = when (
                val result = collectLookup(
                    target.registration.descriptor.id,
                    title,
                    target.facetId,
                )
            ) {
                is ProviderCallResult.Failure -> {
                    traceLookupAttempt(
                        canonicalTitleId,
                        target,
                        queryIndex,
                        title,
                        DiagnosticOutcome.FAILED,
                        elapsedMillis = (System.nanoTime() - queryStarted) / 1_000_000L,
                        errorCode = result.error.code,
                        sourceFailureCode = result.error.sourceFailureCode,
                        malformedStage = result.error.malformedStage,
                        httpStatus = result.error.httpStatus,
                    )
                    trace(
                        canonicalTitleId,
                        target,
                        DiagnosticStage.LOOKUP,
                        DiagnosticOutcome.FAILED,
                        errorCode = result.error.code,
                        malformedStage = result.error.malformedStage,
                    )
                    return result
                }
                is ProviderCallResult.Success -> {
                    traceLookupAttempt(
                        canonicalTitleId,
                        target,
                        queryIndex,
                        title,
                        if (result.value.complete) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.PARTIAL,
                        elapsedMillis = (System.nanoTime() - queryStarted) / 1_000_000L,
                        candidateCount = result.value.items.size,
                        errorCode = result.value.partialErrorCode,
                    )
                    result.value
                }
            }
            val candidates = lookup.items
            val normalizedTitle = normalize(title)
            val matches = exactFacetMatches(title, target.facetId, candidates)
            // Source association is deliberately a shadow work-level portfolio.
            // Multiple corroborated HIDs never become one preferred edition or
            // Reader authority merely because their authors/title match.
            observeSourcePortfolio(canonicalTitleId, title, target, canonicalAuthors, matches)
                .forEach { assessment -> collectedWorkSources[assessment.ref] = assessment }
            if (!lookup.complete) {
                // MangaAll can have more upstream source pages than the Host's
                // bounded lookup permits. Retain verified work evidence, but
                // never choose an edition from a non-exhaustive candidate set.
                traceLookupCandidates(
                    canonicalTitleId,
                    target,
                    queryIndex,
                    title,
                    candidates,
                    matches,
                    selected = null,
                    primary = null,
                )
                trace(
                    canonicalTitleId,
                    target,
                    DiagnosticStage.LOOKUP,
                    DiagnosticOutcome.PARTIAL,
                    candidates = candidates.size,
                    exactMatches = matches.size,
                    bindings = 0,
                )
                return ProviderCallResult.Success(
                    BindingDiscovery(
                        unresolved = true,
                        workSources = collectedWorkSources.values
                            .filter(ProviderWorkSourceAssessment::canCollectProvisionalChapters)
                            .take(MAX_COVERAGE_CONTENDERS),
                    ),
                )
            }
            val sourcePrimaryMatches = matches.filter { normalize(it.title) == normalizedTitle }
            val priorAmbiguous = ambiguousCandidates
            val primary = sourcePrimaryMatches.singleOrNull()
            val unqualifiedPrimary = sourcePrimaryMatches
                .filterNot {
                    ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                        target.registration.descriptor.id,
                        it,
                        title,
                    )
                }
                .singleOrNull()
            // An exact name does not identify an edition. An independent
            // catalog author may eliminate a homonymous *different work*
            // only when every rival supplies positive, contradictory authors.
            // A missing creator, two same-author HIDs, or an explicit edition
            // qualifier remains unresolved.
            val authorCorroborated = matches.takeIf { it.size > 1 }?.singleOrNull { contender ->
                canonicalAuthors != null &&
                    !ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                        target.registration.descriptor.id,
                        contender,
                        title,
                    ) &&
                    contender.authors.any { normalizedCreator(it) in canonicalAuthors } &&
                    matches.filter { it.externalWorkId != contender.externalWorkId }.all { rival ->
                        rival.authors.isNotEmpty() &&
                            rival.authors.none { normalizedCreator(it) in canonicalAuthors }
                    }
            }
            // Only one currently readable English source may be selected when
            // independently verified canonical authors match *every* exact HID
            // and all other HIDs explicitly attest a completely empty English
            // first page. An unknown, failed, unprobed, colored or also-readable
            // alternative remains ambiguous. This is a reading-source decision,
            // not a claim of unique canonical edition or chapter total.
            val uniqueReadable = matches
                .takeIf { priorAmbiguous == null && it.size in 2..MAX_COVERAGE_CONTENDERS }
                ?.takeIf {
                    canonicalAuthors != null && it.all { work ->
                        !ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                            target.registration.descriptor.id,
                            work,
                            title,
                        ) &&
                            work.authors.isNotEmpty() &&
                            work.authors.any { normalizedCreator(it) in canonicalAuthors }
                    }
                }
                ?.singleOrNull { work ->
                    work.coverageOutcome == ProviderCoverageOutcome.OBSERVED &&
                        work.coverageObservedCount?.let { it > 0 } == true &&
                        matches.filter { it.externalWorkId != work.externalWorkId }.all { rival ->
                            rival.coverageOutcome == ProviderCoverageOutcome.EMPTY &&
                                rival.coverageObservedCount == 0 &&
                                rival.coverageHasMore == false
                        }
                }
            val candidate = when {
                uniqueReadable != null -> uniqueReadable
                priorAmbiguous == null && matches.size > 1 && authorCorroborated != null ->
                    authorCorroborated
                // An ambiguous first lookup may only be narrowed to a work
                // already seen. All rivals must explicitly identify a different
                // editorial variant: title similarity and chapter count alone
                // cannot establish canonical edition identity.
                priorAmbiguous != null -> primary?.takeIf { selected ->
                    !ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                        target.registration.descriptor.id,
                        selected,
                        title,
                    ) &&
                        priorAmbiguous.any { it.externalWorkId == selected.externalWorkId } &&
                        priorAmbiguous
                            .filter { it.externalWorkId != selected.externalWorkId }
                            .all {
                                ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                                    target.registration.descriptor.id,
                                    it,
                                    selected.title,
                                )
                            }
                }
                matches.size == 1 &&
                    !ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                        target.registration.descriptor.id,
                        matches.single(),
                        title,
                    ) -> matches.single()
                primary != null &&
                    !ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                        target.registration.descriptor.id,
                        primary,
                        title,
                    ) &&
                    matches
                        .filter { it.externalWorkId != primary.externalWorkId }
                        .all {
                            ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                                target.registration.descriptor.id,
                                it,
                                primary.title,
                            )
                        } -> primary
                // Two work IDs may carry the same primary title, while only
                // one source entry explicitly declares itself a special edition
                // in its own alternative names. This is affirmative source
                // metadata, not a popularity/rank/chapter-count guess.
                unqualifiedPrimary != null && matches
                    .filter { it.externalWorkId != unqualifiedPrimary.externalWorkId }
                    .all {
                        ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                            target.registration.descriptor.id,
                            it,
                            unqualifiedPrimary.title,
                        )
                    } -> unqualifiedPrimary
                matches.size > 1 -> {
                    ambiguousCandidates = matches
                    null
                }
                else -> null
            }
            val decision = when {
                candidate != null && candidate == uniqueReadable ->
                    DiagnosticProviderLookupDecision.UNIQUE_READABLE_SOURCE
                candidate != null && candidate == authorCorroborated ->
                    DiagnosticProviderLookupDecision.AUTHOR_CORROBORATED
                candidate != null -> DiagnosticProviderLookupDecision.SELECTED
                sourcePrimaryMatches.size > 1 -> DiagnosticProviderLookupDecision.MULTIPLE_PRIMARY_IDENTITIES
                matches.isNotEmpty() -> DiagnosticProviderLookupDecision.AMBIGUOUS_EDITION
                else -> DiagnosticProviderLookupDecision.NO_MATCH
            }
            if (decision == DiagnosticProviderLookupDecision.MULTIPLE_PRIMARY_IDENTITIES ||
                decision == DiagnosticProviderLookupDecision.AMBIGUOUS_EDITION
            ) {
                unresolved = true
            }
            traceLookupCandidates(
                canonicalTitleId = canonicalTitleId,
                target = target,
                queryIndex = queryIndex,
                queryTitle = title,
                candidates = candidates,
                matches = matches,
                selected = candidate,
                primary = primary,
            )
            trace(
                canonicalTitleId,
                target,
                DiagnosticStage.LOOKUP,
                when {
                    candidate != null -> DiagnosticOutcome.ACCEPTED
                    matches.isEmpty() -> DiagnosticOutcome.EMPTY
                    else -> DiagnosticOutcome.REJECTED
                },
                candidates = candidates.size,
                exactMatches = matches.size,
                bindings = if (candidate != null) 1 else 0,
                lookupDecision = decision,
            )
            if (candidate == null) {
                // A verified work may be represented by several HIDs. Once
                // even one source independently corroborates its author AND an
                // observed chapter page, record those chapters provisionally
                // without selecting an edition. Unknown rivals stay unresolved.
                // Extra title lookups cannot turn this work-level proof into
                // editorial authority, and would consume the 20-second budget.
                val confirmedSources = collectedWorkSources.values
                    .filter(ProviderWorkSourceAssessment::canCollectProvisionalChapters)
                if (confirmedSources.isNotEmpty()) {
                    return ProviderCallResult.Success(
                        BindingDiscovery(
                            unresolved = true,
                            workSources = confirmedSources.take(MAX_COVERAGE_CONTENDERS),
                        ),
                    )
                }
                continue
            }

            val now = clock()
            val binding = ProviderReadingBinding(
                id = bindingId(
                    canonicalTitleId = canonicalTitleId,
                    providerId = target.registration.descriptor.id,
                    facetId = target.facetId,
                    externalWorkId = candidate.externalWorkId,
                ),
                canonicalTitleId = canonicalTitleId,
                ref = ProviderBindingRef(
                    providerId = target.registration.descriptor.id,
                    facetId = target.facetId,
                    externalWorkId = candidate.externalWorkId,
                ),
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = now,
                updatedAt = now,
            )
            if (!bindingRepository.insertIfAbsent(binding)) {
                // A user-confirmed edition or another discovery won the same slot.
                // Never create a second Provider identity or fetch chapters using this stale result.
                trace(canonicalTitleId, target, DiagnosticStage.BINDING, DiagnosticOutcome.REJECTED)
                return ProviderCallResult.Success(BindingDiscovery(unresolved = true))
            }
            return ProviderCallResult.Success(BindingDiscovery(binding = binding))
        }
        return ProviderCallResult.Success(BindingDiscovery(unresolved = unresolved))
    }

    private suspend fun observeSourcePortfolio(
        canonicalTitleId: String,
        queryTitle: String,
        target: ReadingTarget,
        canonicalAuthors: Set<String>?,
        matches: List<ProviderWorkCandidate>,
    ): List<ProviderWorkSourceAssessment> {
        val repository = workSourceAssociationRepository ?: return emptyList()
        // Preserve the established MangaFire source-detail trust boundary.
        // MangaAll is a single Provider, but only its internal MangaFire adapter
        // currently supplies the verified authors/coverage needed for a
        // provisional source portfolio. Do not promote MangaDex/MangaDot by title.
        val trusted = ProviderWorkSourcePortfolio.trustedCandidates(
            providerId = target.registration.descriptor.id,
            facetId = target.facetId,
            candidates = matches,
        )
        if (trusted.isEmpty()) return emptyList()
        val assessed = ProviderWorkSourcePortfolio.assess(
            providerId = target.registration.descriptor.id,
            facetId = target.facetId,
            queryTitle = queryTitle,
            independentlyVerifiedAuthors = canonicalAuthors,
            candidates = trusted,
        )
        val retained = mutableListOf<ProviderWorkSourceAssessment>()
        for (assessment in assessed) {
            try {
                val recorded = repository.observe(
                    ProviderWorkSourceAssociation(
                        canonicalTitleId = canonicalTitleId,
                        ref = assessment.ref,
                        workVerification = assessment.workVerification,
                        editionQualification = assessment.editionQualification,
                        observedAt = clock(),
                    ),
                )
                if (recorded) retained += assessment
                traceWorkSourceAssociation(
                    canonicalTitleId,
                    target,
                    assessment,
                    if (recorded) DiagnosticOutcome.ACCEPTED else DiagnosticOutcome.REJECTED,
                )
                if (!recorded) {
                    // The same HID was already associated with another canonical
                    // work. Do not overwrite that independent identity.
                    trace(canonicalTitleId, target, DiagnosticStage.BINDING, DiagnosticOutcome.REJECTED)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Local shadow observation failure cannot invalidate existing
                // chapters, user-selected edition, or the Reader's current sources.
                trace(canonicalTitleId, target, DiagnosticStage.BINDING, DiagnosticOutcome.FAILED)
            }
        }
        return retained
    }

    /**
     * An existing MangaAll edition can have a *complete* empty inventory for
     * one language while another internal source has independently corroborated
     * work identity and real numbered chapters. Never replace the preferred
     * ProviderReadingBinding or invent edition equivalence: retain these as
     * source-scoped, additive provisional chapter evidence only.
     */
    private suspend fun collectEmptyBindingAlternatives(
        canonicalTitleId: String,
        titles: List<String>,
        target: ReadingTarget,
        canonicalAuthors: Set<String>,
        preferredRef: ProviderBindingRef,
        onPageCollected: suspend (ProviderBindingRef, List<ProviderChapterObservation>, Long) -> List<ChapterEvidence>,
    ): ProviderReadingEvidenceCollection {
        var allLookupsComplete = titles.size <= MAX_EMPTY_BINDING_FALLBACK_QUERIES
        for ((queryIndex, title) in titles.take(MAX_EMPTY_BINDING_FALLBACK_QUERIES).withIndex()) {
            val started = System.nanoTime()
            traceLookupAttempt(canonicalTitleId, target, queryIndex, title, DiagnosticOutcome.STARTED)
            // A first-page trusted HID is sufficient for a *provisional* read,
            // not a complete edition lookup. Do not consume every MangaDot page
            // after a source-verified MangaFire alternative has been found.
            val lookupResult = collectLookup(
                providerId = target.registration.descriptor.id,
                title = title,
                requestedLanguage = target.facetId,
                maxPages = MAX_EMPTY_BINDING_FALLBACK_LOOKUP_PAGES,
                stopWhen = { candidates ->
                    val exact = exactFacetMatches(title, target.facetId, candidates)
                    val trusted = ProviderWorkSourcePortfolio.trustedCandidates(
                        providerId = target.registration.descriptor.id,
                        facetId = target.facetId,
                        candidates = exact,
                    )
                    ProviderWorkSourcePortfolio.assess(
                        providerId = target.registration.descriptor.id,
                        facetId = target.facetId,
                        queryTitle = title,
                        independentlyVerifiedAuthors = canonicalAuthors,
                        candidates = trusted,
                    ).any { it.ref != preferredRef && it.canCollectProvisionalChapters }
                },
            )
            val lookup = when (lookupResult) {
                is ProviderCallResult.Failure -> {
                    traceLookupAttempt(
                        canonicalTitleId,
                        target,
                        queryIndex,
                        title,
                        DiagnosticOutcome.FAILED,
                        elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
                        errorCode = lookupResult.error.code,
                        sourceFailureCode = lookupResult.error.sourceFailureCode,
                        malformedStage = lookupResult.error.malformedStage,
                        httpStatus = lookupResult.error.httpStatus,
                    )
                    allLookupsComplete = false
                    continue
                }
                is ProviderCallResult.Success -> lookupResult.value
            }
            if (!lookup.complete) allLookupsComplete = false
            traceLookupAttempt(
                canonicalTitleId,
                target,
                queryIndex,
                title,
                if (lookup.complete) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.PARTIAL,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000L,
                candidateCount = lookup.items.size,
                errorCode = lookup.partialErrorCode,
            )
            val matches = exactFacetMatches(title, target.facetId, lookup.items)
            traceLookupCandidates(
                canonicalTitleId = canonicalTitleId,
                target = target,
                queryIndex = queryIndex,
                queryTitle = title,
                candidates = lookup.items,
                matches = matches,
                selected = null,
                primary = null,
            )
            val verified = observeSourcePortfolio(
                canonicalTitleId,
                title,
                target,
                canonicalAuthors,
                matches,
            ).filter { assessment ->
                assessment.ref != preferredRef && assessment.canCollectProvisionalChapters
            }.take(MAX_COVERAGE_CONTENDERS)
            if (verified.isNotEmpty()) {
                trace(
                    canonicalTitleId,
                    target,
                    DiagnosticStage.LOOKUP,
                    DiagnosticOutcome.PARTIAL,
                    candidates = lookup.items.size,
                    exactMatches = matches.size,
                    bindings = 0,
                )
                return collectUnresolvedWorkSources(
                    canonicalTitleId = canonicalTitleId,
                    target = target,
                    sources = verified,
                    onPageCollected = onPageCollected,
                )
            }
        }
        // Only an exhaustively checked set of alternative queries can affirm
        // that the previously completed preferred-empty snapshot is complete.
        // Failed or bounded lookups remain partial and are retried later.
        return ProviderReadingEvidenceCollection(complete = allLookupsComplete)
    }

    private fun exactFacetMatches(
        title: String,
        facetId: String?,
        candidates: List<ProviderWorkCandidate>,
    ): List<ProviderWorkCandidate> {
        val normalizedTitle = normalize(title)
        return candidates.asSequence()
            .filter { candidate ->
                facetId == null || candidate.language == null ||
                    candidate.language.equals(facetId, ignoreCase = true)
            }
            .filter { candidate ->
                sequenceOf(candidate.title)
                    .plus(candidate.aliases.asSequence())
                    .any { normalize(it) == normalizedTitle }
            }
            .distinctBy(ProviderWorkCandidate::externalWorkId)
            .toList()
    }

    /**
     * A work can have more than one author-corroborated source HID. Collect
     * separately from each bounded source without declaring any edition
     * canonical, overriding a preferred binding or publishing an authoritative
     * snapshot. Source chapter IDs remain scoped to their own HID.
     */
    private suspend fun collectUnresolvedWorkSources(
        canonicalTitleId: String,
        target: ReadingTarget,
        sources: List<ProviderWorkSourceAssessment>,
        onPageCollected: suspend (ProviderBindingRef, List<ProviderChapterObservation>, Long) -> List<ChapterEvidence>,
    ): ProviderReadingEvidenceCollection {
        val evidence = mutableListOf<ChapterEvidence>()
        for (source in sources.take(MAX_COVERAGE_CONTENDERS)) {
            if (!source.canCollectProvisionalChapters) continue
            val observedAt = clock()
            val sourceBinding = ProviderReadingBinding(
                // Only an ephemeral transport to the existing paginated fetcher.
                // It is NEVER inserted into ProviderReadingBindingRepository.
                id = bindingId(
                    canonicalTitleId,
                    source.ref.providerId,
                    source.ref.facetId,
                    source.ref.externalWorkId,
                ),
                canonicalTitleId = canonicalTitleId,
                ref = source.ref,
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = observedAt,
                updatedAt = observedAt,
            )
            val trustedPages = mutableListOf<Pair<List<ProviderChapterObservation>, Long>>()
            var lastPage = 0
            var activePage = 0
            val result = withTimeoutOrNull(MAX_WORK_SOURCE_COLLECTION_MILLIS) {
                RefreshProviderReadingChapters(
                    gateway = gateway,
                    evidenceAdapter = evidenceAdapter,
                    // A complete technical page sequence is not an editorial
                    // snapshot. Never detach chapters from an unverified edition.
                    publishSnapshot = null,
                    clock = clock,
                    onPageStarted = { index ->
                        activePage = index
                        traceChapterRequest(canonicalTitleId, target, source.ref, index, DiagnosticOutcome.STARTED)
                    },
                    onPageFinished = { index, count, errorCode, elapsedMillis ->
                        traceChapterRequest(
                            canonicalTitleId,
                            target,
                            source.ref,
                            index,
                            if (errorCode == null) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.FAILED,
                            itemCount = count,
                            errorCode = errorCode,
                            elapsedMillis = elapsedMillis,
                        )
                    },
                    onPageCollected = { page, pageObservedAt ->
                        trustedPages += page.toList() to pageObservedAt
                        if (diagnostics.detailedCaptureActive) {
                            traceReadingPageDetail(canonicalTitleId, target, source.ref, lastPage, page)
                        }
                    },
                    onPageObserved = { pageCount, observations ->
                        lastPage = pageCount
                        trace(
                            canonicalTitleId,
                            target,
                            DiagnosticStage.READ,
                            DiagnosticOutcome.PARTIAL,
                            observed = observations,
                            pageCount = pageCount,
                            name = DiagnosticEventName.PROVIDER_READING_PAGE,
                        )
                    },
                ).execute(sourceBinding)
            }
            if (result is ProviderCallResult.Failure &&
                result.error.code in setOf(ProviderErrorCode.MALFORMED_RESULT, ProviderErrorCode.RESOURCE_LIMIT)
            ) {
                // A structural error invalidates this HID's pending pages.
                trustedPages.clear()
            }
            when (result) {
                null -> {
                    // A bounded partial fetch is technical evidence, never an
                    // editorial snapshot. Report the exact unfinished page only
                    // under explicit detailed capture (public MangaFire HID).
                    if (activePage > 0) {
                        traceChapterRequest(
                            canonicalTitleId,
                            target,
                            source.ref,
                            activePage,
                            DiagnosticOutcome.TIMEOUT,
                        )
                    }
                    trace(
                        canonicalTitleId,
                        target,
                        DiagnosticStage.READ,
                        DiagnosticOutcome.TIMEOUT,
                        observed = trustedPages.sumOf { it.first.size },
                        pageCount = lastPage,
                    )
                }
                is ProviderCallResult.Failure -> trace(
                    canonicalTitleId,
                    target,
                    DiagnosticStage.READ,
                    DiagnosticOutcome.FAILED,
                    errorCode = result.error.code,
                )
                is ProviderCallResult.Success -> trace(
                    canonicalTitleId,
                    target,
                    DiagnosticStage.READ,
                    DiagnosticOutcome.PARTIAL,
                    observed = result.value,
                )
            }
            trustedPages.forEach { (page, pageObservedAt) ->
                // Reuse the provisional evidence already built for timeout salvage.
                // Rehashing the same chapter IDs would allocate a second copy.
                evidence += onPageCollected(source.ref, page, pageObservedAt)
            }
        }
        return ProviderReadingEvidenceCollection(
            bindingCount = 0,
            complete = false,
            partialEvidence = evidence,
        )
    }

    private suspend fun collectLookup(
        providerId: ProviderId,
        title: String,
        requestedLanguage: String?,
        maxPages: Int = MAX_LOOKUP_PAGES,
        stopWhen: ((List<ProviderWorkCandidate>) -> Boolean)? = null,
    ): ProviderCallResult<LookupSnapshot> {
        val items = mutableListOf<ProviderWorkCandidate>()
        val seenCursors = mutableSetOf<String>()
        var cursor: ProviderCursor? = null
        var pageCount = 0
        while (true) {
            if (++pageCount > maxPages) {
                return if (providerId.value == "app.tsuzuki.mangaall") {
                    ProviderCallResult.Success(LookupSnapshot(items.toList(), complete = false))
                } else {
                    malformed()
                }
            }
            val page = when (
                val result = gateway.lookup(
                    providerId = providerId,
                    request = ProviderReadingLookupRequest(
                        titles = listOf(title),
                        cursor = cursor,
                        requestedLanguage = requestedLanguage,
                        coverageEvidenceVersion = if (
                            providerId.value == "app.tsuzuki.mangafire" ||
                            providerId.value == "app.tsuzuki.mangaall"
                        ) {
                            1
                        } else {
                            null
                        },
                    ),
                )
            ) {
                is ProviderCallResult.Failure -> {
                    // A later transient source outage cannot invalidate already
                    // decoded and bounded source candidates. MangaAll may retain
                    // them only as an INCOMPLETE work lookup: never auto-bind
                    // an edition or publish a canonical chapter snapshot.
                    // Malformed/schema/policy failures still fail closed.
                    if (
                        providerId.value == "app.tsuzuki.mangaall" &&
                        items.isNotEmpty() &&
                        result.error.code in RECOVERABLE_LOOKUP_ERRORS
                    ) {
                        return ProviderCallResult.Success(
                            LookupSnapshot(items.toList(), complete = false, partialErrorCode = result.error.code),
                        )
                    }
                    return result
                }
                is ProviderCallResult.Success -> result.value
            }
            items += page.items
            if (items.size > MAX_LOOKUP_ITEMS) {
                return if (providerId.value == "app.tsuzuki.mangaall") {
                    ProviderCallResult.Success(
                        LookupSnapshot(items.take(MAX_LOOKUP_ITEMS), complete = false),
                    )
                } else {
                    malformed()
                }
            }
            val next = page.nextCursor ?: break
            // Evidence-only early exit is deliberately INCOMPLETE; it cannot
            // auto-select an edition or claim an authoritative chapter total.
            if (stopWhen?.invoke(items) == true) {
                return ProviderCallResult.Success(LookupSnapshot(items.toList(), complete = false))
            }
            if (!seenCursors.add(next.value)) return malformed()
            cursor = next
        }
        return ProviderCallResult.Success(LookupSnapshot(items.toList(), complete = true))
    }

    private fun readingTargets(): List<ReadingTarget> = providerRegistry.providers()
        .asSequence()
        .filter { registration -> registration.lifecycleStatus == ProviderLifecycleStatus.ENABLED }
        .filter { registration -> registration.descriptor.runtime == ProviderRuntimeKind.SCRIPT }
        .filter { registration -> REQUIRED_CAPABILITIES.all { it in registration.enabledCapabilities } }
        .flatMap { registration ->
            val facets = registration.facets.map { it.facetId }.ifEmpty { listOf(null) }
            facets.asSequence().map { facetId -> ReadingTarget(registration, facetId) }
        }
        .sortedWith(
            compareBy<ReadingTarget>(
                { it.registration.descriptor.id.value },
                { it.facetId.orEmpty() },
            ),
        )
        .toList()

    /**
     * Source requests that never return still leave an unmatched STARTED event,
     * followed by the collector's timeout event. This records the exact query
     * index at which the budget expired without logging upstream error text.
     */
    private fun traceLookupAttempt(
        titleId: String,
        target: ReadingTarget,
        queryIndex: Int,
        queryTitle: String,
        outcome: DiagnosticOutcome,
        elapsedMillis: Long? = null,
        candidateCount: Int? = null,
        errorCode: ProviderErrorCode? = null,
        sourceFailureCode: ProviderLookupFailureCode? = null,
        malformedStage: DiagnosticProviderMalformedResultStage? = null,
        httpStatus: Int? = null,
    ) {
        if (!diagnostics.detailedCaptureActive) return
        runCatching {
            val attributes = buildMap<String, DiagnosticAttributeValue> {
                put("provider_id", DiagnosticAttributeValue.Text(target.registration.descriptor.id.value))
                put(
                    "provider_version_code",
                    DiagnosticAttributeValue.Number(target.registration.descriptor.version.code.toLong()),
                )
                diagnostics.canonicalTitleReference(titleId)?.let {
                    put("canonical_title_ref", DiagnosticAttributeValue.Text(it))
                }
                put("query_index", DiagnosticAttributeValue.Number((queryIndex + 1).toLong()))
                if (target.registration.descriptor.id.value == "app.tsuzuki.mangafire") {
                    put("provider_lookup_title", DiagnosticAttributeValue.Text(queryTitle.take(256)))
                }
                candidateCount?.let { put("candidate_count", DiagnosticAttributeValue.Number(it.toLong())) }
                errorCode?.let { put("provider_error_code", DiagnosticAttributeValue.Text(it.name)) }
                malformedStage?.let { put("provider_malformed_stage", DiagnosticAttributeValue.Code(it)) }
                sourceFailureCode?.let { reason ->
                    val category = when (reason) {
                        ProviderLookupFailureCode.CHALLENGE_BLOCKED -> DiagnosticErrorCategory.CAPTCHA
                        ProviderLookupFailureCode.HTTP_ERROR -> DiagnosticErrorCategory.HTTP
                        ProviderLookupFailureCode.INVALID_RESPONSE -> DiagnosticErrorCategory.MALFORMED_RESPONSE
                        ProviderLookupFailureCode.UNCLASSIFIED_ERROR -> DiagnosticErrorCategory.UNKNOWN
                    }
                    put("error_category", DiagnosticAttributeValue.Code(category))
                }
                httpStatus?.let { put("http_status", DiagnosticAttributeValue.Number(it.toLong())) }
            }
            diagnostics.record(
                StructuredDiagnosticEvent(
                    timestampMillis = clock().coerceAtLeast(0L),
                    severity = DiagnosticSeverity.DEBUG,
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = DiagnosticEventName.PROVIDER_READING_LOOKUP_ATTEMPT,
                    sessionId = diagnostics.sessionId,
                    operationId = null,
                    stage = DiagnosticStage.LOOKUP,
                    outcome = outcome,
                    durationMillis = elapsedMillis?.coerceIn(0L, 86_400_000L),
                    attributes = attributes,
                ),
            )
        }
    }

    /**
     * Intentionally includes public MangaFire identifiers and names ONLY during a user-triggered
     * detailed capture. The normal diagnostic stream remains pseudonymized. The sanitizer limits
     * IDs/text and only permits public HTTPS MangaFire title paths (no tokens/query/fragment).
     * Chapter counts are emitted only when explicitly attested by the Provider.
     */
    private fun traceLookupCandidates(
        canonicalTitleId: String,
        target: ReadingTarget,
        queryIndex: Int,
        queryTitle: String,
        candidates: List<ProviderWorkCandidate>,
        matches: List<ProviderWorkCandidate>,
        selected: ProviderWorkCandidate?,
        primary: ProviderWorkCandidate?,
    ) {
        val providerId = target.registration.descriptor.id.value
        if (!diagnostics.detailedCaptureActive || providerId !in setOf(
                "app.tsuzuki.mangafire",
                "app.tsuzuki.mangaall",
            )
        ) {
            return
        }
        runCatching {
            // MangaAll is one Provider with several content sources. Sampling
            // only the first 20 hits hides later sources when MangaDex fills
            // the front of the lookup. Keep a bounded, source-balanced sample
            // while preserving the upstream candidate index in diagnostics.
            val sampled = if (providerId == "app.tsuzuki.mangaall") {
                val perSource = mutableMapOf<String, Int>()
                candidates.withIndex().filter { (_, work) ->
                    val source = work.externalWorkId.substringBefore(':')
                    if (source !in setOf("mangadex", "mangadot", "mangafire")) {
                        false
                    } else {
                        val seen = perSource[source] ?: 0
                        if (seen >= MAX_DIAGNOSTIC_CANDIDATES_PER_SOURCE) {
                            false
                        } else {
                            perSource[source] = seen + 1
                            true
                        }
                    }
                }.take(MAX_DIAGNOSTIC_CANDIDATES)
            } else {
                candidates.withIndex().take(MAX_DIAGNOSTIC_CANDIDATES)
            }
            sampled.forEach { (index, work) ->
                val facetMatches = target.facetId == null ||
                    work.language == null ||
                    work.language.equals(target.facetId, ignoreCase = true)
                val exactMatch = matches.any { it.externalWorkId == work.externalWorkId }
                val reason = when {
                    selected?.externalWorkId == work.externalWorkId -> DiagnosticProviderCandidateReason.SELECTED
                    !facetMatches -> DiagnosticProviderCandidateReason.FACET_MISMATCH
                    ProviderWorkSourcePortfolio.hasSourceAttestedAlternateEdition(
                        target.registration.descriptor.id,
                        work,
                        selected?.title ?: primary?.title ?: queryTitle,
                    ) ->
                        DiagnosticProviderCandidateReason.ALTERNATE_EDITION
                    exactMatch && normalize(work.title) == normalize(queryTitle) ->
                        DiagnosticProviderCandidateReason.PRIMARY_MATCH_BLOCKED
                    exactMatch -> DiagnosticProviderCandidateReason.ALIAS_MATCH_BLOCKED
                    else -> DiagnosticProviderCandidateReason.TITLE_MISMATCH
                }
                val aliases = work.aliases
                    .asSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() }
                    .take(3)
                    .map { it.take(128) }
                    .joinToString(" | ")
                val attributes = buildMap<String, DiagnosticAttributeValue> {
                    put("provider_id", DiagnosticAttributeValue.Text(target.registration.descriptor.id.value))
                    put(
                        "provider_version_code",
                        DiagnosticAttributeValue.Number(target.registration.descriptor.version.code.toLong()),
                    )
                    work.language?.let { put("language", DiagnosticAttributeValue.Text(it)) }
                    diagnostics.canonicalTitleReference(canonicalTitleId)?.let {
                        put("canonical_title_ref", DiagnosticAttributeValue.Text(it))
                    }
                    put("query_index", DiagnosticAttributeValue.Number((queryIndex + 1).toLong()))
                    put("provider_lookup_title", DiagnosticAttributeValue.Text(queryTitle.take(256)))
                    put("candidate_index", DiagnosticAttributeValue.Number(index.toLong()))
                    put("provider_work_id", DiagnosticAttributeValue.Text(work.externalWorkId))
                    put("provider_work_title", DiagnosticAttributeValue.Text(work.title.take(256)))
                    work.url?.let { put("provider_work_url", DiagnosticAttributeValue.Text(it)) }
                    if (aliases.isNotEmpty()) put("provider_work_aliases", DiagnosticAttributeValue.Text(aliases))
                    work.chapterCount?.let {
                        put("provider_work_chapter_count", DiagnosticAttributeValue.Number(it.toLong()))
                    }
                    work.coverageOutcome?.let {
                        put(
                            "provider_coverage_outcome",
                            DiagnosticAttributeValue.Code(
                                tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderCoverageOutcome.valueOf(it.name),
                            ),
                        )
                    }
                    work.coverageObservedCount?.let {
                        put("provider_coverage_observed_count", DiagnosticAttributeValue.Number(it.toLong()))
                    }
                    work.coverageHasMore?.let {
                        put("provider_coverage_has_more", DiagnosticAttributeValue.Flag(it))
                    }
                    work.coverageMinMilli?.let {
                        put("chapter_number_min_milli", DiagnosticAttributeValue.Number(it.toLong()))
                    }
                    work.coverageMaxMilli?.let {
                        put("chapter_number_max_milli", DiagnosticAttributeValue.Number(it.toLong()))
                    }
                    put("provider_candidate_reason", DiagnosticAttributeValue.Code(reason))
                    work.detailOutcome?.let {
                        put(
                            "provider_detail_outcome",
                            DiagnosticAttributeValue.Code(DiagnosticProviderDetailOutcome.valueOf(it.name)),
                        )
                    }
                    work.detailHttpStatus?.let { put("http_status", DiagnosticAttributeValue.Number(it.toLong())) }
                }
                diagnostics.record(
                    StructuredDiagnosticEvent(
                        timestampMillis = clock().coerceAtLeast(0L),
                        severity = DiagnosticSeverity.DEBUG,
                        subsystem = DiagnosticSubsystem.CHAPTER,
                        name = DiagnosticEventName.PROVIDER_READING_CANDIDATE,
                        sessionId = diagnostics.sessionId,
                        operationId = null,
                        stage = DiagnosticStage.LOOKUP,
                        outcome = if (selected?.externalWorkId == work.externalWorkId) {
                            DiagnosticOutcome.ACCEPTED
                        } else if (exactMatch) {
                            DiagnosticOutcome.REJECTED
                        } else {
                            DiagnosticOutcome.SKIPPED
                        },
                        attributes = attributes,
                    ),
                )
            }
        }
    }

    /**
     * A missing finish event plus a READ timeout identifies the exact chapter
     * page where the source stopped responding. No cursor or HTTP body is logged.
     */
    private fun traceChapterRequest(
        titleId: String,
        target: ReadingTarget,
        ref: ProviderBindingRef,
        pageIndex: Int,
        outcome: DiagnosticOutcome,
        itemCount: Int? = null,
        errorCode: ProviderErrorCode? = null,
        elapsedMillis: Long? = null,
    ) {
        if (!diagnostics.detailedCaptureActive) return
        runCatching {
            val attributes = buildMap<String, DiagnosticAttributeValue> {
                put("provider_id", DiagnosticAttributeValue.Text(target.registration.descriptor.id.value))
                put(
                    "provider_version_code",
                    DiagnosticAttributeValue.Number(target.registration.descriptor.version.code.toLong()),
                )
                diagnostics.canonicalTitleReference(titleId)?.let {
                    put("canonical_title_ref", DiagnosticAttributeValue.Text(it))
                }
                put("page_index", DiagnosticAttributeValue.Number(pageIndex.toLong()))
                if (target.registration.descriptor.id.value == "app.tsuzuki.mangafire") {
                    put("provider_work_id", DiagnosticAttributeValue.Text(ref.externalWorkId))
                }
                // Keep failures attributable to one internal MangaAll adapter
                // without exposing external chapter IDs or source-written URLs.
                val source = when (MangaAllReadingIdentity.sourceId(ref)) {
                    "mangadex" -> DiagnosticMangaAllNetworkSource.MANGADEX
                    "mangadot" -> DiagnosticMangaAllNetworkSource.MANGADOT
                    "mangafire" -> DiagnosticMangaAllNetworkSource.MANGAFIRE
                    else -> null
                }
                source?.let { put("mangaall_network_source", DiagnosticAttributeValue.Code(it)) }
                itemCount?.let { put("item_count", DiagnosticAttributeValue.Number(it.toLong())) }
                errorCode?.let { put("provider_error_code", DiagnosticAttributeValue.Text(it.name)) }
            }
            diagnostics.record(
                StructuredDiagnosticEvent(
                    timestampMillis = clock().coerceAtLeast(0L),
                    severity = DiagnosticSeverity.DEBUG,
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = DiagnosticEventName.PROVIDER_READING_CHAPTER_ATTEMPT,
                    sessionId = diagnostics.sessionId,
                    operationId = null,
                    stage = DiagnosticStage.READ,
                    outcome = outcome,
                    durationMillis = elapsedMillis?.coerceIn(0L, 86_400_000L),
                    attributes = attributes,
                ),
            )
        }
    }

    /**
     * Only an explicitly activated detailed capture can disclose a public HID.
     * Typed reason codes distinguish an unverified work, conflicting creator,
     * source-attested variant and a valid work with unresolved edition.
     */
    private fun traceWorkSourceAssociation(
        titleId: String,
        target: ReadingTarget,
        assessment: ProviderWorkSourceAssessment,
        outcome: DiagnosticOutcome,
    ) {
        if (!diagnostics.detailedCaptureActive) return
        runCatching {
            val attributes = buildMap<String, DiagnosticAttributeValue> {
                put("provider_id", DiagnosticAttributeValue.Text(target.registration.descriptor.id.value))
                put("provider_work_id", DiagnosticAttributeValue.Text(assessment.ref.externalWorkId))
                put(
                    "provider_work_verification",
                    DiagnosticAttributeValue.Code(
                        DiagnosticProviderWorkVerification.valueOf(assessment.workVerification.name),
                    ),
                )
                put(
                    "provider_edition_qualification",
                    DiagnosticAttributeValue.Code(
                        DiagnosticProviderEditionQualification.valueOf(assessment.editionQualification.name),
                    ),
                )
                diagnostics.canonicalTitleReference(titleId)?.let {
                    put("canonical_title_ref", DiagnosticAttributeValue.Text(it))
                }
            }
            diagnostics.record(
                StructuredDiagnosticEvent(
                    timestampMillis = clock().coerceAtLeast(0L),
                    severity = DiagnosticSeverity.DEBUG,
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = DiagnosticEventName.PROVIDER_READING_SOURCE_ASSOCIATION,
                    sessionId = diagnostics.sessionId,
                    operationId = null,
                    stage = DiagnosticStage.BINDING,
                    outcome = outcome,
                    attributes = attributes,
                ),
            )
        }
    }

    /** The selected HID must remain observable even when lookup was skipped for an existing binding. */
    private fun traceBoundWork(
        titleId: String,
        target: ReadingTarget,
        binding: ProviderReadingBinding,
        outcome: DiagnosticOutcome,
    ) {
        if (!diagnostics.detailedCaptureActive || target.registration.descriptor.id.value != "app.tsuzuki.mangafire") {
            return
        }
        runCatching {
            val attributes = buildMap<String, DiagnosticAttributeValue> {
                put("provider_id", DiagnosticAttributeValue.Text(target.registration.descriptor.id.value))
                put("provider_work_id", DiagnosticAttributeValue.Text(binding.ref.externalWorkId))
                binding.ref.facetId?.let { put("language", DiagnosticAttributeValue.Text(it)) }
                diagnostics.canonicalTitleReference(titleId)?.let {
                    put("canonical_title_ref", DiagnosticAttributeValue.Text(it))
                }
            }
            diagnostics.record(
                StructuredDiagnosticEvent(
                    timestampMillis = clock().coerceAtLeast(0L),
                    severity = DiagnosticSeverity.DEBUG,
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = DiagnosticEventName.PROVIDER_READING_BINDING,
                    sessionId = diagnostics.sessionId,
                    operationId = null,
                    stage = DiagnosticStage.BINDING,
                    outcome = outcome,
                    attributes = attributes,
                ),
            )
        }
    }

    /**
     * User-triggered capture only. Source chapter identities are numeric (MangaFire),
     * never recorded as unbounded URLs/labels, and range is encoded in thousandths
     * to preserve decimal specials (e.g. 0.5). These are observations, not chapters
     * guaranteed to be canonical.
     */
    private fun traceReadingPageDetail(
        titleId: String,
        target: ReadingTarget,
        sourceRef: ProviderBindingRef,
        pageNumber: Int,
        page: List<ProviderChapterObservation>,
    ) {
        if (!diagnostics.detailedCaptureActive || target.registration.descriptor.id.value != "app.tsuzuki.mangafire") {
            return
        }
        runCatching {
            val numbers = page.mapNotNull { observation ->
                observation.rawNumber
                    ?.takeIf { it.isFinite() && it >= 0.0 && it <= 1_000_000.0 }
                    ?.times(1_000.0)
                    ?.roundToLong()
            }
            val firstId = page.firstOrNull()?.providerChapterId?.takeIf { it.matches(NUMERIC_CHAPTER_ID) }
            val lastId = page.lastOrNull()?.providerChapterId?.takeIf { it.matches(NUMERIC_CHAPTER_ID) }
            val attributes = buildMap<String, DiagnosticAttributeValue> {
                put("provider_id", DiagnosticAttributeValue.Text(target.registration.descriptor.id.value))
                put("provider_work_id", DiagnosticAttributeValue.Text(sourceRef.externalWorkId))
                diagnostics.canonicalTitleReference(titleId)?.let {
                    put("canonical_title_ref", DiagnosticAttributeValue.Text(it))
                }
                put("page_index", DiagnosticAttributeValue.Number(pageNumber.toLong()))
                put("item_count", DiagnosticAttributeValue.Number(page.size.toLong()))
                if (numbers.isNotEmpty()) {
                    put("chapter_number_min_milli", DiagnosticAttributeValue.Number(numbers.min()))
                    put("chapter_number_max_milli", DiagnosticAttributeValue.Number(numbers.max()))
                }
                firstId?.let { put("provider_chapter_first_id", DiagnosticAttributeValue.Text(it)) }
                lastId?.let { put("provider_chapter_last_id", DiagnosticAttributeValue.Text(it)) }
            }
            diagnostics.record(
                StructuredDiagnosticEvent(
                    timestampMillis = clock().coerceAtLeast(0L),
                    severity = DiagnosticSeverity.DEBUG,
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = DiagnosticEventName.PROVIDER_READING_PAGE_DETAIL,
                    sessionId = diagnostics.sessionId,
                    operationId = null,
                    stage = DiagnosticStage.READ,
                    outcome = DiagnosticOutcome.PARTIAL,
                    attributes = attributes,
                ),
            )
        }
    }

    /** Sanitized counters only; no title, alias, URL, external work ID, or chapter labels. */
    private fun trace(
        titleId: String,
        target: ReadingTarget?,
        stage: DiagnosticStage,
        outcome: DiagnosticOutcome,
        targets: Int? = null,
        candidates: Int? = null,
        exactMatches: Int? = null,
        bindings: Int? = null,
        lookupDecision: DiagnosticProviderLookupDecision? = null,
        observed: Int? = null,
        accepted: Int? = null,
        partialCount: Int? = null,
        pageCount: Int? = null,
        completed: Boolean? = null,
        errorCode: ProviderErrorCode? = null,
        malformedStage: DiagnosticProviderMalformedResultStage? = null,
        name: DiagnosticEventName = DiagnosticEventName.PROVIDER_READING_EVIDENCE,
    ) {
        runCatching {
            val attributes = buildMap<String, DiagnosticAttributeValue> {
                target?.let { put("provider_id", DiagnosticAttributeValue.Text(it.registration.descriptor.id.value)) }
                diagnostics.canonicalTitleReference(titleId)?.let {
                    put("canonical_title_ref", DiagnosticAttributeValue.Text(it))
                }
                targets?.let { put("target_source_count", DiagnosticAttributeValue.Number(it.toLong())) }
                candidates?.let { put("candidate_count", DiagnosticAttributeValue.Number(it.toLong())) }
                exactMatches?.let { put("accepted_count", DiagnosticAttributeValue.Number(it.toLong())) }
                bindings?.let { put("binding_count", DiagnosticAttributeValue.Number(it.toLong())) }
                lookupDecision?.let { put("lookup_decision", DiagnosticAttributeValue.Code(it)) }
                observed?.let { put("observation_count", DiagnosticAttributeValue.Number(it.toLong())) }
                pageCount?.let { put("page_count", DiagnosticAttributeValue.Number(it.toLong())) }
                accepted?.let { put("chapter_count", DiagnosticAttributeValue.Number(it.toLong())) }
                partialCount?.let { put("partial_evidence_count", DiagnosticAttributeValue.Number(it.toLong())) }
                completed?.let { put("completed", DiagnosticAttributeValue.Flag(it)) }
                errorCode?.let { put("provider_error_code", DiagnosticAttributeValue.Text(it.name)) }
                malformedStage?.let { put("provider_malformed_stage", DiagnosticAttributeValue.Code(it)) }
            }
            diagnostics.record(
                StructuredDiagnosticEvent(
                    timestampMillis = clock().coerceAtLeast(0L),
                    severity = if (outcome in setOf(DiagnosticOutcome.FAILED, DiagnosticOutcome.THREW)) {
                        DiagnosticSeverity.WARN
                    } else {
                        DiagnosticSeverity.INFO
                    },
                    subsystem = DiagnosticSubsystem.CHAPTER,
                    name = name,
                    sessionId = diagnostics.sessionId,
                    operationId = null,
                    stage = stage,
                    outcome = outcome,
                    attributes = attributes,
                ),
            )
        }
    }

    private fun normalizedCreator(value: String): String? {
        // Author order can differ across sources (e.g. ARAKAWA Hiromu).
        // The complete token multiset must match; surname-only fuzzy guesses
        // must never authorize a work/edition binding.
        val tokens = value.trim().lowercase().split(CREATOR_SEPARATORS).filter(String::isNotBlank)
        return tokens.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(" ")
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")

    private fun bindingId(
        canonicalTitleId: String,
        providerId: ProviderId,
        facetId: String?,
        externalWorkId: String,
    ): String {
        val material = listOf(
            canonicalTitleId,
            providerId.value,
            facetId.orEmpty(),
            externalWorkId,
        ).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        return "provider-binding:$digest"
    }

    private fun <T> malformed(): ProviderCallResult<T> = ProviderCallResult.Failure(
        ProviderError(
            code = ProviderErrorCode.MALFORMED_RESULT,
            retryable = false,
        ),
    )

    private data class LookupSnapshot(
        val items: List<ProviderWorkCandidate>,
        val complete: Boolean,
        // Closed transport failure type for partial diagnostics; no upstream text.
        val partialErrorCode: ProviderErrorCode? = null,
    )

    private data class BindingDiscovery(
        val binding: ProviderReadingBinding? = null,
        val unresolved: Boolean = false,
        val workSources: List<ProviderWorkSourceAssessment> = emptyList(),
    )

    private data class ReadingTarget(
        val registration: ProviderRegistration,
        val facetId: String?,
    )

    private companion object {
        // ICE only needs work lookup and chapter observations. ReadingPagesV1
        // belongs to the Reader path, not the independent evidence collector.
        val REQUIRED_CAPABILITIES = setOf(
            ProviderCapabilities.ReadingLookupV1,
            ProviderCapabilities.ReadingChaptersV1,
        )
        val WHITESPACE = Regex("\\s+")
        val CREATOR_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
        val NUMERIC_CHAPTER_ID = Regex("^[0-9]{1,32}$")
        const val MAX_COVERAGE_CONTENDERS = 3

        // A54 Bleach code13: page eight began 30 ms before the old
        // 5 s limit after seven successful pages (~0.6 s per request).
        // This small grace never overrides the outer 20 s Provider deadline;
        // incomplete work-source collections remain additive and provisional.
        const val MAX_WORK_SOURCE_COLLECTION_MILLIS = 5_800L
        const val MAX_CONCURRENT_PROVIDERS = 4
        const val MAX_PROVIDER_COLLECTION_MILLIS = 20_000L
        // A missing catalog snapshot gets one bounded independent identity
        // fetch before source lookup, not an unbounded provider discovery.
        const val MAX_CANONICAL_AUTHOR_BOOTSTRAP_MILLIS = 4_000L
        private val RECOVERABLE_LOOKUP_ERRORS = setOf(
            ProviderErrorCode.TIMEOUT,
            ProviderErrorCode.NETWORK_ERROR,
            ProviderErrorCode.SOURCE_READ_ERROR,
            ProviderErrorCode.HOST_ERROR,
        )
        const val MAX_LOOKUP_PAGES = 8
        const val MAX_EMPTY_BINDING_FALLBACK_LOOKUP_PAGES = 2
        const val MAX_EMPTY_BINDING_FALLBACK_QUERIES = 3
        const val MAX_LOOKUP_ITEMS = 2_000
        const val MAX_DIAGNOSTIC_CANDIDATES = 20
        const val MAX_DIAGNOSTIC_CANDIDATES_PER_SOURCE = 6
    }
}
