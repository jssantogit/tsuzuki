package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.CanonicalStandaloneArchive
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSnapshot
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.download.DownloadCache
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.data.Database

class BackupRestorerCanonicalGateTest {

    private val backupDecoder = mockk<BackupDecoder>()
    private val canonicalImporter = mockk<CanonicalCoreBackupImporter>(relaxed = true)
    private val notifier = mockk<BackupNotifier>(relaxed = true)
    private val categoriesRestorer = mockk<CategoriesRestorer>(relaxed = true)
    private val preferenceRestorer = mockk<PreferenceRestorer>(relaxed = true)
    private val mangaRestorer = mockk<MangaRestorer>(relaxed = true)
    private val extensionStoreRestorer = mockk<ExtensionStoreRestorer>(relaxed = true)
    private val database = mockk<Database>(relaxed = true)
    private val downloadCache = mockk<DownloadCache>(relaxed = true)
    private val uri = mockk<Uri>()

    private fun restorer() = BackupRestorer(
        notifier = notifier,
        isSync = false,
        context = mockk<Context>(relaxed = true),
        database = database,
        downloadCache = downloadCache,
        categoriesRestorer = categoriesRestorer,
        preferenceRestorer = preferenceRestorer,
        extensionStoreRestorer = extensionStoreRestorer,
        mangaRestorer = mangaRestorer,
        backupDecoder = backupDecoder,
        canonicalCoreBackupImporter = canonicalImporter,
    )

    @Test
    fun `canonical archive is refused before legacy library restoration`(): Unit = runBlocking {
        val section = BackupCanonicalSection.fromJson("tsuzuki.core.v1", "{}")
        coEvery { backupDecoder.decode(uri) } returns Backup(
            backupManga = emptyList(),
            canonicalSnapshot = BackupCanonicalSnapshot(sections = listOf(section)),
        )

        val failure = shouldThrow<IllegalStateException> {
            restorer().restore(uri, RestoreOptions())
        }

        failure.message.orEmpty() shouldContain "canonical"
        verify(exactly = 0) { notifier.showRestoreComplete(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { downloadCache.invalidateCache() }
    }

    @Test
    fun `even empty declared canonical snapshots cannot report successful legacy restore`(): Unit = runBlocking {
        coEvery { backupDecoder.decode(uri) } returns Backup(
            backupManga = emptyList(),
            canonicalSnapshot = BackupCanonicalSnapshot(),
        )

        shouldThrow<IllegalStateException> {
            restorer().restore(
                uri,
                RestoreOptions(
                    libraryEntries = false,
                    categories = false,
                    appSettings = false,
                    extensionStores = false,
                    sourceSettings = false,
                ),
            )
        }
        verify(exactly = 0) { notifier.showRestoreComplete(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `canonical-only archive restores exactly one validated section and skips legacy writers`(): Unit = runBlocking {
        val core = BackupCanonicalSection.fromJson(
            "tsuzuki.core.v1",
            Json.encodeToString(BackupCanonicalCore.serializer(), BackupCanonicalCore()),
        )
        coEvery { backupDecoder.decode(uri) } returns CanonicalStandaloneArchive.compose(core)
        restorer().restore(uri, RestoreOptions())
        coVerify(exactly = 1) { canonicalImporter.restoreCore(core, any()) }
        coVerify(exactly = 0) { categoriesRestorer.invoke(any()) }
        verify(exactly = 1) { notifier.showRestoreComplete(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a mixed archive cannot mutate either legacy or canonical data`(): Unit = runBlocking {
        val core = BackupCanonicalSection.fromJson(
            "tsuzuki.core.v1",
            Json.encodeToString(BackupCanonicalCore.serializer(), BackupCanonicalCore()),
        )
        val mixed = CanonicalStandaloneArchive.compose(core).copy(
            backupPreferences = listOf(
                eu.kanade.tachiyomi.data.backup.models.BackupPreference(
                    "settings",
                    eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue("original"),
                ),
            ),
        )
        coEvery { backupDecoder.decode(uri) } returns mixed
        shouldThrow<IllegalArgumentException> { restorer().restore(uri, RestoreOptions()) }
        coVerify(exactly = 0) { canonicalImporter.restoreCore(any(), any()) }
        coVerify(exactly = 0) { categoriesRestorer.invoke(any()) }
        verify(exactly = 0) { notifier.showRestoreComplete(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `legacy archive without canonical field remains restorable`(): Unit = runBlocking {
        coEvery { backupDecoder.decode(uri) } returns Backup(backupManga = emptyList())
        restorer().restore(
            uri,
            RestoreOptions(
                libraryEntries = false,
                categories = false,
                appSettings = false,
                extensionStores = false,
                sourceSettings = false,
            ),
        )

        verify(exactly = 1) { notifier.showRestoreComplete(any(), any(), any(), any(), any()) }
    }
}
