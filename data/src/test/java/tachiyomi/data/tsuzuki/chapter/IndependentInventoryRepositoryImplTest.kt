package tachiyomi.data.tsuzuki.chapter

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import io.kotest.assertions.throwables.shouldThrow
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
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaim
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryStoredBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryStoredClaim
import java.nio.file.Files
import java.nio.file.Path

class IndependentInventoryRepositoryImplTest {
    @Test
    fun `SQLite stores community and user supplied chapter candidates without promoting them`() = runBlocking<Unit> {
        val source = inventoryScope(editionKey = "japanese-original", language = "ja")
        val community = claim(source, "wiki-rev-123-ch1", "1", 10L).copy(
            derivation = InventoryDerivation.COMMUNITY_CURATED,
        )
        val local = claim(source, "epub-isbn-ch2", "2", 10L).copy(
            derivation = InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
        )

        repository.saveBatch(batch(scope = source, claims = listOf(community, local)))
        repository.byTitle(TITLE_ID).map { it.claim.derivation }.toSet() shouldBe setOf(
            InventoryDerivation.COMMUNITY_CURATED,
            InventoryDerivation.USER_SUPPLIED_EDITION_TOC,
        )
        repository.batchesByTitle(TITLE_ID).single().itemCount shouldBe 2
    }


    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var repository: IndependentInventoryRepositoryImpl
    private var originalNativeLibraryPath: String? = null
    private var nativeLibraryDirectory: Path? = null
    private var databaseDirectory: Path? = null

