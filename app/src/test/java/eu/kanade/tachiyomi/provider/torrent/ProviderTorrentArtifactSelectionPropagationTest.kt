package eu.kanade.tachiyomi.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import eu.kanade.tachiyomi.provider.runtime.ProviderP2pDiagnostics
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.tsuzuki.content.TorrentArtifactRequest
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.DebridResolveGateway
import tachiyomi.domain.tsuzuki.provider.torrent.P2pAcquireGateway
import tachiyomi.domain.tsuzuki.provider.torrent.P2pAcquireState
import tachiyomi.domain.tsuzuki.provider.torrent.PrepareProviderTorrentForReader
import tachiyomi.domain.tsuzuki.provider.torrent.ProviderTorrentAcquisitionCoordinator
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionPreference
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentArchiveFormat
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentHttpFileMaterializer
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource

class ProviderTorrentArtifactSelectionPropagationTest {

    @Test
    fun `Reader carries verified URL and file size into consented Host P2P request`() = runTest {
        val providerId = ProviderId("org.example.p2p")
        var calls = 0
        val coordinator = ProviderTorrentAcquisitionCoordinator(
            registry = DefaultProviderRegistry(
                registrations = { listOf(registration(providerId)) },
            ),
            debrid = DebridResolveGateway { _, _ -> error("Debrid must not be invoked") },
            p2p = P2pAcquireGateway { id, request ->
                id shouldBe providerId
                request.candidate.torrentUrl shouldBe "https://source.example/verified.torrent"
                request.candidate.infoHash shouldBe "0123456789abcdef0123456789abcdef01234567"
                request.selectedFile.index shouldBe 2
                request.selectedFile.path shouldBe "Volume 2/Chapter 12.cbz"
                request.selectedFile.sizeBytes shouldBe 48_000L
                calls++
                ProviderCallResult.Success(
                    P2pAcquireState.Ready(
                        TorrentReadableResource.LocalArchive(
                            uri = "content://host/selected.cbz",
                            format = TorrentArchiveFormat.CBZ,
                        ),
                    ),
                )
            },
        )
        val preferences = ProviderTorrentPreferences(InMemoryPreferenceStore()).apply {
            acquisitionPreference.set(TorrentAcquisitionPreference.P2P_ONLY)
            directP2pAllowed.set(true)
        }
        val engine = ProviderTorrentArtifactEngine(
            prepareForReader = PrepareProviderTorrentForReader(
                coordinator = coordinator,
                httpMaterializer = TorrentHttpFileMaterializer { _, _, _ ->
                    error("Direct P2P must never materialize an HTTP file")
                },
            ),
            preferences = preferences,
        )

        val events = mutableListOf<StructuredDiagnosticEvent>()
        ProviderP2pDiagnostics.installRecorder(object : StructuredDiagnosticRecorder {
            override val sessionId = "11111111-1111-1111-1111-111111111111"
            override fun canonicalTitleReference(canonicalTitleId: String): String? = null
            override fun mihonMangaReference(mihonMangaId: Long): String? = null
            override fun record(event: StructuredDiagnosticEvent) {
                events += event
            }
        })
        val result = try {
            engine.acquire(
                TorrentArtifactRequest(
                    infoHash = "0123456789abcdef0123456789abcdef01234567",
                    magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                    fileIndex = 2,
                    filePath = "Volume 2/Chapter 12.cbz",
                    torrentUrl = "https://source.example/verified.torrent",
                    fileSizeBytes = 48_000L,
                    discoveryOperationId = "123e4567-e89b-12d3-a456-426614174000",
                ),
            )
        } finally {
            ProviderP2pDiagnostics.installRecorder(null)
        }

        val bridge = events.single { it.operationId != null }
        bridge.operationId shouldBe "123e4567-e89b-12d3-a456-426614174000"
        bridge.name shouldBe DiagnosticEventName.PROVIDER_P2P_EVENT
        bridge.attributes.getValue("provider_p2p_line").toString().contains("opRef=") shouldBe true

        result.getOrThrow().localUri shouldBe "content://host/selected.cbz"
        result.getOrThrow().format shouldBe "CBZ"
        calls shouldBe 1
    }

    private fun registration(id: ProviderId) = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = id,
            name = "Direct P2P fixture",
            version = ProviderVersion("1.0", 1),
            origin = ProviderOrigin.Repository("fixture"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(ProviderCapabilities.AcquisitionP2pV1),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        ),
        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
    )
}
