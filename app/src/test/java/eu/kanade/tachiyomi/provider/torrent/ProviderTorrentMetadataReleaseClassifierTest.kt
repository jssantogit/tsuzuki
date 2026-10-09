package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderRuntimeLogSink
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
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

class ProviderTorrentMetadataReleaseClassifierTest {

    private val providerId = ProviderId("app.tsuzuki.test")

    @Test
    fun `classifies release identity and single readable evidence without exposing names`() = runTest {
        val candidates = listOf(
            candidate(
                displayName = "Secret Example Manga - Chapter 12 [Digital]",
                files = listOf(file(0, "archive.cbz")),
            ),
            candidate(
                displayName = "Secret Example Manga - Chapter 13 [Digital]",
                files = listOf(file(0, "other.cbz")),
            ),
            candidate(
                displayName = "Secret Example Manga - Ch. 12 pack",
                files = listOf(file(0, "part-a.cbz"), file(1, "part-b.zip")),
            ),
            candidate(
                displayName = "Secret Example Manga 12",
                files = listOf(file(0, "Secret Example Manga 12.cbz")),
            ),
        )
        val logs = mutableListOf<String>()
        val gateway = gateway(candidates, logs)

        gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(
                titles = listOf("Secret Example Manga"),
                chapterNumber = "12",
            ),
        )

        logs.single { it.startsWith("host_torrent_release ") } shouldBe
            "host_torrent_release total=4 releaseExplicit=2 releaseExact=2 singleReadable=3 " +
            "releaseExactSingle=1 fileToken=1 volumeRequested=0"
        val encoded = logs.joinToString("\n")
        encoded.contains("Secret Example Manga") shouldBe false
        encoded.contains("archive.cbz") shouldBe false
        encoded.contains("0123456789abcdef0123456789abcdef01234567") shouldBe false
    }

    @Test
    fun `release exact evidence respects requested volume`() = runTest {
        val candidates = listOf(
            candidate(
                displayName = "Secret Example Manga Vol. 1 Chapter 12",
                files = listOf(file(0, "archive-a.cbz")),
            ),
            candidate(
                displayName = "Secret Example Manga Vol. 2 Chapter 12",
                files = listOf(file(0, "archive-b.cbz")),
            ),
        )
        val logs = mutableListOf<String>()
        val gateway = gateway(candidates, logs)

        gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(
                titles = listOf("Secret Example Manga"),
                chapterNumber = "12",
                volume = 2,
            ),
        )

        logs.single { it.startsWith("host_torrent_release ") } shouldBe
            "host_torrent_release total=2 releaseExplicit=2 releaseExact=1 singleReadable=2 " +
            "releaseExactSingle=1 fileToken=0 volumeRequested=1"
    }

    private fun gateway(
        candidates: List<TorrentCandidate>,
        logs: MutableList<String>,
    ) = ProviderTorrentMetadataSearchGateway(
        delegate = TorrentSearchGateway { _, _ ->
            ProviderCallResult.Success(ProviderPage(candidates, nextCursor = null))
        },
        registry = registry(),
        inspector = ProviderTorrentMetadataInspector { _, _ ->
            error("pre-hydrated candidates must not be inspected")
        },
        diagnosticRecorder = NoOpStructuredDiagnosticRecorder,
        logSink = ProviderRuntimeLogSink { _, message -> logs += message },
    )

    private fun candidate(
        displayName: String,
        files: List<TorrentCandidateFile>,
    ) = TorrentCandidate(
        infoHash = "0123456789abcdef0123456789abcdef01234567",
        magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        torrentUrl = "https://example.invalid/download/123.torrent",
        displayName = displayName,
        files = files,
    )

    private fun file(index: Int, path: String) = TorrentCandidateFile(
        index = index,
        path = path,
        sizeBytes = 1024,
    )

    private fun registry() = DefaultProviderRegistry(
        registrations = {
            listOf(
                ProviderRegistration(
                    descriptor = ProviderDescriptor(
                        id = providerId,
                        name = "Test",
                        version = ProviderVersion("test", 1),
                        origin = ProviderOrigin.Repository("test"),
                        runtime = ProviderRuntimeKind.SCRIPT,
                        capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
                        permissions = ProviderPermissionSet(
                            network = ProviderNetworkPermission(
                                origins = setOf("https://example.invalid"),
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