    @BeforeEach
    fun setUp() = runBlocking<Unit> {
        originalNativeLibraryPath = System.getProperty("org.sqlite.lib.path")
        nativeLibraryDirectory = Files.createTempDirectory("tsuzuki-inventory-sqlite-native")
        val nativeLibrary = nativeLibraryDirectory!!.resolve("libsqlitejdbc.so")
        IndependentInventoryRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${nativeLibraryArchitecture()}/libsqlitejdbc.so")!!
            .use { input -> Files.copy(input, nativeLibrary) }
        nativeLibrary.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeLibraryDirectory.toString())

        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        database = createDatabase(driver, createSchema = true)
        insertTitle(database, TITLE_ID)
        repository = IndependentInventoryRepositoryImpl(database)
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
        databaseDirectory?.toFile()?.deleteRecursively()
    }

    @Test
    fun `saveBatch rolls back earlier claims if a later claim violates a constraint`() =
        runBlocking<Unit> {
            val stable = batch(
                pageKey = "existing-page",
                claims = listOf(claim(inventoryScope(), "existing", "known", 10L)),
            )
            repository.saveBatch(stable)

            driver.execute(
                null,
                """
                    CREATE TRIGGER reject_inventory_claim
                    BEFORE INSERT ON tsuzuki_independent_inventory_claims
                    WHEN NEW.source_record_id = 'reject'
                    BEGIN
                        SELECT RAISE(ABORT, 'rejected test claim');
                    END
                """.trimIndent(),
                0,
            ).await()

            val attempted = batch(
                pageKey = "rejected-page",
                observedAt = 20L,
                claims = listOf(
                    claim(inventoryScope(), "temporary", "temporary", 20L),
                    claim(inventoryScope(), "reject", "invalid", 20L),
                ),
            )
            shouldThrow<Exception> { repository.saveBatch(attempted) }

            repository.byTitle(TITLE_ID).map { it.claim.sourceRecordId } shouldBe listOf("existing")
            repository.batchesByTitle(TITLE_ID).map { it.pageKey } shouldBe listOf("existing-page")
        }

    @Test
    fun `foreign key prevents saving an orphan inventory batch`() = runBlocking<Unit> {
        val orphanScope = inventoryScope(canonicalTitleId = "missing-title")

        shouldThrow<Exception> {
            repository.saveBatch(
                batch(
                    scope = orphanScope,
                    claims = listOf(claim(orphanScope, "orphan", "orphan", 10L)),
                ),
            )
        }

        repository.byTitle("missing-title").size shouldBe 0
        repository.batchesByTitle("missing-title").size shouldBe 0
    }

    @Test
    fun `replay is idempotent and older claim and page observations cannot replace newer data`() = runBlocking<Unit> {
        val newer = batch(
            pageKey = "page-1",
            observedAt = 20L,
            outcome = InventoryFetchOutcome.PARTIAL,
            claims = listOf(claim(inventoryScope(), "release-1", "new label", 20L)),
            nextCursor = "page-2",
        )
        repository.saveBatch(newer)
        repository.saveBatch(newer)

        repository.byTitle(TITLE_ID).size shouldBe 1
        repository.batchesByTitle(TITLE_ID).size shouldBe 1

        repository.saveBatch(
            newer.copy(
                observedAt = 10L,
                outcome = InventoryFetchOutcome.PARTIAL,
                claims = listOf(claim(newer.scope, "release-1", "stale label", 10L)),
                nextCursor = null,
            ),
        )

        val storedClaim = repository.byTitle(TITLE_ID).single()
        storedClaim.claim.label shouldBe "new label"
        storedClaim.claim.observedAt shouldBe 20L

        val storedPage = repository.batchesByTitle(TITLE_ID).single()
        storedPage.observedAt shouldBe 20L
        storedPage.outcome shouldBe InventoryFetchOutcome.PARTIAL
        storedPage.nextCursor shouldBe "page-2"
    }

    @Test
    fun `failed and empty page refreshes retain claims from prior observations`() = runBlocking<Unit> {
        val original = batch(
            pageKey = "page-1",
            observedAt = 10L,
            outcome = InventoryFetchOutcome.PARTIAL,
            claims = listOf(claim(inventoryScope(), "release-1", "retained", 10L)),
            nextCursor = "page-2",
        )
        repository.saveBatch(original)
        repository.saveBatch(
            original.copy(
                observedAt = 20L,
                outcome = InventoryFetchOutcome.FAILED,
                claims = emptyList(),
                nextCursor = null,
            ),
        )
        repository.saveBatch(
            batch(
                scope = original.scope,
                pageKey = "page-2",
                observedAt = 30L,
                outcome = InventoryFetchOutcome.PARTIAL,
                claims = emptyList(),
            ),
        )

        repository.byTitle(TITLE_ID).single().claim.label shouldBe "retained"
        val pages = repository.batchesByTitle(TITLE_ID)
        pages.size shouldBe 2
        pages.single { it.pageKey == "page-1" }.outcome shouldBe InventoryFetchOutcome.FAILED
        pages.single { it.pageKey == "page-2" }.itemCount shouldBe 0
    }

    @Test
    fun `edition and language scopes remain isolated for matching source and page keys`() = runBlocking<Unit> {
        val scopes = listOf(
            inventoryScope(editionKey = "edition-a", language = "en") to "claim-a",
            inventoryScope(editionKey = "edition-b", language = "en") to "claim-b",
            inventoryScope(editionKey = "edition-a", language = "ja") to "claim-c",
        )
        scopes.forEach { (claimScope, label) ->
            repository.saveBatch(
                batch(
                    scope = claimScope,
                    pageKey = "same-page",
                    claims = listOf(claim(claimScope, "same-record", label, 10L)),
                ),
            )
        }

        val claims = repository.byTitle(TITLE_ID)
        claims.size shouldBe 3
        claims.map { it.scope.editionKey to it.scope.language }.toSet() shouldBe setOf(
            "edition-a" to "en",
            "edition-b" to "en",
            "edition-a" to "ja",
        )
        claims.map { it.claim.label }.toSet() shouldBe setOf("claim-a", "claim-b", "claim-c")

        val pages = repository.batchesByTitle(TITLE_ID)
        pages.size shouldBe 3
        pages.map { it.scope.editionKey to it.scope.language }.toSet() shouldBe setOf(
            "edition-a" to "en",
            "edition-b" to "en",
            "edition-a" to "ja",
        )
    }

    @Test
    fun `inventory claims and page metadata persist after closing and reopening a file database`() =
        runBlocking<Unit> {
            driver.close()
            databaseDirectory = Files.createTempDirectory("tsuzuki-inventory-file-db")
            val url = "jdbc:sqlite:${databaseDirectory!!.resolve("inventory.sqlite").toAbsolutePath()}"
            driver = JdbcSqliteDriver(url)
            database = createDatabase(driver, createSchema = true)
            insertTitle(database, TITLE_ID)
            repository = IndependentInventoryRepositoryImpl(database)

            val persisted = batch(
                pageKey = "page-1",
                observedAt = 42L,
                claims = listOf(claim(inventoryScope(), "release-1", "persisted", 42L)),
                nextCursor = "page-2",
            )
            repository.saveBatch(persisted)

            driver.close()
            driver = JdbcSqliteDriver(url)
            database = createDatabase(driver, createSchema = false)
            repository = IndependentInventoryRepositoryImpl(database)

            repository.byTitle(TITLE_ID) shouldBe listOf(
                InventoryStoredClaim(persisted.scope, persisted.claims.single()),
            )
            repository.batchesByTitle(TITLE_ID) shouldBe listOf(
                InventoryStoredBatch(
                    scope = persisted.scope,
                    pageKey = persisted.pageKey,
                    observedAt = persisted.observedAt,
                    outcome = persisted.outcome,
                    itemCount = persisted.claims.size,
                    declaredTotal = persisted.declaredTotal,
                    nextCursor = persisted.nextCursor,
                ),
            )
        }

    private suspend fun createDatabase(driver: SqlDriver, createSchema: Boolean): Database {
        driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
        if (createSchema) Database.Schema.create(driver).await()
        return Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
    }

    private suspend fun insertTitle(database: Database, id: String) {
        database.tsuzuki_titlesQueries.insertTsuzukiTitle(
            id = id,
            displayTitle = "Inventory title",
            identityState = "SOURCE_ONLY",
            createdAt = 1L,
            updatedAt = 1L,
        )
    }

    private fun inventoryScope(
        canonicalTitleId: String = TITLE_ID,
        editionKey: String? = null,
        language: String? = "pt-br",
    ) = InventoryScope(
        canonicalTitleId = canonicalTitleId,
        producerId = "mangadex",
        externalWorkId = "verified-work",
        editionKey = editionKey,
        language = language,
        workIdentityVerified = true,
        editionIdentityVerified = editionKey != null,
    )

    private fun claim(
        scope: InventoryScope,
        sourceRecordId: String,
        label: String?,
        observedAt: Long,
    ) = InventoryClaim(
        sourceRecordId = sourceRecordId,
        label = label,
        volumeLabel = null,
        language = scope.language,
        kind = InventoryClaimKind.INDIVIDUAL,
        derivation = InventoryDerivation.RELEASE_RECORD,
        upstreamSourceId = "mangadex",
        observedAt = observedAt,
    )

    private fun batch(
        scope: InventoryScope = inventoryScope(),
        pageKey: String = "page-1",
        observedAt: Long = 10L,
        outcome: InventoryFetchOutcome = InventoryFetchOutcome.PARTIAL,
        claims: List<InventoryClaim> = emptyList(),
        declaredTotal: Int? = null,
        nextCursor: String? = null,
    ) = InventoryEvidenceBatch(
        scope = scope,
        pageKey = pageKey,
        observedAt = observedAt,
        outcome = outcome,
        claims = claims,
        declaredTotal = declaredTotal,
        nextCursor = nextCursor,
    )

    private fun nativeLibraryArchitecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported test host architecture: ${System.getProperty("os.arch").orEmpty()}")
    }

    private companion object {
        const val TITLE_ID = "inventory-title"
    }
}
