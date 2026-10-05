package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationPolicy
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderCapabilityExecutor
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderPackage
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderPackageSource
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderRuntimeInvoker
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import tachiyomi.domain.tsuzuki.provider.torrent.DebridResolveState
import tachiyomi.domain.tsuzuki.provider.torrent.P2pAcquireState
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionRequest
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentArchiveFormat
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentReadableResource
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

class ScriptProviderTorrentGatewayTest {

    private val providerId = ProviderId("org.example.transport")
    private val manifest = ProviderScriptManifest(
        manifestVersion = 1,
        id = providerId.value,
        name = "Example Transport",
        version = ProviderManifestVersion("1.0.0", 1),
        minHostApi = 1,
        entrypoint = "main.js",
        capabilities = listOf(
            ProviderManifestCapability("torrent.search", 1),
            ProviderManifestCapability("debrid.resolve", 1),
            ProviderManifestCapability("acquisition.p2p", 1),
        ),
        permissions = ProviderManifestPermissions(
            network = ProviderManifestNetworkPermission(
                origins = setOf("https://index.example", "https://debrid.example", "https://cdn.example"),
            ),
        ),
    )
    private val activePackage = ScriptProviderPackage(
        repositoryId = "repo.example",
        versionCode = 1,
        bytes = "package".encodeToByteArray(),
        parsed = ParsedProviderPackage(
            manifest = manifest,
            entries = mapOf("main.js" to "export default {}".encodeToByteArray()),
        ),
    )

    @Test
    fun `torrent search invokes capability and decodes bounded provider candidates`() = runBlocking {
        var captured: ProviderRuntimeInvocationRequest? = null
        var capturedInput: String? = null
        val gateway = gateway { request, _, input, _ ->
            captured = request
            capturedInput = input
            ProviderRuntimeInvocationResponse.success(
                """
                {
                  "items": [
                    {
                      "infoHash": "0123456789abcdef0123456789abcdef01234567",
                      "magnetUri": "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                      "torrentUrl": "https://index.example/files/example.torrent",
                      "displayName": "Example pack",
                      "sizeBytes": 4096,
                      "seeders": 7,
                      "peers": 2,
                      "languages": ["en"],
                      "files": [
                        {
                          "index": 1,
                          "path": "pack/chapter-012.cbz",
                          "sizeBytes": 2048,
                          "languages": ["en"]
                        }
                      ]
                    }
                  ],
                  "nextCursor": "next"
                }
                """.trimIndent(),
            )
        }

        val result = gateway.search(
            providerId,
            TorrentSearchRequest(
                titles = listOf("Example"),
                preferredLanguages = setOf("en"),
                chapterNumber = "12",
                volume = 2,
            ),
        ) as ProviderCallResult.Success

        result.value.items.single().files!!.single().path shouldBe "pack/chapter-012.cbz"
        result.value.nextCursor?.value shouldBe "next"
        captured?.capabilityId shouldBe "torrent.search"
        val input = Json.parseToJsonElement(requireNotNull(capturedInput)).jsonObject
        input["titles"].toString() shouldBe """["Example"]"""
        input["chapterNumber"].toString() shouldBe """"12""""
        input["volume"].toString() shouldBe "2"
    }

