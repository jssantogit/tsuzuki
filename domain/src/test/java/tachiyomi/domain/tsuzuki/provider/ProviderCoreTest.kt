package tachiyomi.domain.tsuzuki.provider

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ProviderCoreTest {

    private val searchV1 = ProviderCapabilityRef("catalog.search", 1)
    private val searchV2 = ProviderCapabilityRef("catalog.search", 2)
    private val readingV1 = ProviderCapabilityRef("reading.chapters", 1)

    @Test
    fun `registry filters enabled providers by exact capability version`() {
        val search = descriptor(
            id = "org.example.search",
            capabilities = setOf(searchV1, readingV1),
        )
        val disabled = descriptor(
            id = "org.example.disabled",
            capabilities = setOf(searchV1),
        )
        val newerContract = descriptor(
            id = "org.example.v2",
            capabilities = setOf(searchV2),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(search, ProviderLifecycleStatus.ENABLED),
                    ProviderRegistration(disabled, ProviderLifecycleStatus.DISABLED),
                    ProviderRegistration(newerContract, ProviderLifecycleStatus.ENABLED),
                )
            },
        )

        registry.enabled(searchV1).map { it.id.value } shouldContainExactly listOf("org.example.search")
        registry.enabled(searchV2).map { it.id.value } shouldContainExactly listOf("org.example.v2")
        registry.enabled(readingV1).map { it.id.value } shouldContainExactly listOf("org.example.search")
    }

    @Test
    fun `registry keeps facets and configuration fingerprints scoped to provider`() {
        val provider = descriptor(
            id = "org.example.reader",
            capabilities = setOf(readingV1),
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = provider,
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        facets = listOf(
                            ProviderFacetRef(provider.id, "en"),
                            ProviderFacetRef(provider.id, "pt-br"),
                        ),
                        configurationFingerprint = "config-v3",
                    ),
                )
            },
        )

        registry.facets(provider.id).map { it.facetId } shouldContainExactly listOf("en", "pt-br")
        registry.configurationFingerprint(provider.id) shouldBe "config-v3"
    }

    @Test
    fun `provider identifiers capabilities and versions fail closed on malformed values`() {
        shouldThrow<IllegalArgumentException> { ProviderId("../escape") }
        shouldThrow<IllegalArgumentException> { ProviderId("") }
        shouldThrow<IllegalArgumentException> { ProviderCapabilityRef("", 1) }
        shouldThrow<IllegalArgumentException> { ProviderCapabilityRef("catalog.search", 0) }
        shouldThrow<IllegalArgumentException> { ProviderVersion("", 1) }
        shouldThrow<IllegalArgumentException> { ProviderVersion("1.0.0", 0) }
    }

    @Test
    fun `provider permissions preserve product capability separation from host authority`() {
        val descriptor = descriptor(
            id = "org.example.reader",
            capabilities = setOf(readingV1),
            permissions = ProviderPermissionSet(
                network = ProviderNetworkPermission(
                    origins = setOf("https://reader.example"),
                    localNetwork = false,
                ),
                browser = ProviderBrowserPermission(
                    origins = setOf("https://reader.example"),
                ),
                storage = ProviderStoragePermission(enabled = true),
                secrets = setOf("session"),
            ),
        )

        descriptor.capabilities shouldBe setOf(readingV1)
        descriptor.permissions.network?.origins shouldBe setOf("https://reader.example")
        descriptor.permissions.browser?.origins shouldBe setOf("https://reader.example")
        descriptor.permissions.storage.enabled shouldBe true
        descriptor.permissions.secrets shouldBe setOf("session")
    }

    private fun descriptor(
        id: String,
        capabilities: Set<ProviderCapabilityRef>,
        permissions: ProviderPermissionSet = ProviderPermissionSet(),
    ) = ProviderDescriptor(
        id = ProviderId(id),
        name = id,
        version = ProviderVersion(name = "1.0.0", code = 1),
        origin = ProviderOrigin.Repository("repo.example"),
        runtime = ProviderRuntimeKind.SCRIPT,
        capabilities = capabilities,
        permissions = permissions,
        settings = emptyList(),
        contentLanguages = setOf("en"),
    )
}
