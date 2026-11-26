package eu.kanade.tachiyomi.provider.runtime

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.tsuzuki.CanonicalChapterRepositoryImpl
import tachiyomi.data.tsuzuki.CanonicalReadingRepositoryImpl
import tachiyomi.data.tsuzuki.provider.ProviderReadingBindingRepositoryImpl
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingAvailability
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingVerification
import tachiyomi.domain.tsuzuki.provider.reading.ProviderReadingBinding
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import java.util.UUID

/** Real repository restart using only fixture observations and a disposable database. */
internal object AdvancedExtractionPersistenceContract {
    suspend fun run(context: Context, refs: List<ProviderBindingRef>) {
        assertEquals(2, refs.size)
        assertEquals(2, refs.map { it.providerId }.distinct().size)
        val name = "advanced-contract-" + UUID.randomUUID() + ".db"
        val dbFile = context.getDatabasePath(name)
        check(dbFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        fun open() = AndroidxSqliteDriver(
            driver = BundledSQLiteDriver(),
            databaseType = AndroidxSqliteDatabaseType.FileProvider { dbFile.absolutePath },
            schema = Database.Schema,
            configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
        )
        fun database(driver: app.cash.sqldelight.db.SqlDriver) = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        val bindings = refs.mapIndexed { index, ref ->
            ProviderReadingBinding(
                id = "fixture-binding-$index",
                canonicalTitleId = "fixture-title",
                ref = ref,
                verification = ProviderBindingVerification.EXACT,
                availability = ProviderBindingAvailability.AVAILABLE,
                createdAt = 1,
                updatedAt = 1,
            )
        }
        val chapter = CanonicalChapter(
            id = "fixture-chapter",
            canonicalTitleId = "fixture-title",
            displayNumber = "1",
            type = CanonicalChapterType.REGULAR,
            baseNumber = 1,
            createdAt = 1,
            updatedAt = 1,
        )
        val progress = CanonicalChapterProgress(chapter.id, read = false, lastPageRead = 0, updatedAt = 2)
        try {
            val driver = open()
            try {
                val db = database(driver)
                db.tsuzuki_titlesQueries.insertTsuzukiTitle(
                    id = "fixture-title",
                    displayTitle = "Fixture Work",
                    identityState = "SOURCE_ONLY",
                    createdAt = 1,
                    updatedAt = 1,
                )
                val repository = ProviderReadingBindingRepositoryImpl(db)
                bindings.forEach { assertTrue(repository.insertIfAbsent(it)) }
                val conflict = bindings.first().copy(
                    id = "different-edition",
                    ref = bindings.first().ref.copy(externalWorkId = "unverified-other-edition"),
                )
                assertFalse(repository.insertIfAbsent(conflict))
                CanonicalChapterRepositoryImpl(db).upsert(chapter)
                CanonicalReadingRepositoryImpl(db).upsertProgress(progress)
            } finally {
                driver.close()
            }
            val reopened = open()
            try {
                val db = database(reopened)
                val repository = ProviderReadingBindingRepositoryImpl(db)
                assertEquals(bindings.toSet(), repository.getByTitle("fixture-title").toSet())
                assertEquals(chapter, CanonicalChapterRepositoryImpl(db).getById(chapter.id))
                assertEquals(progress, CanonicalReadingRepositoryImpl(db).getProgress(chapter.id))
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
