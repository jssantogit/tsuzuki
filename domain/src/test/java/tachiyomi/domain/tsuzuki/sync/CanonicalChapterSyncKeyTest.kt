package tachiyomi.domain.tsuzuki.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.sync.model.CanonicalChapterSyncKey

class CanonicalChapterSyncKeyTest {

    @Test
    fun `same structured chapter identity is portable across local chapter UUIDs`() {
        val first = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "12a",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 12,
            alphaSuffix = "A",
        )
        val second = first.copy(id = "local-b", alphaSuffix = "a")

        CanonicalChapterSyncKey.from(first) shouldBe
            CanonicalChapterSyncKey.from(second)
    }

    @Test
    fun `unnumbered extras require stable mapped evidence rather than only their semantic type`() {
        val bonusA = CanonicalChapter(
            id = "bonus-a",
            canonicalTitleId = "title-1",
            displayNumber = "Extra - Bonus A",
            type = CanonicalChapterType.EXTRA,
        )
        val bonusB = bonusA.copy(id = "bonus-b", displayNumber = "Extra - Bonus B")

        CanonicalChapterSyncKey.from(bonusA) shouldBe null
        CanonicalChapterSyncKey.from(bonusB) shouldBe null
        val keyA = CanonicalChapterSyncKey.from(bonusA, "addon:source:bonus-a")
        val keyB = CanonicalChapterSyncKey.from(bonusB, "addon:source:bonus-b")
        (keyA == keyB) shouldBe false
    }

    @Test
    fun `unknown unstructured chapter is not published without stable evidence`() {
        val chapter = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "?",
            type = CanonicalChapterType.UNKNOWN,
        )

        CanonicalChapterSyncKey.from(chapter) shouldBe null
    }

    @Test
    fun `stable mapped evidence makes unknown chapter portable without local UUID`() {
        val first = CanonicalChapter(
            id = "local-a",
            canonicalTitleId = "title-1",
            displayNumber = "?",
            type = CanonicalChapterType.UNKNOWN,
        )
        val second = first.copy(id = "local-b")

        CanonicalChapterSyncKey.from(
            chapter = first,
            stableEvidenceKey = "integration:kitsu:chapter-44",
        ) shouldBe CanonicalChapterSyncKey.from(
            chapter = second,
            stableEvidenceKey = "integration:kitsu:chapter-44",
        )
    }
}
