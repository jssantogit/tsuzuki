package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.presentation.tsuzuki.detail.CanonicalChapterProgressState
import eu.kanade.presentation.tsuzuki.detail.CanonicalChapterSort
import eu.kanade.presentation.tsuzuki.detail.CanonicalDetailsLowerAction
import eu.kanade.presentation.tsuzuki.detail.CanonicalDetailsLowerMode
import eu.kanade.presentation.tsuzuki.detail.availableDetailChapters
import eu.kanade.presentation.tsuzuki.detail.chapterAtScrubberPosition
import eu.kanade.presentation.tsuzuki.detail.chapterProgressState
import eu.kanade.presentation.tsuzuki.detail.contextualChapters
import eu.kanade.presentation.tsuzuki.detail.detailsModeAfterAction
import eu.kanade.presentation.tsuzuki.detail.resolveChapterJump
import eu.kanade.presentation.tsuzuki.detail.restoreDetailsMode
import eu.kanade.presentation.tsuzuki.detail.showChapterScrubber
import eu.kanade.presentation.tsuzuki.detail.sortDetailChapters
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress

class CanonicalDetailsChapterPresentationTest {

    @Test
    fun `never started window uses the first real ordered chapters without numbering assumptions`() {
        val chapters = chapters("10", "10.5", "Special", "12", "20", "30", "642")

        contextualChapters(chapters).ids() shouldBe chapters.take(5).ids()
    }

    @Test
    fun `active canonical identity centers its context window`() {
        val chapters = chapters("3", "4", "10", "10.5", "Special", "12", "20", "642", "Final")

        contextualChapters(chapters, activeChapterId = chapters[4].chapter.id).ids() shouldBe
            chapters.subList(2, 7).ids()
    }

    @Test
    fun `caught up uses final real chapters`() {
        val chapters = chapters("10", "10.5", "Special", "12", "20", "30", "642").map {
            it.copy(progress = CanonicalChapterProgress(it.chapter.id, read = true))
        }

        contextualChapters(chapters).ids() shouldBe chapters.takeLast(5).ids()
    }

    @Test
    fun `progress at the end retains the final window and preceding context`() {
        val chapters = chapters("10", "10.5", "Special", "12", "20", "30", "642")

        contextualChapters(chapters, activeChapterId = chapters.last().chapter.id).ids() shouldBe
            chapters.takeLast(5).ids()
    }

    @Test
    fun `small collection retains every original object including irregular identities`() {
        val chapters = chapters("10.5", "1/2", "Special", "12")

        contextualChapters(chapters) shouldBe chapters
        contextualChapters(chapters, activeChapterId = chapters[2].chapter.id) shouldBe chapters
    }

