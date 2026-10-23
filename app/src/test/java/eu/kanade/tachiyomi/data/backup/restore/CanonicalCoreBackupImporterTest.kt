package eu.kanade.tachiyomi.data.backup.restore

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.CanonicalCoreBackupExporter
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCoreChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCoreHistory
import eu.kanade.tachiyomi.data.backup.models.BackupCoreLibraryEntry
import eu.kanade.tachiyomi.data.backup.models.BackupCoreProgress
import eu.kanade.tachiyomi.data.backup.models.BackupCoreSourceMapping
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitle
import eu.kanade.tachiyomi.data.backup.models.BackupCoreVariant
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalLibraryRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.SourceTitleMappingRepositoryImpl
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource
import java.nio.file.Files

class CanonicalCoreBackupImporterTest {

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var importer: CanonicalCoreBackupImporter
    private var previousLibraryPath: String? = null
    private var nativeDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        previousLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeDirectory = Files.createTempDirectory("tsuzuki-backup-sqlite")
        val library = nativeDirectory!!.resolve("libsqlitejdbc.so")
        CanonicalCoreBackupImporterTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${architecture()}/libsqlitejdbc.so")!!
            .use { Files.copy(it, library) }
        library.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeDirectory.toString())

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
        importer = CanonicalCoreBackupImporter(database, Json)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (previousLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", previousLibraryPath)
        }
        nativeDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `clean restore keeps exact chapter history and remains idempotent after a local edit`() = runBlocking {
        val section = section()
        importer.restoreCore(section)

        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList().size shouldBe 1
        database.tsuzuki_chapter_historyQueries
            .getTsuzukiChapterHistory("chapter-1").awaitAsOne().time_read shouldBe 55L
        database.tsuzuki_chapter_progressQueries
            .getTsuzukiChapterProgress("chapter-1").awaitAsOne().last_page_read shouldBe 7L

        // A second restore must NOT reset reading done since the first restore.
        database.tsuzuki_chapter_progressQueries.upsertTsuzukiChapterProgress(
            canonicalChapterId = "chapter-1",
            read = true,
            lastPageRead = 19L,
            lastVariantId = "variant-1",
            updatedAt = 300L,
        )
        importer.restoreCore(section)

        database.tsuzuki_chapter_progressQueries
            .getTsuzukiChapterProgress("chapter-1").awaitAsOne().last_page_read shouldBe 19L
        database.tsuzuki_chapter_historyQueries
            .getTsuzukiChapterHistory("chapter-1").awaitAsOne().time_read shouldBe 55L
        database.tsuzuki_backup_restore_receiptsQueries
            .getTsuzukiBackupRestoreReceipt(section.sha256).awaitAsOne().snapshot_hash shouldBe section.sha256
    }

    @Test
    fun `canonical export of restored SQLite graph preserves its local-only state`() = runBlocking {
        importer.restoreCore(section())
        val titleRepo = CanonicalTitleRepositoryImpl(database)
        val exporter = CanonicalCoreBackupExporter(
            titleSource = CanonicalTitleSyncSource { titleRepo.getAllAsFlow().first() },
            titleRepository = titleRepo,
            libraryRepository = CanonicalLibraryRepositoryImpl(database),
            chapterRepository = CanonicalChapterRepositoryImpl(database),
            sourceMappingRepository = SourceTitleMappingRepositoryImpl(database),
            readingRepository = CanonicalReadingRepositoryImpl(database),
            database = database,
            json = Json,
        )
        val section = exporter.export()
        section.validate()
        val restored = Json.decodeFromString(BackupCanonicalCore.serializer(), section.payloadJson)
        restored.validate()

        restored.titles.single().identityState shouldBe "SOURCE_ONLY"
        restored.chapters.single().confirmation shouldBe "PROVISIONAL"
        restored.sourceMappings.single().id shouldBe "mapping-1"
        restored.variants.single().sourceChapterId shouldBe "remote-chapter-1"
        restored.progress.single().lastPageRead shouldBe 7L
        restored.history.single().totalReadDuration shouldBe 55L
    }

    @Test
    fun `foreign device operational Mihon IDs are not rebound to unrelated local rows`() = runBlocking {
        importer.restoreCore(section())

        database.tsuzuki_source_mappingsQueries
            .getTsuzukiSourceMappingsByTitle("title-1").awaitAsList()
            .single().mihon_manga_id shouldBe null
        val restored = database.tsuzuki_chapter_variantsQueries
            .getTsuzukiChapterVariantsByCanonicalChapter("chapter-1").awaitAsList()
            .single()
        restored.mihon_manga_id shouldBe null
        restored.mihon_chapter_id shouldBe null
    }

    @Test
    fun `injected failure before receipt rolls back all canonical writes`() = runBlocking {
        shouldThrow<IllegalStateException> {
            importer.restoreCore(section()) { error("forced crash before commit") }
        }

        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
        database.tsuzuki_backup_restore_receiptsQueries
            .listTsuzukiBackupRestoreReceipts().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `existing unrelated canonical library refuses silent import and leaves data unchanged`() = runBlocking {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "original",
            displayTitle = "Owned locally",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )

        shouldThrow<IllegalStateException> { importer.restoreCore(section()) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList().single().id shouldBe "original"
        database.tsuzuki_backup_restore_receiptsQueries
            .listTsuzukiBackupRestoreReceipts().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `malformed core never begins a partial import`() = runBlocking {
        val invalid = BackupCanonicalSection.fromJson(
            BackupCanonicalCore.SECTION_KEY,
            Json.encodeToString(
                BackupCanonicalCore.serializer(),
                BackupCanonicalCore(
                    library = listOf(BackupCoreLibraryEntry("missing", "READING", true, 1, 2)),
                ),
            ),
        )
        shouldThrow<IllegalArgumentException> { importer.restoreCore(invalid) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
    }

    private fun section(): BackupCanonicalSection {
        val core = BackupCanonicalCore(
            titles = listOf(BackupCoreTitle("title-1", "Example", "SOURCE_ONLY", 1, 2)),
            sourceMappings = listOf(
                BackupCoreSourceMapping(
                    id = "mapping-1",
                    canonicalTitleId = "title-1",
                    mihonMangaId = 501L,
                    sourceId = 42,
                    sourceUrl = "https://example.invalid/manga",
                    language = "pt-BR",
                    matchConfidence = 0.8,
                    verifiedByUser = false,
                    availability = "AVAILABLE",
                    preferredOverride = false,
                    createdAt = 1,
                    updatedAt = 2,
                ),
            ),
            library = listOf(BackupCoreLibraryEntry("title-1", "READING", true, 2, 3)),
            chapters = listOf(
                BackupCoreChapter(
                    id = "chapter-1",
                    canonicalTitleId = "title-1",
                    displayNumber = "1",
                    volume = null,
                    title = "Opening",
                    type = "REGULAR",
                    baseNumber = 1,
                    part = null,
                    alphaSuffix = null,
                    confidence = 0.7,
                    createdAt = 2,
                    updatedAt = 3,
                    confirmation = "PROVISIONAL",
                ),
            ),
            variants = listOf(
                BackupCoreVariant(
                    id = "variant-1",
                    canonicalChapterId = "chapter-1",
                    sourceMappingId = "mapping-1",
                    sourceId = 42,
                    mihonMangaId = 501L,
                    mihonChapterId = 502L,
                    sourceChapterId = "remote-chapter-1",
                    sourceChapterUrl = null,
                    language = "pt-BR",
                    scanlationGroup = null,
                    version = null,
                    releaseDate = null,
                    rawName = "Chapter 1",
                    rawNumberHint = 1.0,
                    rawSourceOrder = null,
                    rawSourceMetadataJson = "{}",
                    createdAt = 2,
                    updatedAt = 3,
                ),
            ),
            progress = listOf(BackupCoreProgress("chapter-1", false, 7, "variant-1", 10)),
            history = listOf(BackupCoreHistory("chapter-1", "variant-1", 11, 55)),
        )
        return BackupCanonicalSection.fromJson(
            BackupCanonicalCore.SECTION_KEY,
            Json.encodeToString(BackupCanonicalCore.serializer(), core),
        )
    }

    private fun architecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported SQLite test host architecture")
    }
}
