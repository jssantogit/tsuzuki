package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestMangaAllChapterSnapshotPermission
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.ProviderHostModule
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

class MangaAllFutureScriptExecutorGateTest {
    private val id = ProviderId("app.tsuzuki.mangaall")

    private fun executor(
        code: Long,
        granted: Boolean,
        captured: MutableList<String>,
    ): ScriptProviderCapabilityExecutor {
        val manifest = ProviderScriptManifest(
            manifestVersion = 1,
            id = id.value,
            name = "MangaALL future synthetic",
            version = ProviderManifestVersion("future-unsigned", code),
            minHostApi = 2,
            entrypoint = "main.js",
            capabilities = listOf(
                ProviderManifestCapability("reading.chapters", 1),
                ProviderManifestCapability("reading.pages", 1),
            ),
            permissions = ProviderManifestPermissions(
                network = ProviderManifestNetworkPermission(origins = setOf("https://mangadot.net")),
                mangaAllChapterSnapshot = ProviderManifestMangaAllChapterSnapshotPermission(granted),
            ),
        )
        val packageSource = ScriptProviderPackageSource { request ->
            if (request == id) {
                ScriptProviderPackage(
                    repositoryId = "future-synthetic",
                    versionCode = code,
                    bytes = "test-only".encodeToByteArray(),
                    parsed = ParsedProviderPackage(
                        manifest = manifest,
                        entries = mapOf("main.js" to "export default {}".encodeToByteArray()),
                    ),
                )
            } else {
                null
            }
        }
        val descriptor = ProviderDescriptor(
            id = id,
            name = manifest.name,
            version = ProviderVersion(manifest.version.name, code),
            origin = ProviderOrigin.Repository("future-synthetic"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(
                ProviderCapabilities.ReadingChaptersV1,
                ProviderCapabilities.ReadingPagesV1,
            ),
            permissions = ProviderPermissionSet(),
            settings = emptyList(),
            contentLanguages = setOf("en"),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = descriptor,
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        configurationFingerprint = "future-synthetic",
                        enabledCapabilities = descriptor.capabilities,
                    ),
                )
            },
        )
        return ScriptProviderCapabilityExecutor(
            registry = registry,
            packageSource = packageSource,
            invokePackage = ScriptProviderRuntimeInvoker { request, _, _, policy ->
                val allowed = policy.allowedHostModules()
                (ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in allowed) shouldBe
                    (request.capabilityId == "reading.chapters" && code >= 11 && granted)
                captured += request.capabilityId
                ProviderRuntimeInvocationResponse.success("{}")
            },
            invocationIdFactory = { "future-invocation" },
        )
    }

    @Test
    fun future_snapshot_is_granted_only_to_reading_chapters_not_pages(): Unit = runBlocking {
        val invoked = mutableListOf<String>()
        val executor = executor(11, true, invoked)
        executor.invoke(id, ProviderCapabilities.ReadingChaptersV1, "{}") { body, _ -> body } shouldBe
            ProviderCallResult.Success("{}")
        executor.invoke(id, ProviderCapabilities.ReadingPagesV1, "{}") { body, _ -> body } shouldBe
            ProviderCallResult.Success("{}")
        invoked shouldBe listOf("reading.chapters", "reading.pages")
    }

    @Test
    fun signed_code10_and_future_without_permission_never_receive_snapshot_module(): Unit = runBlocking {
        for ((code, granted) in listOf(10L to false, 11L to false)) {
            val invoked = mutableListOf<String>()
            executor(code, granted, invoked).invoke(
                id,
                ProviderCapabilities.ReadingChaptersV1,
                "{}",
            ) { body, _ -> body } shouldBe ProviderCallResult.Success("{}")
            invoked shouldBe listOf("reading.chapters")
        }
    }
}
