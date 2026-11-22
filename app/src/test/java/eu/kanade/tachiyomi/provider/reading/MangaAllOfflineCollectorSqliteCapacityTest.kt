package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
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
import tachiyomi.domain.tsuzuki.provider.reading.CollectProviderReadingEvidence
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCallResult
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterEvidenceAdapter
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterObservation
import tachiyomi.domain.tsuzuki.provider.reading.ProviderCursor
import tachiyomi.domain.tsuzuki.provider.reading.ProviderPage
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingChaptersRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingDelivery
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingEvidenceCollection
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingGateway
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingLookupRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingPagesRequest
import tachiyomi.domain.tsuzuki.provider.reading.ProviderWorkCandidate

/**
 * Offline-only synthetic capacity test for the actual collector -> refresh ->
 * reconciler -> production SQLDelight repositories. No live manga, title/author
 * attestation, Provider package, Android IPC or Reader pixels are involved.
 */
class MangaAllOfflineCollectorSqliteCapacityTest {
    @Test
    fun mangaAllCollectorRefreshPersists4341SyntheticChaptersWithoutGrants() = runBlocking {
        val titleId = "mangaall-offline-capacity"
        val providerId = ProviderId("app.tsuzuki.mangaall")
        val bindingRef = ProviderBindingRef(providerId, "en", "mangadot:bleach-synthetic")
        val requests = mutableListOf<Int>()
        val collections = mutableListOf<ProviderReadingEvidenceCollection>()

        MangaAllDisposableSqlite.open().use { sqlite ->
            sqlite.titleRepository.insert(
                CanonicalTitle(
                    id = titleId,
                    displayTitle = "Bleach",
                    identityState = CanonicalIdentityState.SOURCE_ONLY,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
            sqlite.bindingRepository.insertIfAbsent(
                ProviderReadingBinding(
                    id = "offline-bleach-binding",
                    canonicalTitleId = titleId,
                    ref = bindingRef,
                    verification = ProviderBindingVerification.USER_CONFIRMED,
                    availability = ProviderBindingAvailability.AVAILABLE,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ) shouldBe true

            val providerRegistry = DefaultProviderRegistry(
                registrations = {
                    listOf(
                        ProviderRegistration(
                            descriptor = ProviderDescriptor(
                                id = providerId,
                                name = "MangaALL (offline test)",
                                version = ProviderVersion("0.5.5-test", 11),
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
            val gateway = object : ProviderReadingGateway {
                override suspend fun lookup(
                    providerId: ProviderId,
                    request: ProviderReadingLookupRequest,
                ): ProviderCallResult<ProviderPage<ProviderWorkCandidate>> =
                    error("Synthetic preferred binding must not trigger a title-only lookup")

                override suspend fun chapters(
                    providerId: ProviderId,
                    request: ProviderReadingChaptersRequest,
                ): ProviderCallResult<ProviderPage<ProviderChapterObservation>> {
                    providerId shouldBe bindingRef.providerId
                    request.binding shouldBe bindingRef
                    val pageIndex = request.cursor?.value?.removePrefix("page-")?.toInt() ?: 0
                    requests += pageIndex
                    val offset = pageIndex * 256
                    val count = (4_341 - offset).coerceAtMost(256)
                    require(count in 1..256) { "Unexpected synthetic cursor progression" }
                    val rows = (1..count).map { position ->
                        val number = offset + position
                        ProviderChapterObservation(
                            providerChapterId = "mangadot:bleach-chapter-" + number,
                            rawLabel = "Chapter " + number,
                            rawNumber = number.toDouble(),
                            language = "en",
                        )
                    }
                    return ProviderCallResult.Success(
                        ProviderPage(
                            items = rows,
                            nextCursor = if (offset + count < 4_341) {
                                ProviderCursor("page-" + (pageIndex + 1))
                            } else {
                                null
                            },
                        ),
                    )
                }

                override suspend fun pages(
                    providerId: ProviderId,
                    request: ProviderReadingPagesRequest,
                ): ProviderCallResult<ProviderReadingDelivery> =
                    error("Synthetic chapter capacity test never requests Reader pages")
            }
            val actualCollector = CollectProviderReadingEvidence(
                canonicalTitleRepository = sqlite.titleRepository,
                providerRegistry = providerRegistry,
                gateway = gateway,
                bindingRepository = sqlite.bindingRepository,
                evidenceAdapter = ProviderChapterEvidenceAdapter(),
                clock = { 42L },
                canonicalMetadataSnapshotRepository = sqlite.metadataRepository,
                workSourceAssociationRepository = sqlite.workSourceRepository,
                provisionalCursorRepository = sqlite.provisionalCursorRepository,
            )
            val trackedCollector = mockk<CollectProviderReadingEvidence>()
            coEvery { trackedCollector.execute(titleId) } coAnswers {
                actualCollector.execute(titleId).also { collections += it }
            }
            coEvery { trackedCollector.commitProvisionalCursors(any()) } coAnswers {
                actualCollector.commitProvisionalCursors(firstArg())
            }
            val integrations = mockk<IntegrationRegistry>(relaxed = true)
            coEvery { integrations.awaitReady() } returns Unit
            every { integrations.chapterEvidenceProviders() } returns emptyList()
            val refresher = RefreshChapterEvidence(
                registry = integrations,
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = sqlite.chapterRepository,
                    evidenceRepository = sqlite.evidenceRepository,
                ),
                providerReadingEvidence = trackedCollector,
            )

            val before = System.nanoTime()
            refresher.execute(titleId, forceRefresh = true).isSuccess shouldBe true
            val firstMs = (System.nanoTime() - before) / 1_000_000L
            requests shouldBe (0..16).toList()
            collections.single().complete shouldBe true
            collections.single().bindingCount shouldBe 1
            collections.single().snapshots.sumOf { it.evidence.size } shouldBe 4_341
            collections.single().partialEvidence shouldHaveSize 0

            val initialEvidence = sqlite.evidenceRepository.getByCanonicalTitleId(titleId)
            val initialChapters = sqlite.chapterRepository.getByCanonicalTitleId(titleId)
            initialEvidence shouldHaveSize 4_341
            initialChapters shouldHaveSize 4_341
            initialEvidence.mapNotNull { it.mappedCanonicalChapterId }.toSet() shouldBe
                initialChapters.map { it.id }.toSet()
            initialChapters.all { it.confirmation == CanonicalChapterConfirmation.PROVISIONAL } shouldBe true
            val evidenceIds = initialEvidence.associate { it.evidence.externalChapterKey to it.evidence.id }
            val chapterIds = initialEvidence.associate {
                it.evidence.externalChapterKey to it.mappedCanonicalChapterId
            }

            refresher.execute(titleId, forceRefresh = true).isSuccess shouldBe true
            requests shouldBe ((0..16).toList() + (0..16).toList())
            val repeated = sqlite.evidenceRepository.getByCanonicalTitleId(titleId)
            repeated shouldHaveSize 4_341
            repeated.associate { it.evidence.externalChapterKey to it.evidence.id } shouldBe evidenceIds
            repeated.associate { it.evidence.externalChapterKey to it.mappedCanonicalChapterId } shouldBe chapterIds
            sqlite.chapterRepository.getChaptersByCanonicalTitleId(titleId) shouldHaveSize 4_341
            println(
                "MANGAALL_CANONICAL_OFFLINE|collector=REAL|refresh=REAL|sqlite=REAL|" +
                    "synthetic=TRUE|rows=4341|pages=17|replay=IDEMPOTENT|" +
                    "editorial_grants=0|first_refresh_ms=" + firstMs,
            )
        }
    }
}
