package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable

/**
 * Canonical archive's core graph. This is deliberately independent of the
 * online sync document format: a local backup must retain provisional
 * chapters, unverified identities, source variants and local-only checkpoints.
 *
 * A versioned writer/restore pair must be released together; this model alone
 * is not sufficient to claim a complete Tsuzuki backup.
 */
@Serializable
data class BackupCanonicalCore(
    val schemaVersion: Int = VERSION,
    val titles: List<BackupCoreTitle> = emptyList(),
    val identities: List<BackupCoreIdentity> = emptyList(),
    val sourceMappings: List<BackupCoreSourceMapping> = emptyList(),
    val library: List<BackupCoreLibraryEntry> = emptyList(),
    val chapters: List<BackupCoreChapter> = emptyList(),
    val variants: List<BackupCoreVariant> = emptyList(),
    val progress: List<BackupCoreProgress> = emptyList(),
    val history: List<BackupCoreHistory> = emptyList(),
) {
    fun validate() {
        require(schemaVersion == VERSION) { "Unsupported canonical core schema" }
        require(titles.isUniqueBy { it.id }) { "Duplicate canonical title" }
        require(sourceMappings.isUniqueBy { it.id }) { "Duplicate source mapping" }
        require(sourceMappings.isUniqueBy { it.sourceId to it.sourceUrl }) {
            "Duplicate source mapping identity"
        }
        require(chapters.isUniqueBy { it.id }) { "Duplicate canonical chapter" }
        require(variants.isUniqueBy { it.id }) { "Duplicate chapter variant" }
        require(library.isUniqueBy { it.canonicalTitleId }) { "Duplicate library membership" }
        require(progress.isUniqueBy { it.canonicalChapterId }) { "Duplicate reading progress" }
        require(history.isUniqueBy { it.canonicalChapterId }) { "Duplicate reading history" }
        require(identities.isUniqueBy { it.provider to it.externalId }) {
            "Duplicate external identity"
        }
        require(variants.isUniqueBy { it.sourceId to it.sourceChapterId }) {
            "Duplicate source chapter identity"
        }

        val titleIds = titles.mapTo(mutableSetOf()) { it.id }
        val chapterIds = chapters.mapTo(mutableSetOf()) { it.id }
        val mappingsById = sourceMappings.associateBy { it.id }
        val chaptersById = chapters.associateBy { it.id }
        val mappingIds = mappingsById.keys
        val variantsById = variants.associateBy { it.id }
        require(titleIds.none(String::isBlank) && chapterIds.none(String::isBlank)) {
            "Canonical ID must not be blank"
        }
        require(chapters.all { it.canonicalTitleId in titleIds }) {
            "Chapter refers to absent canonical title"
        }
        require(identities.all { it.canonicalTitleId in titleIds }) {
            "Identity refers to absent canonical title"
        }
        require(library.all { it.canonicalTitleId in titleIds }) {
            "Library entry refers to absent canonical title"
        }
        require(sourceMappings.all { it.canonicalTitleId in titleIds }) {
            "Source mapping refers to absent canonical title"
        }
        require(variants.all { it.sourceMappingId in mappingIds }) {
            "Variant refers to absent source mapping"
        }
        require(variants.all { it.canonicalChapterId in chapterIds }) {
            "Variant refers to absent canonical chapter"
        }
        require(
            variants.all {
                mappingsById[it.sourceMappingId]?.canonicalTitleId ==
                    chaptersById[it.canonicalChapterId]?.canonicalTitleId
            },
        ) { "Variant source mapping and canonical chapter belong to different titles" }
        require(progress.all { it.canonicalChapterId in chapterIds }) {
            "Progress refers to absent canonical chapter"
        }
        require(history.all { it.canonicalChapterId in chapterIds }) {
            "History refers to absent canonical chapter"
        }
        require(
            progress.all {
                it.lastVariantId == null ||
                    variantsById[it.lastVariantId]?.canonicalChapterId == it.canonicalChapterId
            } &&
                history.all {
                    it.lastVariantId == null ||
                        variantsById[it.lastVariantId]?.canonicalChapterId == it.canonicalChapterId
                },
        ) { "Reader checkpoint references a variant of another chapter" }
    }

    private fun <T, K> List<T>.isUniqueBy(selector: (T) -> K): Boolean =
        map(selector).toSet().size == size

    companion object {
        const val VERSION = 1
        const val SECTION_KEY = "tsuzuki.core.v1"
    }
}

@Serializable
data class BackupCoreTitle(
    val id: String,
    val displayTitle: String,
    val identityState: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreIdentity(
    val canonicalTitleId: String,
    val provider: String,
    val externalId: String,
    val verified: Boolean,
    val createdAt: Long,
)

@Serializable
data class BackupCoreSourceMapping(
    val id: String,
    val canonicalTitleId: String,
    val mihonMangaId: Long?,
    val sourceId: Long,
    val sourceUrl: String,
    val language: String,
    val matchConfidence: Double?,
    val verifiedByUser: Boolean,
    val availability: String,
    val preferredOverride: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreLibraryEntry(
    val canonicalTitleId: String,
    val status: String,
    val favorite: Boolean,
    val addedAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreChapter(
    val id: String,
    val canonicalTitleId: String,
    val displayNumber: String,
    val volume: Int?,
    val title: String?,
    val type: String,
    val baseNumber: Int?,
    val part: Int?,
    val alphaSuffix: String?,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val confirmation: String,
)

@Serializable
data class BackupCoreVariant(
    val id: String,
    val canonicalChapterId: String,
    val sourceMappingId: String,
    val sourceId: Long,
    val mihonMangaId: Long?,
    val mihonChapterId: Long?,
    val sourceChapterId: String,
    val sourceChapterUrl: String?,
    val language: String,
    val scanlationGroup: String?,
    val version: Long?,
    val releaseDate: Long?,
    val rawName: String,
    val rawNumberHint: Double?,
    val rawSourceOrder: Long?,
    val rawSourceMetadataJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreProgress(
    val canonicalChapterId: String,
    val read: Boolean,
    val lastPageRead: Long,
    val lastVariantId: String?,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreHistory(
    val canonicalChapterId: String,
    val lastVariantId: String?,
    val lastReadAt: Long?,
    val totalReadDuration: Long,
)
