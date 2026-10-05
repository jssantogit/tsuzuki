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
import tachiyomi.domain.tsuzuki.content.PrepareTorrentArtifact
import tachiyomi.domain.tsuzuki.content.PreparedTorrentArtifact
import tachiyomi.domain.tsuzuki.content.TorrentArtifactEngine
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
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class PrepareProviderChapterTorrentTest {

    @Test
    fun `prepares one exact Provider torrent option for Reader`() = runBlocking {
        val chapter = CanonicalChapter(
            id = "chapter-12",
            canonicalTitleId = "title-1",
            displayNumber = "12",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            volume = 2,
        )
        val title = CanonicalTitle(
            id = "title-1",
            displayTitle = "Example Manga",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1,
            updatedAt = 1,
        )
        val providerId = ProviderId("org.example.torrent")
        val file = TorrentCandidateFile(0, "Example Manga - Vol 2 Ch 12.cbz")
        val candidate = TorrentCandidate(
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = null,
            torrentUrl = null,
            displayName = "Example Manga",
            files = listOf(file),
        )
        val resolver = ResolveProviderChapterTorrent(
            canonicalChapterRepository = chapterRepository(chapter),
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = DefaultProviderRegistry(
                registrations = { listOf(registration(providerId)) },
            ),
            gateway = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(listOf(candidate), null))
            },
        )
        var capturedIndex: Int? = null
        var capturedPath: String? = null
        val preparer = PrepareProviderChapterTorrent(
            resolver = resolver,
            prepareTorrentArtifact = PrepareTorrentArtifact(
                TorrentArtifactEngine { request ->
                    capturedIndex = request.fileIndex
                    capturedPath = request.filePath
                    Result.success(
                        PreparedTorrentArtifact(
                            localUri = "content://provider.test/chapter-12.cbz",
                            format = "CBZ",
                        ),
                    )
                },
            ),
        )

        val result = preparer.prepare(chapter.id)

        result shouldBe ProviderChapterTorrentPreparation.Ready(
            providerId = providerId,
            content = PreparedChapterContent.LocalArchive(
                "content://provider.test/chapter-12.cbz",
            ),
        )
        capturedIndex shouldBe 0
        capturedPath shouldBe file.path
    }

    private fun registration(id: ProviderId): ProviderRegistration = ProviderRegistration(
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

    private fun chapterRepository(value: CanonicalChapter) = object : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) = listOf(value)
        override fun observeByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapter>> = flowOf(listOf(value))
        override suspend fun getById(id: String) = value.takeIf { it.id == id }
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String) = emptyList<ChapterVariant>()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String) = emptyList<ChapterVariant>()
        override suspend fun upsert(chapter: CanonicalChapter) = error("not used")
        override suspend fun upsertVariant(variant: ChapterVariant) = error("not used")
        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = error("not used")
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
}
