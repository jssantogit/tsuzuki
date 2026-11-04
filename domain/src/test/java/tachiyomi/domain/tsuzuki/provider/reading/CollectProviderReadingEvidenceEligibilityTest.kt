package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.provider.DefaultProviderRegistry
import tachiyomi.domain.tsuzuki.provider.ProviderCapabilities
import tachiyomi.domain.tsuzuki.provider.ProviderDescriptor
import tachiyomi.domain.tsuzuki.provider.ProviderFacetRef
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderLifecycleStatus
import tachiyomi.domain.tsuzuki.provider.ProviderOrigin
import tachiyomi.domain.tsuzuki.provider.ProviderPermissionSet
import tachiyomi.domain.tsuzuki.provider.ProviderRegistration
import tachiyomi.domain.tsuzuki.provider.ProviderRuntimeKind
import tachiyomi.domain.tsuzuki.provider.ProviderVersion
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

// Chapter evidence may be discovered without pages, but only from enabled SCRIPT metadata capabilities.
class CollectProviderReadingEvidenceEligibilityTest {

    @Test
    fun `Provider with lookup and chapters but no pages contributes provisional evidence`() = runBlocking {
        val providerId = ProviderId("app.tsuzuki.incomplete")
        val title = CanonicalTitle(
            id = "title-1",
            displayTitle = "Dandadan",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val registry = DefaultProviderRegistry(
            registrations = {
                listOf(
                    ProviderRegistration(
                        descriptor = ProviderDescriptor(
                            id = providerId,
                            name = "Metadata-only chapter Provider",
                            version = ProviderVersion("0.1.0", 1),
                            origin = ProviderOrigin.Repository("app.tsuzuki.providers"),
                            runtime = ProviderRuntimeKind.SCRIPT,
                            capabilities = setOf(
                                ProviderCapabilities.ReadingLookupV1,
                                ProviderCapabilities.ReadingChaptersV1,
                            ),
                            permissions = ProviderPermissionSet(),
                            settings = emptyList(),
                            contentLanguages = setOf("en"),
                        ),
                        lifecycleStatus = ProviderLifecycleStatus.ENABLED,
                        facets = listOf(ProviderFacetRef(providerId, "en")),
                    ),
                )
            },
        )
        var gatewayCalls = 0
        val gateway = object : ProviderReadingGateway {
            override suspend fun lookup(
                providerId: ProviderId,
                request: ProviderReadingLookupRequest,
            ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> {
                gatewayCalls++
                request.titles shouldBe listOf("Dandadan")
                return ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(ProviderWorkCandidate("work-dandadan", "Dandadan")),
                        nextCursor = null,
                    ),
                )
            }

            override suspend fun chapters(
                providerId: ProviderId,
                request: ProviderReadingChaptersRequest,
            ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> {
                gatewayCalls++
                request.binding.externalWorkId shouldBe "work-dandadan"
                return ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(
                            ProviderChapterObservation(
                                providerChapterId = "ch-1",
                                rawLabel = "Chapter 1",
                                rawNumber = 1.0,
                                language = "en",
                                scanlationGroup = "Group",
                                releaseDateMillis = 100L,
                            ),
                        ),
                        nextCursor = null,
                    ),
                )
            }

            override suspend fun pages(
                providerId: ProviderId,
                request: ProviderReadingPagesRequest,
            ): ProviderCallResult<ProviderReadingDelivery> {
                error("reading pages must never be called during chapter evidence discovery")
            }
        }
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(title),
            providerRegistry = registry,
            gateway = gateway,
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )

        val result = bridge.execute(title.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence.size shouldBe 1
        result.evidence.single().producerKind shouldBe tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind.PROVIDER
        result.evidence.single().authority shouldBe
            tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority.PROVIDER_PROVISIONAL
        result.evidence.single().externalChapterKey shouldBe "ch-1"
        gatewayCalls shouldBe 2
    }

    private fun titleRepository(title: CanonicalTitle) = object : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = error("not used")
        override suspend fun insert(title: CanonicalTitle) = error("not used")
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = error("not used")
    }

    private class MemoryBindingRepository : ProviderReadingBindingRepository {
        override suspend fun get(
            canonicalTitleId: String,
            providerId: ProviderId,
            facetId: String?,
        ): ProviderReadingBinding? = null

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> = emptyList()

        override suspend fun upsert(binding: ProviderReadingBinding) = Unit

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) = Unit
    }
}
