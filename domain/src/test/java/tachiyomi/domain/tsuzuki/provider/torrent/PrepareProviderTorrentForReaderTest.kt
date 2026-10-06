package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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
import tachiyomi.domain.tsuzuki.reader.model.PreparedChapterContent

class PrepareProviderTorrentForReaderTest {

    @Test
    fun `local p2p resource converges directly to canonical Reader archive`() = runTest {
        var materializerCalls = 0
        val coordinator = coordinator(
            p2p = P2pAcquireGateway { _, _ ->
                ProviderCallResult.Success(
                    P2pAcquireState.Ready(
                        TorrentReadableResource.LocalArchive(
                            uri = "content://app.tsuzuki.provider/chapter.cbz",
                            format = TorrentArchiveFormat.CBZ,
                        ),
                    ),
                )
            },
        )
        val prepare = PrepareProviderTorrentForReader(
            coordinator = coordinator,
            httpMaterializer = TorrentHttpFileMaterializer { _, _, _ ->
                materializerCalls += 1
                error("HTTP materializer must not run for local P2P")
            },
        )

        prepare.prepare(
            request = request(),
            preference = TorrentAcquisitionPreference.P2P_ONLY,
            directP2pAllowed = true,
        ) shouldBe ProviderTorrentReaderState.Ready(
            route = TorrentAcquisitionRoute.DIRECT_P2P,
            providerId = ProviderId("org.example.p2p"),
            content = PreparedChapterContent.CanonicalDownload(
                uri = "content://app.tsuzuki.provider/chapter.cbz",
                format = "CBZ",
            ),
        )
        materializerCalls shouldBe 0
    }

    @Test
    fun `debrid HTTP resource materializes through host before Reader delivery`() = runTest {
        val http = TorrentReadableResource.HttpFile(
            url = "https://cdn.example/chapter.cbz",
            headers = mapOf("Authorization" to "Bearer opaque"),
            allowedOrigins = setOf("https://cdn.example"),
        )
        var capturedProvider: ProviderId? = null
        var capturedRequest: TorrentAcquisitionRequest? = null
        val coordinator = coordinator(
            debrid = DebridResolveGateway { _, _ ->
                ProviderCallResult.Success(DebridResolveState.Ready(http))
            },
        )
        val prepare = PrepareProviderTorrentForReader(
            coordinator = coordinator,
            httpMaterializer = TorrentHttpFileMaterializer { providerId, request, resource ->
                capturedProvider = providerId
                capturedRequest = request
                resource shouldBe http
                ProviderCallResult.Success(
                    PreparedChapterContent.CanonicalDownload(
                        uri = "content://app.tsuzuki.provider/materialized.cbz",
                        format = "CBZ",
                    ),
                )
            },
        )

        prepare.prepare(
            request = request(),
            preference = TorrentAcquisitionPreference.DEBRID_ONLY,
            directP2pAllowed = false,
        ) shouldBe ProviderTorrentReaderState.Ready(
            route = TorrentAcquisitionRoute.DEBRID,
            providerId = ProviderId("org.example.debrid"),
            content = PreparedChapterContent.CanonicalDownload(
                uri = "content://app.tsuzuki.provider/materialized.cbz",
                format = "CBZ",
            ),
        )
        capturedProvider shouldBe ProviderId("org.example.debrid")
        capturedRequest shouldBe request()
    }

    @Test
    fun `pending acquisition remains pending instead of pretending Reader content exists`() = runTest {
        val coordinator = coordinator(
            debrid = DebridResolveGateway { _, _ ->
                ProviderCallResult.Success(DebridResolveState.Pending("debrid-job-12"))
            },
        )
        val prepare = PrepareProviderTorrentForReader(
            coordinator = coordinator,
            httpMaterializer = TorrentHttpFileMaterializer { _, _, _ ->
                error("unused")
            },
        )

        prepare.prepare(
            request = request(),
            preference = TorrentAcquisitionPreference.DEBRID_ONLY,
            directP2pAllowed = false,
        ) shouldBe ProviderTorrentReaderState.Pending(
            route = TorrentAcquisitionRoute.DEBRID,
            providerId = ProviderId("org.example.debrid"),
            jobId = "debrid-job-12",
        )
    }

    private fun coordinator(
        debrid: DebridResolveGateway = DebridResolveGateway { _, _ ->
            error("unused")
        },
        p2p: P2pAcquireGateway = P2pAcquireGateway { _, _ ->
            error("unused")
        },
    ): ProviderTorrentAcquisitionCoordinator {
        val registrations = listOf(
            registration("org.example.debrid", ProviderCapabilities.DebridResolveV1),
            registration("org.example.p2p", ProviderCapabilities.AcquisitionP2pV1),
        )
        return ProviderTorrentAcquisitionCoordinator(
            registry = DefaultProviderRegistry(registrations = { registrations }),
            debrid = debrid,
            p2p = p2p,
        )
    }

    private fun registration(
        id: String,
        capability: tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef,
    ) = ProviderRegistration(
        descriptor = ProviderDescriptor(
            id = ProviderId(id),
            name = id,
            version = ProviderVersion("1.0.0", 1),
            origin = ProviderOrigin.Repository("repo.example"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(capability),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        ),
        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
    )

    private fun request(): TorrentAcquisitionRequest {
        val file = TorrentCandidateFile(
            index = 1,
            path = "pack/chapter-012.cbz",
            sizeBytes = 2048,
        )
        return TorrentAcquisitionRequest(
            operationId = "read:canonical-12",
            candidate = TorrentCandidate(
                infoHash = "0123456789abcdef0123456789abcdef01234567",
                magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                torrentUrl = null,
                displayName = "Example pack",
                files = listOf(file),
            ),
            selectedFile = file,
        )
    }
}
