package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsList
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
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.inventory.EditorialUnitCommitResult
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaim
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryClaimKind
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryDerivation
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryEvidenceBatch
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryScope
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import java.nio.file.Files

/** Entirely synthetic editorial fixture; nothing is installed as an external producer. */
class EditorialEditionUnitCommitRepositoryImplTest {
    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var chapters: CanonicalChapterRepositoryImpl
    private lateinit var writer: EditorialEditionUnitCommitRepositoryImpl
    private var oldNativePath: String? = null
    private var nativeDir: java.nio.file.Path? = null

    @BeforeEach
    fun setUp(): Unit = runBlocking<Unit> {
        oldNativePath = System.getProperty("org.sqlite.lib.path")
        nativeDir = Files.createTempDirectory("tsuzuki-editorial-unit-test")
        val native = nativeDir!!.resolve("libsqlitejdbc.so")
        EditorialEditionUnitCommitRepositoryImplTest::class.java
            .getResourceAsStream("/org/sqlite/native/Linux/${architecture()}/libsqlitejdbc.so")!!
            .use { Files.copy(it, native) }
        native.toFile().setExecutable(true)
        System.setProperty("org.sqlite.lib.path", nativeDir.toString())
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
        database.tsuzuki_titlesQueries.insertTsuzukiTitle("title", "Synthetic", "SOURCE_ONLY", 1L, 1L)
        chapters = CanonicalChapterRepositoryImpl(database)
        writer = EditorialEditionUnitCommitRepositoryImpl(database, chapters, ChapterMutationGate())
    }

    @AfterEach
    fun tearDown() {
        if (::driver.isInitialized) driver.close()
        if (oldNativePath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", oldNativePath)
        }
        nativeDir?.toFile()?.deleteRecursively()
    }

    @Test
    fun `synthetic exact editorial units create chapters without reading providers and replay unchanged`(): Unit =
        runBlocking<Unit> {
            val selected = batch(claim("12.05"), claim("12.5"))
            val first = writer.commit(selected, grant(selected.scope))
            (first is EditorialUnitCommitResult.Applied) shouldBe true
            val written = first as EditorialUnitCommitResult.Applied
            written.inserted shouldBe 2
            val chaptersAfter = chapters.getByCanonicalTitleId("title")
            chaptersAfter.size shouldBe 2
            chaptersAfter.map { it.identity }.distinct().size shouldBe 2
            chaptersAfter.all { it.confirmation.name == "CONFIRMED" } shouldBe true
            val replay = writer.commit(selected, grant(selected.scope))
            replay shouldBe EditorialUnitCommitResult.Applied(written.chapterIds, 0)
            chapters.getByCanonicalTitleId("title") shouldBe chaptersAfter
            database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
                .awaitAsList().size shouldBe 2
        }

    @Test
    fun `second edition with same chapter number cannot cross-link or overwrite earlier edition`(): Unit =
        runBlocking<Unit> {
            val first = batch(claim("1"))
            val second = batch(claim("1")).copy(scope = scope("revised"))
            (writer.commit(first, grant(first.scope)) is EditorialUnitCommitResult.Applied) shouldBe true
            writer.commit(second, grant(second.scope)) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title").size shouldBe 1
            database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
                .awaitAsList().size shouldBe 1
        }

    @Test
    fun `host revocation after insertion rolls back both chapter and scoped ownership`(): Unit = runBlocking<Unit> {
        val selected = batch(claim("1"), claim("2"))
        var checks = 0
        val revoked = writer.commit(selected) { _, _ ->
            checks++
            if (checks == 1) selected.scope else null
        }
        revoked shouldBe EditorialUnitCommitResult.Refused
        chapters.getByCanonicalTitleId("title") shouldBe emptyList()
        database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
            .awaitAsList() shouldBe emptyList()
    }

    @Test
    fun `legacy count chapter cannot be silently rekeyed or reused by new editorial unit`(): Unit =
        runBlocking<Unit> {
            val old = CanonicalChapter(
                id = "tsuzuki:count:old-title:12",
                canonicalTitleId = "title",
                displayNumber = "12",
                type = CanonicalChapterType.REGULAR,
                baseNumber = 12,
                createdAt = 1L,
                updatedAt = 1L,
            )
            chapters.upsert(old)
            val requested = batch(claim("12.05"))
            writer.commit(requested, grant(requested.scope)) shouldBe EditorialUnitCommitResult.Refused
            chapters.getByCanonicalTitleId("title") shouldBe listOf(old)
        }

    @Test
    fun `malformed or self-declared grant cannot persist chapters`(): Unit = runBlocking<Unit> {
        val selected = batch(claim("1"))
        writer.commit(selected) { _, _ -> null } shouldBe EditorialUnitCommitResult.Refused
        writer.commit(selected, grant(scope("other-edition"))) shouldBe EditorialUnitCommitResult.Refused
        writer.commit(
            selected.copy(claims = listOf(claim("1").copy(derivation = InventoryDerivation.RELEASE_RECORD))),
            grant(selected.scope),
        ) shouldBe EditorialUnitCommitResult.Refused
        chapters.getByCanonicalTitleId("title") shouldBe emptyList()
    }

    @Test
    fun `title convergence refuses to destroy scoped ownership and rolls back`(): Unit = runBlocking<Unit> {
        val selected = batch(claim("1"))
        val written = writer.commit(selected, grant(selected.scope))
        (written is EditorialUnitCommitResult.Applied) shouldBe true
        database.tsuzuki_titlesQueries.insertTsuzukiTitle("target", "Target", "SOURCE_ONLY", 2L, 2L)
        val merging = runCatching { CanonicalTitleMergeRepositoryImpl(database).convergeTo("target", "title") }
        merging.isFailure shouldBe true
        chapters.getByCanonicalTitleId("title").size shouldBe 1
        database.tsuzuki_editorial_chapter_bindingsQueries.getTsuzukiEditorialBindingsByTitle("title")
            .awaitAsList().size shouldBe 1
    }

    private fun grant(approved: InventoryScope) =
        tachiyomi.domain.tsuzuki.chapter.inventory.HostEditorialEditionAuthorization { _, _ -> approved }

    private fun scope(edition: String = "original") = InventoryScope(
        canonicalTitleId = "title",
        producerId = "synthetic-editorial",
        externalWorkId = "work",
        editionKey = edition,
        language = "ja",
        workIdentityVerified = true,
        editionIdentityVerified = true,
    )

    private fun claim(label: String) = InventoryClaim(
        sourceRecordId = "publisher-unit-$label",
        label = label,
        volumeLabel = "1",
        language = "ja",
        kind = InventoryClaimKind.INDIVIDUAL,
        derivation = InventoryDerivation.PRIMARY_EDITORIAL,
        upstreamSourceId = "synthetic-publisher-feed",
        observedAt = 42L,
    )

    private fun batch(vararg claims: InventoryClaim) = InventoryEvidenceBatch(
        scope = scope(),
        pageKey = "first",
        observedAt = 42L,
        outcome = InventoryFetchOutcome.PARTIAL,
        claims = claims.toList(),
    )

    private fun architecture(): String = when (System.getProperty("os.arch").orEmpty().lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported architecture")
    }
}
