package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeLogSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderNetworkPermission
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPage
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchGateway
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

class ProviderTorrentMetadataSearchGatewayTest {

    private val providerId = ProviderId("app.tsuzuki.nyaa")

    @Test
    fun `hydrates missing files without losing sequential or parallel continuation evidence`() = runTest {
        val raw = candidate(files = null)
        val hydratedFiles = listOf(
            TorrentCandidateFile(index = 0, path = "pack/Vol. 2 Ch. 11.cbz", sizeBytes = 11L),
            TorrentCandidateFile(index = 1, path = "pack/Vol. 2 Ch. 12.cbz", sizeBytes = 12L),
            TorrentCandidateFile(index = 2, path = "pack/Vol. 2 Ch. 13.cbz", sizeBytes = 13L),
        )
        val legacyCursor = ProviderCursor("legacy-next")
        val parallelCursors = listOf(ProviderCursor("parallel-a"), ProviderCursor("parallel-b"))
        var inspected = 0
        val gateway = ProviderTorrentMetadataSearchGateway(
            delegate = TorrentSearchGateway { id, _ ->
                id shouldBe providerId
                ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(raw),
                        nextCursor = legacyCursor,
                        parallelCursors = parallelCursors,
                    ),
                )
            },
            registry = registry(),
            inspector = ProviderTorrentMetadataInspector { descriptor, candidate ->
                inspected += 1
                descriptor.id shouldBe providerId
                descriptor.permissions.network?.origins shouldBe setOf("https://nyaa.si")
                candidate shouldBe raw
                candidate.copy(files = hydratedFiles)
            },
        )

        val result = gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(
                titles = listOf("Example Manga"),
                chapterNumber = "12",
                volume = 2,
            ),
        )

        inspected shouldBe 1
        val page = (result as ProviderCallResult.Success).value
        page.items.single().files shouldBe hydratedFiles
        page.nextCursor shouldBe legacyCursor
        page.parallelCursors shouldBe parallelCursors
    }

    @Test
    fun `keeps Provider supplied exact files without redundant metadata fetch`() = runTest {
        val suppliedFiles = listOf(
            TorrentCandidateFile(index = 0, path = "Chapter 12.cbz", sizeBytes = 12L),
        )
        val raw = candidate(files = suppliedFiles)
        var inspected = 0
        val gateway = ProviderTorrentMetadataSearchGateway(
            delegate = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(listOf(raw), nextCursor = null))
            },
            registry = registry(),
            inspector = ProviderTorrentMetadataInspector { _, _ ->
                inspected += 1
                error("pre-hydrated candidates must not be inspected")
            },
        )

        val result = gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(titles = listOf("Example Manga")),
        )

        inspected shouldBe 0
        (result as ProviderCallResult.Success).value.items.single().files shouldBe suppliedFiles
    }

    @Test
    fun `metadata inspection failure keeps candidate fail closed`() = runTest {
        val raw = candidate(files = null)
        val gateway = ProviderTorrentMetadataSearchGateway(
            delegate = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(listOf(raw), nextCursor = null))
            },
            registry = registry(),
            inspector = ProviderTorrentMetadataInspector { _, _ -> null },
        )

        val result = gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(titles = listOf("Example Manga")),
        )

        (result as ProviderCallResult.Success).value.items.single().files shouldBe null
    }

    @Test
    fun `emits bounded Provider neutral hydration summary`() = runTest {
        val raw = candidate(files = null)
        val suppliedFiles = listOf(
            TorrentCandidateFile(index = 0, path = "Chapter 12.cbz", sizeBytes = 12L),
        )
        val supplied = candidate(files = suppliedFiles)
        val logs = mutableListOf<String>()
        val gateway = ProviderTorrentMetadataSearchGateway(
            delegate = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(listOf(raw, supplied), nextCursor = null))
            },
            registry = registry(),
            inspector = ProviderTorrentMetadataInspector { _, candidate ->
                candidate.copy(files = suppliedFiles)
            },
            logSink = ProviderRuntimeLogSink { id, message ->
                id shouldBe providerId.value
                logs += message
            },
        )

        gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(titles = listOf("Secret Example Manga")),
        )

        logs.size shouldBe 1
        val summary = logs.single()
        summary.substringBefore(" elapsedMs=") shouldBe
            "host_torrent_metadata total=2 attempted=1 hydrated=1 failed=0"
        val elapsed = summary.substringAfter(" elapsedMs=").toLongOrNull()
        (elapsed != null && elapsed >= 0L) shouldBe true
        summary.contains("nyaa.si") shouldBe false
        summary.contains("Secret Example Manga") shouldBe false
    }

    @Test
    fun `emits bounded Provider neutral exact match classification`() = runTest {
        val candidates = listOf(
            candidate(
                files = listOf(
                    TorrentCandidateFile(index = 0, path = "pack/page-001.jpg"),
                ),
            ),
            candidate(
                files = listOf(
                    TorrentCandidateFile(index = 0, path = "pack/Vol. 2 Ch. 11.cbz"),
                ),
            ),
            candidate(
                files = listOf(
                    TorrentCandidateFile(index = 0, path = "pack/Vol. 1 Ch. 12.cbz"),
                ),
            ),
            candidate(
                files = listOf(
                    TorrentCandidateFile(index = 0, path = "pack/Vol. 2 Ch. 12.cbz"),
                ),
            ),
            candidate(
                files = listOf(
                    TorrentCandidateFile(index = 0, path = "pack-a/Vol. 2 Ch. 12.cbz"),
                    TorrentCandidateFile(index = 1, path = "pack-b/Vol. 2 Chapter 12.zip"),
                ),
            ),
            candidate(
                files = listOf(
                    TorrentCandidateFile(
                        index = 0,
                        path = "pack/Secret Example Manga - Chapter 12.cbz",
                    ),
                ),
            ),
        )
        val logs = mutableListOf<String>()
        val gateway = ProviderTorrentMetadataSearchGateway(
            delegate = TorrentSearchGateway { _, _ ->
                ProviderCallResult.Success(ProviderPage(candidates, nextCursor = null))
            },
            registry = registry(),
            inspector = ProviderTorrentMetadataInspector { _, _ ->
                error("pre-hydrated candidates must not be inspected")
            },
            logSink = ProviderRuntimeLogSink { id, message ->
                id shouldBe providerId.value
                logs += message
            },
        )

        gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(
                titles = listOf("Secret Example Manga"),
                chapterNumber = "12",
                volume = 2,
            ),
        )

        logs.singleOrNull { it.startsWith("host_torrent_match ") } shouldBe
            "host_torrent_match total=6 readable=5 parsed=4 embedded=1 identity=3 volume=2 exact=1 ambiguous=1"
        val encoded = logs.joinToString("\n")
        encoded.contains("Secret Example Manga") shouldBe false
        encoded.contains("Secret Example Manga - Chapter 12.cbz") shouldBe false
        encoded.contains("Vol. 2 Ch. 12.cbz") shouldBe false
        encoded.contains("0123456789abcdef0123456789abcdef01234567") shouldBe false
    }

    private fun candidate(files: List<TorrentCandidateFile>?): TorrentCandidate = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        torrentUrl = "https://nyaa.si/download/1234567.torrent",
        displayName = "Example Manga Chapter 12",
        files = files,
    )

    private fun registry() = DefaultProviderRegistry(
        registrations = {
            listOf(
                ProviderRegistration(
                    descriptor = ProviderDescriptor(
                        id = providerId,
                        name = "Nyaa",
                        version = ProviderVersion("test", 1),
                        origin = ProviderOrigin.Repository("test"),
                        runtime = ProviderRuntimeKind.SCRIPT,
                        capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                        permissions = ProviderPermissionSet(
                            network = ProviderNetworkPermission(
                                origins = setOf("https://nyaa.si"),
                                localNetwork = false,
                            ),
                        ),
                        settings = emptyList(),
                        contentLanguages = emptySet(),
                    ),
                    lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                ),
            )
        },
    )
}
