package eu.kanade.tachiyomi.data.backup.create

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import eu.kanade.tachiyomi.data.backup.models.BackupCoreChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCoreHistory
import eu.kanade.tachiyomi.data.backup.models.BackupCoreIdentity
import eu.kanade.tachiyomi.data.backup.models.BackupCoreLibraryEntry
import eu.kanade.tachiyomi.data.backup.models.BackupCoreProgress
import eu.kanade.tachiyomi.data.backup.models.BackupCoreSourceMapping
import eu.kanade.tachiyomi.data.backup.models.BackupCoreTitle
import eu.kanade.tachiyomi.data.backup.models.BackupCoreVariant
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource

/**
 * Full-fidelity export of the local canonical graph's *core* seven entities.
 *
 * Unlike cloud sync, it does not filter provisional chapters, unverified
 * identities, or progress lacking a portable remote chapter key.
 * Export alone is NOT wired into .tachibk until matching restore is tested.
 */
@Inject
class CanonicalCoreBackupExporter(
    private val titleSource: CanonicalTitleSyncSource,
    private val titleRepository: CanonicalTitleRepository,
    private val libraryRepository: CanonicalLibraryRepository,
    private val chapterRepository: CanonicalChapterRepository,
    private val sourceMappingRepository: SourceTitleMappingRepository,
    private val readingRepository: CanonicalReadingRepository,
    private val database: Database,
    private val json: Json,
) {
    suspend fun export(): BackupCanonicalSection = database.transactionWithResult {
        val titles = titleSource.getAllTitles().sortedBy { it.id }
        val library = libraryRepository.getAllAsFlow().first()
            .sortedBy { it.canonicalTitleId }

        val identities = titles.flatMap { title ->
            titleRepository.getExternalIdentities(title.id)
                .sortedWith(compareBy({ it.provider }, { it.externalId }))
        }
        val sourceMappings = sourceMappingRepository.getAll().sortedBy { it.id }
        val chapters = titles.flatMap { title ->
            chapterRepository.getByCanonicalTitleId(title.id).sortedBy { it.id }
        }
        val variants = chapters.flatMap { chapter ->
            chapterRepository.getVariantsByCanonicalChapterId(chapter.id).sortedBy { it.id }
        }
        val progress = titles.flatMap { title ->
            readingRepository.getProgressByCanonicalTitleId(title.id)
                .sortedBy { it.canonicalChapterId }
        }
        val history = chapters.mapNotNull { chapter ->
            readingRepository.getHistory(chapter.id)
        }

        val core = BackupCanonicalCore(
            titles = titles.map {
                BackupCoreTitle(it.id, it.displayTitle, it.identityState.name, it.createdAt, it.updatedAt)
            },
            identities = identities.map {
                BackupCoreIdentity(it.canonicalTitleId, it.provider, it.externalId, it.verified, it.createdAt)
            },
            sourceMappings = sourceMappings.map {
                BackupCoreSourceMapping(
                    id = it.id,
                    canonicalTitleId = it.canonicalTitleId,
                    mihonMangaId = it.mihonMangaId,
                    sourceId = it.sourceId,
                    sourceUrl = it.sourceUrl,
                    language = it.language,
                    matchConfidence = it.matchConfidence,
                    verifiedByUser = it.verifiedByUser,
                    availability = it.availability.name,
                    preferredOverride = it.preferredOverride,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            library = library.map {
                BackupCoreLibraryEntry(it.canonicalTitleId, it.status.name, it.favorite, it.addedAt, it.updatedAt)
            },
            chapters = chapters.map {
                BackupCoreChapter(
                    id = it.id,
                    canonicalTitleId = it.canonicalTitleId,
                    displayNumber = it.displayNumber,
                    volume = it.volume,
                    title = it.title,
                    type = it.type.name,
                    baseNumber = it.baseNumber,
                    part = it.part,
                    alphaSuffix = it.alphaSuffix,
                    confidence = it.confidence,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    confirmation = it.confirmation.name,
                )
            },
            variants = variants.map {
                BackupCoreVariant(
                    id = it.id,
                    canonicalChapterId = it.canonicalChapterId,
                    sourceMappingId = it.sourceMappingId,
                    sourceId = it.sourceId,
                    mihonMangaId = it.mihonMangaId,
                    mihonChapterId = it.mihonChapterId,
                    sourceChapterId = it.sourceChapterId,
                    sourceChapterUrl = it.sourceChapterUrl,
                    language = it.language,
                    scanlationGroup = it.scanlationGroup,
                    version = it.version,
                    releaseDate = it.releaseDate,
                    rawName = it.rawName,
                    rawNumberHint = it.rawNumberHint,
                    rawSourceOrder = it.rawSourceOrder,
                    rawSourceMetadataJson = it.rawSourceMetadata.toString(),
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            },
            progress = progress.map {
                BackupCoreProgress(it.canonicalChapterId, it.read, it.lastPageRead, it.lastVariantId, it.updatedAt)
            },
            history = history.map {
                BackupCoreHistory(it.canonicalChapterId, it.lastVariantId, it.lastReadAt, it.totalReadDuration)
            },
        )
        core.validate()
        BackupCanonicalSection.fromJson(
            key = BackupCanonicalCore.SECTION_KEY,
            json = json.encodeToString(BackupCanonicalCore.serializer(), core),
        )
    }
}
