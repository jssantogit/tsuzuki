package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.interactor.RepairZeroPlaceholderChapterSemantics
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.interactor.GetCanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.interactor.ImportLegacyCanonicalProgress
import tachiyomi.domain.tsuzuki.reader.interactor.ResolveCanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ResolveReadingSource

class ResolveCanonicalReadingStartTest {

    @Test
    fun `provider-only canonical inventory can start reading without a legacy Mihon source mapping`() = runTest {
        val chapters = mockk<CanonicalChapterRepository>()
        val sources = mockk<SourceTitleMappingRepository>()
        val getStart = mockk<GetCanonicalReadingStart>()
        coEvery { chapters.getByCanonicalTitleId("provider-only") } returns listOf(mockk<CanonicalChapter>())
        coEvery { sources.getByCanonicalTitleId("provider-only") } returns emptyList()
        coEvery { getStart.execute("provider-only") } returns CanonicalReadingStart.Ready("chapter-1")

        val resolver = ResolveCanonicalReadingStart(
            canonicalChapterRepository = chapters,
            sourceTitleMappingRepository = sources,
            refreshCanonicalChapters = mockk<RefreshCanonicalChapters>(relaxed = true),
            repairZeroPlaceholderChapterSemantics = mockk<RepairZeroPlaceholderChapterSemantics>(relaxed = true),
            resolveReadingSource = mockk<ResolveReadingSource>(relaxed = true),
            importLegacyCanonicalProgress = mockk<ImportLegacyCanonicalProgress>(relaxed = true),
            getCanonicalReadingStart = getStart,
        )

        resolver.execute("provider-only") shouldBe CanonicalReadingStart.Ready("chapter-1")
    }
}
