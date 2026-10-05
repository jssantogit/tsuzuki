package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderError
import tachiyomi.domain.tsuzuki.provider.reading.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBindingRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingGateway
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
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
                    ProviderError(ProviderErrorCode.UNAVAILABLE, retryable = false),
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
                                    files = listOf(TorrentCandidateFile(0, "Example Manga - Ch 1.cbz")),
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

        val prepare = PrepareCanonicalChapterForReader(
            resolveChapterContent = emptyAddonResolver(),
            canonicalChapterRepository = chapterRepository,
            canonicalReadingRepository = readingRepository(),
            canonicalDownloadRepository = emptyDownloadRepository(),
            chapterContentPreparer = object : ChapterContentPreparer {
                override suspend fun prepare(
                    option: ContentOption,
                    progress: CanonicalChapterProgress?,
                ): Result<PreparedChapterContent> = error("unused")
            },
            structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
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
}
