package eu.kanade.tachiyomi.ui.tsuzuki.detail

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.artwork.ResolveCanonicalArtwork
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceSupportSnapshot
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.interactor.ResolveCanonicalMetadata
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalTitleRefreshPreservationTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `failed enrichment preserves cached Hero metadata and valid chapters`() = runTest(dispatcher) {
        val metadata = mockk<ResolveCanonicalMetadata>()
        coEvery { metadata.cached("title") } returns ResolvedMetadata(
            synopsis = ProvenancedMetadata("Good synopsis", IntegrationId("kitsu")),
            artworkUrl = ProvenancedMetadata("https://example.test/real.jpg", IntegrationId("kitsu")),
        )
        coEvery { metadata.execute("title", any()) } returns Result.failure(IllegalStateException("offline"))
        val model = model(metadata)
        model.start("title")
        advanceUntilIdle()
        val loaded = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        loaded.description shouldBe "Good synopsis"
        loaded.coverUrl shouldBe "https://example.test/real.jpg"
        loaded.chapters.map { it.chapter.id } shouldBe listOf("real-chapter")
        (loaded.refreshError != null) shouldBe true
        loaded.isRefreshing shouldBe false
    }

    @Test
    fun `thrown secondary refresh keeps the base work usable`() = runTest(dispatcher) {
        val metadata = mockk<ResolveCanonicalMetadata>()
        coEvery { metadata.cached("title") } returns null
        coEvery { metadata.execute("title", any()) } throws IllegalStateException("secondary failure")
        val model = model(metadata)
        model.start("title")
        advanceUntilIdle()
        val loaded = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        loaded.title.displayTitle shouldBe "Title"
        loaded.chapters.map { it.chapter.id } shouldBe listOf("real-chapter")
        (loaded.refreshError != null) shouldBe true
        loaded.isRefreshing shouldBe false
    }

    private fun model(metadata: ResolveCanonicalMetadata): CanonicalTitleScreenModel {
        val titles = mockk<CanonicalTitleRepository>()
        coEvery { titles.getById("title") } returns CanonicalTitle(
            id = "title",
            displayTitle = "Title",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1,
            updatedAt = 1,
        )
        val library = mockk<CanonicalLibraryRepository>()
        coEvery { library.get("title") } returns null
        val chapters = mockk<CanonicalChapterRepository>()
        coEvery { chapters.getByCanonicalTitleId("title") } returns listOf(
            CanonicalChapter(
                id = "real-chapter",
                canonicalTitleId = "title",
                displayNumber = "10.5",
                confirmation = CanonicalChapterConfirmation.CONFIRMED,
            ),
        )
        val evidence = mockk<ChapterEvidenceRepository>()
        coEvery { evidence.getSupportSnapshot("title") } returns ChapterEvidenceSupportSnapshot()
        val reading = mockk<CanonicalReadingRepository>()
        coEvery { reading.getProgressByCanonicalTitleId("title") } returns emptyList()
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
        val counts = mockk<ReportedChapterCountRepository>()
        coEvery { counts.getByTitle("title") } returns emptyList()
        val addons = mockk<AddonRepository>()
        coEvery { addons.snapshot() } returns emptyList()
        val refresh = mockk<RefreshChapterEvidence>()
        coEvery { refresh.executeProgressively("title", any(), any()) } returns Result.success(Unit)
        val artwork = mockk<ResolveCanonicalArtwork>()
        coEvery { artwork.execute("title") } returns null
        val source = mockk<ResolveCanonicalSourceManga>()
        coEvery { source.execute("title", any()) } returns null
        return CanonicalTitleScreenModel(
            canonicalTitleRepository = titles,
            canonicalLibraryRepository = library,
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = evidence,
            canonicalReadingRepository = reading,
            getCanonicalChapterDownloadState = mockk(relaxed = true),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = counts,
            addonRepository = addons,
            refreshReportedChapterCounts = mockk(relaxed = true),
            refreshChapterEvidence = refresh,
            resolveCanonicalMetadata = metadata,
            resolveCanonicalSourceManga = source,
            resolveCanonicalArtwork = artwork,
        )
    }
}
