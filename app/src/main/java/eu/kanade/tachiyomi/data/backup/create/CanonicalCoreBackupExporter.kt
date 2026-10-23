package eu.kanade.tachiyomi.data.backup.create

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.Inject
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
import eu.kanade.tachiyomi.data.backup.models.BackupCoreIdentity
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
        val collections = database.tsuzuki_collectionsQueries
            .getAllTsuzukiCollections().awaitAsList()
        val folders = collections.flatMap { collection ->
            database.tsuzuki_collectionsQueries
                .getAllTsuzukiFoldersByCollection(collection.id).awaitAsList()
        }
        val lists = collections.flatMap { collection ->
            database.tsuzuki_collectionsQueries
                .getAllTsuzukiListsByCollection(collection.id).awaitAsList()
        }
        val providerBindings = titles.flatMap { title ->
            database.tsuzuki_provider_bindingsQueries
                .getTsuzukiProviderBindingsByTitle(title.id).awaitAsList()
        }
        val readerPreferences = database.tsuzuki_reader_preferencesQueries
            .getAllTsuzukiReaderPreferences().awaitAsList()
        val chapterOverrides = database.tsuzuki_chapter_overridesQueries
            .getAllTsuzukiChapterOverrides().awaitAsList()

        val contentPreferences = database.tsuzuki_content_preferencesQueries
            .getAllTsuzukiContentPreferences().awaitAsList()
        val continueReadingStates = database.tsuzuki_continue_reading_stateQueries
            .getAllTsuzukiContinueReadingState().awaitAsList()
        val chapterUpdateStates = database.tsuzuki_chapter_update_stateQueries
            .getAllTsuzukiChapterUpdateState().awaitAsList()
        val titleArtwork = database.tsuzuki_title_artwork_observationsQueries
            .getAllTsuzukiTitleArtworkObservations().awaitAsList()
        val titleFormats = database.tsuzuki_title_format_observationsQueries
            .getAllTsuzukiTitleFormatObservations().awaitAsList()
        val titleNames = titles.flatMap { title ->
            database.tsuzuki_title_name_observationsQueries
                .getTsuzukiTitleNameObservationsByTitle(title.id).awaitAsList()
        }
        val sourceLanguages = database.tsuzuki_source_preferencesQueries
            .getConfiguredLanguages().awaitAsList()
        val sourcePreferences = sourceLanguages.flatMap { language ->
            database.tsuzuki_source_preferencesQueries
                .getPreferencesForLanguage(language).awaitAsList()
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
            collections = collections.map {
                BackupCoreCollection(
                    it.id, it.title, it.origin, it.sort_order,
                    it.schema_version, it.revision, it.created_at, it.updated_at, it.deleted_at,
                )
            },
            folders = folders.map {
                BackupCoreFolder(
                    it.id, it.collection_id, it.parent_folder_id, it.title, it.origin,
                    it.sort_order, it.schema_version, it.revision, it.created_at, it.updated_at, it.deleted_at,
                )
            },
            lists = lists.map {
                BackupCoreList(
                    id = it.id,
                    collectionId = it.collection_id,
                    folderId = it.folder_id,
                    title = it.title,
                    providerId = it.provider_id,
                    queryJson = it.query_json,
                    sort = it.sort,
                    sortDirection = it.sort_direction,
                    layoutType = it.layout_type,
                    sortOrder = it.sort_order,
                    enabled = it.enabled,
                    origin = it.origin,
                    schemaVersion = it.schema_version,
                    revision = it.revision,
                    createdAt = it.created_at,
                    updatedAt = it.updated_at,
                    deletedAt = it.deleted_at,
                )
            },
            providerBindings = providerBindings.map {
                BackupCoreProviderBinding(
                    id = it.id,
                    canonicalTitleId = it.canonical_title_id,
                    providerId = it.provider_id,
                    facetId = it.facet_id,
                    externalWorkId = it.external_work_id,
                    verification = it.verification,
                    availability = it.availability,
                    createdAt = it.created_at,
                    updatedAt = it.updated_at,
                )
            },
            readerPreferences = readerPreferences.map {
                BackupCoreReaderPreference(
                    canonicalTitleId = it.canonical_title_id,
                    automaticFallback = it.automatic_fallback,
                    updatedAt = it.updated_at,
                )
            },
            chapterOverrides = chapterOverrides.map {
                BackupCoreChapterOverride(
                    id = it.id,
                    canonicalTitleId = it.canonical_title_id,
                    canonicalChapterKey = it.canonical_chapter_key,
                    sourceId = it.source_id,
                    sourceTitleUrl = it.source_title_url,
                    sourceChapterId = it.source_chapter_id,
                    kind = it.kind,
                    payloadJson = it.payload_json,
                    schemaVersion = it.schema_version,
                    revision = it.revision,
                    createdAt = it.created_at,
                    updatedAt = it.updated_at,
                    deletedAt = it.deleted_at,
                )
            },
            contentPreferences = contentPreferences.map {
                BackupCoreContentPreference(
                    it.canonical_title_id, it.preferred_addon_id, it.preferred_language, it.updated_at,
                )
            },
            sourcePreferences = sourcePreferences.map {
                BackupCoreSourcePreference(it.language, it.source_id, it.position)
            },
            continueReadingStates = continueReadingStates.map {
                BackupCoreContinueReadingState(it.canonical_title_id, it.hidden_at)
            },
            chapterUpdateStates = chapterUpdateStates.map {
                BackupCoreChapterUpdateState(
                    it.canonical_chapter_id, it.canonical_title_id, it.first_seen_at, it.acknowledged_at,
                )
            },
            titleArtwork = titleArtwork.map {
                BackupCoreTitleArtwork(
                    it.canonical_title_id, it.provider, it.cover_url, it.banner_url, it.updated_at,
                )
            },
            titleFormats = titleFormats.map {
                BackupCoreTitleFormat(it.canonical_title_id, it.provider, it.format, it.updated_at)
            },
            titleNames = titleNames.map {
                BackupCoreTitleName(
                    it.canonical_title_id, it.provider, it.source_key, it.value, it.updated_at,
                )
            },
        )
        core.validate()
        BackupCanonicalSection.fromJson(
            key = BackupCanonicalCore.SECTION_KEY,
            json = json.encodeToString(BackupCanonicalCore.serializer(), core),
        )
    }
}
