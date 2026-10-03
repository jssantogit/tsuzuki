package eu.kanade.tachiyomi.provider.reading

import eu.kanade.tachiyomi.provider.runtime.ProviderHostInvocationPolicy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.core.provider.packageformat.ParsedProviderPackage
import tachiyomi.core.provider.packageformat.ProviderManifestCapability
import tachiyomi.core.provider.packageformat.ProviderManifestNetworkPermission
import tachiyomi.core.provider.packageformat.ProviderManifestPermissions
import tachiyomi.core.provider.packageformat.ProviderManifestVersion
import tachiyomi.core.provider.packageformat.ProviderScriptManifest
import tachiyomi.core.provider.runtime.ProviderRuntimeFailureCode
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
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderErrorCode
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest

class ScriptProviderReadingGatewayTest {

    private val providerId = ProviderId("org.example.reader")
    private val manifest = ProviderScriptManifest(
        manifestVersion = 1,
        id = providerId.value,
        name = "Example Reader",
        version = ProviderManifestVersion("1.0.0", 1),
        minHostApi = 1,
        entrypoint = "main.js",
        capabilities = listOf(
            ProviderManifestCapability("reading.lookup", 1),
            ProviderManifestCapability("reading.chapters", 1),
            ProviderManifestCapability("reading.pages", 1),
        ),
        permissions = ProviderManifestPermissions(
            network = ProviderManifestNetworkPermission(
                origins = setOf("https://reader.example", "https://cdn.example"),
            ),
        ),
        contentLanguages = setOf("en"),
    )
    private val activePackage = ActiveProviderScriptPackage(
        repositoryId = "repo.example",
        versionCode = 1,
        bytes = "package".encodeToByteArray(),
        parsed = ParsedProviderPackage(
            manifest = manifest,
            entries = mapOf("main.js" to "export default {}".encodeToByteArray()),
        ),
    )

    @Test
    fun `lookup invokes exact capability with manifest-derived authority and decodes bounded result`() = runBlocking {
        var captured: ProviderRuntimeInvocationRequest? = null
        var capturedPolicy: ProviderHostInvocationPolicy? = null
        var capturedInput: String? = null
        val gateway = gateway { request, _, input, policy ->
            captured = request
            capturedPolicy = policy
            capturedInput = input
            ProviderRuntimeInvocationResponse.success(
                """
                {
                  "items": [
                    {
                      "externalWorkId": "work-42",
                      "title": "Tsuzuki",
                      "aliases": ["Tsuzuki Alt"],
                      "url": "https://reader.example/title/42",
                      "language": "en"
                    }
                  ],
                  "nextCursor": "next"
                }
                """.trimIndent(),
            )
        }

        val result = gateway.lookup(
            providerId,
            ProviderReadingLookupRequest(titles = listOf("Tsuzuki")),
        )

        val success = result as ProviderCallResult.Success
        success.value.items.single().externalWorkId shouldBe "work-42"
        success.value.items.single().title shouldBe "Tsuzuki"
        success.value.nextCursor?.value shouldBe "next"

        captured?.capabilityId shouldBe "reading.lookup"
        captured?.capabilityVersion shouldBe 1
        captured?.artifactVersionCode shouldBe 1L
        captured?.configurationFingerprint shouldBe "config-v1"
        capturedPolicy?.networkOrigins shouldBe setOf("https://reader.example", "https://cdn.example")
        captured?.hostModules shouldBe capturedPolicy?.allowedHostModules()

        val input = Json.parseToJsonElement(requireNotNull(capturedInput)).jsonObject
        input["titles"]!!.toString() shouldBe """["Tsuzuki"]"""
        input["cursor"] shouldBe null
    }

    @Test
    fun `chapters require matching provider binding and preserve observations as provider data`() = runBlocking {
        var invocations = 0
        val gateway = gateway { request, _, _, _ ->
            invocations += 1
            request.capabilityId shouldBe "reading.chapters"
            ProviderRuntimeInvocationResponse.success(
                """
                {
                  "items": [
                    {
                      "providerChapterId": "chapter-12",
                      "rawLabel": "Vol. 3 Ch. 12.5 — Bonus",
                      "rawNumber": 12.5,
                      "volume": 3,
                      "title": "Bonus",
                      "language": "en",
                      "scanlationGroup": "Group",
                      "releaseDateMillis": 1234
                    }
                  ],
                  "nextCursor": null
                }
                """.trimIndent(),
            )
        }
        val binding = ProviderBindingRef(providerId, "en", "work-42")

        val success = gateway.chapters(
            providerId,
            ProviderReadingChaptersRequest(binding),
        ) as ProviderCallResult.Success

        success.value.items.single().providerChapterId shouldBe "chapter-12"
        success.value.items.single().rawNumber shouldBe 12.5
        invocations shouldBe 1

        val mismatch = gateway.chapters(
            providerId,
            ProviderReadingChaptersRequest(
                binding.copy(providerId = ProviderId("org.example.other")),
            ),
        )
        mismatch shouldBe ProviderCallResult.Failure(
            error = tachiyomi.domain.tsuzuki.provider.reading.ProviderError(
                code = ProviderErrorCode.MALFORMED_RESULT,
                retryable = false,
            ),
        )
        invocations shouldBe 1
    }

