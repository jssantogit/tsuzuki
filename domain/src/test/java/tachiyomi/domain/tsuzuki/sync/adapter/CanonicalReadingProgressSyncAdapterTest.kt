package tachiyomi.domain.tsuzuki.sync.adapter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.sync.model.SyncRevision
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource
import tachiyomi.domain.tsuzuki.sync.service.ChapterSyncEvidenceRepository
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CanonicalReadingProgressSyncAdapterTest {

    @Test
    fun `progress maps across different local chapter and variant ids by portable keys`() = runTest {
        val chapterA = chapter(id = "chapter-local-a")
        val chapterB = chapter(id = "chapter-local-b")
        val variantA = variant(id = "variant-local-a", chapterId = chapterA.id)
        val variantB = variant(id = "variant-local-b", chapterId = chapterB.id)

        val readingA = FakeReadingRepository().apply {
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = chapterA.id,
                    read = false,
                    lastPageRead = 5,
                    lastVariantId = variantA.id,
                    updatedAt = 100,
                ),
            )
        }
        val readingB = FakeReadingRepository()

        val exported = adapter(
            chapters = FakeChapterRepository(listOf(chapterA), listOf(variantA)),
            reading = readingA,
        ).exportDocument()

        adapter(
            chapters = FakeChapterRepository(listOf(chapterB), listOf(variantB)),
            reading = readingB,
        ).applyDocument(exported)

        readingB.getProgress(chapterB.id) shouldBe CanonicalChapterProgress(
            canonicalChapterId = chapterB.id,
            read = false,
            lastPageRead = 5,
            lastVariantId = variantB.id,
            updatedAt = 100,
        )
        readingB.getProgress(chapterA.id) shouldBe null
    }

    @Test
    fun `unknown chapter without stable evidence stays local and marks adapter unportable`() = runTest {
        val unknown = CanonicalChapter(
            id = "unknown-local",
            canonicalTitleId = "title-1",
            displayNumber = "?",
            type = CanonicalChapterType.UNKNOWN,
        )
        val reading = FakeReadingRepository().apply {
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = unknown.id,
                    lastPageRead = 2,
                    updatedAt = 10,
                ),
            )
        }
        val adapter = adapter(
            chapters = FakeChapterRepository(listOf(unknown), emptyList()),
            reading = reading,
        )

        val exported = adapter.exportDocument()

        exported.records shouldBe emptyMap()
        adapter.hasUnportableLocalState() shouldBe true
    }

    @Test
    fun `two same-number chapters in distinct volumes cannot overwrite exported progress`() = runTest {
        val first = chapter("volume-1-chapter-12").copy(volume = 1)
        val second = chapter("volume-2-chapter-12").copy(volume = 2)
        val reading = FakeReadingRepository().apply {
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = first.id,
                    lastPageRead = 3,
                    updatedAt = 10,
                ),
            )
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = second.id,
                    lastPageRead = 7,
                    updatedAt = 20,
                ),
            )
        }
        val sync = adapter(
            chapters = FakeChapterRepository(listOf(first, second), emptyList()),
            reading = reading,
        )

        shouldThrow<IllegalStateException> { sync.exportDocument() }
        sync.hasUnportableLocalState() shouldBe true
    }

    @Test
    fun `one tracked volume chapter is not portable while a sibling shares its identity`() = runTest {
        val tracked = chapter("tracked-volume-1").copy(volume = 1)
        val sibling = chapter("untracked-volume-2").copy(volume = 2)
        val reading = FakeReadingRepository().apply {
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = tracked.id,
                    lastPageRead = 4,
                    updatedAt = 10,
                ),
            )
        }
        val sync = adapter(
            chapters = FakeChapterRepository(listOf(tracked, sibling), emptyList()),
            reading = reading,
        )

        shouldThrow<IllegalStateException> { sync.exportDocument() }
        sync.hasUnportableLocalState() shouldBe true
    }

    @Test
    fun `opt-in v2 can transport exact decimal progress across devices`() = runTest {
        val source = chapter("exact-source").copy(
            displayNumber = "12.05",
            part = 5,
            volume = 2,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )
        val destination = source.copy(id = "exact-destination")
        val readingA = FakeReadingRepository().apply {
            upsertProgress(
                CanonicalChapterProgress(
                    canonicalChapterId = source.id,
                    lastPageRead = 9,
                    updatedAt = 100,
                ),
            )
        }
        val readingB = FakeReadingRepository()
        val exported = adapter(
            chapters = FakeChapterRepository(listOf(source), emptyList()),
            reading = readingA,
            exactVersion2Enabled = true,
        ).exportDocument()

        exported.schemaVersion shouldBe 2
        exported.kind shouldBe tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind.READING_PROGRESS_V2
        exported.records.size shouldBe 1
        adapter(
            chapters = FakeChapterRepository(listOf(destination), emptyList()),
            reading = readingB,
            exactVersion2Enabled = true,
        ).applyDocument(exported)

        readingB.getProgress(destination.id)?.lastPageRead shouldBe 9L
        readingB.getProgress(source.id) shouldBe null
    }

    @Test
    fun `v2 cannot assign a five-hundredths checkpoint to a five-tenths chapter`() = runTest {
        val exact = chapter("exact-005").copy(
            displayNumber = "12.05",
            part = 5,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )
        val wrongFraction = exact.copy(
            id = "wrong-fraction",
            displayNumber = "12.5",
            decimalFraction = CanonicalChapterDecimalFraction(5, 1),
        )
        val readingA = FakeReadingRepository().apply {
            upsertProgress(CanonicalChapterProgress(exact.id, lastPageRead = 6, updatedAt = 1))
        }
        val readingB = FakeReadingRepository()
        val exported = adapter(
            chapters = FakeChapterRepository(listOf(exact), emptyList()),
            reading = readingA,
            exactVersion2Enabled = true,
        ).exportDocument()

        shouldThrow<IllegalArgumentException> {
            adapter(
                chapters = FakeChapterRepository(listOf(wrongFraction), emptyList()),
                reading = readingB,
                exactVersion2Enabled = true,
            ).applyDocument(exported)
        }
        readingB.getProgress(wrongFraction.id) shouldBe null
    }

    @Test
    fun `v2 exports same numbered chapters in distinct volumes without collisions`() = runTest {
        val first = chapter("vol-one").copy(volume = 1)
        val second = chapter("vol-two").copy(volume = 2)
        val reading = FakeReadingRepository().apply {
            upsertProgress(CanonicalChapterProgress(first.id, lastPageRead = 2, updatedAt = 1))
            upsertProgress(CanonicalChapterProgress(second.id, lastPageRead = 4, updatedAt = 2))
        }
        val sync = adapter(
            chapters = FakeChapterRepository(listOf(first, second), emptyList()),
            reading = reading,
            exactVersion2Enabled = true,
        )
        sync.hasUnportableLocalState() shouldBe false
        sync.exportDocument().records.size shouldBe 2
    }

    @Test
    fun `a numbered v2 chapter with an untracked identical sibling is not portable`() = runTest {
        val tracked = chapter("tracked-exact").copy(
            displayNumber = "12.05",
            part = 5,
            volume = 1,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )
        val sibling = tracked.copy(id = "untracked-exact")
        val reading = FakeReadingRepository().apply {
            upsertProgress(CanonicalChapterProgress(tracked.id, lastPageRead = 4, updatedAt = 5))
        }
        val sync = adapter(
            chapters = FakeChapterRepository(listOf(tracked, sibling), emptyList()),
            reading = reading,
            exactVersion2Enabled = true,
        )

        sync.hasUnportableLocalState() shouldBe true
        shouldThrow<IllegalStateException> { sync.exportDocument() }
    }

    @Test
    fun `protocol versions never interpret each others progress records`() = runTest {
        val exact = chapter("exact").copy(
            displayNumber = "12.05",
            part = 5,
            numberingKind = CanonicalChapterNumberingKind.DECIMAL,
            decimalFraction = CanonicalChapterDecimalFraction(5, 2),
        )
        val reading = FakeReadingRepository().apply {
            upsertProgress(CanonicalChapterProgress(exact.id, lastPageRead = 6, updatedAt = 1))
        }
        val v2 = adapter(FakeChapterRepository(listOf(exact), emptyList()), reading, exactVersion2Enabled = true)
        val v1 = adapter(FakeChapterRepository(listOf(exact), emptyList()), FakeReadingRepository())
        val version2Document = v2.exportDocument()
        shouldThrow<IllegalArgumentException> { v1.applyDocument(version2Document) }
        val version1Document = v1.exportDocument()
        shouldThrow<IllegalArgumentException> { v2.applyDocument(version1Document) }
    }

    @Test
    fun `a malformed later progress record cannot partially apply a valid earlier record`() = runTest {
        val first = chapter("aa-first")
        val second = chapter("zz-second").copy(baseNumber = 13, displayNumber = "13")
        for (version2 in listOf(false, true)) {
            val sourceReading = FakeReadingRepository().apply {
                upsertProgress(CanonicalChapterProgress(first.id, lastPageRead = 7, updatedAt = 1))
                upsertProgress(CanonicalChapterProgress(second.id, lastPageRead = 9, updatedAt = 2))
            }
            val chapters = FakeChapterRepository(listOf(first, second), emptyList())
            val exported = adapter(chapters, sourceReading, exactVersion2Enabled = version2).exportDocument()
            val records = exported.records.toMutableMap()
            val badId = records.keys.sorted().last()
            val badRecord = records.getValue(badId)
            records[badId] = badRecord.copy(
                fields = JsonObject(badRecord.fields + ("lastPageRead" to JsonPrimitive("invalid"))),
            )
            val destinationReading = FakeReadingRepository()

            shouldThrow<Exception> {
                adapter(chapters, destinationReading, exactVersion2Enabled = version2)
                    .applyDocument(exported.copy(records = records))
            }
            destinationReading.getProgress(first.id) shouldBe null
            destinationReading.getProgress(second.id) shouldBe null
        }
    }

    @Test
    fun `tampered portable record fields cannot redirect a checkpoint to another chapter`() = runTest {
        val first = chapter("aa-first")
        val second = chapter("zz-second").copy(baseNumber = 13, displayNumber = "13")
        for (version2 in listOf(false, true)) {
            val chapters = FakeChapterRepository(listOf(first, second), emptyList())
            val sourceReading = FakeReadingRepository().apply {
                upsertProgress(CanonicalChapterProgress(first.id, lastPageRead = 7, updatedAt = 1))
            }
            val valid = adapter(chapters, sourceReading, exactVersion2Enabled = version2).exportDocument()
            val otherReading = FakeReadingRepository().apply {
                upsertProgress(CanonicalChapterProgress(second.id, lastPageRead = 9, updatedAt = 2))
            }
            val secondKey = adapter(chapters, otherReading, exactVersion2Enabled = version2)
                .exportDocument().records.values.single().fields.getValue("chapterKey")

            val sourceRecord = valid.records.values.single()
            val forged = sourceRecord.copy(
                fields = JsonObject(sourceRecord.fields + ("chapterKey" to secondKey)),
            )
            val destinationReading = FakeReadingRepository()
            shouldThrow<IllegalArgumentException> {
                adapter(chapters, destinationReading, exactVersion2Enabled = version2)
                    .applyDocument(valid.copy(records = mapOf(forged.id to forged)))
            }
            destinationReading.getProgress(first.id) shouldBe null
            destinationReading.getProgress(second.id) shouldBe null
        }
    }

    private fun adapter(
        chapters: CanonicalChapterRepository,
        reading: CanonicalReadingRepository,
        exactVersion2Enabled: Boolean = false,
    ) = CanonicalReadingProgressSyncAdapter(
        titleSource = CanonicalTitleSyncSource { listOf(title()) },
        chapterRepository = chapters,
        readingRepository = reading,
        evidenceRepository = ChapterSyncEvidenceRepository { null },
        revisionSource = FakeRevisionSource(),
        clock = object : SyncClock {
            override fun nowEpochMillis(): Long = 100L
        },
        exactVersion2Enabled = exactVersion2Enabled,
    )

    private fun title() = CanonicalTitle(
        id = "title-1",
        displayTitle = "Example",
        identityState = CanonicalIdentityState.RESOLVED,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun chapter(id: String) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = "12",
        type = CanonicalChapterType.REGULAR,
        baseNumber = 12,
    )

    private fun variant(
        id: String,
        chapterId: String,
    ) = ChapterVariant(
        id = id,
        canonicalChapterId = chapterId,
        sourceMappingId = "mapping-local",
        sourceId = 10,
        sourceChapterId = "chapter-12",
        rawName = "Chapter 12",
    )

    private class FakeRevisionSource : SyncRevisionSource {
        override val deviceId = "device-test"
        private var sequence = 0L

        override fun nextRevision() = SyncRevision(
            deviceId = deviceId,
            sequence = ++sequence,
        )
    }

    private class FakeChapterRepository(
        private val chapters: List<CanonicalChapter>,
        variants: List<ChapterVariant>,
    ) : CanonicalChapterRepository {
        private val variants = variants.toMutableList()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            chapters.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String) =
            flowOf(chapters.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String) = chapters.find { it.id == id }

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ) = variants.find {
            it.sourceId == sourceId && it.sourceChapterId == sourceChapterId
        }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String) =
            variants.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String) =
            variants.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) = error("not used")

        override suspend fun upsertVariant(variant: ChapterVariant) = error("not used")

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) = error("not used")
    }

    private class FakeReadingRepository : CanonicalReadingRepository {
        private val progress = mutableMapOf<String, CanonicalChapterProgress>()

        override suspend fun getProgress(canonicalChapterId: String) =
            progress[canonicalChapterId]

        override fun observeProgress(canonicalChapterId: String): Flow<CanonicalChapterProgress?> =
            flowOf(progress[canonicalChapterId])

        override suspend fun getProgressByCanonicalTitleId(canonicalTitleId: String) =
            progress.values.toList()

        override fun observeProgressByCanonicalTitleId(
            canonicalTitleId: String,
        ): Flow<List<CanonicalChapterProgress>> = flowOf(progress.values.toList())

        override suspend fun upsertProgress(progress: CanonicalChapterProgress) {
            this.progress[progress.canonicalChapterId] = progress
        }

        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? =
            null

        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit

        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) {
            upsertProgress(progress)
        }
    }
}
