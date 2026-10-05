package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
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
import tachiyomi.domain.tsuzuki.provider.ProviderError
import tachiyomi.domain.tsuzuki.provider.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class ResolveProviderChapterTorrentDiagnosticsTest {

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
        displayTitle = "Example Manga",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1,
        updatedAt = 1,
    )
    private val providerId = ProviderId("app.tsuzuki.nyaa")

    @Test
    fun `typed discovery failure is not reported as an empty successful search`() = runBlocking {
        val recorder = RecordingDiagnostics()
        val resolver = ResolveProviderChapterTorrent(
            canonicalChapterRepository = chapterRepository(),
            canonicalTitleRepository = titleRepository(),
            providerRegistry = DefaultProviderRegistry(
                registrations = { listOf(registration()) },
            ),
            gateway = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Failure(
                    ProviderError(
                        code = ProviderErrorCode.SCRIPT_ERROR,
                        retryable = false,
                    ),
                )
            },
        )
        val trace = DiagnosticTrace.start(
            recorder = recorder,
            workflow = DiagnosticWorkflow.READER_OPEN,
            canonicalTitleId = title.id,
            subsystem = DiagnosticSubsystem.READER,
        )

        resolver.options(chapter.id, trace) shouldBe emptyList()

        val search = recorder.events.single {
            it.name == DiagnosticEventName.CONTENT_BINDING_LOOKUP &&
                it.stage == DiagnosticStage.SEARCH
        }
        search.outcome shouldBe DiagnosticOutcome.TYPED_FAILURE
        search.attributes["provider_id"] shouldBe DiagnosticAttributeValue.Text(providerId.value)
        search.attributes["provider_version_name"] shouldBe DiagnosticAttributeValue.Text("0.1.2")
        search.attributes["provider_version_code"] shouldBe DiagnosticAttributeValue.Number(3)
        search.attributes["provider_error_code"] shouldBe DiagnosticAttributeValue.Text("SCRIPT_ERROR")
        search.attributes["provider_retryable"] shouldBe DiagnosticAttributeValue.Flag(false)

        val match = recorder.events.single {
            it.name == DiagnosticEventName.CONTENT_BINDING_LOOKUP &&
                it.stage == DiagnosticStage.MATCH
        }
        match.outcome shouldBe DiagnosticOutcome.SKIPPED
    }

    private fun registration() = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = providerId,
            name = "Nyaa",
            version = ProviderVersion("0.1.2", 3),
            origin = ProviderOrigin.Repository("app.tsuzuki.providers"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        ),
        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
    )

    private fun chapterRepository() = object : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) = listOf(chapter)
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            flowOf(listOf(chapter))
        override suspend fun getById(id: String) = chapter.takeIf { it.id == id }
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String) = emptyList<ChapterVariant>()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String) = emptyList<ChapterVariant>()
        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) = Unit
    }

    private fun titleRepository() = object : CanonicalTitleRepository {
        override suspend fun getById(id: String) = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("unused")
        override suspend fun insert(title: CanonicalTitle) = Unit
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
    }

    private class RecordingDiagnostics : StructuredDiagnosticRecorder {
        override val sessionId: String = "00000000-0000-0000-0000-000000000001"
        val events = mutableListOf<StructuredDiagnosticEvent>()

        override fun canonicalTitleReference(canonicalTitleId: String): String = "title-ref"
        override fun mihonMangaReference(mihonMangaId: Long): String? = null
        override fun record(event: StructuredDiagnosticEvent) {
            events += event
        }
    }
}
