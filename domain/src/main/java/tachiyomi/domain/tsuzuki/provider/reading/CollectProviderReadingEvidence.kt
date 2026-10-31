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
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderCandidateReason
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderDetailOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderLookupDecision
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
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
                        val finished = withTimeoutOrNull(MAX_PROVIDER_COLLECTION_MILLIS) {
                            collectTarget(
                                canonicalTitleId = canonicalTitleId,
                                titles = queryTitles,
                                target = target,
                                onStageChanged = { activeStage = it },
                                onPageCollected = { binding, page, observedAt ->
                                    partial += evidenceAdapter.toEvidence(
                                        canonicalTitleId = canonicalTitleId,
                                        binding = binding,
                                        observations = page,
                                        observedAt = observedAt,
                                    )
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
                        (finished ?: ProviderReadingEvidenceCollection(complete = false)).copy(
                            partialEvidence = if (finished == null && stillEnabled) {
                                partial.toList()
                            } else {
                                emptyList()
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
        onStageChanged: (DiagnosticStage) -> Unit,
        onPageCollected: suspend (ProviderBindingRef, List<ProviderChapterObservation>, Long) -> Unit,
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
                )
            ) {
                is ProviderCallResult.Failure -> return ProviderReadingEvidenceCollection(complete = false)
                is ProviderCallResult.Success ->
                    discovery.value.binding
                        ?: return ProviderReadingEvidenceCollection(complete = !discovery.value.unresolved)
            }

            if (existing == null) {
                traceBoundWork(canonicalTitleId, target, binding, DiagnosticOutcome.ACCEPTED)
            }
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
                        traceReadingPageDetail(canonicalTitleId, target, lastPageNumber, page)
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
                    ProviderReadingEvidenceCollection(
                        snapshots = listOf(snapshot),
                        bindingCount = 1,
                        complete = true,
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
    ): ProviderCallResult<BindingDiscovery> {
        // Work IDs are private to this Provider and are only used to narrow
        // earlier matches. They never cross a source, title, edition or facet.
        var ambiguousCandidates: List<ProviderWorkCandidate>? = null
        var unresolved = false
        for ((queryIndex, title) in titles.withIndex()) {
            val queryStarted = System.nanoTime()
            traceLookupAttempt(canonicalTitleId, target, queryIndex, title, DiagnosticOutcome.STARTED)
            val candidates = when (val result = collectLookup(target.registration.descriptor.id, title)) {
                is ProviderCallResult.Failure -> {
                    traceLookupAttempt(
                        canonicalTitleId,
                        target,
                        queryIndex,
                        title,
                        DiagnosticOutcome.FAILED,
                        elapsedMillis = (System.nanoTime() - queryStarted) / 1_000_000L,
                        errorCode = result.error.code,
                    )
                    trace(
                        canonicalTitleId,
                        target,
                        DiagnosticStage.LOOKUP,
                        DiagnosticOutcome.FAILED,
                        errorCode = result.error.code,
                    )
                    return result
                }
                is ProviderCallResult.Success -> {
                    traceLookupAttempt(
                        canonicalTitleId,
                        target,
                        queryIndex,
                        title,
                        DiagnosticOutcome.SUCCEEDED,
                        elapsedMillis = (System.nanoTime() - queryStarted) / 1_000_000L,
                        candidateCount = result.value.size,
                    )
                    result.value
                }
            }
            val normalizedTitle = normalize(title)
            val matches = candidates
                .asSequence()
                .filter { candidate ->
                    target.facetId == null ||
                        candidate.language == null ||
                        candidate.language.equals(target.facetId, ignoreCase = true)
                }
                .filter { candidate ->
                    sequenceOf(candidate.title)
                        .plus(candidate.aliases.asSequence())
                        .any { normalize(it) == normalizedTitle }
                }
                .distinctBy(ProviderWorkCandidate::externalWorkId)
                .toList()
            val sourcePrimaryMatches = matches.filter { normalize(it.title) == normalizedTitle }
            val priorAmbiguous = ambiguousCandidates
            val primary = sourcePrimaryMatches.singleOrNull()
            val unqualifiedPrimary = sourcePrimaryMatches
                .filterNot { isSourceAttestedAlternateEdition(it, title) }
                .singleOrNull()
            val candidate = when {
                // An ambiguous first lookup may only be narrowed to a work
                // already seen. All rivals must explicitly identify a different
                // editorial variant: title similarity and chapter count alone
                // cannot establish canonical edition identity.
                priorAmbiguous != null -> primary?.takeIf { selected ->
                    !isSourceAttestedAlternateEdition(selected, title) &&
                        priorAmbiguous.any { it.externalWorkId == selected.externalWorkId } &&
                        priorAmbiguous
                            .filter { it.externalWorkId != selected.externalWorkId }
                            .all { isSourceAttestedAlternateEdition(it, selected.title) }
                }
                matches.size == 1 && !isSourceAttestedAlternateEdition(matches.single(), title) -> matches.single()
                primary != null && !isSourceAttestedAlternateEdition(primary, title) && matches
                    .filter { it.externalWorkId != primary.externalWorkId }
                    .all { isSourceAttestedAlternateEdition(it, primary.title) } -> primary
                // Two work IDs may carry the same primary title, while only
                // one source entry explicitly declares itself a special edition
                // in its own alternative names. This is affirmative source
                // metadata, not a popularity/rank/chapter-count guess.
                unqualifiedPrimary != null && matches
                    .filter { it.externalWorkId != unqualifiedPrimary.externalWorkId }
                    .all { isSourceAttestedAlternateEdition(it, unqualifiedPrimary.title) } -> unqualifiedPrimary
                matches.size > 1 -> {
                    ambiguousCandidates = matches
                    null
                }
                else -> null
            }
            val decision = when {
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
            if (candidate == null) continue

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

    private suspend fun collectLookup(
        providerId: ProviderId,
        title: String,
    ): ProviderCallResult<List<ProviderWorkCandidate>> {
        val items = mutableListOf<ProviderWorkCandidate>()
        val seenCursors = mutableSetOf<String>()
        var cursor: ProviderCursor? = null
        var pageCount = 0
        while (true) {
            if (++pageCount > MAX_LOOKUP_PAGES) return malformed()
            val page = when (
                val result = gateway.lookup(
                    providerId = providerId,
                    request = ProviderReadingLookupRequest(
                        titles = listOf(title),
                        cursor = cursor,
                    ),
                )
            ) {
                is ProviderCallResult.Failure -> return result
                is ProviderCallResult.Success -> result.value
            }
            items += page.items
            if (items.size > MAX_LOOKUP_ITEMS) return malformed()
            val next = page.nextCursor ?: break
            if (!seenCursors.add(next.value)) return malformed()
            cursor = next
        }
        return ProviderCallResult.Success(items)
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
        if (!diagnostics.detailedCaptureActive || target.registration.descriptor.id.value != "app.tsuzuki.mangafire") {
            return
        }
        runCatching {
            candidates.take(MAX_DIAGNOSTIC_CANDIDATES).forEachIndexed { index, work ->
                val facetMatches = target.facetId == null ||
                    work.language == null ||
                    work.language.equals(target.facetId, ignoreCase = true)
                val exactMatch = matches.any { it.externalWorkId == work.externalWorkId }
                val reason = when {
                    selected?.externalWorkId == work.externalWorkId -> DiagnosticProviderCandidateReason.SELECTED
                    !facetMatches -> DiagnosticProviderCandidateReason.FACET_MISMATCH
                    isSourceAttestedAlternateEdition(work, selected?.title ?: primary?.title ?: queryTitle) ->
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

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(WHITESPACE, " ")

    /**
     * The Provider's own explicit alternate title may qualify a work as colored,
     * omnibus or another editorial variant even when its primary title is shared.
     * Ignore unqualified synonyms; they never prove edition uniqueness.
     */
    private fun isSourceAttestedAlternateEdition(candidate: ProviderWorkCandidate, base: String): Boolean =
        isExplicitEditionVariant(candidate.title, base) ||
            candidate.aliases.any { isExplicitEditionVariant(it, base) }

    /** An explicit edition label is evidence of a variant, not a fuzzy title mismatch. */
    private fun isExplicitEditionVariant(candidateTitle: String, baseTitle: String): Boolean {
        val base = normalize(baseTitle)
        val candidate = normalize(candidateTitle)
        if (candidate == base || !candidate.startsWith(base)) return false
        val suffix = candidate.removePrefix(base).trim()
        if (suffix.none { it.isLetterOrDigit() }) return false
        val explicitlyQualified = suffix.startsWith("(") ||
            suffix.startsWith("[") ||
            suffix.startsWith("-") ||
            suffix.startsWith(":")
        return explicitlyQualified && EXPLICIT_ALTERNATE_EDITION_HINTS.any { hint ->
            suffix.contains(hint)
        }
    }

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

    private data class BindingDiscovery(
        val binding: ProviderReadingBinding? = null,
        val unresolved: Boolean = false,
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
        val NUMERIC_CHAPTER_ID = Regex("^[0-9]{1,32}$")
        val EXPLICIT_ALTERNATE_EDITION_HINTS = listOf(
            "colored",
            "coloured",
            "full color",
            "full colour",
            "omnibus",
            "one shot",
            "oneshot",
            "special edition",
        )
        const val MAX_CONCURRENT_PROVIDERS = 4
        const val MAX_PROVIDER_COLLECTION_MILLIS = 20_000L
        const val MAX_LOOKUP_PAGES = 8
        const val MAX_LOOKUP_ITEMS = 2_000
        const val MAX_DIAGNOSTIC_CANDIDATES = 20
    }
}