    @Test
    fun `page list is constrained to manifest network origins before reaching Reader`() = runBlocking {
        val allowed = gateway { _, _, _, _ ->
            ProviderRuntimeInvocationResponse.success(
                """
                {
                  "type": "page_list",
                  "pages": [
                    {
                      "url": "https://cdn.example/001.jpg",
                      "headers": {"Referer": "https://reader.example/"}
                    }
                  ]
                }
                """.trimIndent(),
            )
        }
        val request = ProviderReadingPagesRequest(
            binding = ProviderBindingRef(providerId, "en", "work-42"),
            providerChapterId = "chapter-1",
        )

        val success = allowed.pages(providerId, request) as ProviderCallResult.Success
        val delivery = success.value as ProviderReadingDelivery.PageList
        delivery.pages.single().url shouldBe "https://cdn.example/001.jpg"
        delivery.pages.single().allowedOrigins shouldBe
            setOf("https://reader.example", "https://cdn.example")
        delivery.pages.single().allowLocalNetwork shouldBe false

        val escaped = gateway { _, _, _, _ ->
            ProviderRuntimeInvocationResponse.success(
                """
                {
                  "type": "page_list",
                  "pages": [{"url": "https://evil.example/001.jpg", "headers": {}}]
                }
                """.trimIndent(),
            )
        }
        escaped.pages(providerId, request) shouldBe ProviderCallResult.Failure(
            tachiyomi.domain.tsuzuki.provider.reading.ProviderError(
                ProviderErrorCode.MALFORMED_RESULT,
                retryable = false,
            ),
        )
    }

    @Test
    fun `managed file result fails closed until host-owned promotion exists`() = runBlocking {
        val gateway = gateway { _, _, _, _ ->
            ProviderRuntimeInvocationResponse.success(
                """{"type":"managed_file","resource":"managed:unowned","format":"CBZ"}""",
            )
        }

        gateway.pages(
            providerId,
            ProviderReadingPagesRequest(
                binding = ProviderBindingRef(providerId, null, "work-42"),
                providerChapterId = "chapter-1",
            ),
        ) shouldBe ProviderCallResult.Failure(
            tachiyomi.domain.tsuzuki.provider.reading.ProviderError(
                ProviderErrorCode.MALFORMED_RESULT,
                retryable = false,
            ),
        )
    }

    @Test
    fun `disabled stale or runtime-failed providers fail closed with typed domain errors`() = runBlocking {
        var invoked = false
        val disabled = gateway(
            lifecycle = ProviderLifecycleStatus.DISABLED,
        ) { _, _, _, _ ->
            invoked = true
            error("must not invoke")
        }

        disabled.lookup(providerId, ProviderReadingLookupRequest(listOf("Title"))) shouldBe
            ProviderCallResult.Failure(
                tachiyomi.domain.tsuzuki.provider.reading.ProviderError(
                    ProviderErrorCode.UNAVAILABLE,
                    retryable = false,
                ),
            )
        invoked shouldBe false

        val timeout = gateway { _, _, _, _ ->
            ProviderRuntimeInvocationResponse.failure(ProviderRuntimeFailureCode.TIMEOUT)
        }
        timeout.lookup(providerId, ProviderReadingLookupRequest(listOf("Title"))) shouldBe
            ProviderCallResult.Failure(
                tachiyomi.domain.tsuzuki.provider.reading.ProviderError(
                    ProviderErrorCode.TIMEOUT,
                    retryable = true,
                ),
            )

        val stale = gateway(
            packageOverride = activePackage.copy(versionCode = 2),
        ) { _, _, _, _ ->
            invoked = true
            error("must not invoke")
        }
        stale.lookup(providerId, ProviderReadingLookupRequest(listOf("Title"))) shouldBe
            ProviderCallResult.Failure(
                tachiyomi.domain.tsuzuki.provider.reading.ProviderError(
                    ProviderErrorCode.UNAVAILABLE,
                    retryable = false,
                ),
            )
    }

    private fun gateway(
        lifecycle: ProviderLifecycleStatus = ProviderLifecycleStatus.ENABLED,
        packageOverride: ActiveProviderScriptPackage = activePackage,
        invoke: suspend (
            ProviderRuntimeInvocationRequest,
            ByteArray,
            String,
            ProviderHostInvocationPolicy,
        ) -> ProviderRuntimeInvocationResponse,
    ): ScriptProviderReadingGateway {
        val descriptor = ProviderDescriptor(
            id = providerId,
            name = "Example Reader",
            version = ProviderVersion("1.0.0", 1),
            origin = ProviderOrigin.Repository("repo.example"),
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
                        lifecycleStatus = lifecycle,
                        configurationFingerprint = "config-v1",
                    ),
                )
            },
        )
        return ScriptProviderReadingGateway(
            registry = registry,
            packageSource = ActiveProviderScriptPackageSource { requested ->
                if (requested == providerId) packageOverride else null
            },
            invokePackage = ProviderPackageCapabilityInvoker { request, bytes, input, policy ->
                invoke(request, bytes, input, policy)
            },
            invocationIdFactory = { "reading-test" },
        )
    }
}
