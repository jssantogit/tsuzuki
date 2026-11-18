package eu.kanade.tachiyomi.provider.reading

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class MangaAllReaderSqliteProbeTest {
    @Test
    fun `Reader sample includes both boundaries and exceptional chapter identities`() {
        val chapters = (1..100).map { ordinal ->
            chapter(ordinal).let { if (ordinal == 25) it.copy(part = 1) else it }
        }
        val sampled = MangaAllReaderSqliteProbe.sample(chapters, max = 10)
        sampled.size shouldBe 10
        sampled.first().id shouldBe "chapter-1"
        sampled.last().id shouldBe "chapter-100"
        sampled.any { it.id == "chapter-25" } shouldBe true
        sampled.map { it.id }.distinct().size shouldBe 10
    }

    @Test
    fun `small Reader budget never omits first in favor of final chapter`() {
        val chapters = (1..100).map(::chapter)
        MangaAllReaderSqliteProbe.sample(chapters, max = 1).map { it.id } shouldBe
            listOf("chapter-1")
        MangaAllReaderSqliteProbe.sample(chapters, max = 2).map { it.id } shouldBe
            listOf("chapter-1", "chapter-100")
        MangaAllReaderSqliteProbe.sample(emptyList(), max = 2) shouldBe emptyList()
    }

    private fun chapter(number: Int) = CanonicalChapter(
        id = "chapter-$number",
        canonicalTitleId = "contract-title",
        displayNumber = "$number",
        volume = null,
        title = null,
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        part = null,
        alphaSuffix = null,
        confidence = 0.95,
        createdAt = 1L,
        updatedAt = 1L,
        confirmation = CanonicalChapterConfirmation.PROVISIONAL,
    )
}
