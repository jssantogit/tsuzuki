package eu.kanade.tachiyomi.provider.reading

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalTitleRepositoryImpl
import tachiyomi.data.tsuzuki.chapter.ChapterEvidenceRepositoryImpl
import tachiyomi.data.tsuzuki.integration.CanonicalMetadataSnapshotRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderWorkSourceAssociationRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderProvisionalCursorRepositoryImpl
import tachiyomi.data.tsuzuki.provider.MangaAllSourceSelectionRepositoryImpl
import java.nio.file.Files
import java.nio.file.Path

/**
 * TEST-ONLY genuine in-memory SQLite/SQLDelight using the production schema
 * and repository implementations. No user's library or installed database is read.
 */
internal class MangaAllDisposableSqlite private constructor(
    val database: Database,
    private val driver: SqlDriver,
    private val previousNativePath: String?,
    private val nativeDirectory: Path,
) : AutoCloseable {
    val titleRepository = CanonicalTitleRepositoryImpl(database)
    val metadataRepository = CanonicalMetadataSnapshotRepositoryImpl(database)
    val bindingRepository = ProviderReadingBindingRepositoryImpl(database)
    val workSourceRepository = ProviderWorkSourceAssociationRepositoryImpl(database)
    val provisionalCursorRepository = ProviderProvisionalCursorRepositoryImpl(database)
    val sourceSelectionRepository = MangaAllSourceSelectionRepositoryImpl(database)
    val chapterRepository = CanonicalChapterRepositoryImpl(database)
    val evidenceRepository = ChapterEvidenceRepositoryImpl(database)

    override fun close() {
        driver.close()
        if (previousNativePath == null) {
            System.clearProperty("org.sqlite.lib.path")
        } else {
            System.setProperty("org.sqlite.lib.path", previousNativePath)
        }
        nativeDirectory.toFile().deleteRecursively()
    }

    companion object {
        suspend fun open(): MangaAllDisposableSqlite {
            val previous = System.getProperty("org.sqlite.lib.path")
            val directory = Files.createTempDirectory("mangaall-sqldelight-ci-")
            try {
                val arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
                    "aarch64", "arm64" -> "aarch64"
                    "x86_64", "amd64" -> "x86_64"
                    "x86", "i386", "i486", "i586", "i686" -> "x86"
                    else -> error("Unsupported CI architecture")
                }
                val name = "libsqlitejdbc.so"
                val native = directory.resolve(name)
                val resource = "/org/sqlite/native/Linux/$arch/$name"
                requireNotNull(MangaAllDisposableSqlite::class.java.getResourceAsStream(resource)) {
                    "SQLite JDBC native library missing for CI"
                }.use { input -> Files.copy(input, native) }
                check(native.toFile().setExecutable(true)) { "SQLite CI driver cannot execute" }
                System.setProperty("org.sqlite.lib.path", directory.toString())
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                try {
                    driver.execute(null, "PRAGMA foreign_keys = ON", 0).await()
                    Database.Schema.create(driver).await()
                    val db = Database(
                        driver = driver,
                        historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                        mangasAdapter = Mangas.Adapter(
                            genreAdapter = StringListColumnAdapter,
                            update_strategyAdapter = UpdateStrategyColumnAdapter,
                            memoAdapter = MemoColumnAdapter,
                        ),
                        chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                    )
                    return MangaAllDisposableSqlite(db, driver, previous, directory)
                } catch (error: Throwable) {
                    driver.close()
                    throw error
                }
            } catch (error: Throwable) {
                if (previous == null) {
                    System.clearProperty("org.sqlite.lib.path")
                } else {
                    System.setProperty("org.sqlite.lib.path", previous)
                }
                directory.toFile().deleteRecursively()
                throw error
            }
        }
    }
}
