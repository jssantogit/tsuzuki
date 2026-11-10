package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.PrepareTorrentArtifact
import tachiyomi.domain.tsuzuki.content.PreparedTorrentArtifact
import tachiyomi.domain.tsuzuki.content.TorrentArtifactEngine
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticProviderReaderNoMatchReason
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderFacetRef
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterObservation
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterReadingOption
import tachiyomi.domain.tsuzuki.provider.reading.ProviderError
import tachiyomi.domain.tsuzuki.provider.reading.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import tachiyomi.domain.tsuzuki.provider.reading.ProviderPageRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBindingRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingGateway
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingNoMatchReason
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ResolveProviderChapterReading
import tachiyomi.domain.tsuzuki.provider.reading.evidenceProducerId
import tachiyomi.domain.tsuzuki.provider.torrent.PrepareProviderChapterTorrent
import tachiyomi.domain.tsuzuki.provider.torrent.ResolveProviderChapterTorrent
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.reader.interactor.PrepareCanonicalChapterForReader
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreparation
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.reader.service.ChapterContentPreparer
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult as TorrentProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult as ReadingProviderCallResult

class PrepareCanonicalChapterProviderFallbackTest {

    @Test
    fun `ambiguous reading without an independent source requests a confirmed edition`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val options = listOf("work-a", "work-b").map { workId ->
            ProviderChapterReadingOption(
                canonicalChapterId = chapter.id,
                bindingId = "source-$workId",
                providerId = ProviderId("app.tsuzuki.mangafire"),
                facetId = "en",
                externalWorkId = workId,
                providerChapterId = "chapter-$workId",
            )
        }
        val resolver = mockk<ResolveProviderChapterReading>()
        coEvery { resolver.options(chapter.id) } returns options
        coEvery { resolver.uniqueUserConfirmedOption(chapter.id, options) } returns null
        val reader = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapterRepository(chapter),
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            canonicalDownloadGateway = object : CanonicalDownloadGateway {
                override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
            },
            chapterContentPreparer = mockk(),
            structuredDiagnostics = RecordingStructuredDiagnosticRecorder(detailed = true),
            resolveProviderChapterReading = resolver,
        )
        val unresolved = reader.execute(chapter.id)
            .shouldBeInstanceOf<CanonicalReaderPreparation.ProviderEditionConfirmationRequired>()
        unresolved.canonicalTitleId shouldBe chapter.canonicalTitleId
        unresolved.candidateCount shouldBe 2
    }

    @Test
    fun `ambiguous verified sources do not await slow torrent fallback before asking for edition`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val options = listOf("source-a", "source-b").map { workId ->
            ProviderChapterReadingOption(
                canonicalChapterId = chapter.id,
                bindingId = "binding-$workId",
                providerId = ProviderId("app.tsuzuki.mangaall"),
                facetId = "en",
                externalWorkId = "mangafire:$workId",
                providerChapterId = "mangafire:chapter-$workId",
            )
        }
        val resolver = mockk<ResolveProviderChapterReading>()
        coEvery { resolver.options(chapter.id) } returns options
        coEvery { resolver.uniqueUserConfirmedOption(chapter.id, options) } returns null
        val torrent = mockk<PrepareProviderChapterTorrent>()
        coEvery { torrent.prepare(chapter.id, any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val prepare = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapterRepository(chapter),
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            canonicalDownloadGateway = object : CanonicalDownloadGateway {
                override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
            },
            chapterContentPreparer = mockk(),
            structuredDiagnostics = RecordingStructuredDiagnosticRecorder(detailed = true),
            resolveProviderChapterReading = resolver,
            prepareProviderChapterTorrent = torrent,
        )
        val result = kotlinx.coroutines.withTimeout(2_500L) { prepare.execute(chapter.id) }
        result.shouldBeInstanceOf<CanonicalReaderPreparation.ProviderEditionConfirmationRequired>()
    }

    @Test
    fun `unavailable HTTP sources bound slow torrent fallback before Reader can exit loading`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val torrent = mockk<PrepareProviderChapterTorrent>()
        coEvery { torrent.prepare(chapter.id, any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val recorder = RecordingStructuredDiagnosticRecorder(detailed = false)
        val prepare = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapterRepository(chapter),
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            canonicalDownloadGateway = object : CanonicalDownloadGateway {
                override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
            },
            chapterContentPreparer = mockk(),
            structuredDiagnostics = recorder,
            prepareProviderChapterTorrent = torrent,
        )
        val result = kotlinx.coroutines.withTimeout(25_000L) { prepare.execute(chapter.id) }
        result.shouldBeInstanceOf<CanonicalReaderPreparation.Unavailable>()
        recorder.events.any {
            it.name == DiagnosticEventName.CONTENT_BINDING_RESOLVE_COMPLETED &&
                it.outcome == DiagnosticOutcome.TIMEOUT
        } shouldBe true
        // A timed-out fallback cannot grant Reader source authority.
    }

    @Test
    fun `unresolved work sources have distinct actionable preparation`() {
        val unresolved = CanonicalReaderPreparation.ProviderEditionConfirmationRequired(
            canonicalTitleId = "title-1",
            canonicalChapterId = "chapter-1",
            candidateCount = 3,
        )
        unresolved.canonicalTitleId shouldBe "title-1"
        unresolved.candidateCount shouldBe 3
    }

    @Test
    fun `empty Provider audit runs only during detailed capture and never breaks Reader fallback`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val providerId = ProviderId("app.tsuzuki.mangafire")
        val binding = ProviderReadingBinding(
            id = "binding-1",
            canonicalTitleId = chapter.canonicalTitleId,
            ref = ProviderBindingRef(providerId, "en", "work-1"),
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1,
            updatedAt = 1,
        )

        listOf(false to false, true to false, true to true).forEach { (detailed, failAudit) ->
            var evidenceReads = 0
            val localEvidence = object : ChapterEvidenceRepository by evidenceRepository(emptyList()) {
                override suspend fun getByCanonicalTitleId(
                    canonicalTitleId: String,
                ): List<PersistedChapterEvidence> {
                    evidenceReads++
                    if (evidenceReads > 1 && failAudit) error("diagnostic-only repository failure")
                    return emptyList()
                }
            }
            val resolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository(chapter),
                evidenceRepository = localEvidence,
                bindingRepository = bindingRepository(binding),
                providerRegistry = DefaultProviderRegistry(
                    registrations = { listOf(readingRegistration(providerId, "en")) },
                ),
                gateway = object : ProviderReadingGateway {
                    override suspend fun lookup(
                        providerId: ProviderId,
                        request: ProviderReadingLookupRequest,
                    ): ReadingProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("unused")

                    override suspend fun chapters(
                        providerId: ProviderId,
                        request: ProviderReadingChaptersRequest,
                    ): ReadingProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("unused")

                    override suspend fun pages(
                        providerId: ProviderId,
                        request: ProviderReadingPagesRequest,
                    ): ReadingProviderCallResult<ProviderReadingDelivery> = error("unused")
                },
            )
            val recorder = RecordingStructuredDiagnosticRecorder(detailed = detailed)
            val reader = PrepareCanonicalChapterForReader(
                resolveChapterContent = emptyAddonResolver(),
                canonicalChapterRepository = chapterRepository(chapter),
                canonicalReadingRepository = readingRepository(),
                canonicalDownloadRepository = emptyDownloadRepository(),
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
                chapterContentPreparer = object : ChapterContentPreparer {
                    override suspend fun prepare(
                        option: ContentOption,
                        progress: CanonicalChapterProgress?,
                    ): Result<PreparedChapterContent> = error("unused")
                },
                structuredDiagnostics = recorder,
                resolveProviderChapterReading = resolver,
                prepareProviderChapterTorrent = null,
            )

            reader.execute(chapter.id).shouldBeInstanceOf<CanonicalReaderPreparation.Unavailable>()
            evidenceReads shouldBe (if (detailed) 2 else 1)
            recorder.events.any { event ->
                event.name == DiagnosticEventName.CONTENT_BINDING_LOOKUP &&
                    event.outcome == DiagnosticOutcome.EMPTY &&
                    event.attributes["candidate_count"] == DiagnosticAttributeValue.Number(0)
            } shouldBe true
            recorder.events.any { event ->
                event.name == DiagnosticEventName.CONTENT_BINDING_LOOKUP &&
                    event.attributes["provider_reader_no_match_reason"] == DiagnosticAttributeValue.Code(
                        DiagnosticProviderReaderNoMatchReason.NO_PROVIDER_EVIDENCE,
                    )
            } shouldBe (detailed && !failAudit)
        }
    }

    @Test
    fun `Reader audit separates orphaned mapped evidence from inactive and ineligible source refs`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-one",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val activeProvider = ProviderId("app.tsuzuki.mangafire")
        val activeRef = ProviderBindingRef(activeProvider, "en", "current-work")
        val active = ProviderReadingBinding(
            id = "active",
            canonicalTitleId = chapter.canonicalTitleId,
            ref = activeRef,
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1,
            updatedAt = 1,
        )
        val inactiveRef = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "en", "mangadex:old")
        val unavailableRef = ProviderBindingRef(activeProvider, "en", "missing-work")
        val phantomRef = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "en", "mangadot:317")
        val cases = listOf(
            Triple(phantomRef, null, 0 to 0),
            Triple(inactiveRef, inactiveRef, 1 to 0),
            Triple(unavailableRef, unavailableRef, 1 to 1),
        )
        for ((mappedRef, persistRef, expected) in cases) {
            // Every fixture has one other eligible source, but only an
            // ineligible/unregistered producer owns the mapped chapter.
            val additional = persistRef?.let {
                active.copy(id = "mapped", ref = it, availability = ProviderBindingAvailability.UNAVAILABLE)
            }
            val bindings = listOfNotNull(active, additional)
            val bindingStore = object : ProviderReadingBindingRepository {
                override suspend fun get(
                    canonicalTitleId: String,
                    providerId: ProviderId,
                    facetId: String?,
                ): ProviderReadingBinding? = bindings.firstOrNull {
                    it.canonicalTitleId == canonicalTitleId &&
                        it.ref.providerId == providerId &&
                        it.ref.facetId == facetId
                }
                override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
                    bindings.filter { it.canonicalTitleId == canonicalTitleId }
                override suspend fun upsert(binding: ProviderReadingBinding) = Unit
                override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = Unit
            }
            val evidence = PersistedChapterEvidence(
                evidence = ChapterEvidence(
                    id = "mapped",
                    canonicalTitleId = chapter.canonicalTitleId,
                    producerKind = ProducerKind.PROVIDER,
                    producerId = mappedRef.evidenceProducerId(),
                    externalChapterKey = "chapter-one",
                    rawLabel = "Chapter 1",
                    rawNumber = 1.0,
                    observedAt = 1,
                    confidence = 0.95,
                    authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
                ),
                mappedCanonicalChapterId = chapter.id,
            )
            val resolver = ResolveProviderChapterReading(
                canonicalChapterRepository = chapterRepository(chapter),
                evidenceRepository = evidenceRepository(listOf(evidence)),
                bindingRepository = bindingStore,
                providerRegistry = DefaultProviderRegistry(
                    registrations = { listOf(readingRegistration(activeProvider, "en")) },
                ),
                gateway = object : ProviderReadingGateway {
                    override suspend fun lookup(
                        providerId: ProviderId,
                        request: ProviderReadingLookupRequest,
                    ): ReadingProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("not used")
                    override suspend fun chapters(
                        providerId: ProviderId,
                        request: ProviderReadingChaptersRequest,
                    ): ReadingProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("not used")
                    override suspend fun pages(
                        providerId: ProviderId,
                        request: ProviderReadingPagesRequest,
                    ): ReadingProviderCallResult<ProviderReadingDelivery> = error("not used")
                },
            )
            val audit = resolver.auditNoMatch(chapter.id)
            audit.reason shouldBe ProviderReadingNoMatchReason.NO_ELIGIBLE_MAPPED_SOURCE
            audit.eligibleSourceCount shouldBe 1
            audit.mappedEvidenceCount shouldBe 1
            audit.mappedKnownSourceCount shouldBe expected.first
            audit.mappedActiveSourceCount shouldBe expected.second
        }
    }

    @Test
    fun `detailed Reader source trace includes original source structure but never raw chapter labels`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-zero-volume-one",
            canonicalTitleId = "title-1",
            displayNumber = "0",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 0,
            volume = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val providerId = ProviderId("app.tsuzuki.mangafire")
        val binding = ProviderReadingBinding(
            id = "binding-1",
            canonicalTitleId = chapter.canonicalTitleId,
            ref = ProviderBindingRef(providerId, "en", "work-1"),
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1,
            updatedAt = 1,
        )
        val readingResolver = ResolveProviderChapterReading(
            canonicalChapterRepository = chapterRepository(chapter),
            evidenceRepository = evidenceRepository(
                listOf(
                    PersistedChapterEvidence(
                        evidence = ChapterEvidence(
                            id = "source-chapter",
                            canonicalTitleId = chapter.canonicalTitleId,
                            producerKind = ProducerKind.PROVIDER,
                            producerId = binding.ref.evidenceProducerId(),
                            externalChapterKey = "12345",
                            rawLabel = "Vol. 1 Ch. 0 - Opening",
                            rawNumber = 0.0,
                            volume = 1,
                            title = "Opening",
                            observedAt = 1,
                            confidence = 0.95,
                            authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
                        ),
                        mappedCanonicalChapterId = chapter.id,
                    ),
                ),
            ),
            bindingRepository = bindingRepository(binding),
            providerRegistry = DefaultProviderRegistry(
                registrations = { listOf(readingRegistration(providerId, "en")) },
            ),
            gateway = object : ProviderReadingGateway {
                override suspend fun lookup(
                    providerId: ProviderId,
                    request: ProviderReadingLookupRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("unused")

                override suspend fun chapters(
                    providerId: ProviderId,
                    request: ProviderReadingChaptersRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("unused")

                override suspend fun pages(
                    providerId: ProviderId,
                    request: ProviderReadingPagesRequest,
                ): ReadingProviderCallResult<ProviderReadingDelivery> = ReadingProviderCallResult.Failure(
                    ProviderError(ProviderErrorCode.PACKAGE_INVALID, retryable = false),
                )
            },
        )
        val diagnostics = RecordingStructuredDiagnosticRecorder(detailed = true)
        val reader = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapterRepository(chapter),
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            canonicalDownloadGateway = object : CanonicalDownloadGateway {
                override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
            },
            chapterContentPreparer = object : ChapterContentPreparer {
                override suspend fun prepare(
                    option: ContentOption,
                    progress: CanonicalChapterProgress?,
                ): Result<PreparedChapterContent> = error("unused")
            },
            structuredDiagnostics = diagnostics,
            resolveProviderChapterReading = readingResolver,
        )
        reader.execute(chapter.id).shouldBeInstanceOf<CanonicalReaderPreparation.Unavailable>()

        val event = diagnostics.events.first {
            it.name == DiagnosticEventName.READER_SOURCE_SELECTED &&
                it.outcome == DiagnosticOutcome.SUCCEEDED
        }
        event.attributes["provider_reader_source_number_milli"] shouldBe DiagnosticAttributeValue.Number(0)
        event.attributes["provider_reader_source_volume"] shouldBe DiagnosticAttributeValue.Number(1)
        event.attributes["provider_reader_label_has_volume_marker"] shouldBe DiagnosticAttributeValue.Flag(true)
        event.attributes["provider_reader_title_is_volume_container"] shouldBe DiagnosticAttributeValue.Flag(false)
        event.attributes["provider_reader_title_present"] shouldBe DiagnosticAttributeValue.Flag(true)
        event.attributes.values.any { it == DiagnosticAttributeValue.Text("Opening") } shouldBe false
    }

    @Test
    fun `Reader opens only uniquely confirmed edition when provisional alternate is also mapped`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val sourceIds = listOf(ProviderId("app.tsuzuki.mangafire"), ProviderId("app.tsuzuki.mangadot"))
        val bindings = sourceIds.mapIndexed { index, source ->
            ProviderReadingBinding(
                id = "binding-$index",
                canonicalTitleId = chapter.canonicalTitleId,
                ref = ProviderBindingRef(source, "en", "work-$index"),
                verification = if (index == 0) {
                    ProviderBindingVerification.USER_CONFIRMED
                } else {
                    ProviderBindingVerification.EXACT
                },
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1,
                updatedAt = 1,
            )
        }
        val evidence = bindings.mapIndexed { index, binding ->
            PersistedChapterEvidence(
                evidence = ChapterEvidence(
                    id = "source-$index",
                    canonicalTitleId = chapter.canonicalTitleId,
                    producerKind = ProducerKind.PROVIDER,
                    producerId = binding.ref.evidenceProducerId(),
                    externalChapterKey = "page-source-$index",
                    rawLabel = "Chapter 1",
                    rawNumber = 1.0,
                    volume = null,
                    title = null,
                    observedAt = 1,
                    confidence = 0.95,
                    authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
                ),
                mappedCanonicalChapterId = chapter.id,
            )
        }
        val resolver = ResolveProviderChapterReading(
            canonicalChapterRepository = chapterRepository(chapter),
            evidenceRepository = evidenceRepository(evidence),
            bindingRepository = object : ProviderReadingBindingRepository {
                override suspend fun get(
                    canonicalTitleId: String,
                    providerId: ProviderId,
                    facetId: String?,
                ): ProviderReadingBinding? = bindings.firstOrNull {
                    it.canonicalTitleId == canonicalTitleId &&
                        it.ref.providerId == providerId &&
                        it.ref.facetId == facetId
                }
                override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
                    bindings.filter { it.canonicalTitleId == canonicalTitleId }
                override suspend fun upsert(binding: ProviderReadingBinding) = Unit
                override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = Unit
            },
            providerRegistry = DefaultProviderRegistry(
                registrations = { bindings.map { readingRegistration(it.ref.providerId, it.ref.facetId) } },
            ),
            gateway = object : ProviderReadingGateway {
                override suspend fun lookup(
                    providerId: ProviderId,
                    request: ProviderReadingLookupRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("unused")
                override suspend fun chapters(
                    providerId: ProviderId,
                    request: ProviderReadingChaptersRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("unused")
                override suspend fun pages(
                    providerId: ProviderId,
                    request: ProviderReadingPagesRequest,
                ): ReadingProviderCallResult<ProviderReadingDelivery> {
                    providerId shouldBe sourceIds.first()
                    request.providerChapterId shouldBe "page-source-0"
                    return ReadingProviderCallResult.Success(
                        ProviderReadingDelivery.PageList(
                            listOf(
                                ProviderPageRequest(
                                    url = "https://reader.example/chapter-1/001.jpg",
                                    allowedOrigins = setOf("https://reader.example"),
                                ),
                            ),
                        ),
                    )
                }
            },
        )
        val recorder = RecordingStructuredDiagnosticRecorder(detailed = true)
        val reader = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapterRepository(chapter),
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            canonicalDownloadGateway = object : CanonicalDownloadGateway {
                override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
            },
            chapterContentPreparer = object : ChapterContentPreparer {
                override suspend fun prepare(
                    option: ContentOption,
                    progress: CanonicalChapterProgress?,
                ): Result<PreparedChapterContent> = error("unused")
            },
            structuredDiagnostics = recorder,
            resolveProviderChapterReading = resolver,
        )

        resolver.options(chapter.id).size shouldBe 2
        val result = reader.execute(chapter.id).shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.target.shouldBeInstanceOf<PreparedChapterContent.HttpPages>().pages.size shouldBe 1
        recorder.events.any {
            it.name == DiagnosticEventName.READER_SOURCE_SELECTED &&
                it.outcome == DiagnosticOutcome.SUCCEEDED &&
                it.attributes["provider_id"] == DiagnosticAttributeValue.Text(sourceIds.first().value)
        } shouldBe true
        recorder.events.none {
            it.name == DiagnosticEventName.READER_SOURCE_SELECTED &&
                it.outcome == DiagnosticOutcome.NEEDS_CONFIRMATION
        } shouldBe true
    }

    @Test
    fun `ambiguous HTTP Provider sources never get chosen but allow exact torrent fallback`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val chapters = chapterRepository(chapter)
        val httpProviders = listOf(
            ProviderId("app.tsuzuki.mangafire"),
            ProviderId("app.tsuzuki.mangadot"),
        )
        val bindings = httpProviders.mapIndexed { index, providerId ->
            ProviderReadingBinding(
                id = "binding-$index",
                canonicalTitleId = chapter.canonicalTitleId,
                ref = ProviderBindingRef(providerId, "en", "work-$index"),
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1,
                updatedAt = 1,
            )
        }
        val observations = bindings.mapIndexed { index, binding ->
            PersistedChapterEvidence(
                evidence = ChapterEvidence(
                    id = "source-$index",
                    canonicalTitleId = chapter.canonicalTitleId,
                    producerKind = ProducerKind.PROVIDER,
                    producerId = binding.ref.evidenceProducerId(),
                    externalChapterKey = "source-chapter-$index",
                    rawLabel = "Chapter 1",
                    rawNumber = 1.0,
                    volume = null,
                    title = null,
                    observedAt = 1,
                    confidence = 0.95,
                    authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
                ),
                mappedCanonicalChapterId = chapter.id,
            )
        }
        var unexpectedHttpCalls = 0
        var failConfirmationRead = false
        var bindingReads = 0
        val http = ResolveProviderChapterReading(
            canonicalChapterRepository = chapters,
            evidenceRepository = evidenceRepository(observations),
            bindingRepository = object : ProviderReadingBindingRepository {
                override suspend fun get(
                    canonicalTitleId: String,
                    providerId: ProviderId,
                    facetId: String?,
                ): ProviderReadingBinding? = bindings.firstOrNull {
                    it.canonicalTitleId == canonicalTitleId &&
                        it.ref.providerId == providerId &&
                        it.ref.facetId == facetId
                }

                override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> {
                    bindingReads++
                    if (failConfirmationRead && bindingReads % 2 == 0) {
                        error("A secondary confirmed-binding lookup failed")
                    }
                    return bindings.filter { it.canonicalTitleId == canonicalTitleId }
                }

                override suspend fun upsert(binding: ProviderReadingBinding) = Unit
                override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = Unit
            },
            providerRegistry = DefaultProviderRegistry(
                registrations = {
                    bindings.map { readingRegistration(it.ref.providerId, it.ref.facetId) }
                },
            ),
            gateway = object : ProviderReadingGateway {
                override suspend fun lookup(
                    providerId: ProviderId,
                    request: ProviderReadingLookupRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("unused")
                override suspend fun chapters(
                    providerId: ProviderId,
                    request: ProviderReadingChaptersRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("unused")
                override suspend fun pages(
                    providerId: ProviderId,
                    request: ProviderReadingPagesRequest,
                ): ReadingProviderCallResult<ProviderReadingDelivery> {
                    unexpectedHttpCalls++
                    error("Ambiguous HTTP selection must never fetch pages")
                }
            },
        )
        val torrentProviderId = ProviderId("app.tsuzuki.nyaa")
        var torrentArtifactCalls = 0
        val torrent = PrepareProviderChapterTorrent(
            resolver = ResolveProviderChapterTorrent(
                canonicalChapterRepository = chapters,
                canonicalTitleRepository = titleRepository(),
                providerRegistry = DefaultProviderRegistry(
                    registrations = { listOf(torrentRegistration(torrentProviderId)) },
                ),
                gateway = TorrentSearchGateway { _, _ ->
                    TorrentProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                TorrentCandidate(
                                    infoHash = "0123456789abcdef0123456789abcdef01234567",
                                    magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                                    torrentUrl = null,
                                    displayName = "Example Manga Chapter 1",
                                    files = listOf(TorrentCandidateFile(0, "chapter-001.cbz")),
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            prepareTorrentArtifact = PrepareTorrentArtifact(
                TorrentArtifactEngine {
                    torrentArtifactCalls++
                    Result.success(
                        PreparedTorrentArtifact(
                            localUri = "content://provider.test/chapter-1.cbz",
                            format = "CBZ",
                        ),
                    )
                },
            ),
        )
        val recorder = RecordingStructuredDiagnosticRecorder(detailed = true)
        val reader = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapters,
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            canonicalDownloadGateway = object : CanonicalDownloadGateway {
                override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
            },
            chapterContentPreparer = object : ChapterContentPreparer {
                override suspend fun prepare(
                    option: ContentOption,
                    progress: CanonicalChapterProgress?,
                ): Result<PreparedChapterContent> = error("unused")
            },
            structuredDiagnostics = recorder,
            resolveProviderChapterReading = http,
            prepareProviderChapterTorrent = torrent,
        )
        val result = reader.execute(chapter.id).shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.target shouldBe PreparedChapterContent.LocalArchive("content://provider.test/chapter-1.cbz")
        unexpectedHttpCalls shouldBe 0
        torrentArtifactCalls shouldBe 1
        recorder.events.any {
            it.name == DiagnosticEventName.READER_SOURCE_SELECTED &&
                it.outcome == DiagnosticOutcome.NEEDS_CONFIRMATION &&
                it.attributes["candidate_count"] == DiagnosticAttributeValue.Number(2)
        } shouldBe true
        recorder.events.any {
            it.name == DiagnosticEventName.READER_PAGES_READY &&
                it.outcome == DiagnosticOutcome.READY &&
                it.attributes["provider_id"] == DiagnosticAttributeValue.Text(torrentProviderId.value)
        } shouldBe true
        // The optional DB read needed to check user-confirmed preferences may
        // fail after options() succeeded. It must not throw or hide the
        // independently verified torrent fallback.
        failConfirmationRead = true
        bindingReads = 0
        reader.execute(chapter.id).shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        unexpectedHttpCalls shouldBe 0
        torrentArtifactCalls shouldBe 2
    }

    @Test
    fun `failed Provider HTTP delivery falls through to exact torrent archive`() = runTest {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            confidence = 1.0,
            createdAt = 1,
            updatedAt = 1,
        )
        val chapterRepository = chapterRepository(chapter)
        val httpProviderId = ProviderId("app.tsuzuki.mangafire")
        val binding = ProviderReadingBinding(
            id = "binding-1",
            canonicalTitleId = chapter.canonicalTitleId,
            ref = ProviderBindingRef(httpProviderId, "en", "work-1"),
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1,
            updatedAt = 1,
        )
        val readingResolver = ResolveProviderChapterReading(
            canonicalChapterRepository = chapterRepository,
            evidenceRepository = evidenceRepository(
                listOf(
                    PersistedChapterEvidence(
                        evidence = ChapterEvidence(
                            id = "evidence-1",
                            canonicalTitleId = chapter.canonicalTitleId,
                            producerKind = ProducerKind.PROVIDER,
                            producerId = binding.ref.evidenceProducerId(),
                            externalChapterKey = "provider-chapter-1",
                            rawLabel = "Chapter 1",
                            rawNumber = 1.0,
                            volume = null,
                            title = null,
                            observedAt = 1,
                            confidence = 0.95,
                            authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
                        ),
                        mappedCanonicalChapterId = chapter.id,
                    ),
                ),
            ),
            bindingRepository = bindingRepository(binding),
            providerRegistry = DefaultProviderRegistry(
                registrations = { listOf(readingRegistration(binding.ref.providerId, binding.ref.facetId)) },
            ),
            gateway = object : ProviderReadingGateway {
                override suspend fun lookup(
                    providerId: ProviderId,
                    request: ProviderReadingLookupRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderWorkCandidate>> = error("unused")

                override suspend fun chapters(
                    providerId: ProviderId,
                    request: ProviderReadingChaptersRequest,
                ): ReadingProviderCallResult<ProviderPage<ProviderChapterObservation>> = error("unused")

                override suspend fun pages(
                    providerId: ProviderId,
                    request: ProviderReadingPagesRequest,
                ): ReadingProviderCallResult<ProviderReadingDelivery> = ReadingProviderCallResult.Failure(
                    ProviderError(ProviderErrorCode.PACKAGE_INVALID, retryable = false),
                )
            },
            managedResources = ProviderManagedResourceResolver.DenyAll,
        )

        val torrentProviderId = ProviderId("app.tsuzuki.nyaa")
        var torrentArtifactCalls = 0
        val torrentPreparer = PrepareProviderChapterTorrent(
            resolver = ResolveProviderChapterTorrent(
                canonicalChapterRepository = chapterRepository,
                canonicalTitleRepository = titleRepository(),
                providerRegistry = DefaultProviderRegistry(
                    registrations = { listOf(torrentRegistration(torrentProviderId)) },
                ),
                gateway = TorrentSearchGateway { _, _ ->
                    TorrentProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                TorrentCandidate(
                                    infoHash = "0123456789abcdef0123456789abcdef01234567",
                                    magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                                    torrentUrl = null,
                                    displayName = "Example Manga Chapter 1",
                                    files = listOf(TorrentCandidateFile(0, "chapter-001.cbz")),
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            prepareTorrentArtifact = PrepareTorrentArtifact(
                TorrentArtifactEngine {
                    torrentArtifactCalls += 1
                    Result.success(
                        PreparedTorrentArtifact(
                            localUri = "content://provider.test/chapter-1.cbz",
                            format = "CBZ",
                        ),
                    )
                },
            ),
        )
        val diagnostics = RecordingStructuredDiagnosticRecorder()

        val prepare = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapterRepository,
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            canonicalDownloadGateway = object : CanonicalDownloadGateway {
                override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
            },
            chapterContentPreparer = object : ChapterContentPreparer {
                override suspend fun prepare(
                    option: ContentOption,
                    progress: CanonicalChapterProgress?,
                ): Result<PreparedChapterContent> = error("unused")
            },
            structuredDiagnostics = diagnostics,
            resolveProviderChapterReading = readingResolver,
            prepareProviderChapterTorrent = torrentPreparer,
        )

        val result = prepare.execute(chapter.id)

        result.shouldBeInstanceOf<CanonicalReaderPreparation.Ready>()
        result.target shouldBe PreparedChapterContent.LocalArchive(
            uri = "content://provider.test/chapter-1.cbz",
        )
        result.usedFallback shouldBe true
        torrentArtifactCalls shouldBe 1
        diagnostics.events.any { event ->
            event.name == DiagnosticEventName.READER_PAGES_READY &&
                event.attributes["provider_error_code"] == DiagnosticAttributeValue.Text("PACKAGE_INVALID")
        } shouldBe true
        diagnostics.events.any { event ->
            event.name == DiagnosticEventName.CONTENT_BINDING_LOOKUP &&
                event.stage == DiagnosticStage.SEARCH &&
                event.outcome == DiagnosticOutcome.CANDIDATES &&
                event.attributes["provider_id"] == DiagnosticAttributeValue.Text(torrentProviderId.value)
        } shouldBe true
        // Reader identity correlation must be opt-in; normal INFO/WARN logs
        // must not expose public work or exact chapter IDs.
        diagnostics.events.filter { event ->
            event.name == DiagnosticEventName.READER_SOURCE_SELECTED ||
                event.name == DiagnosticEventName.READER_PAGES_READY
        }.none { event ->
            "provider_work_id" in event.attributes || "provider_chapter_id" in event.attributes
        } shouldBe true
    }

    private fun emptyAddonResolver() = ResolveChapterContent(
        addonRegistry = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> = emptyList()
        },
        contentPreferenceRepository = object : ContentPreferenceRepository {
            override suspend fun get(canonicalTitleId: String): ContentPreference? = null
            override fun observe(canonicalTitleId: String): Flow<ContentPreference?> = MutableStateFlow(null)
            override suspend fun upsert(preference: ContentPreference) = Unit
            override suspend fun delete(canonicalTitleId: String) = Unit
        },
        readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore()),
        rankContentOptions = RankContentOptions(),
        contentOptionCache = ContentOptionCache(),
        inFlightContentResolution = InFlightContentResolution(),
        addonRepository = null,
    )

    private fun readingRegistration(id: ProviderId, facetId: String?) = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = id,
            name = id.value,
            version = ProviderVersion("1.0.0", 1),
            origin = ProviderOrigin.Repository("repo"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(
                ProviderCapabilities.ReadingLookupV1,
                ProviderCapabilities.ReadingChaptersV1,
                ProviderCapabilities.ReadingPagesV1,
            ),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = setOf("en"),
        ),
        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
        facets = facetId?.let { listOf(ProviderFacetRef(id, it)) }.orEmpty(),
    )

    private fun torrentRegistration(id: ProviderId) = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = id,
            name = id.value,
            version = ProviderVersion("1.0.0", 1),
            origin = ProviderOrigin.Repository("repo"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        ),
        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
    )

    private fun bindingRepository(binding: ProviderReadingBinding) = object : ProviderReadingBindingRepository {
        override suspend fun get(
            canonicalTitleId: String,
            providerId: ProviderId,
            facetId: String?,
        ): ProviderReadingBinding? = binding.takeIf {
            it.canonicalTitleId == canonicalTitleId && it.ref.providerId == providerId && it.ref.facetId == facetId
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
            listOf(binding).filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ProviderReadingBinding) = Unit
        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = Unit
    }

    private fun evidenceRepository(values: List<PersistedChapterEvidence>) = object : ChapterEvidenceRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
            values.filter { it.evidence.canonicalTitleId == canonicalTitleId }

        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? = values.firstOrNull {
            it.evidence.producerKind == producerKind &&
                it.evidence.producerId == producerId &&
                it.evidence.externalChapterKey == externalChapterKey
        }

        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ) = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
    }

    private fun titleRepository() = object : CanonicalTitleRepository {
        private val title = CanonicalTitle(
            id = "title-1",
            displayTitle = "Example Manga",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1,
            updatedAt = 1,
        )

        override suspend fun getById(id: String) = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("unused")
        override suspend fun insert(title: CanonicalTitle) = error("unused")
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = error("unused")
    }

    private fun chapterRepository(chapter: CanonicalChapter) = object : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            listOf(chapter).filter { it.canonicalTitleId == canonicalTitleId }
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            flowOf(listOf(chapter).filter { it.canonicalTitleId == canonicalTitleId })
        override suspend fun getById(id: String) = chapter.takeIf { it.id == id }
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String) = emptyList<ChapterVariant>()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String) = emptyList<ChapterVariant>()
        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) = Unit
    }

    private fun emptyDownloadRepository() = object : CanonicalDownloadRepository {
        override suspend fun get(canonicalChapterId: String): CanonicalDownloadArtifact? = null
        override suspend fun upsert(artifact: CanonicalDownloadArtifact) = Unit
        override suspend fun delete(canonicalChapterId: String) = Unit
        override suspend fun deleteOriginMetadata(addonId: AddonId) = Unit
    }

    private fun readingRepository() = object : CanonicalReadingRepository {
        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? = null
        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> = flowOf(null)
        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ) = emptyList<CanonicalChapterProgress>()
        override suspend fun upsertProgress(progress: CanonicalChapterProgress) = Unit
        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit
        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }

    private class RecordingStructuredDiagnosticRecorder(
        private val detailed: Boolean = false,
    ) : StructuredDiagnosticRecorder {
        override val detailedCaptureActive: Boolean get() = detailed
        override val sessionId: String = "00000000-0000-0000-0000-000000000001"
        val events = mutableListOf<StructuredDiagnosticEvent>()

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null

        override fun mihonMangaReference(mihonMangaId: Long): String? = null

        override fun record(event: StructuredDiagnosticEvent) {
            events += event
        }
    }
}
