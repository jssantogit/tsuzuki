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

// Chapter evidence is independent from whether a SCRIPT Provider can deliver Reader pages.
class MangaAllChapterEvidenceEligibilityTest {

    @Test
    fun mangaAllChaptersContributeWithoutPages() = runBlocking<Unit> {
        val providerId = ProviderId("app.tsuzuki.mangaall.mangadex")
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
                            name = "MangaAll MangaDex evidence Provider",
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
        var lookupCalls = 0
        var chapterCalls = 0
        var pageCalls = 0
        val gateway = object : ProviderReadingGateway {
            override suspend fun lookup(
                providerId: ProviderId,
                request: ProviderReadingLookupRequest,
            ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> {
                lookupCalls++
                return ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(
                            ProviderWorkCandidate(
                                externalWorkId = "mangadex:verified-work",
                                title = "Dandadan",
                            ),
                        ),
                        nextCursor = null,
                    ),
                )
            }

            override suspend fun chapters(
                providerId: ProviderId,
                request: ProviderReadingChaptersRequest,
            ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> {
                chapterCalls++
                return ProviderCallResult.Success(
                    ProviderPage(
                        items = listOf(
                            ProviderChapterObservation(
                                providerChapterId = "mangadex:verified-chapter-1",
                                rawLabel = "Ch. 1",
                                rawNumber = 1.0,
                                language = "en",
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
                pageCalls++
                error("ICE must never request Reader pages")
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
        result.snapshots.size shouldBe 1
        result.evidence.size shouldBe 1
        result.evidence.single().authority shouldBe
            tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority.PROVIDER_PROVISIONAL
        lookupCalls shouldBe 1
        chapterCalls shouldBe 1
        pageCalls shouldBe 0
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
