package eu.kanade.tachiyomi.provider.torrent

import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationPolicy
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderCapabilityExecutor
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderPackage
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderPackageSource
import eu.kanade.tachiyomi.provider.runtime.ScriptProviderRuntimeInvoker
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
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
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentSearchRequest

class ScriptProviderTorrentParallelOptInTest {

    @Test
    fun `torrent search advertises parallel cursor support to script providers`() = runBlocking {
        val providerId = ProviderId("org.example.parallel")
        val manifest = ProviderScriptManifest(
            manifestVersion = 1,
            id = providerId.value,
            name = "Parallel Provider",
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
        val activePackage = ScriptProviderPackage(
            repositoryId = "repo.example",
            versionCode = 1,
            bytes = "package".encodeToByteArray(),
            parsed = ParsedProviderPackage(
                manifest = manifest,
                entries = mapOf("main.js" to "export default {}".encodeToByteArray()),
            ),
        )
        val descriptor = ProviderDescriptor(
            id = providerId,
            name = "Parallel Provider",
            version = ProviderVersion("1.0.0", 1),
            origin = ProviderOrigin.Repository("repo.example"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(ProviderCapabilities.TorrentSearchV1),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        )
        var capturedInput: String? = null
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
            invokePackage = ScriptProviderRuntimeInvoker { request: ProviderRuntimeInvocationRequest, _: ByteArray, input: String, _: ProviderHostInvocationPolicy ->
                request.capabilityId
                capturedInput = input
                ProviderRuntimeInvocationResponse.success("""{"items":[]}""")
            },
            invocationIdFactory = { capability -> "parallel-opt-in:" + capability.id },
        )
        val gateway = ScriptProviderTorrentGateway(executor)

        gateway.search(
            providerId = providerId,
            request = TorrentSearchRequest(titles = listOf("Example")),
        )

        assertTrue(
            requireNotNull(capturedInput).contains("\"supportsParallelCursors\":true"),
            "Host torrent.search request must explicitly opt in to parallel cursor responses",
        )
    }
}