    @Test
    fun `unknown active identity cannot invent a chapter`() {
        val chapters = chapters("10", "10.5", "Special", "12", "20", "30")

        contextualChapters(chapters, activeChapterId = "not-loaded").ids() shouldBe chapters.take(5).ids()
        contextualChapters(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `reported count placeholders never become real rail jump or scrubber chapters`() {
        val observed = chapters("10", "10.5", "Special", "12")
        val placeholder = chapters("11").single().copy(
            chapter = chapters("11").single().chapter.copy(id = "count-derived"),
            inferredFromCount = true,
        )
        val outline = observed.take(2) + placeholder + observed.drop(2)

        availableDetailChapters(outline) shouldBe observed
        contextualChapters(outline) shouldBe observed
        resolveChapterJump(outline, "11") shouldBe null
        chapterAtScrubberPosition(outline, 0.5f) shouldBe observed[2]
        sortDetailChapters(outline, CanonicalChapterSort.ORIGINAL) shouldBe observed
    }

    @Test
    fun `full mode back restores compact before leaving the work`() {
        restoreDetailsMode(CanonicalDetailsLowerMode.FULL) shouldBe CanonicalDetailsLowerMode.COMPACT
        restoreDetailsMode(CanonicalDetailsLowerMode.EDITORIAL) shouldBe CanonicalDetailsLowerMode.COMPACT
        restoreDetailsMode(CanonicalDetailsLowerMode.COMPACT) shouldBe null
    }

    @Test
    fun `view all action enters full mode and back first restores the work`() {
        val full = detailsModeAfterAction(CanonicalDetailsLowerMode.COMPACT, CanonicalDetailsLowerAction.VIEW_ALL)
        full shouldBe CanonicalDetailsLowerMode.FULL
        detailsModeAfterAction(full!!, CanonicalDetailsLowerAction.BACK) shouldBe CanonicalDetailsLowerMode.COMPACT
        detailsModeAfterAction(CanonicalDetailsLowerMode.COMPACT, CanonicalDetailsLowerAction.BACK) shouldBe null
        detailsModeAfterAction(CanonicalDetailsLowerMode.FULL, CanonicalDetailsLowerAction.EDITORIAL) shouldBe
            CanonicalDetailsLowerMode.EDITORIAL
        detailsModeAfterAction(CanonicalDetailsLowerMode.EDITORIAL, CanonicalDetailsLowerAction.CHAPTERS) shouldBe
            CanonicalDetailsLowerMode.COMPACT
    }

    @Test
    fun `partial state follows actual page progress and never generic timestamps`() {
        val unread = chapters("Special").single()
        chapterProgressState(unread) shouldBe CanonicalChapterProgressState.UNREAD
        chapterProgressState(
            unread.copy(progress = CanonicalChapterProgress(unread.chapter.id, updatedAt = 100)),
        ) shouldBe
            CanonicalChapterProgressState.UNREAD
        chapterProgressState(
            unread.copy(progress = CanonicalChapterProgress(unread.chapter.id, lastPageRead = 8)),
        ) shouldBe
            CanonicalChapterProgressState.PARTIAL
        chapterProgressState(
            unread.copy(progress = CanonicalChapterProgress(unread.chapter.id, lastVariantId = "release")),
        )
            .shouldBe(CanonicalChapterProgressState.PARTIAL)
        chapterProgressState(unread.copy(progress = CanonicalChapterProgress(unread.chapter.id, read = true))) shouldBe
            CanonicalChapterProgressState.READ
    }

    @Test
    fun `sorting reverses the actual ordered collection without reconstructing identities`() {
        val chapters = chapters("10", "10.5", "Special", "12", "1/2")

        sortDetailChapters(chapters, CanonicalChapterSort.ORIGINAL) shouldBe chapters
        sortDetailChapters(chapters, CanonicalChapterSort.REVERSED) shouldBe chapters.reversed()
    }

    @Test
    fun `jump resolves only actual identity label or title including fractions and specials`() {
        val chapters = chapters("10", "10.5", "Special", "12", "1/2")
        val special = chapters[2].copy(chapter = chapters[2].chapter.copy(title = "Winter special"))
        val loaded = chapters.toMutableList().apply { set(2, special) }

        resolveChapterJump(loaded, " 10.5 ") shouldBe loaded[1]
        resolveChapterJump(loaded, "1/2") shouldBe loaded[4]
        resolveChapterJump(loaded, "special") shouldBe special
        resolveChapterJump(loaded, "WINTER SPECIAL") shouldBe special
        resolveChapterJump(loaded, special.chapter.id) shouldBe special
    }

    @Test
    fun `missing jump and empty input never manufacture chapters`() {
        val chapters = chapters("10", "10.5", "Special", "12")

        resolveChapterJump(chapters, "11") shouldBe null
        resolveChapterJump(chapters, "642") shouldBe null
        resolveChapterJump(chapters, " ") shouldBe null
        resolveChapterJump(emptyList(), "10") shouldBe null
    }

    @Test
    fun `scrubber resolves position to an actual object instead of interpolating numbers`() {
        val chapters = chapters("10", "10.5", "Special", "12", "642")

        chapterAtScrubberPosition(chapters, 0f) shouldBe chapters.first()
        chapterAtScrubberPosition(chapters, 0.5f) shouldBe chapters[2]
        chapterAtScrubberPosition(chapters, 1f) shouldBe chapters.last()
        chapterAtScrubberPosition(chapters, -1f) shouldBe chapters.first()
        chapterAtScrubberPosition(chapters, 2f) shouldBe chapters.last()
        chapterAtScrubberPosition(emptyList(), 0.5f) shouldBe null
    }

    @Test
    fun `scrubber has a named local threshold and remains omitted for small works`() {
        showChapterScrubber(0) shouldBe false
        showChapterScrubber(5) shouldBe false
        showChapterScrubber(80) shouldBe true
        showChapterScrubber(20, threshold = 20) shouldBe true
        showChapterScrubber(19, threshold = 20) shouldBe false
    }

    private fun chapters(vararg labels: String): List<CanonicalChapterDetailItem> = labels.mapIndexed { index, label ->
        CanonicalChapterDetailItem(
            chapter = CanonicalChapter(
                id = "canonical-$index",
                canonicalTitleId = "work-id",
                displayNumber = label,
            ),
            progress = null,
            downloaded = false,
        )
    }

    private fun List<CanonicalChapterDetailItem>.ids() = map { it.chapter.id }
}