    @Test
    fun `debrid resolve converges provider output to validated HTTP resource`() = runBlocking {
        val gateway = gateway { request, _, _, _ ->
            request.capabilityId shouldBe "debrid.resolve"
            ProviderRuntimeInvocationResponse.success(
                """
                {
                  "status": "ready",
                  "url": "https://cdn.example/chapter-012.cbz",
                  "headers": {"Authorization": "Bearer opaque"}
                }
                """.trimIndent(),
            )
        }

        gateway.resolve(providerId, acquisitionRequest()) shouldBe ProviderCallResult.Success(
            DebridResolveState.Ready(
                TorrentReadableResource.HttpFile(
                    url = "https://cdn.example/chapter-012.cbz",
                    headers = mapOf("Authorization" to "Bearer opaque"),
                    allowedOrigins = setOf(
                        "https://index.example",
                        "https://debrid.example",
                        "https://cdn.example",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `debrid output outside provider network authority fails closed`() = runBlocking {
        val gateway = gateway { _, _, _, _ ->
            ProviderRuntimeInvocationResponse.success(
                """{"status":"ready","url":"https://evil.example/chapter.cbz","headers":{}}""",
            )
        }

        val result = gateway.resolve(providerId, acquisitionRequest())
        (result is ProviderCallResult.Failure) shouldBe true
    }

    @Test
    fun `p2p capability accepts only host owned managed archive resources`() = runBlocking {
        val resolver = ProviderManagedResourceResolver { requestedProviderId, resource, format ->
            if (
                requestedProviderId == providerId &&
                resource.value == "managed:p2p-ready" &&
                format == ProviderManagedFileFormat.CBZ
            ) {
                "content://app.tsuzuki.provider/provider-p2p/chapter-012.cbz"
            } else {
                null
            }
        }
        val gateway = gateway(
            managedResources = resolver,
        ) { request, _, _, _ ->
            request.capabilityId shouldBe "acquisition.p2p"
            ProviderRuntimeInvocationResponse.success(
                """{"status":"ready","resource":"managed:p2p-ready","format":"CBZ"}""",
            )
        }

        gateway.acquire(providerId, acquisitionRequest()) shouldBe ProviderCallResult.Success(
            P2pAcquireState.Ready(
                TorrentReadableResource.LocalArchive(
                    uri = "content://app.tsuzuki.provider/provider-p2p/chapter-012.cbz",
                    format = TorrentArchiveFormat.CBZ,
                ),
            ),
        )

        val unowned = gateway(
            managedResources = ProviderManagedResourceResolver.DenyAll,
        ) { _, _, _, _ ->
            ProviderRuntimeInvocationResponse.success(
                """{"status":"ready","resource":"managed:p2p-ready","format":"CBZ"}""",
            )
        }
        (unowned.acquire(providerId, acquisitionRequest()) is ProviderCallResult.Failure) shouldBe true
    }

    @Test
    fun `long running debrid and p2p operations preserve provider job identity`() = runBlocking {
        val gateway = gateway { request, _, _, _ ->
            when (request.capabilityId) {
                "debrid.resolve" -> ProviderRuntimeInvocationResponse.success(
                    """{"status":"pending","jobId":"debrid-job-12"}""",
                )
                "acquisition.p2p" -> ProviderRuntimeInvocationResponse.success(
                    """{"status":"pending","jobId":"p2p-job-12"}""",
                )
                else -> error("unexpected capability")
            }
        }

        gateway.resolve(providerId, acquisitionRequest()) shouldBe ProviderCallResult.Success(
            DebridResolveState.Pending("debrid-job-12"),
        )
        gateway.acquire(providerId, acquisitionRequest()) shouldBe ProviderCallResult.Success(
            P2pAcquireState.Pending("p2p-job-12"),
        )
    }

    private fun gateway(
        managedResources: ProviderManagedResourceResolver = ProviderManagedResourceResolver.DenyAll,
        invoke: suspend (
            ProviderRuntimeInvocationRequest,
            ByteArray,
            String,
            ProviderHostInvocationPolicy,
        ) -> ProviderRuntimeInvocationResponse,
    ): ScriptProviderTorrentGateway {
        val descriptor = ProviderDescriptor(
            id = providerId,
            name = "Example Transport",
            version = ProviderVersion("1.0.0", 1),
            origin = ProviderOrigin.Repository("repo.example"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(
                ProviderCapabilities.TorrentSearchV1,
                ProviderCapabilities.DebridResolveV1,
                ProviderCapabilities.AcquisitionP2pV1,
            ),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        )
        val executor = ScriptProviderCapabilityExecutor(
            registry = DefaultProviderRegistry(
                registrations = {
                    listOf(
                        ProviderRegistration(
                            descriptor = descriptor,
                            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                            configurationFingerprint = "config-v1",
                        ),
                    )
                },
            ),
            packageSource = ScriptProviderPackageSource { requested ->
                activePackage.takeIf { requested == providerId }
            },
            invokePackage = ScriptProviderRuntimeInvoker { request, bytes, input, policy ->
                invoke(request, bytes, input, policy)
            },
            invocationIdFactory = { capability -> "torrent-test:" + capability.id },
        )
        return ScriptProviderTorrentGateway(
            executor = executor,
            managedResources = managedResources,
        )
    }

    private fun acquisitionRequest(): TorrentAcquisitionRequest {
        val file = TorrentCandidateFile(
            index = 1,
            path = "pack/chapter-012.cbz",
            sizeBytes = 2048,
            languages = setOf("en"),
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
