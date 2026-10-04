package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderRegistry
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

class CollectProviderReadingEvidenceLifecycleTest {

    @Test
    fun `inactive Provider detaches its persisted reading binding with an empty complete snapshot`() = runBlocking {
        val canonicalTitleId = "title-1"
        val providerId = ProviderId("app.tsuzuki.mangafire")
        val binding = ProviderReadingBinding(
            id = "binding-1",
            canonicalTitleId = canonicalTitleId,
            ref = ProviderBindingRef(
                providerId = providerId,
                facetId = "en",
                externalWorkId = "dandadan-1",
            ),
            verification = ProviderBindingVerification.EXACT,
            availability = ProviderBindingAvailability.AVAILABLE,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val titleRepository = mockk<CanonicalTitleRepository>()
        coEvery { titleRepository.getById(canonicalTitleId) } returns CanonicalTitle(
            id = canonicalTitleId,
            displayTitle = "Dandadan",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val registry = mockk<ProviderRegistry>()
        coEvery { registry.awaitReady() } returns Unit
        every { registry.providers() } returns emptyList()
        val bindings = mockk<ProviderReadingBindingRepository>(relaxed = true)
        coEvery { bindings.getByTitle(canonicalTitleId) } returns listOf(binding)
        val bridge = CollectProviderReadingEvidence(
            canonicalTitleRepository = titleRepository,
            providerRegistry = registry,
            gateway = mockk(relaxed = true),
            bindingRepository = bindings,
            evidenceAdapter = ProviderChapterEvidenceAdapter(),
            clock = { 77L },
        )

        val result = bridge.execute(canonicalTitleId)

        result.complete shouldBe true
        result.bindingCount shouldBe 1
        result.snapshots.single() shouldBe ProviderReadingEvidenceSnapshot(
            producerId = binding.ref.evidenceProducerId(),
            observedAt = 77L,
            evidence = emptyList(),
        )
        coVerify(exactly = 1) {
            bindings.markUnavailable(binding.id, 77L)
        }
    }
}
