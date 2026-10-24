package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
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
import tachiyomi.domain.tsuzuki.library.model.ExternalLibraryMembership
import java.nio.file.Files

class ExternalLibraryOwnershipGateTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: ExternalLibraryRepositoryImpl
    private var originalLibraryPath: String? = null
    private var nativeDirectory: java.nio.file.Path? = null

    @BeforeEach
    fun setUp() = runBlocking {
        originalLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeDirectory = Files.createTempDirectory("tsuzuki-external-account-jdbc")
        val library = nativeDirectory!!.resolve("libsqlitejdbc.so")
        ExternalLibraryOwnershipGateTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
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
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = "A-title",
            displayTitle = "A private work",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
        database.tsuzuki_external_library_membershipsQueries.upsertTsuzukiExternalLibraryMembership(
            canonicalTitleId = "A-title",
            provider = "mal",
            externalId = "123",
            listKey = "mal:reading",
            listTitle = "A's private list",
            selectionGroup = null,
            status = "READING",
            remoteStatus = "reading",
            progress = 3.0,
            score = null,
            listedAt = null,
            syncedAt = 100L,
            coverUrl = null,
        )
        repository = ExternalLibraryRepositoryImpl(database)
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (originalLibraryPath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", originalLibraryPath)
        }
        nativeDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `unowned legacy memberships are invisible and remain unchanged after account switch`() = runBlocking {
        repository.hasVerifiedOwnerScope shouldBe false
        repository.observeAll().first() shouldBe emptyList()
        repository.getProviderIds() shouldBe emptySet()
        repository.clearProvider("mal")
        val stored = database.tsuzuki_external_library_membershipsQueries
            .getAllTsuzukiExternalLibraryMemberships().awaitAsList()
        stored.single().canonical_title_id shouldBe "A-title"
        stored.single().list_title shouldBe "A's private list"
    }

    @Test
    fun `unverified B refresh cannot overwrite A private membership`() = runBlocking {
        shouldThrow<IllegalStateException> {
            repository.replaceProvider(
                "mal",
                listOf(
                    ExternalLibraryMembership(
                        canonicalTitleId = "A-title",
                        provider = "mal",
                        externalId = "777",
                        listKey = "mal:reading",
                        status = null,
                        remoteStatus = null,
                        progress = null,
                        score = null,
                        syncedAt = 200L,
                    ),
                ),
            )
        }
        val stored = database.tsuzuki_external_library_membershipsQueries
            .getAllTsuzukiExternalLibraryMemberships().awaitAsList()
        stored.single().external_id shouldBe "123"
        stored.single().synced_at shouldBe 100L
    }
}
