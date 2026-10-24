package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceSupportSnapshot
import tachiyomi.domain.tsuzuki.chapter.interactor.OpenCanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.home.interactor.OpenHomeHero
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

class OpenHomeHeroTest {
    @Test
    fun `first readable chapter follows canonical ordering rather than repository order`() = runTest {
        val fixture = Fixture()
        fixture.chapters = fixture.chapters.reversed()
        fixture.open.execute(fixture.item) shouldBe "resolved-c1"
    }

    @Test
    fun `reported count without individually known chapter cannot open a fictitious reader target`() = runTest {
        val fixture = Fixture()
        fixture.chapters = emptyList()
        coEvery { fixture.counts.getByTitle("title") } returns listOf(ReportedChapterCount("title", "kitsu", 2, 1L))
        fixture.open.execute(fixture.item) shouldBe null
        coVerify(exactly = 0) { fixture.gateway.execute(any()) }
    }

    @Test
    fun `historical count chapter with existing progress stays readable through original gateway`() = runTest {
        val fixture = Fixture()
        val legacy = chapter("tsuzuki:count:title:1", 1)
            .copy(confirmation = CanonicalChapterConfirmation.PROVISIONAL)
        fixture.chapters = listOf(legacy)
        fixture.progress = listOf(CanonicalChapterProgress(legacy.id, lastPageRead = 1, updatedAt = 10))
        coEvery { fixture.counts.getByTitle("title") } returns listOf(ReportedChapterCount("title", "kitsu", 120, 1L))

        fixture.open.execute(fixture.item) shouldBe "resolved-tsuzuki:count:title:1"
        coVerify(exactly = 1) { fixture.gateway.execute(legacy) }
    }

    @Test
    fun `unsupported unconfirmed chapter is not offered as a read target`() = runTest {
        val fixture = Fixture()
        fixture.chapters =
            listOf(fixture.chapters.first().copy(confirmation = CanonicalChapterConfirmation.PROVISIONAL))
        fixture.open.execute(fixture.item) shouldBe null
        coVerify(exactly = 0) { fixture.gateway.execute(any()) }
    }

    @Test
    fun `read prefers newest resumable progress and ignores completed history`() = runTest {
        val fixture = Fixture()
        fixture.progress = listOf(
            CanonicalChapterProgress("c1", read = true, lastPageRead = 99, updatedAt = 900),
            CanonicalChapterProgress("c2", lastPageRead = 8, updatedAt = 200),
        )
        fixture.open.execute(fixture.item) shouldBe "resolved-c2"
        coVerify(exactly = 1) { fixture.gateway.execute(fixture.chapters[1]) }
    }

    @Test
    fun `without resumable progress read opens first chapter through canonical gateway`() = runTest {
        val fixture = Fixture()
        fixture.progress = listOf(CanonicalChapterProgress("c2", lastPageRead = 0, updatedAt = 100))
        fixture.open.execute(fixture.item) shouldBe "resolved-c1"
        coVerify(exactly = 1) { fixture.gateway.execute(fixture.chapters[0]) }
    }

    @Test
    fun `variant checkpoint at first page remains resumable`() = runTest {
        val fixture = Fixture()
        fixture.progress = listOf(CanonicalChapterProgress("c2", lastVariantId = "variant", updatedAt = 10))
        fixture.open.execute(fixture.item) shouldBe "resolved-c2"
    }

    @Test
    fun `missing chapter checkpoint cannot open an invalid reader`() = runTest {
        val fixture = Fixture()
        fixture.chapters = emptyList()
        fixture.progress = listOf(CanonicalChapterProgress("absent", lastPageRead = 1, updatedAt = 10))
        fixture.open.execute(fixture.item) shouldBe null
        coVerify(exactly = 0) { fixture.gateway.execute(any()) }
    }

    private class Fixture {
        val item = CatalogItem("kitsu", "123", "Title")
        var chapters = listOf(chapter("c1", 1), chapter("c2", 2))
        var progress = emptyList<CanonicalChapterProgress>()
        val materialize = mockk<MaterializeCanonicalTitleFromCatalog> {
            coEvery { execute(item) } returns CanonicalTitle("title", "Title", CanonicalIdentityState.RESOLVED, 0L, 0L)
        }
        val chapterRepository = mockk<CanonicalChapterRepository> {
            coEvery { getByCanonicalTitleId("title") } answers { chapters }
        }
        val reading = mockk<CanonicalReadingRepository> {
            coEvery { getProgressByCanonicalTitleId("title") } answers { progress }
        }
        val counts = mockk<ReportedChapterCountRepository> { coEvery { getByTitle("title") } returns emptyList() }
        val evidence = mockk<ChapterEvidenceRepository> {
            coEvery { getSupportSnapshot("title") } returns ChapterEvidenceSupportSnapshot()
        }
        val downloads = mockk<CanonicalDownloadRepository> {
            coEvery { getChapterIdsByCanonicalTitle("title") } returns emptySet()
        }
        val gateway = mockk<OpenCanonicalChapter> {
            coEvery { execute(any()) } answers {
                firstArg<CanonicalChapter>().copy(id = "resolved-${firstArg<CanonicalChapter>().id}")
            }
        }
        val open = OpenHomeHero(materialize, chapterRepository, reading, counts, gateway, evidence, downloads)
    }

    companion object {
        private fun chapter(id: String, number: Int) = CanonicalChapter(
            id,
            "title",
            "$number",
            type = CanonicalChapterType.REGULAR,
            baseNumber = number,
            confirmation = CanonicalChapterConfirmation.CONFIRMED,
        )
    }
}
