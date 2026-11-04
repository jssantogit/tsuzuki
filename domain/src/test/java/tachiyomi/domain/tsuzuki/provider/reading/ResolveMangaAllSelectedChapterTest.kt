package tachiyomi.domain.tsuzuki.provider.reading

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.provider.ProviderId

class ResolveMangaAllSelectedChapterTest {
    @Test
    fun `Reader retrieves validated canonical options before applying exact source preference`() = runBlocking {
        val chapter = CanonicalChapter(
            id = "chapter-1",
            canonicalTitleId = "title-1",
            displayNumber = "1",
            volume = null,
            title = null,
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            part = null,
            alphaSuffix = null,
            confidence = 0.95,
            createdAt = 1,
            updatedAt = 1,
            confirmation = CanonicalChapterConfirmation.PROVISIONAL,
        )
        val ref = ProviderBindingRef(ProviderId("app.tsuzuki.mangaall"), "en", "mangafire:one")
        val option = ProviderChapterReadingOption(
            canonicalChapterId = chapter.id,
            bindingId = "verified",
            providerId = ref.providerId,
            facetId = ref.facetId,
            externalWorkId = ref.externalWorkId,
            providerChapterId = "mangafire:chapter-one",
        )
        val chapters = mockk<CanonicalChapterRepository>()
        val sources = mockk<ResolveProviderChapterReading>()
        coEvery { chapters.getById(chapter.id) } returns chapter
        coEvery { sources.options(chapter.id) } returns listOf(option)
        val selections = object : MangaAllSourceSelectionRepository {
            override suspend fun get(canonicalTitleId: String, language: String) =
                MangaAllSourceSelection("title-1", ref, 10)
            override suspend fun select(choice: MangaAllSourceSelection) = error("read only")
        }
        val resolver = ResolveMangaAllSelectedChapter(
            chapters,
            sources,
            ResolveMangaAllSelectedReadingOption(selections),
        )
        resolver.execute(chapter.id, "en") shouldBe option
        resolver.execute(chapter.id, "pt-br") shouldBe null
        coVerify(exactly = 2) { sources.options(chapter.id) }
    }

    @Test
    fun `missing canonical chapter never queries source options`() = runBlocking {
        val chapters = mockk<CanonicalChapterRepository>()
        val sources = mockk<ResolveProviderChapterReading>()
        val selections = mockk<ResolveMangaAllSelectedReadingOption>()
        coEvery { chapters.getById("missing") } returns null
        ResolveMangaAllSelectedChapter(chapters, sources, selections).execute("missing", "en") shouldBe null
        coVerify(exactly = 0) { sources.options(any()) }
        coVerify(exactly = 0) { selections.select(any(), any(), any()) }
    }
}
