package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.collections.shouldHaveSize
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

class CollectProviderReadingEvidenceTest {

    private val providerId = ProviderId("app.tsuzuki.mangafire")
    private val canonicalTitle = CanonicalTitle(
        id = "title-1",
        displayTitle = "Dandadan",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `unique exact lookup creates binding and returns provider chapter evidence`() = runBlocking {
        val bindings = MemoryBindingRepository()
        var lookupCalls = 0
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    lookupCalls++
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate(
                                    externalWorkId = "dandadan-1",
                                    title = "Dandadan",
                                    aliases = listOf("Dan Da Dan"),
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderChapterObservation(
                                    providerChapterId = "chapter-1",
                                    rawLabel = "Chapter 1",
                                    rawNumber = 1.0,
                                    language = "en",
                                ),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 42L },
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence.shouldHaveSize(1)
        result.evidence.single().canonicalTitleId shouldBe canonicalTitle.id
        result.evidence.single().externalChapterKey shouldBe "chapter-1"
        result.evidence.single().observedAt shouldBe 42L
        bindings.get(canonicalTitle.id, providerId, "en")?.ref?.externalWorkId shouldBe "dandadan-1"
        lookupCalls shouldBe 1
    }

    @Test
    fun `existing available binding is reused without lookup`() = runBlocking {
        val bindings = MemoryBindingRepository()
        bindings.upsert(
            ProviderReadingBinding(
                id = "binding-existing",
                canonicalTitleId = canonicalTitle.id,
                ref = ProviderBindingRef(providerId, "en", "dandadan-1"),
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        var lookupCalls = 0
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    lookupCalls++
                    error("existing binding must be reused")
                },
                chaptersHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(ProviderChapterObservation("chapter-2", "Chapter 2")),
                            nextCursor = null,
                        ),
                    )
                },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 2L },
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.evidence.single().externalChapterKey shouldBe "chapter-2"
        lookupCalls shouldBe 0
    }

    @Test
    fun `ambiguous exact lookup does not auto bind`() = runBlocking {
        val bindings = MemoryBindingRepository()
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Success(
                        ProviderPage(
                            items = listOf(
                                ProviderWorkCandidate("work-1", "Dandadan", language = "en"),
                                ProviderWorkCandidate("work-2", "DANDADAN", language = "en"),
                            ),
                            nextCursor = null,
                        ),
                    )
                },
                chaptersHandler = { error("ambiguous lookup must not request chapters") },
            ),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 3L },
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe true
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
        bindings.getByTitle(canonicalTitle.id) shouldBe emptyList()
    }

    @Test
    fun `lookup failure marks collection incomplete`() = runBlocking {
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository(canonicalTitle),
            providerRegistry = registry(),
            gateway = gateway(
                lookupHandler = {
                    ProviderCallResult.Failure(
                        ProviderError(
                            code = ProviderErrorCode.NETWORK_ERROR,
                            retryable = true,
                        ),
                    )
                },
                chaptersHandler = { error("failed lookup must not request chapters") },
            ),
            bindingRepository = MemoryBindingRepository(),
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 4L },
        )

        val result = bridge.execute(canonicalTitle.id)

        result.complete shouldBe false
        result.bindingCount shouldBe 0
        result.evidence shouldBe emptyList()
    }

    private fun registry() = DefaultProviderRegistry(
        registrations = {
            listOf(
                ProviderRegistration(
                    descriptor = ProviderDescriptor(
                        id = providerId,
                        name = "MangaFire",
                        version = ProviderVersion("0.1.1", 2),
                        origin = ProviderOrigin.Repository("app.tsuzuki.providers"),
                        runtime = ProviderRuntimeKind.SCRIPT,
                        capabilities = setOf(
                            ProviderCapabilities.ReadingLookupV1,
                            ProviderCapabilities.ReadingChaptersV1,
                            ProviderCapabilities.ReadingPagesV1,
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

    private fun gateway(
        lookupHandler: suspend (ProviderReadingLookupRequest) -> ProviderCallResult<ProviderPage<ProviderWorkCandidate>>,
        chaptersHandler: suspend (ProviderReadingChaptersRequest) -> ProviderCallResult<ProviderPage<ProviderChapterObservation>>,
    ) = object : ProviderReadingGateway {
        override suspend fun lookup(
            providerId: ProviderId,
            request: ProviderReadingLookupRequest,
        ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> {
            providerId shouldBe this@CollectProviderReadingEvidenceTest.providerId
            return lookupHandler(request)
        }

        override suspend fun chapters(
            providerId: ProviderId,
            request: ProviderReadingChaptersRequest,
        ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> {
            providerId shouldBe this@CollectProviderReadingEvidenceTest.providerId
            return chaptersHandler(request)
        }

        override suspend fun pages(
            providerId: ProviderId,
            request: ProviderReadingPagesRequest,
        ): ProviderCallResult<ProviderReadingDelivery> = error("not used")
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
        private val values = linkedMapOf<String, ProviderReadingBinding>()

        override suspend fun get(
            canonicalTitleId: String,
            providerId: ProviderId,
            facetId: String?,
        ): ProviderReadingBinding? = values.values.firstOrNull {
            it.canonicalTitleId == canonicalTitleId &&
                it.ref.providerId == providerId &&
                it.ref.facetId == facetId
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ProviderReadingBinding> =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ProviderReadingBinding) {
            values[binding.id] = binding
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            values[bindingId]?.let { existing ->
                values[bindingId] = existing.copy(
                    availability = ProviderBindingAvailability.UNAVAILABLE,
                    updatedAt = updatedAt,
                )
            }
        }
    }
}
