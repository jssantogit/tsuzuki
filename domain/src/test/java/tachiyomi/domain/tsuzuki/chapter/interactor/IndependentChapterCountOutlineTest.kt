package tachiyomi.domain.tsuzuki.chapter.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount

/** Issue #155: declared totals are not individually identified chapters. */
class IndependentChapterCountOutlineTest {

    @Test
    fun `a declared 120 with 80 observed does not invent the remaining 40`() {
        val observed = (1..80).map { number ->
            CanonicalChapter(
                id = "observed-$number",
                canonicalTitleId = "work",
                displayNumber = number.toString(),
                type = CanonicalChapterType.REGULAR,
                baseNumber = number,
                confirmation = CanonicalChapterConfirmation.CONFIRMED,
            )
        }

        val result = buildCanonicalChapterOutline(
            canonicalTitleId = "work",
            observedChapters = observed,
            reportedCounts = listOf(ReportedChapterCount("work", "kitsu", 120, 1L)),
        )

        result.size shouldBe 80
        result.map { it.chapter.id }.shouldContainExactly(observed.map { it.id })
        result.none { it.inferredFromReportedCount } shouldBe true
    }

    @Test
    fun `an unverified count of 5000 creates no chapter or identity`() {
        buildCanonicalChapterOutline(
            canonicalTitleId = "work",
            observedChapters = emptyList(),
            reportedCounts = listOf(ReportedChapterCount("work", "kitsu", 5_000, 1L)),
        ).isEmpty() shouldBe true
    }

    @Test
    fun `historical materialized count ids stay visible without inventing adjacent slots`() {
        val legacy = CanonicalChapter(
            id = inferredChapterId("work", 2),
            canonicalTitleId = "work",
            displayNumber = "2",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 2,
            confirmation = CanonicalChapterConfirmation.PROVISIONAL,
        )
        val result = buildCanonicalChapterOutline(
            canonicalTitleId = "work",
            observedChapters = listOf(legacy),
            reportedCounts = listOf(ReportedChapterCount("work", "kitsu", 120, 1L)),
        )

        result.map { it.chapter.id }.shouldContainExactly(legacy.id)
        result.single().inferredFromReportedCount shouldBe true
    }
}
