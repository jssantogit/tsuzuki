package tachiyomi.domain.tsuzuki.integration.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.TitleNameObservation
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

class ResolveCanonicalMetadataTitleNamesTest {

    @Test
    fun `verified metadata progressively persists discovery names for existing canonical title`() = runTest {
        val canonicalTitleRepository = mockk<CanonicalTitleRepository>(relaxed = true)
        coEvery { canonicalTitleRepository.getById(any()) } returns canonicalTitle()
        coEvery { canonicalTitleRepository.getExternalIdentities(any()) } returns listOf(
            ExternalIdentity(
                canonicalTitleId = TITLE_ID,
                provider = "kitsu",
                externalId = "k1",
                verified = true,
                createdAt = 1L,
            ),
        )
        val provider = object : MetadataProvider {
            override val integrationId = IntegrationId("kitsu")

            override suspend fun getDetails(externalId: String): Result<CatalogItem> = Result.success(
                CatalogItem(
                    provider = "kitsu",
                    providerId = externalId,
                    title = "Sousou no Frieren",
                    titles = linkedMapOf(
                        "en" to "Frieren: Beyond Journey's End",
                        "ja_jp" to "葬送のフリーレン",
                        "duplicate" to " sousou NO FRIEREN ",
                    ),
                ),
            )
        }
        val registry = mockk<IntegrationRegistry>(relaxed = true)
        every { registry.metadataProviders(any()) } returns listOf(provider)
        every { registry.isGlobalCapabilityActive(any(), any()) } returns true
        every { registry.manifests() } returns emptyList()
        every { registry.ratingsProviders() } returns emptyList()
        val names = RecordingTitleNameObservationRepository()

        ResolveCanonicalMetadata(
            canonicalTitleRepository = canonicalTitleRepository,
            registry = registry,
            titleArtworkRepository = mockk(relaxed = true),
            diagnosticRecorder = NoOpStructuredDiagnosticRecorder,
            titleNameObservationRepository = names,
        ).execute(TITLE_ID).getOrThrow()

        names.observations.map { it.provider to it.sourceKey to it.value } shouldContainExactly listOf(
            ("kitsu" to "primary") to "Sousou no Frieren",
            ("kitsu" to "ja_jp") to "葬送のフリーレン",
        )
    }

    private fun canonicalTitle() = CanonicalTitle(
        id = TITLE_ID,
        displayTitle = "Frieren: Beyond Journey's End",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class RecordingTitleNameObservationRepository : TitleNameObservationRepository {
        val observations = mutableListOf<TitleNameObservation>()

        override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> =
            observations.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(observation: TitleNameObservation) {
            observations += observation
        }
    }

    private companion object {
        const val TITLE_ID = "canonical"
    }
}
