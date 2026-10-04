package tachiyomi.domain.tsuzuki.provider.torrent

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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

class ProviderTorrentAcquisitionCoordinatorTest {

    @Test
    fun `debrid pending blocks p2p fallback while preserving provider identity`() = runTest {
        var p2pCalls = 0
        val coordinator = coordinator(
            debrid = DebridResolveGateway { providerId, _ ->
                providerId shouldBe ProviderId("org.example.debrid")
                ProviderCallResult.Success(DebridResolveState.Pending("debrid-job-12"))
            },
            p2p = P2pAcquireGateway { _, _ ->
                p2pCalls += 1
                error("P2P must not run while Debrid is pending")
            },
        )

        coordinator.acquire(
            request = request(),
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            directP2pAllowed = true,
        ) shouldBe ProviderTorrentAcquisitionState.Pending(
            route = TorrentAcquisitionRoute.DEBRID,
            providerId = ProviderId("org.example.debrid"),
            jobId = "debrid-job-12",
        )
        p2pCalls shouldBe 0
    }

    @Test
    fun `definitive debrid failure falls through to allowed p2p provider`() = runTest {
        val local = TorrentReadableResource.LocalArchive(
            uri = "content://app.tsuzuki.provider/provider-p2p/chapter-012.cbz",
            format = TorrentArchiveFormat.CBZ,
        )
        val coordinator = coordinator(
            debrid = DebridResolveGateway { _, _ ->
                ProviderCallResult.Failure(
                    ProviderError(
                        code = ProviderErrorCode.UNAVAILABLE,
                        retryable = false,
                    ),
                )
            },
            p2p = P2pAcquireGateway { providerId, _ ->
                providerId shouldBe ProviderId("org.example.p2p")
                ProviderCallResult.Success(P2pAcquireState.Ready(local))
            },
        )

        coordinator.acquire(
            request = request(),
            preference = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
            directP2pAllowed = true,
        ) shouldBe ProviderTorrentAcquisitionState.Ready(
            route = TorrentAcquisitionRoute.DIRECT_P2P,
            providerId = ProviderId("org.example.p2p"),
            resource = local,
        )
    }

    @Test
    fun `coordinator never calls p2p without explicit consent`() = runTest {
        var p2pCalls = 0
        val coordinator = coordinator(
            debridProviders = emptyList(),
            p2p = P2pAcquireGateway { _, _ ->
                p2pCalls += 1
                error("P2P must remain blocked")
            },
        )

        coordinator.acquire(
            request = request(),
            preference = TorrentAcquisitionPreference.P2P_ONLY,
            directP2pAllowed = false,
        ) shouldBe ProviderTorrentAcquisitionState.Failure(
            TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED,
        )
        p2pCalls shouldBe 0
    }

    @Test
    fun `multiple providers are tried in stable provider id order after definitive failures`() = runTest {
        val calls = mutableListOf<String>()
        val http = TorrentReadableResource.HttpFile(
            url = "https://cdn.example/chapter-012.cbz",
            allowedOrigins = setOf("https://cdn.example"),
        )
        val coordinator = coordinator(
            debridProviders = listOf("org.example.zeta", "org.example.alpha"),
            debrid = DebridResolveGateway { providerId, _ ->
                calls += providerId.value
                if (providerId.value == "org.example.alpha") {
                    ProviderCallResult.Failure(
                        ProviderError(
                            code = ProviderErrorCode.AUTH_REQUIRED,
                            retryable = false,
                        ),
                    )
                } else {
                    ProviderCallResult.Success(DebridResolveState.Ready(http))
                }
            },
        )

        coordinator.acquire(
            request = request(),
            preference = TorrentAcquisitionPreference.DEBRID_ONLY,
            directP2pAllowed = false,
        ) shouldBe ProviderTorrentAcquisitionState.Ready(
            route = TorrentAcquisitionRoute.DEBRID,
            providerId = ProviderId("org.example.zeta"),
            resource = http,
        )
        calls shouldBe listOf("org.example.alpha", "org.example.zeta")
    }

    @Test
    fun `missing capability providers fails without invoking unrelated gateways`() = runTest {
        var calls = 0
        val coordinator = coordinator(
            debridProviders = emptyList(),
            p2pProviders = emptyList(),
            debrid = DebridResolveGateway { _, _ ->
                calls += 1
                error("unused")
            },
            p2p = P2pAcquireGateway { _, _ ->
                calls += 1
                error("unused")
            },
        )

        coordinator.acquire(
            request = request(),
            preference = TorrentAcquisitionPreference.DEBRID_ONLY,
            directP2pAllowed = true,
        ) shouldBe ProviderTorrentAcquisitionState.Failure(
            TorrentAcquisitionFailure.UNAVAILABLE,
        )
        calls shouldBe 0
    }

    private fun coordinator(
        debridProviders: List<String> = listOf("org.example.debrid"),
        p2pProviders: List<String> = listOf("org.example.p2p"),
        debrid: DebridResolveGateway = DebridResolveGateway { _, _ ->
            error("unused")
        },
        p2p: P2pAcquireGateway = P2pAcquireGateway { _, _ ->
            error("unused")
        },
    ): ProviderTorrentAcquisitionCoordinator {
        val registrations = buildList {
            debridProviders.forEach { id ->
                add(registration(id, ProviderCapabilities.DebridResolveV1))
            }
            p2pProviders.forEach { id ->
                add(registration(id, ProviderCapabilities.AcquisitionP2pV1))
            }
        }
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
