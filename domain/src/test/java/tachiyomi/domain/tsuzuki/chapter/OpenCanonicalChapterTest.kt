package tachiyomi.domain.tsuzuki.chapter

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.MaterializeInferredChapter
import tachiyomi.domain.tsuzuki.chapter.interactor.OpenCanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.interactor.inferredChapterId
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType

class OpenCanonicalChapterTest {
    @Test
    fun `inferred slot is materialized before its resolved identity reaches reader`() = runTest {
        val slot = CanonicalChapter(
            inferredChapterId("title", 1),
            "title",
            "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
        )
        val materialize = mockk<MaterializeInferredChapter> {
            coEvery { execute(slot) } returns slot.copy(id = "persisted-chapter")
        }
        OpenCanonicalChapter(materialize).execute(slot).id shouldBe "persisted-chapter"
        coVerify(exactly = 1) { materialize.execute(slot) }
    }

    @Test
    fun `real canonical chapter retains its identity without inferred materialization`() = runTest {
        val chapter = CanonicalChapter("chapter", "title", "1")
        val materialize = mockk<MaterializeInferredChapter>()
        OpenCanonicalChapter(materialize).execute(chapter).id shouldBe "chapter"
        coVerify(exactly = 0) { materialize.execute(any()) }
    }
}
