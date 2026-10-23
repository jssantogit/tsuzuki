package tachiyomi.domain.tsuzuki.sync.interactor

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.sync.model.CanonicalChapterSyncKey
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentEnvelope
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentKind

/**
 * Pure inspection of an old remote reading-progress snapshot.
 *
 * This is deliberately NOT a writer or an activation decision: without a
 * server-owned account epoch, device handshake and downgrade fence, even an
 * entirely Ready preview cannot authorize a v2 remote mutation.
 *
 * Unknown and exact-decimal chapters are not assigned guessed legacy keys.
 * All title siblings must be supplied, including chapters with no progress,
 * so resets across volumes cannot create a false unique match.
 */
class PreviewCanonicalProgressV2Migration {

    fun assess(
        legacyDocument: SyncDocumentEnvelope,
        chapters: Collection<CanonicalChapter>,
        stableEvidenceByChapterId: Map<String, String> = emptyMap(),
    ): ProgressMigrationPreview {
        if (
            legacyDocument.kind != SyncDocumentKind.READING_PROGRESS ||
            legacyDocument.schemaVersion != 1
        ) {
            return ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNSUPPORTED_DOCUMENT)
        }

        val legacyCandidates = chapters.mapNotNull { chapter ->
            CanonicalChapterSyncKey.from(
                chapter,
                stableEvidenceByChapterId[chapter.id],
            )?.recordId?.let { it to chapter }
        }.groupBy({ it.first }, { it.second })

        val exactCandidates = chapters.mapNotNull { chapter ->
            CanonicalChapterSyncKey.fromVersion2(
                chapter,
                stableEvidenceByChapterId[chapter.id],
            )?.recordId?.let { it to chapter }
        }.groupBy({ it.first }, { it.second })

        val remap = linkedMapOf<String, String>()
        val usedTargetKeys = mutableSetOf<String>()
        for (record in legacyDocument.records.values.sortedBy { it.id }) {
            val titleId = (record.fields["canonicalTitleId"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            val chapterKey = (record.fields["chapterKey"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            val read = (record.fields["read"] as? JsonPrimitive)?.booleanOrNull
            val lastPageRead = (record.fields["lastPageRead"] as? JsonPrimitive)?.longOrNull
            val variantKey = record.fields["lastVariantEvidenceKey"]

            if (
                record.isTombstone ||
                titleId.isNullOrBlank() ||
                chapterKey.isNullOrBlank() ||
                read == null ||
                lastPageRead == null ||
                lastPageRead < 0 ||
                (
                    variantKey != null &&
                        variantKey != JsonNull &&
                        (variantKey !is JsonPrimitive || !variantKey.isString)
                    ) ||
                CanonicalChapterSyncKey(titleId, chapterKey).recordId != record.id
            ) {
                return ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.MALFORMED_REMOTE_RECORD)
            }

            // This preview has no source-variant inventory. An unresolved
            // per-source page checkpoint cannot be promoted safely merely
            // because its chapter identity was unambiguous.
            if (variantKey != null && variantKey != JsonNull) {
                return ProgressMigrationPreview.Blocked(
                    ProgressMigrationPreview.Reason.VARIANT_EVIDENCE_REQUIRES_REVIEW,
                )
            }

            val candidates = legacyCandidates[record.id].orEmpty()
            when (candidates.size) {
                0 -> return ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNRESOLVED_LEGACY_KEY)
                1 -> Unit
                else -> return ProgressMigrationPreview.Blocked(
                    ProgressMigrationPreview.Reason.AMBIGUOUS_LEGACY_IDENTITY,
                )
            }

            val chapter = candidates.single()
            val target = CanonicalChapterSyncKey.fromVersion2(
                chapter,
                stableEvidenceByChapterId[chapter.id],
            )?.recordId
                ?: return ProgressMigrationPreview.Blocked(ProgressMigrationPreview.Reason.UNRESOLVED_LEGACY_KEY)

            if (exactCandidates[target]?.size != 1 || !usedTargetKeys.add(target)) {
                return ProgressMigrationPreview.Blocked(
                    ProgressMigrationPreview.Reason.AMBIGUOUS_TARGET_IDENTITY,
                )
            }
            remap[record.id] = target
        }

        return ProgressMigrationPreview.Ready(remap)
    }
}

/** A review result, never an authorization token or a rewritten remote document. */
sealed interface ProgressMigrationPreview {
    data class Ready(
        val recordIdMapping: Map<String, String>,
    ) : ProgressMigrationPreview

    data class Blocked(
        val reason: Reason,
    ) : ProgressMigrationPreview

    enum class Reason {
        UNSUPPORTED_DOCUMENT,
        MALFORMED_REMOTE_RECORD,
        UNRESOLVED_LEGACY_KEY,
        AMBIGUOUS_LEGACY_IDENTITY,
        AMBIGUOUS_TARGET_IDENTITY,
        VARIANT_EVIDENCE_REQUIRES_REVIEW,
    }
}
