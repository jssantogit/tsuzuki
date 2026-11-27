package eu.kanade.tachiyomi.provider.reading

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
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
import tachiyomi.core.provider.runtime.ProviderRuntimeInvocationResponse
import tachiyomi.core.provider.runtime.ProviderRuntimeLimitsDto
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest

/**
 * The real reading gateway is independent of ScriptProviderCapabilityExecutor.
 * Its production Host module policy must therefore independently deny future
 * native large-chapter snapshots to lookup and pages, even for a code11 grant.
 */
class MangaAllFutureGatewaySnapshotGateTest {
    private val providerId = ProviderId("app.tsuzuki.mangaall")

    @Test
    fun real_reading_gateway_only_grants_future_snapshot_to_chapters(): Unit = runBlocking {
        val manifest = ProviderScriptManifest(
            manifestVersion = 1,
            id = providerId.value,
            name = "MangaALL future simulated",
            version = ProviderManifestVersion("future-unsigned", 11),
            minHostApi = 2,
            entrypoint = "main.js",
            capabilities = listOf(
                ProviderManifestCapability("reading.lookup", 1),
                ProviderManifestCapability("reading.chapters", 1),
                ProviderManifestCapability("reading.pages", 1),
            ),
            permissions = ProviderManifestPermissions(
                network = ProviderManifestNetworkPermission(origins = setOf("https://mangadot.net")),
                mangaAllChapterSnapshot = ProviderManifestMangaAllChapterSnapshotPermission(true),
            ),
            contentLanguages = setOf("en"),
        )
        val descriptor = ProviderDescriptor(
            id = providerId,
            name = manifest.name,
            version = ProviderVersion(manifest.version.name, manifest.version.code),
            origin = ProviderOrigin.Repository("future-test"),
            runtime = ProviderRuntimeKind.SCRIPT,
            capabilities = setOf(
                ProviderCapabilities.ReadingLookupV1,
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
                        configurationFingerprint = "future-test",
                        enabledCapabilities = descriptor.capabilities,
                    ),
                )
            },
        )
        val pkg = ActiveProviderScriptPackage(
            repositoryId = "future-test",
            versionCode = 11,
            bytes = "synthetic only".encodeToByteArray(),
            parsed = ParsedProviderPackage(
                manifest = manifest,
                entries = mapOf("main.js" to "export default {}".encodeToByteArray()),
            ),
        )
        val observed = mutableListOf<Triple<String, Boolean, ProviderRuntimeLimitsDto>>()
        val gateway = ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource { requested ->
                pkg.takeIf { requested == providerId }
            },
            invokePackage = ProviderPackageCapabilityInvoker { request, _, _, policy ->
                val allowed = ProviderHostModule.MANGAALL_CHAPTER_SNAPSHOT in policy.allowedHostModules()
                observed += Triple(request.capabilityId, allowed, request.limits)
                ProviderRuntimeInvocationResponse.failure(ProviderRuntimeFailureCode.HOST_ERROR)
            },
            invocationIdFactory = { "future-gateway-test" },
        )
        val binding = ProviderBindingRef(providerId, "en", "mangadot:131")
        gateway.lookup(providerId, ProviderReadingLookupRequest(listOf("Bleach")))
        gateway.chapters(providerId, ProviderReadingChaptersRequest(binding))
        gateway.chapters(
            providerId,
            ProviderReadingChaptersRequest(binding, cursor = ProviderCursor("m1:131:en:aaaaaaaaaaaaaaaa:200")),
        )
        gateway.chapters(
            providerId,
            ProviderReadingChaptersRequest(binding.copy(externalWorkId = "mangadex:fixture")),
        )
        gateway.pages(providerId, ProviderReadingPagesRequest(binding, "mangadot:uploads:171"))
        val normal = ProviderRuntimeLimitsDto()
        observed shouldBe listOf(
            Triple("reading.lookup", false, normal),
            Triple("reading.chapters", true, normal.copy(wallClockTimeoutMs = 6_500L, jsExecutionTimeoutMs = 6_000L)),
            Triple("reading.chapters", true, normal),
            Triple("reading.chapters", true, normal),
            Triple("reading.pages", false, normal),
        )
    }
}
