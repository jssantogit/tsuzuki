package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

class ResolveProviderChapterTorrentParallelPagesTest {

    private val providerId = ProviderId("org.example.parallel-torrent")
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

    @Test
    fun `independent continuation pages overlap at most two calls and bypass legacy cursor`() = runTest {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val calls = mutableListOf<String?>()
        val resolver = resolver(
            gateway = TorrentSearchGateway { _, request ->
                val cursor = request.cursor?.value
                synchronized(calls) { calls += cursor }
                when (cursor) {
                    null -> ProviderCallResult.Success(
                        ProviderPage(
                            items = emptyList(),
                            nextCursor = ProviderCursor("legacy-next"),
                            parallelCursors = listOf(
                                ProviderCursor("parallel-1"),
                                ProviderCursor("parallel-2"),
                                ProviderCursor("parallel-3"),
                            ),
                        ),
                    )
                    "legacy-next" -> error("parallel-aware Host must not traverse the legacy cursor")
                    else -> {
                        val now = active.incrementAndGet()
                        maxActive.getAndUpdate { previous -> maxOf(previous, now) }
                        try {
                            delay(100)
                            ProviderCallResult.Success(
                                ProviderPage(
                                    items = listOf(candidate(hashFor(cursor), "pack/Vol. 2 Ch. 11.cbz")),
                                    nextCursor = null,
                                ),
                            )
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            },
        )

        resolver.options(chapter.id) shouldBe emptyList()

        synchronized(calls) { calls.toList() }.sortedWith(compareBy(nullsFirst()) { it }) shouldBe
            listOf(null, "parallel-1", "parallel-2", "parallel-3").sortedWith(compareBy(nullsFirst()) { it })
        maxActive.get() shouldBe 2
    }

    @Test
    fun `exact mappings from separate parallel pages preserve global ambiguity evidence`() = runTest {
        val resolver = resolver(
            gateway = TorrentSearchGateway { _, request ->
                when (request.cursor?.value) {
                    null -> ProviderCallResult.Success(
                        ProviderPage(
                            items = emptyList(),
                            nextCursor = ProviderCursor("legacy-next"),
                            parallelCursors = listOf(
                                ProviderCursor("parallel-a"),
                                ProviderCursor("parallel-b"),
                            ),
                        ),
                    )
                    "parallel-a" -> ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                candidate("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "pack/A Vol. 2 Ch. 12.cbz"),
                            ),
                            nextCursor = null,
                        ),
                    )
                    "parallel-b" -> ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                candidate("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "pack/B Vol. 2 Ch. 12.zip"),
                            ),
                            nextCursor = null,
                        ),
                    )
                    else -> error("legacy cursor must not be used")
                }
            },
        )

        val options = resolver.options(chapter.id)

        options.map { it.candidate.infoHash } shouldBe listOf(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
    }

    private fun hashFor(cursor: String?): String = when (cursor) {
        "parallel-1" -> "1111111111111111111111111111111111111111"
        "parallel-2" -> "2222222222222222222222222222222222222222"
        else -> "3333333333333333333333333333333333333333"
    }

    private fun candidate(hash: String, path: String) = TorrentCandidate(
        infoHash = hash,
        magnetUri = "magnet:?xt=urn:btih:$hash",
        torrentUrl = "https://example.org/$hash.torrent",
        displayName = "Example Manga",
        files = listOf(TorrentCandidateFile(0, path)),
    )

    private fun resolver(gateway: TorrentSearchGateway) = ResolveProviderChapterTorrent(
        canonicalChapterRepository = chapterRepository(chapter),
        canonicalTitleRepository = titleRepository(title),
        providerRegistry = DefaultProviderRegistry(
            registrations = { listOf(registration()) },
        ),
        gateway = gateway,
    )

    private fun registration(): ProviderRegistration {
        val capabilities = setOf(ProviderCapabilities.TorrentSearchV1)
        return ProviderRegistration(
            descriptor = ProviderDescriptor(
                id = providerId,
                name = providerId.value,
                version = ProviderVersion("1.0.0", 1),
                origin = ProviderOrigin.Repository("repo"),
                runtime = ProviderRuntimeKind.SCRIPT,
                capabilities = capabilities,
                permissions = ProviderPermissionSet(),
                settings = emptyList(),
                contentLanguages = emptySet(),
            ),
            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
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
