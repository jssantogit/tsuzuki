package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationPolicy
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderCapabilityExecutor
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderPackage
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderPackageSource
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderRuntimeInvoker
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.ProviderNetworkPolicy
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderManagedResourceResolver
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidate
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentCandidateFile
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

class ScriptProviderTorrentGatewayHostInspectionTest {

    private val providerId = ProviderId("org.example.transport")
    private val manifest = ProviderScriptManifest(
        manifestVersion = 1,
        id = providerId.value,
        name = "Example Transport",
        version = ProviderManifestVersion("1.0.0", 1),
        minHostApi = 1,
        entrypoint = "main.js",
        capabilities = listOf(ProviderManifestCapability("torrent.search", 1)),
        permissions = ProviderManifestPermissions(
            network = ProviderManifestNetworkPermission(
                origins = setOf("https://index.example"),
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
    fun `host inspection hint enriches missing torrent files before search returns`() = runBlocking {
        var capturedInput: String? = null
        var inspections = 0
        val inspector = object : TorrentMetadataInspector {
            override val available: Boolean = true

            override suspend fun inspect(
                providerId: ProviderId,
                networkPolicy: ProviderNetworkPolicy,
                candidate: TorrentCandidate,
            ): TorrentCandidate {
                providerId shouldBe this@ScriptProviderTorrentGatewayHostInspectionTest.providerId
                networkPolicy.validate(requireNotNull(candidate.torrentUrl), resolveAddress = false)
                inspections += 1
                return candidate.copy(
                    files = listOf(
                        TorrentCandidateFile(
                            index = 0,
                            path = "Chapter 12.cbz",
                            sizeBytes = 2048,
                            languages = candidate.languages,
                        ),
                    ),
                )
            }
        }
        val gateway = gateway(inspector) { _, _, input, _ ->
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
                      "languages": ["en"]
                    }
                  ],
                  "nextCursor": null
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
            ),
        ) as ProviderCallResult.Success

        val input = Json.parseToJsonElement(requireNotNull(capturedInput)).jsonObject
        input.getValue("hostFileInspection").jsonPrimitive.boolean shouldBe true
        inspections shouldBe 1
        result.value.items.single().files!!.single().path shouldBe "Chapter 12.cbz"
    }

    @Test
    fun `unavailable Host inspection keeps legacy Provider enrichment contract`() = runBlocking {
        var capturedInput: String? = null
        val inspector = object : TorrentMetadataInspector {
            override val available: Boolean = false

            override suspend fun inspect(
                providerId: ProviderId,
                networkPolicy: ProviderNetworkPolicy,
                candidate: TorrentCandidate,
            ): TorrentCandidate = error("unavailable inspector must not run")
        }
        val gateway = gateway(inspector) { _, _, input, _ ->
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
                      "files": [{"index": 0, "path": "Chapter 12.cbz"}]
                    }
                  ],
                  "nextCursor": null
                }
                """.trimIndent(),
            )
        }

        val result = gateway.search(
            providerId,
            TorrentSearchRequest(titles = listOf("Example"), chapterNumber = "12"),
        ) as ProviderCallResult.Success

        val input = Json.parseToJsonElement(requireNotNull(capturedInput)).jsonObject
        input.getValue("hostFileInspection").jsonPrimitive.boolean shouldBe false
        result.value.items.single().files!!.single().path shouldBe "Chapter 12.cbz"
    }

    private fun gateway(
        inspector: TorrentMetadataInspector,
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
            capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
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
            invocationIdFactory = { capability -> "torrent-host-inspection-test:" + capability.id },
        )
        return ScriptProviderTorrentGateway(
            executor = executor,
            managedResources = ProviderManagedResourceResolver.DenyAll,
            metadataInspector = inspector,
        )
    }
}
