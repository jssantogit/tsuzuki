package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
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
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class ResolveProviderChapterTorrentMatchDiagnosticsTest {

    private val chapter = CanonicalChapter(
        id = "chapter-12",
        canonicalTitleId = "title-1",
        displayNumber = "12",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 12,
        volume = 2,
    )
    private val title = CanonicalTitle(
        id = "title-1",
        displayTitle = "Secret Example Manga",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1,
        updatedAt = 1,
    )
    private val providerId = ProviderId("org.example.torrent")

    @Test
    fun `reports bounded aggregate reasons for exact torrent match rejection`() = runTest {
        val recorder = RecordingRecorder()
        val trace = DiagnosticTrace(
            recorder = recorder,
            workflow = DiagnosticWorkflow.READER_OPEN,
            workflowId = "11111111-1111-4111-8111-111111111111",
            operationId = "22222222-2222-4222-8222-222222222222",
        )
        val candidates = listOf(
            candidate(1, "pack/page-001.jpg"),
            candidate(2, "pack/Vol. 2 Ch. 11.cbz"),
            candidate(3, "pack/Vol. 1 Ch. 12.cbz"),
            candidate(4, "pack/Vol. 2 Ch. 12.cbz"),
            candidate(
                5,
                "pack-a/Vol. 2 Ch. 12.cbz",
                "pack-b/Vol. 2 Chapter 12.zip",
            ),
        )
        val resolver = ResolveProviderChapterTorrent(
            canonicalChapterRepository = chapterRepository(chapter),
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = DefaultProviderRegistry(
                registrations = { listOf(registration()) },
            ),
            gateway = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(candidates, nextCursor = null))
            },
        )

        val options = resolver.options(chapter.id, trace)

        options.map { it.candidate.infoHash }.shouldContainExactly(candidates[3].infoHash)
        val event = recorder.events.single { it.name == DiagnosticEventName.TORRENT_MATCH_CLASSIFIED }
        event.stage shouldBe DiagnosticStage.MATCH
        event.number(DiagnosticAttribute.CANDIDATE_COUNT) shouldBe 5L
        event.number(DiagnosticAttribute.READABLE_CANDIDATE_COUNT) shouldBe 4L
        event.number(DiagnosticAttribute.IDENTITY_MATCH_CANDIDATE_COUNT) shouldBe 3L
        event.number(DiagnosticAttribute.VOLUME_MATCH_CANDIDATE_COUNT) shouldBe 2L
        event.number(DiagnosticAttribute.EXACT_MATCH_CANDIDATE_COUNT) shouldBe 1L
        event.number(DiagnosticAttribute.AMBIGUOUS_MATCH_CANDIDATE_COUNT) shouldBe 1L

        val encoded = event.attributes.toString()
        encoded.contains("Secret Example Manga") shouldBe false
        encoded.contains("Vol. 2 Ch. 12.cbz") shouldBe false
        encoded.contains(candidates[3].infoHash.orEmpty()) shouldBe false
    }

    private fun StructuredDiagnosticEvent.number(attribute: DiagnosticAttribute): Long? =
        (attributes[attribute.name.lowercase()] as? DiagnosticAttributeValue.Number)?.value

    private fun candidate(id: Int, vararg paths: String): TorrentCandidate {
        val infoHash = id.toString(16).padStart(40, '0')
        return TorrentCandidate(
            infoHash = infoHash,
            magnetUri = "magnet:?xt=urn:btih:$infoHash",
            torrentUrl = "https://example.org/$id.torrent",
            displayName = "Release $id",
            files = paths.mapIndexed { index, path -> TorrentCandidateFile(index, path) },
        )
    }

    private fun registration() = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = providerId,
            name = "Example torrent Provider",
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

    private fun chapterRepository(value: CanonicalChapter) = object : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) = listOf(value)
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            flowOf(listOf(value))
        override suspend fun getById(id: String) = value.takeIf { it.id == id }
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String) = emptyList<ChapterVariant>()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String) = emptyList<ChapterVariant>()
        override suspend fun upsert(chapter: CanonicalChapter) = error("not used")
        override suspend fun upsertVariant(variant: ChapterVariant) = error("not used")
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) = error("not used")
    }

    private fun titleRepository(value: CanonicalTitle) = object : CanonicalTitleRepository {
        override suspend fun getById(id: String) = value.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(value.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("not used")
        override suspend fun insert(title: CanonicalTitle) = error("not used")
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = error("not used")
    }

    private class RecordingRecorder : StructuredDiagnosticRecorder {
        override val sessionId = "33333333-3333-4333-8333-333333333333"
        val events = mutableListOf<StructuredDiagnosticEvent>()

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null
        override fun mihonMangaReference(mihonMangaId: Long): String? = null
        override fun record(event: StructuredDiagnosticEvent) {
            events += event
        }
    }
}
