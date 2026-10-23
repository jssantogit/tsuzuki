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
                    mihonMangaId = it.mihonMangaId,
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
                    rawSourceMetadata = MemoColumnAdapter.encode(variantMetadata.getValue(it.id)),
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
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
