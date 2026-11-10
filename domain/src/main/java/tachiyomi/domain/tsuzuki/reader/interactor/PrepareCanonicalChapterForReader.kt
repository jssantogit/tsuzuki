package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.model.ContentResolution
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticCandidateType
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticErrorCategory
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticMangaAllNetworkSource
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderLookupDecision
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderReaderIdentityRejectionReason
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderReaderNoMatchReason
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSeverity
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import tachiyomi.domain.tsuzuki.download.service.canReuseArtifact
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.reading.ResolveMangaAllSelectedChapter
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.provider.torrent.PrepareProviderChapterTorrent
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderChapterTorrentPreparation
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.ChapterContentPreparer
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Inject
class PrepareCanonicalChapterForReader(
    private val resolveChapterContent: ResolveChapterContent,
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
    private val canonicalDownloadRepository: CanonicalDownloadRepository,
    private val canonicalDownloadGateway: CanonicalDownloadGateway,
    private val chapterContentPreparer: ChapterContentPreparer,
    private val structuredDiagnostics: StructuredDiagnosticRecorder,
    private val resolveProviderChapterReading: ResolveProviderChapterReading? = null,
    private val prepareProviderChapterTorrent: PrepareProviderChapterTorrent? = null,
    private val resolveMangaAllSelectedChapter: ResolveMangaAllSelectedChapter? = null,
) {

    suspend fun execute(
        canonicalChapterId: String,
        selectedOption: ContentOption? = null,
        requestedMangaAllLanguage: String? = null,
    ): CanonicalReaderPreparation {
        var trace = DiagnosticTrace.start(
            recorder = structuredDiagnostics,
            workflow = DiagnosticWorkflow.READER_OPEN,
            subsystem = DiagnosticSubsystem.READER,
        )
        val started = TimeSource.Monotonic.markNow()
        trace.event(
            subsystem = DiagnosticSubsystem.READER,
            name = DiagnosticEventName.READER_OPEN_STARTED,
            stage = DiagnosticStage.READER,
            outcome = DiagnosticOutcome.STARTED,
        )

        val result = try {
            val chapter = canonicalChapterRepository.getById(canonicalChapterId)
                ?: return complete(
                    trace,
                    started,
                    CanonicalReaderPreparation.Unavailable(canonicalChapterId),
                )
            // Bind subsequent Reader events to the same pseudonymous title
            // reference used by chapter discovery and reconciliation logs.
            trace = trace.copy(
                canonicalTitleRef = structuredDiagnostics.canonicalTitleReference(chapter.canonicalTitleId),
            )

            // This opt-in argument comes only from an explicit source+language
            // choice in the MangaAll picker. Do not silently choose a different
            // source, language, cached edition or torrent if the chosen chapter
            // is not verifiably readable.
            if (requestedMangaAllLanguage != null && selectedOption == null) {
                val option = resolveMangaAllSelectedChapter?.execute(
                    canonicalChapterId,
                    requestedMangaAllLanguage,
                )
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_SOURCE_SELECTED,
                    stage = DiagnosticStage.READER,
                    outcome = if (option != null) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.REJECTED,
                    attributes = mapOf(
                        DiagnosticAttribute.CANDIDATE_TYPE to
                            DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                        DiagnosticAttribute.INITIALIZED to
                            DiagnosticAttributeValue.Flag(resolveMangaAllSelectedChapter != null),
                    ),
                )
                val prepared = if (option != null) {
                    resolveProviderChapterReading?.preparedContent(option)
                } else {
                    null
                }
                if (option != null) {
                    trace.event(
                        subsystem = DiagnosticSubsystem.READER,
                        name = DiagnosticEventName.READER_PAGES_READY,
                        stage = DiagnosticStage.READER,
                        outcome = if (prepared is ProviderCallResult.Success) {
                            DiagnosticOutcome.READY
                        } else {
                            DiagnosticOutcome.TYPED_FAILURE
                        },
                        attributes = buildMap {
                            put(DiagnosticAttribute.PROVIDER_ID, DiagnosticAttributeValue.Text(option.providerId.value))
                            if (prepared is ProviderCallResult.Failure) {
                                put(
                                    DiagnosticAttribute.PROVIDER_ERROR_CODE,
                                    DiagnosticAttributeValue.Text(prepared.error.code.name),
                                )
                                put(
                                    DiagnosticAttribute.ERROR_CATEGORY,
                                    DiagnosticAttributeValue.Code(prepared.error.code.toDiagnosticCategory()),
                                )
                            }
                            (prepared as? ProviderCallResult.Success)?.value?.let { delivery ->
                                (delivery as? PreparedChapterContent.HttpPages)?.pages?.size?.let { count ->
                                    put(DiagnosticAttribute.PAGE_COUNT, DiagnosticAttributeValue.Number(count.toLong()))
                                }
                            }
                        },
                    )
                }
                val explicitResult = when (prepared) {
                    is ProviderCallResult.Success -> CanonicalReaderPreparation.Ready(
                        canonicalChapterId = canonicalChapterId,
                        target = prepared.value,
                        usedFallback = false,
                    )
                    else -> CanonicalReaderPreparation.Unavailable(canonicalChapterId)
                }
                return complete(trace, started, explicitResult)
            }

            if (selectedOption == null) {
                val artifact = canonicalDownloadRepository.get(canonicalChapterId)
                val cacheUsable = artifact != null && canonicalDownloadGateway.canReuseArtifact(artifact)
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.CACHE_LOOKUP,
                    stage = DiagnosticStage.LOOKUP,
                    outcome = if (cacheUsable) DiagnosticOutcome.HIT else DiagnosticOutcome.MISS,
                )
                if (cacheUsable && artifact != null) {
                    return complete(
                        trace,
                        started,
                        CanonicalReaderPreparation.Ready(
                            canonicalChapterId = canonicalChapterId,
                            target = PreparedChapterContent.CanonicalDownload(
                                uri = artifact.localUri,
                                format = artifact.format,
                            ),
                            usedFallback = false,
                        ),
                    )
                }
            }

            val resolution = if (selectedOption != null) {
                require(selectedOption.canonicalChapterId == canonicalChapterId) {
                    "Selected content option does not belong to canonical chapter"
                }
                ContentResolution.Direct(
                    option = selectedOption,
                    usedFallback = false,
                )
            } else {
                resolveChapterContent.execute(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                )
            }

            when (resolution) {
                is ContentResolution.Direct -> prepare(
                    canonicalChapterId = canonicalChapterId,
                    option = resolution.option,
                    usedFallback = resolution.usedFallback,
                )

                is ContentResolution.NeedsSelection -> CanonicalReaderPreparation.SelectionRequired(
                    canonicalTitleId = chapter.canonicalTitleId,
                    canonicalChapterId = canonicalChapterId,
                    options = resolution.options,
                    preferredAddonId = resolution.preferredAddonId,
                    preferredUnavailable = resolution.preferredUnavailable,
                )

                ContentResolution.Unavailable -> prepareProviderFallback(
                    canonicalChapterId = canonicalChapterId,
                    trace = trace,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            CanonicalReaderPreparation.Failed(canonicalChapterId, error)
        }

        return complete(trace, started, result)
    }

    private suspend fun prepareProviderFallback(
        canonicalChapterId: String,
        trace: DiagnosticTrace,
    ): CanonicalReaderPreparation {
        val resolver = resolveProviderChapterReading
        // Only identify the exact canonical number during voluntary detailed
        // capture. Without this, Ch. 0 / Ch. 0.1 misroutes look identical.
        val capturedCanonicalIdentity = if (structuredDiagnostics.detailedCaptureActive) {
            canonicalChapterRepository.getById(canonicalChapterId)?.baseNumber?.let { number ->
                mapOf(
                    DiagnosticAttribute.CANONICAL_CHAPTER_BASE_NUMBER to
                        DiagnosticAttributeValue.Number(number.toLong()),
                )
            }.orEmpty()
        } else {
            emptyMap()
        }
        trace.event(
            subsystem = DiagnosticSubsystem.CONTENT,
            name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
            stage = DiagnosticStage.BINDING,
            outcome = if (resolver == null) DiagnosticOutcome.REJECTED else DiagnosticOutcome.STARTED,
            attributes = mapOf(
                DiagnosticAttribute.CANDIDATE_TYPE to
                    DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                DiagnosticAttribute.INITIALIZED to DiagnosticAttributeValue.Flag(resolver != null),
            ),
        )

        if (resolver != null) {
            val options = resolver.options(canonicalChapterId)
            // An empty result could mean genuinely absent source chapter,
            // missing canonical mapping, ineligible HID, or rejected identity.
            // Log count-only causes rather than hiding every case as no source.
            val noMatchAudit = if (options.isEmpty() && structuredDiagnostics.detailedCaptureActive) {
                try {
                    resolver.auditNoMatch(canonicalChapterId)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Detailed logging is best-effort. Its separate database reads
                    // must never prevent a normal Reader fallback or torrent lookup.
                    null
                }
            } else {
                null
            }
            trace.event(
                subsystem = DiagnosticSubsystem.CONTENT,
                name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                stage = DiagnosticStage.BINDING,
                outcome = if (options.isEmpty()) DiagnosticOutcome.EMPTY else DiagnosticOutcome.CANDIDATES,
                attributes = buildMap {
                    put(
                        DiagnosticAttribute.CANDIDATE_TYPE,
                        DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                    )
                    put(DiagnosticAttribute.CANDIDATE_COUNT, DiagnosticAttributeValue.Number(options.size.toLong()))
                    noMatchAudit?.let { audit ->
                        put(
                            DiagnosticAttribute.PROVIDER_READER_ELIGIBLE_SOURCE_COUNT,
                            DiagnosticAttributeValue.Number(audit.eligibleSourceCount.toLong()),
                        )
                        put(
                            DiagnosticAttribute.PROVIDER_READER_OBSERVED_EVIDENCE_COUNT,
                            DiagnosticAttributeValue.Number(audit.providerEvidenceCount.toLong()),
                        )
                        put(
                            DiagnosticAttribute.PROVIDER_READER_MATCHING_NUMBER_COUNT,
                            DiagnosticAttributeValue.Number(audit.matchingNumberEvidenceCount.toLong()),
                        )
                        put(
                            DiagnosticAttribute.PROVIDER_READER_MAPPED_EVIDENCE_COUNT,
                            DiagnosticAttributeValue.Number(audit.mappedEvidenceCount.toLong()),
                        )
                        put(
                            DiagnosticAttribute.PROVIDER_READER_ELIGIBLE_MAPPED_COUNT,
                            DiagnosticAttributeValue.Number(audit.mappedEligibleSourceEvidenceCount.toLong()),
                        )
                        put(
                            DiagnosticAttribute.PROVIDER_READER_MAPPED_KNOWN_SOURCE_COUNT,
                            DiagnosticAttributeValue.Number(audit.mappedKnownSourceCount.toLong()),
                        )
                        put(
                            DiagnosticAttribute.PROVIDER_READER_MAPPED_ACTIVE_SOURCE_COUNT,
                            DiagnosticAttributeValue.Number(audit.mappedActiveSourceCount.toLong()),
                        )
                        put(
                            DiagnosticAttribute.PROVIDER_READER_NO_MATCH_REASON,
                            DiagnosticAttributeValue.Code(
                                DiagnosticProviderReaderNoMatchReason.valueOf(audit.reason.name),
                            ),
                        )
                        audit.identityRejectionReason?.let { rejection ->
                            put(
                                DiagnosticAttribute.PROVIDER_READER_IDENTITY_REJECTION_REASON,
                                DiagnosticAttributeValue.Code(
                                    DiagnosticProviderReaderIdentityRejectionReason.valueOf(rejection.name),
                                ),
                            )
                        }
                    }
                } + capturedCanonicalIdentity,
            )

            // Work-level evidence never licenses an arbitrary source choice.
            // Only a single chapter option belonging to an edition explicitly
            // confirmed by the user can outrank other eligible sources.
            val explicitlyConfirmedOption = if (options.size > 1) {
                try {
                    resolver.uniqueUserConfirmedOption(canonicalChapterId, options)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A supplementary binding read is not license to guess a
                    // source or to suppress the independent torrent fallback.
                    null
                }
            } else {
                null
            }
            if (options.size > 1 && explicitlyConfirmedOption == null) {
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_SOURCE_SELECTED,
                    stage = DiagnosticStage.READER,
                    outcome = DiagnosticOutcome.NEEDS_CONFIRMATION,
                    attributes = mapOf(
                        DiagnosticAttribute.CANDIDATE_COUNT to DiagnosticAttributeValue.Number(options.size.toLong()),
                        DiagnosticAttribute.LOOKUP_DECISION to
                            DiagnosticAttributeValue.Code(DiagnosticProviderLookupDecision.AMBIGUOUS_EDITION),
                    ) + capturedCanonicalIdentity,
                )
                // Keep an independently verified, fast torrent archive as a
                // valid alternative. A slow torrent scan, however, cannot hold
                // two already-mapped HTTP sources behind a black Reader screen.
                val alternative = withTimeoutOrNull(AMBIGUOUS_TORRENT_PROBE_MILLIS) {
                    prepareProviderTorrentFallback(canonicalChapterId, trace)
                }
                if (alternative != null && alternative !is CanonicalReaderPreparation.Unavailable) {
                    return alternative
                }
                val canonicalTitleId = canonicalChapterRepository.getById(canonicalChapterId)?.canonicalTitleId
                return if (canonicalTitleId == null) {
                    CanonicalReaderPreparation.Unavailable(canonicalChapterId)
                } else {
                    CanonicalReaderPreparation.ProviderEditionConfirmationRequired(
                        canonicalTitleId = canonicalTitleId,
                        canonicalChapterId = canonicalChapterId,
                        candidateCount = options.size,
                    )
                }
            }

            val option = explicitlyConfirmedOption ?: options.singleOrNull()
            if (option != null) {
                // Public source identities are useful for exact Reader debugging,
                // but must never enter ordinary logs outside opt-in capture.
                val capturedSourceIdentity = if (structuredDiagnostics.detailedCaptureActive) {
                    mapOf(
                        DiagnosticAttribute.PROVIDER_WORK_ID to DiagnosticAttributeValue.Text(option.externalWorkId),
                        DiagnosticAttribute.PROVIDER_CHAPTER_ID to
                            DiagnosticAttributeValue.Text(option.providerChapterId),
                    )
                } else {
                    emptyMap()
                }
                // A closed internal source code survives even a generic SCRIPT_ERROR,
                // so the next A54 capture identifies which adapter failed.
                // Never log upstream URLs, cookies, HIDs or query strings here.
                val capturedInternalSource = when (option.contentSourceId) {
                    "mangadex" -> DiagnosticMangaAllNetworkSource.MANGADEX
                    "mangadot" -> DiagnosticMangaAllNetworkSource.MANGADOT
                    "mangafire" -> DiagnosticMangaAllNetworkSource.MANGAFIRE
                    else -> null
                }?.let { source ->
                    mapOf(DiagnosticAttribute.MANGAALL_NETWORK_SOURCE to DiagnosticAttributeValue.Code(source))
                } ?: emptyMap()
                val capturedSourceStructure = if (structuredDiagnostics.detailedCaptureActive) {
                    val sourceAudit = try {
                        resolver.auditSourceIdentity(option)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Diagnostics cannot block selection of a validated Reader source.
                        null
                    }
                    if (sourceAudit == null) {
                        emptyMap()
                    } else {
                        buildMap {
                            sourceAudit.sourceNumberMilli?.let { number ->
                                put(
                                    DiagnosticAttribute.PROVIDER_READER_SOURCE_NUMBER_MILLI,
                                    DiagnosticAttributeValue.Number(number),
                                )
                            }
                            sourceAudit.sourceVolume?.let { volume ->
                                put(
                                    DiagnosticAttribute.PROVIDER_READER_SOURCE_VOLUME,
                                    DiagnosticAttributeValue.Number(volume.toLong()),
                                )
                            }
                            put(
                                DiagnosticAttribute.PROVIDER_READER_LABEL_HAS_VOLUME_MARKER,
                                DiagnosticAttributeValue.Flag(sourceAudit.labelHasExplicitVolumeMarker),
                            )
                            put(
                                DiagnosticAttribute.PROVIDER_READER_TITLE_IS_VOLUME_CONTAINER,
                                DiagnosticAttributeValue.Flag(sourceAudit.sourceTitleIsVolumeContainer),
                            )
                            put(
                                DiagnosticAttribute.PROVIDER_READER_TITLE_PRESENT,
                                DiagnosticAttributeValue.Flag(sourceAudit.sourceTitlePresent),
                            )
                        }
                    }
                } else {
                    emptyMap()
                }
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_SOURCE_SELECTED,
                    stage = DiagnosticStage.READER,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    attributes = mapOf(
                        DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text(option.providerId.value),
                        DiagnosticAttribute.CANDIDATE_TYPE to
                            DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                    ) + capturedCanonicalIdentity + capturedSourceIdentity + capturedSourceStructure +
                        capturedInternalSource,
                )

                when (val prepared = resolver.preparedContent(option)) {
                    is ProviderCallResult.Failure -> {
                        trace.event(
                            subsystem = DiagnosticSubsystem.READER,
                            name = DiagnosticEventName.READER_PAGES_READY,
                            stage = DiagnosticStage.READER,
                            outcome = DiagnosticOutcome.TYPED_FAILURE,
                            severity = DiagnosticSeverity.WARN,
                            attributes = buildMap {
                                put(
                                    DiagnosticAttribute.PROVIDER_ID,
                                    DiagnosticAttributeValue.Text(option.providerId.value),
                                )
                                put(
                                    DiagnosticAttribute.ERROR_CATEGORY,
                                    DiagnosticAttributeValue.Code(prepared.error.code.toDiagnosticCategory()),
                                )
                                put(
                                    DiagnosticAttribute.PROVIDER_ERROR_CODE,
                                    DiagnosticAttributeValue.Text(prepared.error.code.name),
                                )
                                putAll(capturedCanonicalIdentity)
                                putAll(capturedSourceIdentity)
                                putAll(capturedInternalSource)
                                prepared.error.observedPageCount?.let { count ->
                                    put(DiagnosticAttribute.PAGE_COUNT, DiagnosticAttributeValue.Number(count.toLong()))
                                }
                            },
                        )
                    }
                    is ProviderCallResult.Success -> {
                        val pageCount = (prepared.value as? PreparedChapterContent.HttpPages)?.pages?.size
                        trace.event(
                            subsystem = DiagnosticSubsystem.READER,
                            name = DiagnosticEventName.READER_PAGES_READY,
                            stage = DiagnosticStage.READER,
                            outcome = DiagnosticOutcome.READY,
                            attributes = buildMap {
                                put(
                                    DiagnosticAttribute.PROVIDER_ID,
                                    DiagnosticAttributeValue.Text(option.providerId.value),
                                )
                                putAll(capturedCanonicalIdentity)
                                putAll(capturedSourceIdentity)
                                putAll(capturedInternalSource)
                                pageCount?.let {
                                    put(DiagnosticAttribute.PAGE_COUNT, DiagnosticAttributeValue.Number(it.toLong()))
                                }
                            },
                        )
                        return CanonicalReaderPreparation.Ready(
                            canonicalChapterId = canonicalChapterId,
                            target = prepared.value,
                            usedFallback = true,
                            selectedOption = null,
                        )
                    }
                }
            }
        }

        return prepareProviderTorrentFallback(
            canonicalChapterId = canonicalChapterId,
            trace = trace,
        )
    }

    private companion object {
        const val AMBIGUOUS_TORRENT_PROBE_MILLIS = 1_500L
        const val TORRENT_FALLBACK_READER_BUDGET_MILLIS = 20_000L
    }

    private suspend fun prepareProviderTorrentFallback(
        canonicalChapterId: String,
        trace: DiagnosticTrace,
    ): CanonicalReaderPreparation {
        val preparer = prepareProviderChapterTorrent
        trace.event(
            subsystem = DiagnosticSubsystem.CONTENT,
            name = DiagnosticEventName.CONTENT_BINDING_RESOLVE_STARTED,
            stage = DiagnosticStage.BINDING,
            outcome = if (preparer == null) DiagnosticOutcome.REJECTED else DiagnosticOutcome.STARTED,
            attributes = mapOf(
                DiagnosticAttribute.CANDIDATE_TYPE to
                    DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                DiagnosticAttribute.INITIALIZED to DiagnosticAttributeValue.Flag(preparer != null),
            ),
        )
        if (preparer == null) {
            return CanonicalReaderPreparation.Unavailable(canonicalChapterId)
        }

        // Torrent discovery can involve slow trackers and metadata hydration.
        // It must not keep a black Reader screen open for minutes when no HTTP
        // mapping exists. A timed-out attempt is not an absent canonical chapter.
        val prepared = withTimeoutOrNull(TORRENT_FALLBACK_READER_BUDGET_MILLIS) {
            preparer.prepare(canonicalChapterId, trace)
        } ?: run {
            trace.event(
                subsystem = DiagnosticSubsystem.CONTENT,
                name = DiagnosticEventName.CONTENT_BINDING_RESOLVE_COMPLETED,
                stage = DiagnosticStage.BINDING,
                outcome = DiagnosticOutcome.TIMEOUT,
                severity = DiagnosticSeverity.WARN,
                attributes = mapOf(
                    DiagnosticAttribute.CANDIDATE_TYPE to
                        DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                ),
            )
            return CanonicalReaderPreparation.Unavailable(canonicalChapterId)
        }
        return when (prepared) {
            ProviderChapterTorrentPreparation.Unavailable -> {
                trace.event(
                    subsystem = DiagnosticSubsystem.CONTENT,
                    name = DiagnosticEventName.CONTENT_BINDING_RESOLVE_COMPLETED,
                    stage = DiagnosticStage.BINDING,
                    outcome = DiagnosticOutcome.EMPTY,
                    attributes = mapOf(
                        DiagnosticAttribute.CANDIDATE_TYPE to
                            DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                    ),
                )
                CanonicalReaderPreparation.Unavailable(canonicalChapterId)
            }

            is ProviderChapterTorrentPreparation.Ambiguous -> {
                trace.event(
                    subsystem = DiagnosticSubsystem.CONTENT,
                    name = DiagnosticEventName.CONTENT_BINDING_LOOKUP,
                    stage = DiagnosticStage.BINDING,
                    outcome = DiagnosticOutcome.CANDIDATES,
                    attributes = mapOf(
                        DiagnosticAttribute.CANDIDATE_TYPE to
                            DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                        DiagnosticAttribute.CANDIDATE_COUNT to
                            DiagnosticAttributeValue.Number(prepared.candidateCount.toLong()),
                    ),
                )
                CanonicalReaderPreparation.Unavailable(canonicalChapterId)
            }

            is ProviderChapterTorrentPreparation.Failed -> {
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_PAGES_READY,
                    stage = DiagnosticStage.READER,
                    outcome = DiagnosticOutcome.TYPED_FAILURE,
                    severity = DiagnosticSeverity.WARN,
                    attributes = mapOf(
                        DiagnosticAttribute.PROVIDER_ID to
                            DiagnosticAttributeValue.Text(prepared.providerId.value),
                        DiagnosticAttribute.ERROR_CATEGORY to
                            DiagnosticAttributeValue.Code(DiagnosticErrorCategory.SOURCE_UNAVAILABLE),
                    ),
                )
                CanonicalReaderPreparation.Failed(
                    canonicalChapterId = canonicalChapterId,
                    error = prepared.error,
                )
            }

            is ProviderChapterTorrentPreparation.Ready -> {
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_SOURCE_SELECTED,
                    stage = DiagnosticStage.READER,
                    outcome = DiagnosticOutcome.SUCCEEDED,
                    attributes = mapOf(
                        DiagnosticAttribute.PROVIDER_ID to
                            DiagnosticAttributeValue.Text(prepared.providerId.value),
                        DiagnosticAttribute.CANDIDATE_TYPE to
                            DiagnosticAttributeValue.Code(DiagnosticCandidateType.PROVIDER),
                    ),
                )
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_PAGES_READY,
                    stage = DiagnosticStage.READER,
                    outcome = DiagnosticOutcome.READY,
                    attributes = mapOf(
                        DiagnosticAttribute.PROVIDER_ID to
                            DiagnosticAttributeValue.Text(prepared.providerId.value),
                    ),
                )
                CanonicalReaderPreparation.Ready(
                    canonicalChapterId = canonicalChapterId,
                    target = prepared.content,
                    usedFallback = true,
                    selectedOption = null,
                )
            }
        }
    }

    private fun complete(
        trace: DiagnosticTrace,
        started: TimeMark,
        result: CanonicalReaderPreparation,
    ): CanonicalReaderPreparation {
        val duration = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(0)
        when (result) {
            is CanonicalReaderPreparation.Ready -> {
                result.selectedOption?.let { option ->
                    trace.event(
                        subsystem = DiagnosticSubsystem.READER,
                        name = DiagnosticEventName.READER_SOURCE_SELECTED,
                        stage = DiagnosticStage.READER,
                        outcome = DiagnosticOutcome.SUCCEEDED,
                        attributes = mapOf(
                            DiagnosticAttribute.ADDON_ID to DiagnosticAttributeValue.Text(option.addonId.value),
                        ),
                    )
                }
                trace.event(
                    subsystem = DiagnosticSubsystem.READER,
                    name = DiagnosticEventName.READER_OPEN_COMPLETED,
                    stage = DiagnosticStage.COMPLETE,
                    outcome = DiagnosticOutcome.READY,
                    durationMillis = duration,
                )
            }
            is CanonicalReaderPreparation.SelectionRequired -> trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_OPEN_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.NEEDS_CONFIRMATION,
                durationMillis = duration,
                attributes = mapOf(
                    DiagnosticAttribute.CANDIDATE_COUNT to
                        DiagnosticAttributeValue.Number(result.options.size.toLong()),
                ),
            )
            is CanonicalReaderPreparation.ProviderEditionConfirmationRequired -> trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_OPEN_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.NEEDS_CONFIRMATION,
                durationMillis = duration,
                attributes = mapOf(
                    DiagnosticAttribute.CANDIDATE_COUNT to
                        DiagnosticAttributeValue.Number(result.candidateCount.toLong()),
                ),
            )
            is CanonicalReaderPreparation.Unavailable -> trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_OPEN_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.EMPTY,
                durationMillis = duration,
            )
            is CanonicalReaderPreparation.Failed -> trace.event(
                subsystem = DiagnosticSubsystem.READER,
                name = DiagnosticEventName.READER_OPEN_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.FAILED,
                severity = DiagnosticSeverity.ERROR,
                durationMillis = duration,
            )
        }
        return result
    }

    private suspend fun prepare(
        canonicalChapterId: String,
        option: ContentOption,
        usedFallback: Boolean,
    ): CanonicalReaderPreparation {
        if (!resolveChapterContent.isOptionEnabled(option)) {
            return CanonicalReaderPreparation.Unavailable(canonicalChapterId)
        }
        val progress = canonicalReadingRepository.getProgress(canonicalChapterId)
        return chapterContentPreparer.prepare(option, progress).fold(
            onSuccess = { target ->
                CanonicalReaderPreparation.Ready(
                    canonicalChapterId = canonicalChapterId,
                    target = target,
                    usedFallback = usedFallback,
                    selectedOption = option,
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                CanonicalReaderPreparation.Failed(canonicalChapterId, error)
            },
        )
    }

    suspend operator fun invoke(
        canonicalChapterId: String,
    ): CanonicalReaderPreparation = execute(canonicalChapterId)
}

