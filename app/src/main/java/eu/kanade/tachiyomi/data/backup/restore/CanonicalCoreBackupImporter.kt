package eu.kanade.tachiyomi.data.backup.restore

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalCore
import eu.kanade.tachiyomi.data.backup.models.BackupCanonicalSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import tachiyomi.data.Database
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterOverrideKind
import tachiyomi.domain.tsuzuki.integration.model.PortableIntegrationConfig
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability

/**
 * Atomic, exact-value import of the canonical core into a CLEAN local graph.
 *
 * Not wired into BackupRestorer yet: all other durable Tsuzuki sections and
 * verified account-safe migration still need an end-to-end rollout.
 * A durable receipt stops duplicate imports from overwriting subsequent edits.
 */
@Inject
class CanonicalCoreBackupImporter(
    private val database: Database,
    private val json: Json,
) {

    suspend fun restoreCore(
        section: BackupCanonicalSection,
        beforeCommitReceipt: () -> Unit = {},
    ) {
        require(section.key == BackupCanonicalCore.SECTION_KEY) {
            "Only canonical core sections can be restored by this importer"
        }
        section.validate()
        val core = json.decodeFromString(BackupCanonicalCore.serializer(), section.payloadJson)
        core.validate()
        // Validate enums and metadata before acquiring any database write locks.
        core.titles.forEach { CanonicalIdentityState.valueOf(it.identityState) }
        core.library.forEach { LibraryStatus.valueOf(it.status) }
        core.sourceMappings.forEach { SourceMappingAvailability.valueOf(it.availability) }
        core.chapters.forEach {
            CanonicalChapterType.valueOf(it.type)
            CanonicalChapterConfirmation.valueOf(it.confirmation)
        }
        core.lists.forEach { list ->
            list.queryJson?.let(json::parseToJsonElement)
        }
        core.chapterOverrides.forEach { override ->
            ChapterOverrideKind.valueOf(override.kind)
            json.parseToJsonElement(override.payloadJson)
        }
        core.integrationSettings.forEach { setting ->
            val sanitized = PortableIntegrationConfig.redact(setting.configJson, json)
            require(json.parseToJsonElement(setting.configJson) == json.parseToJsonElement(sanitized)) {
                "Backup cannot import authentication secrets in integration settings"
            }
        }
        val variantMetadata = core.variants.associate { variant ->
            variant.id to json.parseToJsonElement(variant.rawSourceMetadataJson).jsonObject
        }
        require(core.progress.all { it.lastPageRead >= 0 }) { "Negative canonical page index" }
        require(core.history.all { it.totalReadDuration >= 0 }) { "Negative canonical reading duration" }

        database.transaction {
            val receipt = database.tsuzuki_backup_restore_receiptsQueries
                .getTsuzukiBackupRestoreReceipt(section.sha256)
                .awaitAsOneOrNull()
            if (receipt != null) return@transaction // Already applied; never replay over newer edits.

            check(
                database.tsuzuki_backup_restore_receiptsQueries
                    .listTsuzukiBackupRestoreReceipts().awaitAsList().isEmpty(),
            ) { "A different canonical backup has already been restored" }
            check(database.tsuzuki_titlesQueries.getAllTsuzukiTitles().awaitAsList().isEmpty()) {
                "Canonical library is not empty: explicit merge or safe migration is required"
            }

            check(
                database.tsuzuki_collectionsQueries
                    .getAllTsuzukiCollections().awaitAsList().isEmpty(),
            ) { "Collections already exist: explicit merge is required" }

            // No device/account ownership is portable through an archive.
            // A clean canonical graph may still have local integration settings.
            // Only exact non-secret equivalents can be safely reused.
            val existingIntegrations = database.tsuzuki_integration_settingsQueries
                .getAllTsuzukiIntegrationSettings().awaitAsList()
            val archivedIntegrations = core.integrationSettings.associateBy { it.integrationId }
            check(
                existingIntegrations.all { local ->
                    val archived = archivedIntegrations[local.integration_id]
                    archived != null &&
                        local.enabled == archived.enabled &&
                        json.parseToJsonElement(local.config_json) ==
                        json.parseToJsonElement(archived.configJson)
                },
            ) { "Existing integration settings require explicit account-safe reconciliation" }
            val existingIntegrationIds = existingIntegrations.mapTo(mutableSetOf()) { it.integration_id }

            // Preserve matching first-run source defaults without modifying their
            // order. Unrecognized or conflicting local rows require explicit merge.
            val existingSourcePreferences = database.tsuzuki_source_preferencesQueries
                .getConfiguredLanguages().awaitAsList().flatMap { language ->
                    database.tsuzuki_source_preferencesQueries
                        .getPreferencesForLanguage(language).awaitAsList()
                }
            val archivedSourcePreferences = core.sourcePreferences.associateBy { it.language to it.sourceId }
            check(
                existingSourcePreferences.all { local ->
                    archivedSourcePreferences[local.language to local.source_id]?.position == local.position
                },
            ) { "Existing source priorities require explicit reconciliation" }
            val existingSourceIds = existingSourcePreferences
                .mapTo(mutableSetOf()) { it.language to it.source_id }

            core.titles.forEach {
                database.tsuzuki_titlesQueries.insertTsuzukiTitle(
                    id = it.id,
                    displayTitle = it.displayTitle,
                    identityState = it.identityState,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
            core.identities.forEach {
                database.tsuzuki_external_identitiesQueries.insertTsuzukiExternalIdentity(
                    canonicalTitleId = it.canonicalTitleId,
                    provider = it.provider,
                    externalId = it.externalId,
                    verified = it.verified,
                    createdAt = it.createdAt,
                )
            }
            core.sourceMappings.forEach {
                database.tsuzuki_source_mappingsQueries.upsertTsuzukiSourceMapping(
                    id = it.id,
                    canonicalTitleId = it.canonicalTitleId,
                    // Operational Mihon IDs are device-local, never portable.
                    mihonMangaId = null,
                    sourceId = it.sourceId,
                    sourceUrl = it.sourceUrl,
                    language = it.language,
                    matchConfidence = it.matchConfidence,
                    verifiedByUser = it.verifiedByUser,
                    availability = it.availability,
                    preferredOverride = it.preferredOverride,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
            core.chapters.forEach {
                database.tsuzuki_canonical_chaptersQueries.upsertTsuzukiCanonicalChapter(
                    id = it.id,
                    canonicalTitleId = it.canonicalTitleId,
                    displayNumber = it.displayNumber,
                    volume = it.volume?.toLong(),
                    title = it.title,
                    type = it.type,
                    baseNumber = it.baseNumber?.toLong(),
                    part = it.part?.toLong(),
                    alphaSuffix = it.alphaSuffix,
                    confidence = it.confidence,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    confirmationState = it.confirmation,
                )
            }
            core.variants.forEach {
                database.tsuzuki_chapter_variantsQueries.upsertTsuzukiChapterVariant(
                    id = it.id,
                    canonicalChapterId = it.canonicalChapterId,
                    sourceMappingId = it.sourceMappingId,
                    sourceId = it.sourceId,
                    // Operational Mihon IDs are device-local, never portable.
                    mihonMangaId = null,
                    mihonChapterId = null,
                    sourceChapterId = it.sourceChapterId,
                    sourceChapterUrl = it.sourceChapterUrl,
                    language = it.language,
                    scanlationGroup = it.scanlationGroup,
                    version = it.version,
                    releaseDate = it.releaseDate,
                    rawName = it.rawName,
                    rawNumberHint = it.rawNumberHint,
                    rawSourceOrder = it.rawSourceOrder,
                    rawSourceMetadata = MemoColumnAdapter.encode(variantMetadata.getValue(it.id)),
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
            core.collections.forEach {
                database.tsuzuki_collectionsQueries.upsertTsuzukiCollection(
                    id = it.id,
                    title = it.title,
                    origin = it.origin,
                    sortOrder = it.sortOrder,
                    schemaVersion = it.schemaVersion,
                    revision = it.revision,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    deletedAt = it.deletedAt,
                )
            }
            // Folder parents have foreign keys. Insert them before descendants
            // regardless of ordering in the serialized backup.
            val insertedFolders = mutableSetOf<String>()
            val remainingFolders = core.folders.toMutableList()
            while (remainingFolders.isNotEmpty()) {
                val available = remainingFolders.filter { folder ->
                    folder.parentFolderId == null || folder.parentFolderId in insertedFolders
                }
                check(available.isNotEmpty()) { "Folder dependency cycle in canonical backup" }
                available.forEach {
                    database.tsuzuki_collectionsQueries.upsertTsuzukiFolder(
                        id = it.id,
                        collectionId = it.collectionId,
                        parentFolderId = it.parentFolderId,
                        title = it.title,
                        origin = it.origin,
                        sortOrder = it.sortOrder,
                        schemaVersion = it.schemaVersion,
                        revision = it.revision,
                        createdAt = it.createdAt,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                    insertedFolders += it.id
                }
                remainingFolders.removeAll(available.toSet())
            }
            core.lists.forEach {
                database.tsuzuki_collectionsQueries.upsertTsuzukiList(
                    id = it.id,
                    collectionId = it.collectionId,
                    folderId = it.folderId,
                    title = it.title,
                    providerId = it.providerId,
                    queryJson = it.queryJson,
                    sort = it.sort,
                    sortDirection = it.sortDirection,
                    layoutType = it.layoutType,
                    sortOrder = it.sortOrder,
                    enabled = it.enabled,
                    origin = it.origin,
                    schemaVersion = it.schemaVersion,
                    revision = it.revision,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    deletedAt = it.deletedAt,
                )
            }
            core.providerBindings.forEach {
                database.tsuzuki_provider_bindingsQueries.upsertTsuzukiProviderBinding(
                    id = it.id,
                    canonicalTitleId = it.canonicalTitleId,
                    providerId = it.providerId,
                    facetId = it.facetId,
                    externalWorkId = it.externalWorkId,
                    verification = it.verification,
                    availability = it.availability,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                )
            }
            core.readerPreferences.forEach {
                database.tsuzuki_reader_preferencesQueries.upsertTsuzukiReaderPreference(
                    canonicalTitleId = it.canonicalTitleId,
                    automaticFallback = it.automaticFallback,
                    updatedAt = it.updatedAt,
                )
            }
            core.chapterOverrides.forEach {
                database.tsuzuki_chapter_overridesQueries.upsertTsuzukiChapterOverride(
                    id = it.id,
                    canonicalTitleId = it.canonicalTitleId,
                    canonicalChapterKey = it.canonicalChapterKey,
                    sourceId = it.sourceId,
                    sourceTitleUrl = it.sourceTitleUrl,
                    sourceChapterId = it.sourceChapterId,
                    kind = it.kind,
                    payloadJson = it.payloadJson,
                    schemaVersion = it.schemaVersion,
                    revision = it.revision,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    deletedAt = it.deletedAt,
                )
            }
            core.library.forEach {
                database.tsuzuki_library_entriesQueries.upsertTsuzukiLibraryEntry(
                    canonicalTitleId = it.canonicalTitleId,
                    status = it.status,
                    favorite = it.favorite,
                    addedAt = it.addedAt,
                    updatedAt = it.updatedAt,
                )
            }
            // The portable archive never reuses device-local category row IDs.
            // Reconcile by unique name only when order and flags match exactly;
            // incompatible/ambiguous existing rows are a hard rollback.
            val categoryIds = mutableMapOf<String, Long>()
            for (membership in core.libraryCategories) {
                if (membership.name !in categoryIds) {
                    var matches = database.categoriesQueries.getCategories()
                        .awaitAsList().filter { it.name == membership.name }
                    check(matches.size <= 1) { "Ambiguous category name during restore" }
                    if (matches.isEmpty()) {
                        database.categoriesQueries.insert(
                            name = membership.name,
                            order = membership.order,
                            flags = membership.flags,
                        )
                        matches = database.categoriesQueries.getCategories()
                            .awaitAsList().filter { it.name == membership.name }
                    }
                    check(matches.size == 1) { "Cannot resolve restored category" }
                    check(
                        matches.single().order == membership.order &&
                            matches.single().flags == membership.flags,
                    ) { "Existing category has incompatible order or flags" }
                    categoryIds[membership.name] = matches.single().id
                }
                database.tsuzuki_library_categoriesQueries.insertTsuzukiLibraryCategory(
                    canonicalTitleId = membership.canonicalTitleId,
                    categoryId = categoryIds.getValue(membership.name),
                )
            }
            core.integrationSettings.filterNot { it.integrationId in existingIntegrationIds }.forEach {
                database.tsuzuki_integration_settingsQueries.upsertTsuzukiIntegrationSetting(
                    integrationId = it.integrationId,
                    enabled = it.enabled,
                    configJson = it.configJson,
                    updatedAt = it.updatedAt,
                )
            }
            core.contentPreferences.forEach {
                database.tsuzuki_content_preferencesQueries.upsertTsuzukiContentPreference(
                    canonicalTitleId = it.canonicalTitleId,
                    preferredAddonId = it.preferredAddonId,
                    preferredLanguage = it.preferredLanguage,
                    updatedAt = it.updatedAt,
                )
            }
            core.sourcePreferences
                .filterNot { (it.language to it.sourceId) in existingSourceIds }
                .forEach {
                    database.tsuzuki_source_preferencesQueries.insertPreference(
                        language = it.language,
                        sourceId = it.sourceId,
                        position = it.position,
                    )
                }
            core.continueReadingStates.forEach {
                database.tsuzuki_continue_reading_stateQueries.upsertTsuzukiContinueReadingState(
                    canonicalTitleId = it.canonicalTitleId,
                    hiddenAt = it.hiddenAt,
                )
            }
            core.chapterUpdateStates.forEach {
                database.tsuzuki_chapter_update_stateQueries.upsertTsuzukiChapterUpdateState(
                    canonicalChapterId = it.canonicalChapterId,
                    canonicalTitleId = it.canonicalTitleId,
                    firstSeenAt = it.firstSeenAt,
                    acknowledgedAt = it.acknowledgedAt,
                )
            }
            core.titleArtwork.forEach {
                database.tsuzuki_title_artwork_observationsQueries.upsertTsuzukiTitleArtworkObservation(
                    canonicalTitleId = it.canonicalTitleId,
                    provider = it.provider,
                    coverUrl = it.coverUrl,
                    bannerUrl = it.bannerUrl,
                    updatedAt = it.updatedAt,
                )
            }
            core.titleFormats.forEach {
                database.tsuzuki_title_format_observationsQueries.upsertTsuzukiTitleFormatObservation(
                    canonicalTitleId = it.canonicalTitleId,
                    provider = it.provider,
                    format = it.format,
                    updatedAt = it.updatedAt,
                )
            }
            core.titleNames.forEach {
                database.tsuzuki_title_name_observationsQueries.upsertTsuzukiTitleNameObservation(
                    canonicalTitleId = it.canonicalTitleId,
                    provider = it.provider,
                    sourceKey = it.sourceKey,
                    value = it.value,
                    updatedAt = it.updatedAt,
                )
            }
            core.progress.forEach {
                database.tsuzuki_chapter_progressQueries.upsertTsuzukiChapterProgress(
                    canonicalChapterId = it.canonicalChapterId,
                    read = it.read,
                    lastPageRead = it.lastPageRead,
                    lastVariantId = it.lastVariantId,
                    updatedAt = it.updatedAt,
                )
            }
            core.history.forEach {
                database.tsuzuki_chapter_historyQueries.restoreTsuzukiChapterHistorySnapshot(
                    canonicalChapterId = it.canonicalChapterId,
                    lastVariantId = it.lastVariantId,
                    lastReadAt = it.lastReadAt,
                    totalReadDuration = it.totalReadDuration,
                )
            }

            beforeCommitReceipt()
            database.tsuzuki_backup_restore_receiptsQueries.insertTsuzukiBackupRestoreReceipt(
                snapshotHash = section.sha256,
                formatVersion = core.schemaVersion.toLong(),
                importedAt = System.currentTimeMillis(),
            )
        }
    }
}
