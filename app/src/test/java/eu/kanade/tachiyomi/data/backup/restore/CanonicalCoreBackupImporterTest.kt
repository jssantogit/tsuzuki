package eu.kanade.tachiyomi.data.backup.restore

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.backup.create.CanonicalCoreBackupExporter
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCoreChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCoreChapterOverride
import eu.kanade.tachiyomi.data.backup.models.BackupCoreChapterUpdateState
import eu.kanade.tachiyomi.data.backup.models.BackupCoreCollection
import eu.kanade.tachiyomi.data.backup.models.BackupCoreContentPreference
import eu.kanade.tachiyomi.data.backup.models.BackupCoreContinueReadingState
import eu.kanade.tachiyomi.data.backup.models.BackupCoreFolder
import eu.kanade.tachiyomi.data.backup.models.BackupCoreHistory
import eu.kanade.tachiyomi.data.backup.models.BackupCoreIntegrationSetting
import eu.kanade.tachiyomi.data.backup.models.BackupCoreLibraryCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCoreLibraryEntry
import eu.kanade.tachiyomi.data.backup.models.BackupCoreList
import eu.kanade.tachiyomi.data.backup.models.BackupCoreProgress
import eu.kanade.tachiyomi.data.backup.models.BackupCoreProviderBinding
import eu.kanade.tachiyomi.data.backup.models.BackupCoreReaderPreference
import eu.kanade.tachiyomi.data.backup.models.BackupCoreSourceMapping
import eu.kanade.tachiyomi.data.backup.models.BackupCoreSourcePreference
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitle
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitleArtwork
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitleFormat
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitleName
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
    fun `collection folders lists and provider bindings survive SQLite export restore`() = runBlocking {
        importer.restoreCore(sectionWithCollectionsAndBindings())
        val restoredCollection = database.tsuzuki_collectionsQueries
            .getAllTsuzukiCollections().awaitAsList().single()
        restoredCollection.id shouldBe "collection-1"
        restoredCollection.title shouldBe "Favorite manga"
        val folder = database.tsuzuki_collectionsQueries
            .getAllTsuzukiFoldersByCollection("collection-1").awaitAsList().single()
        folder.id shouldBe "folder-1"
        folder.parent_folder_id shouldBe null
        val list = database.tsuzuki_collectionsQueries
            .getAllTsuzukiListsByCollection("collection-1").awaitAsList().single()
        list.folder_id shouldBe "folder-1"
        list.provider_id shouldBe "kitsu"
        val binding = database.tsuzuki_provider_bindingsQueries
            .getTsuzukiProviderBindingsByTitle("title-1").awaitAsList().single()
        binding.external_work_id shouldBe "remote-title-1"

        val titles = CanonicalTitleRepositoryImpl(database)
        val exporter = CanonicalCoreBackupExporter(
            titleSource = CanonicalTitleSyncSource { titles.getAllAsFlow().first() },
            titleRepository = titles,
            libraryRepository = CanonicalLibraryRepositoryImpl(database),
            chapterRepository = CanonicalChapterRepositoryImpl(database),
            sourceMappingRepository = SourceTitleMappingRepositoryImpl(database),
            readingRepository = CanonicalReadingRepositoryImpl(database),
            database = database,
            json = Json,
        )
        val decoded = Json.decodeFromString(
            BackupCanonicalCore.serializer(),
            exporter.export().payloadJson,
        )
        decoded.validate()
        decoded.collections.single().id shouldBe "collection-1"
        decoded.folders.single().id shouldBe "folder-1"
        decoded.lists.single().id shouldBe "list-1"
        decoded.providerBindings.single().externalWorkId shouldBe "remote-title-1"
    }

    @Test
    fun `collection cycle or list in another folder fails before database writes`() = runBlocking {
        val good = coreWithCollectionsAndBindings()
        val cycle = good.copy(
            folders = good.folders.map { it.copy(parentFolderId = "folder-1") },
        )
        shouldThrow<IllegalArgumentException> {
            importer.restoreCore(coreSection(cycle))
        }
        val badList = good.copy(
            lists = good.lists.map { it.copy(collectionId = "other") },
        )
        shouldThrow<IllegalArgumentException> {
            importer.restoreCore(coreSection(badList))
        }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `child folder is restored after parent even when backup is out of order`() = runBlocking {
        val core = coreWithCollectionsAndBindings()
        val child = core.folders.single().copy(
            id = "folder-child",
            parentFolderId = "folder-1",
            title = "Nested folder",
        )
        importer.restoreCore(coreSection(core.copy(folders = listOf(child) + core.folders)))

        val folders = database.tsuzuki_collectionsQueries
            .getAllTsuzukiFoldersByCollection("collection-1").awaitAsList()
        folders.size shouldBe 2
        folders.single { it.id == "folder-child" }.parent_folder_id shouldBe "folder-1"
    }

    @Test
    fun `existing collections cannot be silently overwritten by a canonical restore`() = runBlocking {
        database.tsuzuki_collectionsQueries.upsertTsuzukiCollection(
            id = "existing",
            title = "Personal",
            origin = "USER",
            sortOrder = 0,
            schemaVersion = 2,
            revision = 1,
            createdAt = 1,
            updatedAt = 1,
            deletedAt = null,
        )

        shouldThrow<IllegalStateException> {
            importer.restoreCore(sectionWithCollectionsAndBindings())
        }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
        database.tsuzuki_collectionsQueries.getAllTsuzukiCollections()
            .awaitAsList().single().id shouldBe "existing"
    }

    @Test
    fun `reader preference and durable chapter override survive export restore`() = runBlocking {
        val core = coreWithCollectionsAndBindings().copy(
            readerPreferences = listOf(
                BackupCoreReaderPreference("title-1", true, 50),
            ),
            chapterOverrides = listOf(
                BackupCoreChapterOverride(
                    id = "override-1",
                    canonicalTitleId = "title-1",
                    canonicalChapterKey = "chapter:1",
                    sourceId = null,
                    sourceTitleUrl = null,
                    sourceChapterId = null,
                    kind = "MARK_MISSING",
                    payloadJson = """{"reason":"user"}""",
                    schemaVersion = 1,
                    revision = 2,
                    createdAt = 30,
                    updatedAt = 40,
                    deletedAt = null,
                ),
            ),
        )
        importer.restoreCore(coreSection(core))
        database.tsuzuki_reader_preferencesQueries
            .getAllTsuzukiReaderPreferences().awaitAsList()
            .single().automatic_fallback shouldBe true
        database.tsuzuki_chapter_overridesQueries
            .getAllTsuzukiChapterOverrides().awaitAsList()
            .single().payload_json shouldBe """{"reason":"user"}"""

        val titles = CanonicalTitleRepositoryImpl(database)
        val exporter = CanonicalCoreBackupExporter(
            titleSource = CanonicalTitleSyncSource { titles.getAllAsFlow().first() },
            titleRepository = titles,
            libraryRepository = CanonicalLibraryRepositoryImpl(database),
            chapterRepository = CanonicalChapterRepositoryImpl(database),
            sourceMappingRepository = SourceTitleMappingRepositoryImpl(database),
            readingRepository = CanonicalReadingRepositoryImpl(database),
            database = database,
            json = Json,
        )
        val exported = Json.decodeFromString(BackupCanonicalCore.serializer(), exporter.export().payloadJson)
        exported.readerPreferences.single().automaticFallback shouldBe true
        exported.chapterOverrides.single().payloadJson shouldBe """{"reason":"user"}"""
    }

    @Test
    fun `unknown chapter override target is rejected before database mutation`() = runBlocking {
        val core = coreWithCollectionsAndBindings().copy(
            chapterOverrides = listOf(
                BackupCoreChapterOverride(
                    id = "override-bad",
                    canonicalTitleId = "unknown-title",
                    canonicalChapterKey = null,
                    sourceId = null,
                    sourceTitleUrl = null,
                    sourceChapterId = null,
                    kind = "CUSTOM",
                    payloadJson = "{}",
                    schemaVersion = 1,
                    revision = 0,
                    createdAt = 1,
                    updatedAt = 1,
                    deletedAt = null,
                ),
            ),
        )
        shouldThrow<IllegalArgumentException> { importer.restoreCore(coreSection(core)) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `source and content preferences and title observations preserve the original values`() = runBlocking {
        val input = coreWithCollectionsAndBindings().copy(
            contentPreferences = listOf(
                BackupCoreContentPreference("title-1", "provider-a", "pt-BR", 10),
            ),
            sourcePreferences = listOf(
                BackupCoreSourcePreference("pt-BR", 42, 0),
            ),
            continueReadingStates = listOf(
                BackupCoreContinueReadingState("title-1", 1234),
            ),
            chapterUpdateStates = listOf(
                BackupCoreChapterUpdateState("chapter-1", "title-1", 10, 20),
            ),
            titleArtwork = listOf(
                BackupCoreTitleArtwork("title-1", "kitsu", "cover-url", null, 11),
            ),
            titleFormats = listOf(
                BackupCoreTitleFormat("title-1", "kitsu", "MANGA", 12),
            ),
            titleNames = listOf(
                BackupCoreTitleName("title-1", "kitsu", "synonym", "Alias", 13),
            ),
        )
        importer.restoreCore(coreSection(input))

        val titles = CanonicalTitleRepositoryImpl(database)
        val exported = CanonicalCoreBackupExporter(
            titleSource = CanonicalTitleSyncSource { titles.getAllAsFlow().first() },
            titleRepository = titles,
            libraryRepository = CanonicalLibraryRepositoryImpl(database),
            chapterRepository = CanonicalChapterRepositoryImpl(database),
            sourceMappingRepository = SourceTitleMappingRepositoryImpl(database),
            readingRepository = CanonicalReadingRepositoryImpl(database),
            database = database,
            json = Json,
        ).export()
        val decoded = Json.decodeFromString(BackupCanonicalCore.serializer(), exported.payloadJson)
        decoded.contentPreferences shouldBe input.contentPreferences
        decoded.sourcePreferences shouldBe input.sourcePreferences
        decoded.continueReadingStates shouldBe input.continueReadingStates
        decoded.chapterUpdateStates shouldBe input.chapterUpdateStates
        decoded.titleArtwork shouldBe input.titleArtwork
        decoded.titleFormats shouldBe input.titleFormats
        decoded.titleNames shouldBe input.titleNames
    }

    @Test
    fun `conflicting source preference order or orphan chapter update prevents all writes`() = runBlocking {
        val input = coreWithCollectionsAndBindings()
        val duplicateOrder = input.copy(
            sourcePreferences = listOf(
                BackupCoreSourcePreference("pt-BR", 42, 0),
                BackupCoreSourcePreference("pt-BR", 43, 0),
            ),
        )
        shouldThrow<IllegalArgumentException> { importer.restoreCore(coreSection(duplicateOrder)) }
        val orphan = input.copy(
            chapterUpdateStates = listOf(
                BackupCoreChapterUpdateState("chapter-1", "different-title", 10, null),
            ),
        )
        shouldThrow<IllegalArgumentException> { importer.restoreCore(coreSection(orphan)) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `library categories restore by stable attributes rather than source device row ID`() = runBlocking {
        val core = coreWithCollectionsAndBindings().copy(
            libraryCategories = listOf(
                BackupCoreLibraryCategory("title-1", "Favorites", 5, 7),
            ),
        )
        importer.restoreCore(coreSection(core))
        val membership = database.tsuzuki_library_categoriesQueries
            .getAllTsuzukiLibraryCategories().awaitAsList().single()
        membership.canonical_title_id shouldBe "title-1"
        membership.name shouldBe "Favorites"
        membership.category_order shouldBe 5L
        membership.flags shouldBe 7L
        val titles = CanonicalTitleRepositoryImpl(database)
        val exported = CanonicalCoreBackupExporter(
            titleSource = CanonicalTitleSyncSource { titles.getAllAsFlow().first() },
            titleRepository = titles,
            libraryRepository = CanonicalLibraryRepositoryImpl(database),
            chapterRepository = CanonicalChapterRepositoryImpl(database),
            sourceMappingRepository = SourceTitleMappingRepositoryImpl(database),
            readingRepository = CanonicalReadingRepositoryImpl(database),
            database = database,
            json = Json,
        ).export()
        val decoded = Json.decodeFromString(BackupCanonicalCore.serializer(), exported.payloadJson)
        decoded.libraryCategories.map { it.copy(sourceCategoryId = null) } shouldBe core.libraryCategories
        importer.restoreCore(coreSection(core))
        database.tsuzuki_library_categoriesQueries
            .getAllTsuzukiLibraryCategories().awaitAsList().size shouldBe 1
    }

    @Test
    fun `same category name with conflicting original identity cannot merge during restore`() = runBlocking {
        val base = coreWithCollectionsAndBindings()
        val broken = base.copy(
            titles = base.titles + BackupCoreTitle("title-2", "Other", "SOURCE_ONLY", 1, 2),
            libraryCategories = listOf(
                BackupCoreLibraryCategory("title-1", "Read", 1, 0, sourceCategoryId = 12),
                BackupCoreLibraryCategory("title-2", "Read", 1, 0, sourceCategoryId = 13),
            ),
        )
        shouldThrow<IllegalArgumentException> { importer.restoreCore(coreSection(broken)) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `default Mihon category maps only to the reserved system row zero`() = runBlocking {
        val core = coreWithCollectionsAndBindings().copy(
            libraryCategories = listOf(
                BackupCoreLibraryCategory("title-1", "", -1, 0, sourceCategoryId = 0),
            ),
        )
        importer.restoreCore(coreSection(core))
        database.tsuzuki_library_categoriesQueries
            .getAllTsuzukiLibraryCategories().awaitAsList().single().category_id shouldBe 0L
    }

    @Test
    fun `integration settings preserve toggles but never export tokens or nested secrets`() = runBlocking {
        importer.restoreCore(section())
        database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
            integrationId = "kitsu",
            enabled = true,
            configJson = """{"language":"pt-BR","access_token":"private","nested":{"api_key":"secret"}}""",
            updatedAt = 99,
        )
        val titles = CanonicalTitleRepositoryImpl(database)
        val archived = CanonicalCoreBackupExporter(
            titleSource = CanonicalTitleSyncSource { titles.getAllAsFlow().first() },
            titleRepository = titles,
            libraryRepository = CanonicalLibraryRepositoryImpl(database),
            chapterRepository = CanonicalChapterRepositoryImpl(database),
            sourceMappingRepository = SourceTitleMappingRepositoryImpl(database),
            readingRepository = CanonicalReadingRepositoryImpl(database),
            database = database,
            json = Json,
        ).export()
        val decoded = Json.decodeFromString(BackupCanonicalCore.serializer(), archived.payloadJson)
        decoded.integrationSettings shouldBe listOf(
            BackupCoreIntegrationSetting("kitsu", true, """{"language":"pt-BR","nested":{}}""", 99),
        )
        archived.payloadJson.contains("private") shouldBe false
        archived.payloadJson.contains("secret") shouldBe false
    }

    @Test
    fun `crafted backup cannot restore authentication credentials in integration config`() = runBlocking {
        val core = coreWithCollectionsAndBindings().copy(
            integrationSettings = listOf(
                BackupCoreIntegrationSetting("kitsu", true, """{"access_token":"stolen"}""", 30),
            ),
        )
        shouldThrow<IllegalArgumentException> { importer.restoreCore(coreSection(core)) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `unknown provider runtime payload blocks an incomplete portable export`() = runBlocking {
        importer.restoreCore(section())
        database.tsuzuki_content_bindingsQueries.upsertTsuzukiContentBinding(
            id = "provider-runtime-1",
            canonicalTitleId = "title-1",
            addonId = "custom-provider",
            providerTitleKey = "remote-id",
            matchConfidence = 0.8,
            verifiedByUser = true,
            availability = "AVAILABLE",
            runtimePayload = byteArrayOf(1, 2, 3),
            createdAt = 1,
            updatedAt = 2,
        )
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
        shouldThrow<IllegalStateException> { exporter.export() }
    }

    @Test
    fun `ambiguous existing categories abort without importing canonical records`() = runBlocking {
        database.categoriesQueries.insert(name = "Favorites", order = 1, flags = 0)
        database.categoriesQueries.insert(name = "Favorites", order = 2, flags = 0)
        val core = coreWithCollectionsAndBindings().copy(
            libraryCategories = listOf(
                BackupCoreLibraryCategory("title-1", "Favorites", 1, 0),
            ),
        )
        shouldThrow<IllegalStateException> { importer.restoreCore(coreSection(core)) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `colliding provider binding external identities are rejected`() = runBlocking {
        val good = coreWithCollectionsAndBindings()
        val conflicting = good.copy(
            providerBindings = good.providerBindings + good.providerBindings.single().copy(id = "binding-2"),
        )
        shouldThrow<IllegalArgumentException> { importer.restoreCore(coreSection(conflicting)) }
        database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList() shouldBe emptyList()
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

    private fun sectionWithCollectionsAndBindings() =
        coreSection(coreWithCollectionsAndBindings())

    private fun coreWithCollectionsAndBindings(): BackupCanonicalCore {
        val core = Json.decodeFromString(BackupCanonicalCore.serializer(), section().payloadJson)
        return core.copy(
            collections = listOf(
                BackupCoreCollection("collection-1", "Favorite manga", "USER", 0, 2, 1, 10, 20, null),
            ),
            folders = listOf(
                BackupCoreFolder("folder-1", "collection-1", null, "All", "USER", 0, 2, 1, 10, 20, null),
            ),
            lists = listOf(
                BackupCoreList(
                    id = "list-1",
                    collectionId = "collection-1",
                    folderId = "folder-1",
                    title = "Reading",
                    providerId = "kitsu",
                    queryJson = null,
                    sort = "DEFAULT",
                    sortDirection = null,
                    layoutType = null,
                    sortOrder = 0,
                    enabled = true,
                    origin = "USER",
                    schemaVersion = 2,
                    revision = 1,
                    createdAt = 10,
                    updatedAt = 20,
                    deletedAt = null,
                ),
            ),
            providerBindings = listOf(
                BackupCoreProviderBinding(
                    id = "binding-1",
                    canonicalTitleId = "title-1",
                    providerId = "provider.example",
                    facetId = "reading",
                    externalWorkId = "remote-title-1",
                    verification = "VERIFIED",
                    availability = "AVAILABLE",
                    createdAt = 10,
                    updatedAt = 20,
                ),
            ),
        )
    }

    private fun coreSection(core: BackupCanonicalCore) = BackupCanonicalSection.fromJson(
        BackupCanonicalCore.SECTION_KEY,
        Json.encodeToString(BackupCanonicalCore.serializer(), core),
    )

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
