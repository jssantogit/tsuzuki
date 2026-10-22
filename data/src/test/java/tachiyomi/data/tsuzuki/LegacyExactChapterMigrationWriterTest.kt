package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterDecimalFraction
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterNumberingKind
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import java.nio.file.Files

class LegacyExactChapterMigrationWriterTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: CanonicalChapterRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-sqlite-jdbc")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        LegacyExactChapterMigrationWriterTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        Database.Schema.create(driver).await()
        database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "title-1",
            displayTitle = "Title",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        repository = CanonicalChapterRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (originalNativeLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalNativeLibraryPath)
        }
        nativeLibraryDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `writer cannot migrate without a separately audited remote state and explicit approval`() = runBlocking<Unit> {
        seedCandidate("legacy")
        val writer = LegacyExactChapterMigrationWriter(database)
        writer.promote("legacy", reviewerApproved = true) shouldBe
            LegacyExactMigrationWriteResult.Refused(LegacyExactMigrationWriteResult.Reason.REMOTE_SYNC_UNVERIFIED)
        writer.promote("legacy", remoteSyncStateAuditedAbsent = true) shouldBe
            LegacyExactMigrationWriteResult.Refused(LegacyExactMigrationWriteResult.Reason.REVIEW_REQUIRED)
        repository.getById("legacy")?.numberingKind shouldBe null
    }

    @Test
    fun `audited migration preserves original canonical id and evidence foreign keys`() = runBlocking<Unit> {
        seedCandidate("legacy")
        LegacyExactChapterMigrationWriter(database).promote(
            chapterId = "legacy",
            reviewerApproved = true,
            remoteSyncStateAuditedAbsent = true,
        ) shouldBe LegacyExactMigrationWriteResult.Promoted("legacy")

        val migrated = requireNotNull(repository.getById("legacy"))
        migrated.numberingKind shouldBe CanonicalChapterNumberingKind.DECIMAL
        migrated.decimalFraction shouldBe CanonicalChapterDecimalFraction(5, 2)
        migrated.baseNumber shouldBe 12
        migrated.part shouldBe 5
        database.tsuzuki_chapter_evidenceQueries
            .getTsuzukiChapterEvidenceByMappedChapter("legacy")
            .awaitAsList().size shouldBe 2
    }

    @Test
    fun `protected reader state prevents mutation even after an earlier positive preflight`() = runBlocking<Unit> {
        seedCandidate("legacy")
        val writer = LegacyExactChapterMigrationWriter(database)
        (
            writer.inspect("legacy", remoteSyncStateAuditedAbsent = true) is
                LegacyExactMigrationPreflight.ReviewOnly
            ) shouldBe true
        database.tsuzuki_chapter_progressQueries.upsertTsuzukiChapterProgress(
            canonicalChapterId = "legacy",
            read = true,
            lastPageRead = 12L,
            lastVariantId = null,
            updatedAt = 200L,
        )

        writer.promote("legacy", reviewerApproved = true, remoteSyncStateAuditedAbsent = true) shouldBe
            LegacyExactMigrationWriteResult.Refused(LegacyExactMigrationWriteResult.Reason.PREFLIGHT_BLOCKED)
        repository.getById("legacy")?.numberingKind shouldBe null
        database.tsuzuki_chapter_progressQueries
            .getTsuzukiChapterProgress("legacy")
            .awaitAsOneOrNull()?.last_page_read shouldBe 12L
    }

    @Test
    fun `contradictory evidence prevents a later approved write`() = runBlocking<Unit> {
        seedCandidate("legacy")
        val writer = LegacyExactChapterMigrationWriter(database)
        (
            writer.inspect("legacy", remoteSyncStateAuditedAbsent = true) is
                LegacyExactMigrationPreflight.ReviewOnly
            ) shouldBe true
        seedEvidence("legacy", "source-b", "Chapter 12.5")

        writer.promote("legacy", reviewerApproved = true, remoteSyncStateAuditedAbsent = true) shouldBe
            LegacyExactMigrationWriteResult.Refused(LegacyExactMigrationWriteResult.Reason.PREFLIGHT_BLOCKED)
        repository.getById("legacy")?.numberingKind shouldBe null
    }

    @Test
    fun `a second promotion is rejected without affecting exact identity`() = runBlocking<Unit> {
        seedCandidate("legacy")
        val writer = LegacyExactChapterMigrationWriter(database)
        writer.promote("legacy", reviewerApproved = true, remoteSyncStateAuditedAbsent = true) shouldBe
            LegacyExactMigrationWriteResult.Promoted("legacy")
        writer.promote("legacy", reviewerApproved = true, remoteSyncStateAuditedAbsent = true) shouldBe
            LegacyExactMigrationWriteResult.Refused(LegacyExactMigrationWriteResult.Reason.PREFLIGHT_BLOCKED)
        repository.getById("legacy")?.decimalFraction shouldBe CanonicalChapterDecimalFraction(5, 2)
    }

    private suspend fun seedCandidate(id: String) {
        repository.upsert(
            CanonicalChapter(
                id = id,
                canonicalTitleId = "title-1",
                displayNumber = "12.05",
                type = CanonicalChapterType.REGULAR,
                baseNumber = 12,
                part = 5,
            ),
        )
        seedEvidence(id, "source-a", "Chapter 12.05")
        seedEvidence(id, "source-b", "Chapter 12.05")
    }

    private suspend fun seedEvidence(chapterId: String, producerId: String, label: String) {
        ChapterEvidenceRepositoryImpl(database).upsert(
            ChapterEvidence(
                id = "proof-$producerId",
                canonicalTitleId = "title-1",
                producerKind = ProducerKind.ADDON,
                producerId = producerId,
                externalChapterKey = "stable-$producerId",
                rawLabel = label,
                rawNumber = null,
                volume = null,
                title = null,
                observedAt = 10L,
                confidence = 1.0,
                authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
            ),
            chapterId,
        )
    }

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }
}
