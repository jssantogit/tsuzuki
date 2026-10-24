package tachiyomi.domain.tsuzuki.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.interactor.GetCanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

class GetCanonicalReadingStartTest {

    @Test
    fun `most recently active unread chapter wins`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            listOf(chapter("chapter-1", 1), chapter("chapter-2", 2), chapter("chapter-3", 3)),
        )
        val reading = FakeCanonicalReadingRepository(
            listOf(
                progress("chapter-1", read = true, updatedAt = 100L),
                progress("chapter-2", read = false, updatedAt = 300L),
                progress("chapter-3", read = false, updatedAt = 200L),
            ),
        )

        GetCanonicalReadingStart(chapters, reading).execute("title-1") shouldBe
            CanonicalReadingStart.Ready("chapter-2")
    }

    @Test
    fun `equal progress timestamps prefer the later canonical volume after numbering resets`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            listOf(
                chapter("volume-1-chapter-1", 1).copy(volume = 1),
                chapter("volume-1-chapter-2", 2).copy(volume = 1),
                chapter("volume-2-chapter-1", 1).copy(volume = 2),
                chapter("volume-2-chapter-2", 2).copy(volume = 2),
            ),
        )
        val reading = FakeCanonicalReadingRepository(
            listOf(
                progress("volume-1-chapter-2", read = false, updatedAt = 300L),
                progress("volume-2-chapter-1", read = false, updatedAt = 300L),
            ),
        )

        // Chapter 2 (volume 1) must not outrank chapter 1 (volume 2)
        // merely because its numeric legacy sort key is larger.
        GetCanonicalReadingStart(chapters, reading).execute("title-1") shouldBe
            CanonicalReadingStart.Ready("volume-2-chapter-1")
    }

    @Test
    fun `newer checkpoint wins even when its volume precedes a newer numbered volume`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            listOf(
                chapter("volume-2-chapter-1", 1).copy(volume = 2),
                chapter("volume-1-chapter-2", 2).copy(volume = 1),
                chapter("volume-1-chapter-1", 1).copy(volume = 1),
            ),
        )
        val reading = FakeCanonicalReadingRepository(
            listOf(
                progress("volume-1-chapter-2", read = false, updatedAt = 400L),
                progress("volume-2-chapter-1", read = false, updatedAt = 300L),
            ),
        )

        GetCanonicalReadingStart(chapters, reading).execute("title-1") shouldBe
            CanonicalReadingStart.Ready("volume-1-chapter-2")
    }

    @Test
    fun `first unread uses canonical volume order when repository rows are shuffled`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            listOf(
                chapter("volume-2-chapter-2", 2).copy(volume = 2),
                chapter("volume-1-chapter-2", 2).copy(volume = 1),
                chapter("volume-2-chapter-1", 1).copy(volume = 2),
                chapter("volume-1-chapter-1", 1).copy(volume = 1),
            ),
        )
        val reading = FakeCanonicalReadingRepository(
            listOf(progress("volume-1-chapter-1", read = true, updatedAt = 200L)),
        )

        GetCanonicalReadingStart(chapters, reading).execute("title-1") shouldBe
            CanonicalReadingStart.Ready("volume-1-chapter-2")
    }

    @Test
    fun `first unread chapter starts when there is no active progress`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            listOf(chapter("chapter-1", 1), chapter("chapter-2", 2), chapter("chapter-3", 3)),
        )
        val reading = FakeCanonicalReadingRepository(
            listOf(progress("chapter-1", read = true, updatedAt = 100L)),
        )

        GetCanonicalReadingStart(chapters, reading).execute("title-1") shouldBe
            CanonicalReadingStart.Ready("chapter-2")
    }

    @Test
    fun `last canonical chapter is returned when all chapters are read`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            listOf(chapter("chapter-1", 1), chapter("chapter-2", 2)),
        )
        val reading = FakeCanonicalReadingRepository(
            listOf(
                progress("chapter-1", read = true, updatedAt = 100L),
                progress("chapter-2", read = true, updatedAt = 200L),
            ),
        )

        GetCanonicalReadingStart(chapters, reading).execute("title-1") shouldBe
            CanonicalReadingStart.Ready("chapter-2")
    }

    @Test
    fun `empty canonical inventory is unavailable`() = runTest {
        val result = GetCanonicalReadingStart(
            FakeCanonicalChapterRepository(emptyList()),
            FakeCanonicalReadingRepository(emptyList()),
        ).execute("title-1")

        result shouldBe CanonicalReadingStart.Unavailable("title-1")
    }

    @Test
    fun `irregular loaded identities remain the reading gateway targets`() = runTest {
        val realChapters = listOf(
            chapter("ten", 10),
            chapter("fraction", 10).copy(displayNumber = "10.5", part = 5),
            chapter("special", 11).copy(displayNumber = "Special", type = CanonicalChapterType.SPECIAL),
            chapter("gap", 642),
        )
        val inventory = FakeCanonicalChapterRepository(realChapters)

        GetCanonicalReadingStart(inventory, FakeCanonicalReadingRepository(emptyList()))
            .execute("title-1") shouldBe CanonicalReadingStart.Ready("ten")
        GetCanonicalReadingStart(
            inventory,
            FakeCanonicalReadingRepository(listOf(progress("fraction", read = false, updatedAt = 100))),
        ).execute("title-1") shouldBe CanonicalReadingStart.Ready("fraction")
        GetCanonicalReadingStart(
            inventory,
            FakeCanonicalReadingRepository(realChapters.map { progress(it.id, read = true, updatedAt = 100) }),
        ).execute("title-1") shouldBe CanonicalReadingStart.Ready("gap")
    }

    private fun chapter(id: String, number: Int) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = number.toString(),
        type = CanonicalChapterType.REGULAR,
        baseNumber = number,
        confidence = 1.0,
        createdAt = number.toLong(),
        updatedAt = number.toLong(),
    )

    private fun progress(
        id: String,
        read: Boolean,
        updatedAt: Long,
    ) = CanonicalChapterProgress(
        canonicalChapterId = id,
        read = read,
        lastPageRead = 0L,
        updatedAt = updatedAt,
    )

    private class FakeCanonicalChapterRepository(
        private val chapters: List<CanonicalChapter>,
    ) : CanonicalChapterRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters.firstOrNull { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) = Unit
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = Unit
    }

    private class FakeCanonicalReadingRepository(
        private val progressRows: List<CanonicalChapterProgress>,
    ) : CanonicalReadingRepository {
        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? =
            progressRows.firstOrNull { it.canonicalChapterId == canonicalChapterId }

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            MutableStateFlow(progressRows.firstOrNull { it.canonicalChapterId == canonicalChapterId })

        override suspend fun getProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<CanonicalChapterProgress> = progressRows

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) = Unit
        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit
        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }
}
