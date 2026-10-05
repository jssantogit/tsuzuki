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

class ResolveProviderChapterTorrentTest {

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
    fun `discovers exact archive mapping from enabled SCRIPT torrent Provider`() = runBlocking {
        val calls = mutableListOf<TorrentSearchRequest>()
        val resolver = resolver(
            gateway = TorrentSearchGateway { id, request ->
                id shouldBe providerId
                calls += request
                ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(candidate("Example Manga - Vol 2 Ch 12.cbz")),
                        nextCursor = null,
                    ),
                )
            },
        )

        val options = resolver.options(chapter.id)

        calls.single().titles shouldBe listOf("Example Manga")
        calls.single().chapterNumber shouldBe "12"
        calls.single().volume shouldBe 2
        options.map { it.providerId } shouldBe listOf(providerId)
        options.single().selectedFile.path shouldBe "Example Manga - Vol 2 Ch 12.cbz"
    }

    @Test
    fun `ambiguous chapter mapping stays fail closed`() = runBlocking {
        val resolver = resolver(
            gateway = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(
                            candidate(
                                "Example Manga - Vol 2 Ch 12.cbz",
                                "Example Manga - Vol 2 Chapter 12.zip",
                            ),
                        ),
                        nextCursor = null,
                    ),
                )
            },
        )

        resolver.options(chapter.id) shouldBe emptyList()
    }

    @Test
    fun `disabled and non torrent Providers are never queried`() = runBlocking {
        var calls = 0
        val registrations = listOf(
            registration(providerId, enabled = false, torrent = true),
            registration(ProviderId("org.example.reader"), enabled = true, torrent = false),
        )
        val resolver = resolver(
            registrations = registrations,
            gateway = TorrentSearchGateway { _, _ ->
                calls += 1
                error("must not be called")
            },
        )

        resolver.options(chapter.id) shouldBe emptyList()
        calls shouldBe 0
    }

    private fun resolver(
        registrations: List<ProviderRegistration> = listOf(registration(providerId)),
        gateway: TorrentSearchGateway,
    ) = ResolveProviderChapterTorrent(
        canonicalChapterRepository = chapterRepository(chapter),
        canonicalTitleRepository = titleRepository(title),
        providerRegistry = DefaultProviderRegistry(
            registrations = { registrations },
        ),
        gateway = gateway,
    )

    private fun candidate(vararg paths: String): TorrentCandidate = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        torrentUrl = "https://example.org/file.torrent",
        displayName = "Example Manga",
        files = paths.mapIndexed { index, path -> TorrentCandidateFile(index, path) },
    )

    private fun registration(
        id: ProviderId,
        enabled: Boolean = true,
        torrent: Boolean = true,
    ): ProviderRegistration {
        val capabilities = if (torrent) setOf(ProviderCapabilities.TorrentSearchV1) else emptySet()
        return ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = id,
                name = id.value,
                version = ProviderVersion("1.0.0", 1),
                origin = ProviderOrigin.Repository("repo"),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = capabilities,
                permissions = ProviderPermissionSet(),
                settings = emptyList(),
                contentLanguages = emptySet(),
            ),
            lifecycleStatus = if (enabled) ProviderLifecycleStatus.ENABLED else ProviderLifecycleStatus.DISABLED,
        )
    }

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
