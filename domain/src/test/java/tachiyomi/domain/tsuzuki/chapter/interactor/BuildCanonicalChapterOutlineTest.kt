package tachiyomi.domain.tsuzuki.chapter.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount

class BuildCanonicalChapterOutlineTest {

    @Test
    fun `Death Note metadata count of 108 does not establish any individual chapter`() {
        buildCanonicalChapterOutline("death-note", emptyList(), listOf(count("death-note", 108))).isEmpty() shouldBe
            true
    }

    @Test
    fun `Nanatsu metadata count of 346 remains metadata not canonical rows`() {
        buildCanonicalChapterOutline("nanatsu", emptyList(), listOf(count("nanatsu", 346))).isEmpty() shouldBe true
    }

    @Test
    fun `observed chapters remain ordered with extras without filling metadata count gaps`() {
        val observed = (1..62).map(::chapter) +
            (1..10).map { fraction -> chapter(1, part = fraction) }
        val result = buildCanonicalChapterOutline("title", observed, listOf(count("title", 64)))
        result.size shouldBe 72
        result.none(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
        result.first { it.chapter.baseNumber == 1 && it.chapter.part == null }
            .chapter.id shouldBe "chapter-1"
        result.last().chapter.baseNumber shouldBe 62
    }

    @Test
    fun `zero and fractional observed identities preserve their ordering without phantom rows`() {
        val outline = buildCanonicalChapterOutline(
            "title",
            listOf(chapter(0), chapter(0, part = 5), chapter(1), chapter(1, part = 5)),
            listOf(count("title", 80)),
        )
        outline.map { it.chapter.displayNumber }.shouldContainExactly("0", "0.5", "1", "1.5")
        outline.none(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
    }

    @Test
    fun `volume-local chapters keep their distinct IDs with conflicting metadata count`() {
        val observed = listOf(
            chapter(2).copy(id = "v2-ch2", volume = 2),
            chapter(1).copy(id = "v1-ch1", volume = 1),
            chapter(1).copy(id = "v2-ch1", volume = 2),
            chapter(2).copy(id = "v1-ch2", volume = 1),
        )
        val outline = buildCanonicalChapterOutline("title", observed, listOf(count("title", 120)))
        outline.map { it.chapter.id }.shouldContainExactly("v1-ch1", "v1-ch2", "v2-ch1", "v2-ch2")
        outline.none(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
    }

    @Test
    fun `an observed chapter replaces no fabricated slot and keeps its own ID`() {
        val initial = buildCanonicalChapterOutline("title", emptyList(), listOf(count("title", 3)))
        val observed = buildCanonicalChapterOutline("title", listOf(chapter(2)), listOf(count("title", 3)))
        initial.isEmpty() shouldBe true
        observed.map { it.chapter.id }.shouldContainExactly("chapter-2")
        observed.none(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
    }

    @Test
    fun `One Punch-Man observed chapters 138 and 139 are not padded to 138`() {
        val outline = buildCanonicalChapterOutline(
            "title",
            listOf(chapter(138), chapter(139)),
            listOf(count("title", 138)),
        )
        outline.map { it.chapter.baseNumber }.shouldContainExactly(138, 139)
        outline.none(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
    }

    @Test
    fun `already materialized count chapter remains with its historical ID`() {
        val legacy = CanonicalChapter(
            id = inferredChapterId("title", 2),
            canonicalTitleId = "title",
            displayNumber = "2",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 2,
            confidence = 0.0,
        )
        val outline = buildCanonicalChapterOutline("title", listOf(legacy), listOf(count("title", 3)))
        outline.map { it.chapter.id }.shouldContainExactly(legacy.id)
        outline.single().inferredFromReportedCount shouldBe true
    }

    @Test
    fun `different count providers cannot manufacture ordinal identities`() {
        val outline = buildCanonicalChapterOutline(
            "title",
            listOf(chapter(1)),
            listOf(
                count("title", 3, "kitsu"),
                count("title", 2, "mal"),
                count("title", -100, "invalid"),
                count("different-title", 500, "other"),
            ),
        )
        outline.map { it.chapter.baseNumber }.shouldContainExactly(1)
        outline.none(CanonicalChapterOutlineEntry::inferredFromReportedCount) shouldBe true
        buildCanonicalChapterOutline("title", emptyList(), listOf(count("title", 100_000))).isEmpty() shouldBe true
    }

    private fun count(title: String, value: Int, provider: String = "kitsu") =
        ReportedChapterCount(title, provider, value, 1L)

    private fun chapter(number: Int, part: Int? = null): CanonicalChapter = CanonicalChapter(
        id = "chapter-$number" + (part?.let { "-$it" } ?: ""),
        canonicalTitleId = "title",
        displayNumber = number.toString() + (part?.let { ".$it" } ?: ""),
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        part = part,
        confidence = 1.0,
    )
}
