package tachiyomi.domain.tsuzuki.sync.adapter

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.sync.model.CanonicalChapterSyncKey
import tachiyomi.domain.tsuzuki.sync.model.CanonicalVariantSyncKey
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind
import tachiyomi.domain.tsuzuki.sync.model.SyncRecordEnvelope
import tachiyomi.domain.tsuzuki.sync.service.CanonicalTitleSyncSource
import tachiyomi.domain.tsuzuki.sync.service.ChapterSyncEvidenceRepository
import tachiyomi.domain.tsuzuki.sync.service.SyncClock
import tachiyomi.domain.tsuzuki.sync.service.SyncDocumentAdapter
import tachiyomi.domain.tsuzuki.sync.service.SyncRevisionSource

class CanonicalReadingProgressSyncAdapter(
    private val titleSource: CanonicalTitleSyncSource,
    private val chapterRepository: CanonicalChapterRepository,
    private val readingRepository: CanonicalReadingRepository,
    private val evidenceRepository: ChapterSyncEvidenceRepository,
    private val revisionSource: SyncRevisionSource,
    private val clock: SyncClock,
    /**
     * Experimental v2 codec only. No production composition passes true until
     * mixed-device document negotiation is implemented and rolled out.
     */
    private val exactVersion2Enabled: Boolean = false,
) : SyncDocumentAdapter {

    override val documentKind = if (exactVersion2Enabled) {
        SyncDocumentKind.READING_PROGRESS_V2
    } else {
        SyncDocumentKind.READING_PROGRESS
    }

    override suspend fun exportDocument(): SyncDocumentEnvelope {
        val records = linkedMapOf<String, SyncRecordEnvelope>()

        titleSource.getAllTitles()
            .sortedBy { it.id }
            .forEach { title ->
                val chapters = chapterRepository
                    .getByCanonicalTitleId(title.id)
                    .associateBy(CanonicalChapter::id)
                val ambiguousNumberedIds = if (exactVersion2Enabled) {
                    emptySet()
                } else {
                    ambiguousNumberedChapterIds(
                        chapters.values,
                    )
                }

                readingRepository.getProgressByCanonicalTitleId(title.id)
                    .sortedBy(CanonicalChapterProgress::canonicalChapterId)
                    .forEach { progress ->
                        val chapter = chapters[progress.canonicalChapterId]
                            ?: return@forEach
                        check(chapter.id !in ambiguousNumberedIds) {
                            "Distinct canonical chapters would share a portable progress key"
                        }
                        val stableEvidence = evidenceRepository
                            .getStableEvidenceKey(chapter.id)
                        val syncKey = portableKey(chapter, stableEvidence) ?: return@forEach

                        val variantEvidenceKey = resolveVariantEvidenceKey(
                            progress = progress,
                        )

                        check(syncKey.recordId !in records) {
                            "Duplicate portable chapter progress key would overwrite another record"
                        }
                        records[syncKey.recordId] = SyncRecordEnvelope(
                            id = syncKey.recordId,
                            revision = revisionSource.nextRevision(),
                            updatedAtEpochMillis = progress.updatedAt,
                            fields = buildJsonObject {
                                put("canonicalTitleId", syncKey.canonicalTitleId)
                                put("chapterKey", syncKey.chapterKey)
                                put("read", progress.read)
                                put("lastPageRead", progress.lastPageRead)
                                put(
                                    "lastVariantEvidenceKey",
                                    variantEvidenceKey?.let(::JsonPrimitive) ?: JsonNull,
                                )
                            },
                        )
                    }
            }

        return SyncDocumentEnvelope(
            schemaVersion = if (exactVersion2Enabled) EXACT_SCHEMA_VERSION else SCHEMA_VERSION,
            kind = documentKind,
            revision = revisionSource.nextRevision(),
            generatedAtEpochMillis = clock.nowEpochMillis(),
            records = records,
        )
    }

    override suspend fun applyDocument(document: SyncDocumentEnvelope) {
        require(document.kind == documentKind) {
            "Reading progress adapter cannot apply ${document.kind}"
        }
        require(document.schemaVersion == if (exactVersion2Enabled) EXACT_SCHEMA_VERSION else SCHEMA_VERSION) {
            "Reading progress protocol version mismatch; explicit cross-device negotiation is required"
        }

        document.records.values
            .sortedBy(SyncRecordEnvelope::id)
            .forEach { record ->
                require(!record.isTombstone) {
                    "Reading progress tombstones are not supported"
                }

                val canonicalTitleId = record.fields.requiredString("canonicalTitleId")
                val chapterKey = record.fields.requiredString("chapterKey")
                val localChapter = resolveLocalChapter(
                    canonicalTitleId = canonicalTitleId,
                    chapterKey = chapterKey,
                )
                val variantEvidenceKey = record.fields.optionalString(
                    "lastVariantEvidenceKey",
                )
                val localVariantId = variantEvidenceKey?.let { evidenceKey ->
                    chapterRepository
                        .getVariantsByCanonicalChapterId(localChapter.id)
                        .singleOrNull { variant ->
                            CanonicalVariantSyncKey.from(variant) == evidenceKey
                        }
                        ?.id
                }

                readingRepository.upsertProgress(
                    CanonicalChapterProgress(
                        canonicalChapterId = localChapter.id,
                        read = record.fields.requiredBoolean("read"),
                        lastPageRead = record.fields.requiredLong("lastPageRead"),
                        lastVariantId = localVariantId,
                        updatedAt = record.updatedAtEpochMillis,
                    ),
                )
            }
    }

    override suspend fun hasUnportableLocalState(): Boolean {
        for (title in titleSource.getAllTitles()) {
            val chapters = chapterRepository
                .getByCanonicalTitleId(title.id)
                .associateBy(CanonicalChapter::id)
            val ambiguousNumberedIds = if (exactVersion2Enabled) {
                emptySet()
            } else {
                ambiguousNumberedChapterIds(
                    chapters.values,
                )
            }
            val seenKeys = mutableSetOf<String>()
            for (progress in readingRepository.getProgressByCanonicalTitleId(title.id)) {
                val chapter = chapters[progress.canonicalChapterId] ?: return true
                if (chapter.id in ambiguousNumberedIds) return true
                val stableEvidence = evidenceRepository.getStableEvidenceKey(chapter.id)
                val key = portableKey(chapter, stableEvidence) ?: return true
                if (!seenKeys.add(key.recordId)) return true
            }
        }
        return false
    }

    private suspend fun resolveVariantEvidenceKey(
        progress: CanonicalChapterProgress,
    ): String? {
        val variantId = progress.lastVariantId ?: return null
        return chapterRepository
            .getVariantsByCanonicalChapterId(progress.canonicalChapterId)
            .singleOrNull { it.id == variantId }
            ?.let(CanonicalVariantSyncKey::from)
    }

    private suspend fun resolveLocalChapter(
        canonicalTitleId: String,
        chapterKey: String,
    ): CanonicalChapter {
        val matches = mutableListOf<CanonicalChapter>()
        for (chapter in chapterRepository.getByCanonicalTitleId(canonicalTitleId)) {
            val stableEvidence = evidenceRepository.getStableEvidenceKey(chapter.id)
            val localKey = portableKey(chapter, stableEvidence)
            if (localKey?.chapterKey == chapterKey) {
                matches += chapter
            }
        }

        return requireNotNull(matches.singleOrNull()) {
            "Portable chapter key does not resolve to exactly one local chapter"
        }
    }

    private fun portableKey(
        chapter: CanonicalChapter,
        stableEvidenceKey: String?,
    ): CanonicalChapterSyncKey? = if (exactVersion2Enabled) {
        CanonicalChapterSyncKey.fromVersion2(chapter, stableEvidenceKey)
    } else {
        CanonicalChapterSyncKey.from(chapter, stableEvidenceKey)
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        const val EXACT_SCHEMA_VERSION = 2
    }
}

private fun ambiguousNumberedChapterIds(chapters: Collection<CanonicalChapter>): Set<String> =
    chapters.asSequence()
        .filter { it.identity.isNumbered }
        .groupBy(CanonicalChapter::identity)
        .values.asSequence()
        .filter { it.size > 1 }
        .flatten()
        .map(CanonicalChapter::id)
        .toSet()

private fun JsonObject.requiredString(name: String): String =
    getValue(name).jsonPrimitive.content

private fun JsonObject.requiredLong(name: String): Long =
    getValue(name).jsonPrimitive.long

private fun JsonObject.requiredBoolean(name: String): Boolean =
    getValue(name).jsonPrimitive.boolean

private fun JsonObject.optionalString(name: String): String? {
    val value = getValue(name)
    return if (value is JsonNull) null else value.jsonPrimitive.content
}
