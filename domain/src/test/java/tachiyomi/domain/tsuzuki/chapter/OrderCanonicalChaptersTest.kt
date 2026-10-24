package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.hasVolumeLocalChapterNumbering
import tachiyomi.domain.tsuzuki.chapter.model.orderCanonicalChapters

class OrderCanonicalChaptersTest {

    @Test
    fun `two editions repeating a number activate volume-local ordering`() {
        val chapters = listOf(
            chapter("v2-2", 2, 2),
            chapter("v1-1", 1, 1),
            chapter("v2-1", 1, 2),
            chapter("v1-2", 2, 1),
        )

        hasVolumeLocalChapterNumbering(chapters) shouldBe true
        orderCanonicalChapters(chapters).map { it.id }
            .shouldContainExactly("v1-1", "v1-2", "v2-1", "v2-2")
    }

    @Test
    fun `nonoverlapping volume numbers retain global numeric order`() {
        val chapters = listOf(
            chapter("four", 4, 2),
            chapter("three", 3, 1),
            chapter("one", 1, 1),
            chapter("two", 2, 2),
        )

        hasVolumeLocalChapterNumbering(chapters) shouldBe false
        orderCanonicalChapters(chapters).map { it.id }
            .shouldContainExactly("one", "two", "three", "four")
    }

    @Test
    fun `special types retain semantic boundaries in volume-local numbering`() {
        val chapters = listOf(
            chapter("epilogue", null, null, CanonicalChapterType.EPILOGUE),
            chapter("v2-1", 1, 2),
            chapter("extra", 1, 2, CanonicalChapterType.EXTRA),
            chapter("v1-1", 1, 1),
            chapter("prologue", null, null, CanonicalChapterType.PROLOGUE),
        )

        orderCanonicalChapters(chapters).map { it.id }
            .shouldContainExactly("prologue", "v1-1", "v2-1", "extra", "epilogue")
    }

    @Test
    fun `unknown volume never fabricates an earlier volume position`() {
        val chapters = listOf(
            chapter("unknown-volume", 1, null),
            chapter("v2-1", 1, 2),
            chapter("v1-1", 1, 1),
        )

        orderCanonicalChapters(chapters).map { it.id }
            .shouldContainExactly("v1-1", "v2-1", "unknown-volume")
    }

    private fun chapter(
        id: String,
        number: Int?,
        volume: Int?,
        type: CanonicalChapterType = CanonicalChapterType.REGULAR,
    ) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title",
        displayNumber = number?.toString() ?: id,
        volume = volume,
        type = type,
        baseNumber = number,
        confidence = 1.0,
    )
}
