package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupArchiveByteLimit
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

@AssistedInject
class BackupCreator(
    @Assisted private val isAutoBackup: Boolean,
    private val context: Context,
    private val parser: ProtoBuf,
    private val getFavorites: GetFavorites,
    private val backupPreferences: BackupPreferences,
    private val mangaRepository: MangaRepository,
    private val categoriesBackupCreator: CategoriesBackupCreator,
    private val mangaBackupCreator: MangaBackupCreator,
    private val preferenceBackupCreator: PreferenceBackupCreator,
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator,
    private val sourcesBackupCreator: SourcesBackupCreator,
    private val backupFileValidator: BackupFileValidator,
) {
    @AssistedFactory
    fun interface Factory {
        fun create(isAutoBackup: Boolean): BackupCreator
    }

    suspend fun backup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        var createdByThisBackup = false
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Only the automatic output created by this attempt is ours
                // to clean up after failure; never delete a manual URI.
                val created = dir?.createFile(getFilename())
                createdByThisBackup = created != null
                created
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            val backupManga = backupMangas(getFavorites.await() + nonFavoriteManga, options)

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga),
                backupPreferences = backupAppPreferences(options),
                backupExtensionStores = backupExtensionStores(options),
                backupSourcePreferences = backupSourcePreferences(options),
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            // Never overwrite a destination with an archive the decoder
            // would reject because its decoded size exceeds the safety cap.
            BackupArchiveByteLimit.requireRestorableSize(byteArray.size.toLong())

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            backupFileValidator.validate(fileUri)

            if (isAutoBackup) {
                // Retire older backups only after the new archive is verified.
                // Any listing error conservatively retains existing copies.
                try {
                    val candidates = UniFile.fromUri(context, uri)
                        ?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                        .orEmpty()
                    val files = candidates.mapNotNull { candidate ->
                        candidate.name?.let { name ->
                            AutomaticBackupRetentionPolicy.BackupFile(
                                uri = candidate.uri.toString(),
                                filename = name,
                                verifiedRestorable = candidate.uri == fileUri,
                            )
                        }
                    }
                    if (files.any { it.uri == fileUri.toString() }) {
                        val obsolete = AutomaticBackupRetentionPolicy.obsoleteAfterVerifying(
                            backups = files,
                            validatedUri = fileUri.toString(),
                            maximumCopies = MAX_AUTO_BACKUPS,
                            maximumInspectionCount = MAX_AUTO_RETENTION_INSPECTION,
                            verify = { candidate ->
                                // Only a complete, decodable backup is eligible.
                                // Files in progress and corrupt archives stay.
                                backupFileValidator.validate(Uri.parse(candidate.uri))
                                true
                            },
                        )
                        candidates.filter { it.uri.toString() in obsolete }
                            .forEach { it.delete() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logcat(LogPriority.WARN, error) {
                        "Validated backup saved, but automatic retention could not be completed"
                    }
                }
                backupPreferences.lastAutoBackupTimestamp.set(Clock.System.now().toEpochMilliseconds())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            BackupOutputCleanupPolicy.onFailure(file, createdByThisBackup)
            throw e
        }
    }

    private suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    private suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    private suspend fun backupSources(mangas: List<BackupManga>): List<BackupSource> {
        return sourcesBackupCreator(mangas)
    }

    private fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    private suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    private suspend fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private const val MAX_AUTO_RETENTION_INSPECTION: Int = 16
        private val FILENAME_REGEX = AutomaticBackupFileNames.pattern(BuildConfig.APPLICATION_ID)

        fun getFilename(): String = AutomaticBackupFileNames.generate(BuildConfig.APPLICATION_ID)
    }
}
