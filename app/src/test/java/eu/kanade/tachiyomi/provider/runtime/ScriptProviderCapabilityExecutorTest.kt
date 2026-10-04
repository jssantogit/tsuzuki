package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.ProviderHostModule
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
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

class ScriptProviderCapabilityExecutorTest {

    private val providerId = ProviderId("org.example.torrent")
    private val manifest = ProviderScriptManifest(
        manifestVersion = 1,
        id = providerId.value,
        name = "Example Torrent",
        version = ProviderManifestVersion("1.0.0", 1),
        minHostApi = 1,
        entrypoint = "main.js",
        capabilities = listOf(
            ProviderManifestCapability("torrent.search", 1),
            ProviderManifestCapability("acquisition.p2p", 1),
        ),
        permissions = ProviderManifestPermissions(),
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
    fun `executor invokes enabled script capability through the common runtime boundary`() = runBlocking {
        var invoked = false
        val executor = executor(
            enabledCapabilities = setOf(ProviderCapabilities.TorrentSearchV1),
        ) { request, _, input, policy ->
            invoked = true
            request.capabilityId shouldBe "torrent.search"
            request.capabilityVersion shouldBe 1
            request.configurationFingerprint shouldBe "config-v1"
            input shouldBe """{"query":"title"}"""
            policy.providerId shouldBe providerId.value
            ProviderRuntimeInvocationResponse.success("""{"ok":true}""")
        }

        executor.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.TorrentSearchV1,
            inputJson = """{"query":"title"}""",
        ) { value, active ->
            active.versionCode shouldBe 1
            value
        } shouldBe ProviderCallResult.Success("""{"ok":true}""")
        invoked shouldBe true
    }

    @Test
    fun `privileged P2P grant is explicit and capability scoped`() = runBlocking {
        var invocations = 0
        val executor = executor(
            enabledCapabilities = setOf(
                ProviderCapabilities.TorrentSearchV1,
                ProviderCapabilities.AcquisitionP2pV1,
            ),
        ) { request, _, _, policy ->
            invocations += 1
            request.capabilityId shouldBe "acquisition.p2p"
            policy.directP2pEnabled shouldBe true
            (ProviderHostModule.P2P in policy.allowedHostModules()) shouldBe true
            ProviderRuntimeInvocationResponse.success("""{"status":"pending","jobId":"host-job"}""")
        }

        executor.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.AcquisitionP2pV1,
            inputJson = "{}",
            privilegedHostGrants = ScriptProviderPrivilegedHostGrants(
                directP2p = true,
            ),
        ) { value, _ -> value } shouldBe ProviderCallResult.Success(
            """{"status":"pending","jobId":"host-job"}""",
        )
        invocations shouldBe 1

        executor.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.TorrentSearchV1,
            inputJson = "{}",
            privilegedHostGrants = ScriptProviderPrivilegedHostGrants(
                directP2p = true,
            ),
        ) { value, _ -> value } shouldBe ProviderCallResult.Failure(
            ProviderError(
                code = ProviderErrorCode.PERMISSION_DENIED,
                retryable = false,
            ),
        )
        invocations shouldBe 1
    }

    @Test
    fun `executor rejects disabled capability and builtin collision before script execution`() = runBlocking {
        var invoked = false
        val disabled = executor(
            enabledCapabilities = emptySet(),
        ) { _, _, _, _ ->
            invoked = true
            error("must not execute")
        }

        disabled.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.TorrentSearchV1,
            inputJson = "{}",
        ) { value, _ -> value } shouldBe ProviderCallResult.Failure(
            ProviderError(
                code = ProviderErrorCode.UNAVAILABLE,
                retryable = false,
            ),
        )
        invoked shouldBe false

        val builtinDescriptor = descriptor(
            runtime = ProviderRuntimeKind.BUILTIN,
            origin = ProviderOrigin.Builtin,
            enabledCapabilities = setOf(ProviderCapabilities.TorrentSearchV1),
        )
        val builtin = ScriptProviderCapabilityExecutor(
            registry = DefaultProviderRegistry(
                registrations = {
                    listOf(
                        ProviderRegistration(
                            descriptor = builtinDescriptor.first,
                            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                            configurationFingerprint = "config-v1",
                            enabledCapabilities = builtinDescriptor.second,
                        ),
                    )
                },
            ),
            packageSource = ScriptProviderPackageSource { activePackage },
            invokePackage = ScriptProviderRuntimeInvoker { _, _, _, _ ->
                invoked = true
                error("must not execute")
            },
            invocationIdFactory = { _ -> "torrent-test" },
        )

        builtin.invoke(
            providerId = providerId,
            capability = ProviderCapabilities.TorrentSearchV1,
            inputJson = "{}",
        ) { value, _ -> value } shouldBe ProviderCallResult.Failure(
            ProviderError(
                code = ProviderErrorCode.UNAVAILABLE,
                retryable = false,
            ),
        )
        invoked shouldBe false
    }

    private fun executor(
        enabledCapabilities: Set<tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef>,
        invoke: suspend (
            tachiyomi.core.provider.runtime.ProviderRuntimeInvocationRequest,
            ByteArray,
            String,
            ProviderHostInvocationPolicy,
        ) -> ProviderRuntimeInvocationResponse,
    ): ScriptProviderCapabilityExecutor {
        val descriptor = descriptor(
            runtime = ProviderRuntimeKind.SCRIPT,
            origin = ProviderOrigin.Repository("repo.example"),
            enabledCapabilities = enabledCapabilities,
        )
        return ScriptProviderCapabilityExecutor(
            registry = DefaultProviderRegistry(
                registrations = {
                    listOf(
                        ProviderRegistration(
                            descriptor = descriptor.first,
                            lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                            configurationFingerprint = "config-v1",
                            enabledCapabilities = descriptor.second,
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
            invocationIdFactory = { _ -> "torrent-test" },
        )
    }

    private fun descriptor(
        runtime: ProviderRuntimeKind,
        origin: ProviderOrigin,
        enabledCapabilities: Set<tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef>,
    ): Pair<ProviderDescriptor, Set<tachiyomi.domain.tsuzuki.provider.ProviderCapabilityRef>> =
        ProviderDescriptor(
            id = providerId,
            name = "Example Torrent",
            version = ProviderVersion("1.0.0", 1),
            origin = origin,
            runtime = runtime,
            capabilities = setOf(
                ProviderCapabilities.TorrentSearchV1,
                ProviderCapabilities.AcquisitionP2pV1,
            ),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = emptySet(),
        ) to enabledCapabilities
}
