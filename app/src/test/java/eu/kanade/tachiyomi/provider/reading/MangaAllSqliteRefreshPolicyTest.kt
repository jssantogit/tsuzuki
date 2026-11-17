package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.provider.reading.CollectProviderReadingEvidence
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingEvidenceCollection

/** CONTRACT fixtures only. Live matrices NEVER use these labels or chapter keys. */
class MangaAllSqliteRefreshPolicyTest {

    @Test
    fun `real refresh and real SQLDelight preserve support after incomplete Provider evidence`() = runBlocking {
        MangaAllDisposableSqlite.open().use { sqlite ->
            val title = canonicalTitle()
            sqlite.titleRepository.insert(title)
            val collector = mockk<CollectProviderReadingEvidence>()
            val first = evidence("first", "Chapter 1", 100L)
            val second = evidence("second", "Chapter 2", 200L)
            coEvery { collector.execute(title.id) } returnsMany listOf(
                ProviderReadingEvidenceCollection(
                    snapshots = listOf(snapshot(100L, listOf(first))),
                    bindingCount = 1,
                    complete = true,
                ),
                ProviderReadingEvidenceCollection(
                    partialEvidence = listOf(second),
                    bindingCount = 1,
                    complete = false,
                ),
            )
            val refresh = refresh(sqlite, collector)
            refresh.execute(title.id, forceRefresh = true).isSuccess shouldBe true
            sqlite.chapterRepository.getChaptersByCanonicalTitleId(title.id).size shouldBe 1

            refresh.execute(title.id, forceRefresh = true).isSuccess shouldBe true
            val persistent = sqlite.evidenceRepository.getByCanonicalTitleId(title.id)
            persistent.map { it.evidence.externalChapterKey }.toSet() shouldBe setOf("first", "second")
            persistent.all { it.mappedCanonicalChapterId != null } shouldBe true
            sqlite.chapterRepository.getChaptersByCanonicalTitleId(title.id).size shouldBe 2
        }
    }

    @Test
    fun `only complete empty Provider inventory detaches stale source mappings in SQLDelight`() = runBlocking {
        MangaAllDisposableSqlite.open().use { sqlite ->
            val title = canonicalTitle()
            sqlite.titleRepository.insert(title)
            val collector = mockk<CollectProviderReadingEvidence>()
            val first = evidence("first", "Chapter 1", 100L)
            coEvery { collector.execute(title.id) } returnsMany listOf(
                ProviderReadingEvidenceCollection(
                    snapshots = listOf(snapshot(100L, listOf(first))),
                    bindingCount = 1,
                    complete = true,
                ),
                ProviderReadingEvidenceCollection(
                    snapshots = listOf(snapshot(300L, emptyList())),
                    bindingCount = 1,
                    complete = true,
                ),
            )
            val refresh = refresh(sqlite, collector)
            refresh.execute(title.id, forceRefresh = true).isSuccess shouldBe true
            sqlite.evidenceRepository.getByCanonicalTitleId(title.id)
                .single().mappedCanonicalChapterId.let { it != null } shouldBe true

            refresh.execute(title.id, forceRefresh = true).isSuccess shouldBe true
            val persisted = sqlite.evidenceRepository.getByCanonicalTitleId(title.id).single()
            persisted.evidence.externalChapterKey shouldBe "first"
            persisted.mappedCanonicalChapterId shouldBe null
        }
    }

    @Test
    fun `complete empty snapshot detaches only the exact Provider binding and retains rival source support`() =
        runBlocking {
            MangaAllDisposableSqlite.open().use { sqlite ->
                val title = canonicalTitle()
                sqlite.titleRepository.insert(title)
                val sourceA = evidence("first", "Chapter 1", 100L)
                val sourceB = sourceA.copy(
                    id = "contract-source-b",
                    producerId = "provider-binding:another-source",
                )
                val collector = mockk<CollectProviderReadingEvidence>()
                coEvery { collector.execute(title.id) } returnsMany listOf(
                    ProviderReadingEvidenceCollection(
                        snapshots = listOf(
                            snapshot(100L, listOf(sourceA)),
                            tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingEvidenceSnapshot(
                                producerId = sourceB.producerId,
                                observedAt = 100L,
                                evidence = listOf(sourceB),
                            ),
                        ),
                        bindingCount = 2,
                        complete = true,
                    ),
                    ProviderReadingEvidenceCollection(
                        snapshots = listOf(snapshot(300L, emptyList())),
                        bindingCount = 2,
                        complete = true,
                    ),
                )
                val refresh = refresh(sqlite, collector)
                refresh.execute(title.id, forceRefresh = true).isSuccess shouldBe true
                sqlite.evidenceRepository.getByCanonicalTitleId(title.id).size shouldBe 2

                refresh.execute(title.id, forceRefresh = true).isSuccess shouldBe true
                val rows = sqlite.evidenceRepository.getByCanonicalTitleId(title.id)
                rows.single { it.evidence.producerId == PRODUCER }.mappedCanonicalChapterId shouldBe null
                (rows.single { it.evidence.producerId == sourceB.producerId }
                    .mappedCanonicalChapterId != null) shouldBe true
                sqlite.chapterRepository.getChaptersByCanonicalTitleId(title.id).size shouldBe 1
            }
        }

    private fun snapshot(at: Long, evidence: List<ChapterEvidence>) =
        tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingEvidenceSnapshot(
            producerId = PRODUCER,
            observedAt = at,
            evidence = evidence,
        )

    private fun canonicalTitle() = CanonicalTitle(
        id = TITLE_ID,
        displayTitle = "CONTRACT_ONLY_TITLE",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun evidence(key: String, label: String, at: Long) = ChapterEvidence(
        id = "contract-evidence-$key",
        canonicalTitleId = TITLE_ID,
        producerKind = ProducerKind.PROVIDER,
        producerId = PRODUCER,
        externalChapterKey = key,
        rawLabel = label,
        rawNumber = if (key == "first") 1.0 else 2.0,
        volume = null,
        title = null,
        observedAt = at,
        confidence = 0.95,
        authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
    )

    private fun refresh(
        sqlite: MangaAllDisposableSqlite,
        collector: CollectProviderReadingEvidence,
    ): RefreshChapterEvidence {
        val integrations = mockk<IntegrationRegistry>(relaxed = true)
        coEvery { integrations.awaitReady() } returns Unit
        every { integrations.chapterEvidenceProviders() } returns emptyList()
        val reconcile = ReconcileChapterEvidence(
            parser = ParseCanonicalChapterLabel(),
            canonicalChapterRepository = sqlite.chapterRepository,
            evidenceRepository = sqlite.evidenceRepository,
        )
        return RefreshChapterEvidence(integrations, reconcile, collector)
    }

    private companion object {
        const val TITLE_ID = "mangaall-sqlite-contract-title"
        const val PRODUCER = "provider-binding:contract-only"
    }
}
