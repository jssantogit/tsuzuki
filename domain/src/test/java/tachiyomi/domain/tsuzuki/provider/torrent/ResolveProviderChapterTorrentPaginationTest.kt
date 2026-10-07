package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderCursor
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

class ResolveProviderChapterTorrentPaginationTest {

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
    private val providerId = ProviderId("org.example.torrent")

    @Test
    fun `stops paging once a second exact candidate proves provider ambiguity`() = runTest {
        val cursors = mutableListOf<String?>()
        val gateway = TorrentSearchGateway { _, request ->
            cursors += request.cursor?.value
            when (request.cursor?.value) {
                null -> ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(candidate("1", "pack/Vol. 2 Ch. 12.cbz")),
                        nextCursor = ProviderCursor("page-2"),
                    ),
                )
                "page-2" -> ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(candidate("2", "pack/Vol. 2 Chapter 12.zip")),
                        nextCursor = ProviderCursor("page-3"),
                    ),
                )
                else -> error("third page must not be requested after ambiguity is proven")
            }
        }

        val options = resolver(gateway = gateway).options(chapter.id)

        options.size shouldBe 2
        cursors shouldBe listOf(null, "page-2")
    }

    @Test
    fun `hydrates missing torrent files before exact mapping`() = runTest {
        var metadataCalls = 0
        val lightweight = candidate("3", path = null)
        val gateway = TorrentSearchGateway { _, _ ->
            ProviderCallResult.Success(
                ProviderPage(
                    items = listOf(lightweight),
                    nextCursor = null,
                ),
            )
        }
        val metadataGateway = TorrentCandidateMetadataGateway { id, candidate ->
            id shouldBe providerId
            candidate shouldBe lightweight
            metadataCalls += 1
            ProviderCallResult.Success(
                candidate.copy(
                    files = listOf(
                        TorrentCandidateFile(
                            index = 0,
                            path = "pack/Vol. 2 Ch. 12.cbz",
                        ),
                    ),
                ),
            )
        }

        val options = resolver(
            gateway = gateway,
            metadataGateway = metadataGateway,
        ).options(chapter.id)

        metadataCalls shouldBe 1
        options.single().selectedFile.path shouldBe "pack/Vol. 2 Ch. 12.cbz"
    }

    private fun resolver(
        gateway: TorrentSearchGateway,
        metadataGateway: TorrentCandidateMetadataGateway = TorrentCandidateMetadataGateway.Passthrough,
    ) = ResolveProviderChapterTorrent(
        canonicalChapterRepository = chapterRepository(chapter),
        canonicalTitleRepository = titleRepository(title),
        providerRegistry = DefaultProviderRegistry(
            registrations = { listOf(registration(providerId)) },
        ),
        gateway = gateway,
        metadataGateway = metadataGateway,
    )

    private fun candidate(
        suffix: String,
        path: String?,
    ) = TorrentCandidate(
        infoHash = suffix.padStart(40, '0'),
        magnetUri = "magnet:?xt=urn:btih:${suffix.padStart(40, '0')}",
        torrentUrl = "https://example.org/$suffix.torrent",
        displayName = "Example Manga",
        files = path?.let { listOf(TorrentCandidateFile(index = 0, path = it)) },
    )

    private fun registration(id: ProviderId) = ProviderRegistration(
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

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(
            canonicalChapterId: String,
        ) = emptyList<ChapterVariant>()

        override suspend fun getVariantsBySourceMappingId(
            sourceMappingId: String,
        ) = emptyList<ChapterVariant>()

        override suspend fun upsert(chapter: CanonicalChapter) = error("not used")

        override suspend fun upsertVariant(variant: ChapterVariant) = error("not used")

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = error("not used")
    }

    private fun titleRepository(value: CanonicalTitle) = object : CanonicalTitleRepository {
        override suspend fun getById(id: String) = value.takeIf { it.id == id }

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> =
            flowOf(value.takeIf { it.id == id })

        override suspend fun getByExternalIdentity(
            provider: String,
            externalId: String,
        ): CanonicalTitle? = null

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("not used")

        override suspend fun insert(title: CanonicalTitle) = error("not used")

        override suspend fun addExternalIdentity(identity: ExternalIdentity) = error("not used")
    }
}
