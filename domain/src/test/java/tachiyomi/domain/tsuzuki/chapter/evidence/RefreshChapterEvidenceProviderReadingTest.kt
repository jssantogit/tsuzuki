package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.provider.reading.CollectProviderReadingEvidence
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingEvidenceCollection
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingEvidenceSnapshot

class RefreshChapterEvidenceProviderReadingTest {

    @Test
    fun `canonical refresh publishes complete SCRIPT Provider snapshots through provider reconciliation`() = runTest {
        val canonicalTitleId = "title-1"
        val producerId = "provider-binding:fixture"
        val evidence = ChapterEvidence(
            id = "provider-evidence-1",
            canonicalTitleId = canonicalTitleId,
            producerKind = ProducerKind.PROVIDER,
            producerId = producerId,
            externalChapterKey = "chapter-1",
            rawLabel = "Chapter 1",
            rawNumber = 1.0,
            volume = null,
            title = null,
            observedAt = 42L,
            confidence = 0.95,
            authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
        )
        val collector = mockk<CollectProviderReadingEvidence>()
        coEvery { collector.execute(canonicalTitleId) } returns ProviderReadingEvidenceCollection(
            snapshots = listOf(
                ProviderReadingEvidenceSnapshot(
                    producerId = producerId,
                    observedAt = 42L,
                    evidence = listOf(evidence),
                ),
            ),
            bindingCount = 1,
            complete = true,
        )
        val registry = mockk<IntegrationRegistry>(relaxed = true)
        coEvery { registry.awaitReady() } returns Unit
        every { registry.chapterEvidenceProviders() } returns emptyList()
        val reconcile = mockk<ReconcileChapterEvidence>(relaxed = true)
        val refresh = RefreshChapterEvidence(
            registry = registry,
            reconcileChapterEvidence = reconcile,
            providerReadingEvidence = collector,
        )

        refresh.execute(canonicalTitleId).isSuccess shouldBe true

        coVerify(exactly = 1) {
            reconcile.executeProviderSnapshot(
                canonicalTitleId = canonicalTitleId,
                producerId = producerId,
                snapshotObservedAt = 42L,
                evidence = listOf(evidence),
            )
        }
    }

    @Test
    fun `incomplete Provider pages reconcile only additive provisional evidence without detaching support`() = runTest {
        val canonicalTitleId = "title-1"
        val evidence = ChapterEvidence(
            id = "provider-partial-1",
            canonicalTitleId = canonicalTitleId,
            producerKind = ProducerKind.PROVIDER,
            producerId = "provider-binding:fixture",
            externalChapterKey = "chapter-1",
            rawLabel = "Chapter 1",
            rawNumber = 1.0,
            volume = null,
            title = null,
            observedAt = 42L,
            confidence = 0.95,
            authority = ChapterEvidenceAuthority.PROVIDER_PROVISIONAL,
        )
        val collector = mockk<CollectProviderReadingEvidence>()
        coEvery { collector.execute(canonicalTitleId) } returns ProviderReadingEvidenceCollection(
            partialEvidence = listOf(evidence),
            bindingCount = 1,
            complete = false,
        )
        val registry = mockk<IntegrationRegistry>(relaxed = true)
        coEvery { registry.awaitReady() } returns Unit
        every { registry.chapterEvidenceProviders() } returns emptyList()
        val reconcile = mockk<ReconcileChapterEvidence>(relaxed = true)
        val refresh = RefreshChapterEvidence(registry, reconcile, collector)

        refresh.execute(canonicalTitleId).isSuccess shouldBe true

        coVerify(exactly = 1) { reconcile.execute(canonicalTitleId, listOf(evidence)) }
        coVerify(exactly = 0) {
            reconcile.executeProviderSnapshot(any(), any(), any(), any())
        }
    }

    @Test
    fun `empty complete Provider snapshot is still published so stale support can detach`() = runTest {
        val canonicalTitleId = "title-1"
        val producerId = "provider-binding:fixture"
        val collector = mockk<CollectProviderReadingEvidence>()
        coEvery { collector.execute(canonicalTitleId) } returns ProviderReadingEvidenceCollection(
            snapshots = listOf(
                ProviderReadingEvidenceSnapshot(
                    producerId = producerId,
                    observedAt = 99L,
                    evidence = emptyList(),
                ),
            ),
            bindingCount = 1,
            complete = true,
        )
        val registry = mockk<IntegrationRegistry>(relaxed = true)
        coEvery { registry.awaitReady() } returns Unit
        every { registry.chapterEvidenceProviders() } returns emptyList()
        val reconcile = mockk<ReconcileChapterEvidence>(relaxed = true)
        val refresh = RefreshChapterEvidence(
            registry = registry,
            reconcileChapterEvidence = reconcile,
            providerReadingEvidence = collector,
        )

        refresh.execute(canonicalTitleId).isSuccess shouldBe true

        coVerify(exactly = 1) {
            reconcile.executeProviderSnapshot(
                canonicalTitleId = canonicalTitleId,
                producerId = producerId,
                snapshotObservedAt = 99L,
                evidence = emptyList(),
            )
        }
    }
}