private fun ProviderErrorCode.toDiagnosticCategory(): DiagnosticErrorCategory = when (this) {
    ProviderErrorCode.TIMEOUT -> DiagnosticErrorCategory.TIMEOUT
    ProviderErrorCode.NETWORK_POLICY,
    ProviderErrorCode.NETWORK_ERROR,
    ProviderErrorCode.BROWSER_ERROR,
    -> DiagnosticErrorCategory.NETWORK
    ProviderErrorCode.MALFORMED_RESULT -> DiagnosticErrorCategory.MALFORMED_RESPONSE
    ProviderErrorCode.CHAPTER_IDENTITY_UNVERIFIED -> DiagnosticErrorCategory.CHAPTER_IDENTITY
    ProviderErrorCode.RUNTIME_DIED,
    ProviderErrorCode.SCRIPT_ERROR,
    ProviderErrorCode.PACKAGE_INVALID,
    ProviderErrorCode.MALFORMED_REQUEST,
    ProviderErrorCode.SOURCE_TOO_LARGE,
    ProviderErrorCode.SOURCE_READ_ERROR,
    ProviderErrorCode.INVOCATION_CONFLICT,
    ProviderErrorCode.HOST_ERROR,
    ProviderErrorCode.HOST_API_UNSUPPORTED,
    ProviderErrorCode.RESOURCE_LIMIT,
    -> DiagnosticErrorCategory.EXTENSION
    ProviderErrorCode.UNAVAILABLE,
    ProviderErrorCode.PERMISSION_DENIED,
    ProviderErrorCode.AUTH_REQUIRED,
    ProviderErrorCode.ACQUISITION_FAILED,
    -> DiagnosticErrorCategory.SOURCE_UNAVAILABLE
}
