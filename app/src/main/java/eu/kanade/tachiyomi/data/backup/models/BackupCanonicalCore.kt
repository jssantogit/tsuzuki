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
    val collections: List<BackupCoreCollection> = emptyList(),
    val folders: List<BackupCoreFolder> = emptyList(),
    val lists: List<BackupCoreList> = emptyList(),
    val providerBindings: List<BackupCoreProviderBinding> = emptyList(),
    val readerPreferences: List<BackupCoreReaderPreference> = emptyList(),
    val chapterOverrides: List<BackupCoreChapterOverride> = emptyList(),
    val contentPreferences: List<BackupCoreContentPreference> = emptyList(),
    val sourcePreferences: List<BackupCoreSourcePreference> = emptyList(),
    val libraryCategories: List<BackupCoreLibraryCategory> = emptyList(),
    val continueReadingStates: List<BackupCoreContinueReadingState> = emptyList(),
    val chapterUpdateStates: List<BackupCoreChapterUpdateState> = emptyList(),
    val titleArtwork: List<BackupCoreTitleArtwork> = emptyList(),
    val titleFormats: List<BackupCoreTitleFormat> = emptyList(),
    val titleNames: List<BackupCoreTitleName> = emptyList(),
) {
    fun validate() {
        require(schemaVersion == VERSION) { "Unsupported canonical core schema" }
        require(titles.isUniqueBy { it.id }) { "Duplicate canonical title" }
        require(collections.isUniqueBy { it.id }) { "Duplicate collection ID" }
        require(folders.isUniqueBy { it.id }) { "Duplicate folder ID" }
        require(lists.isUniqueBy { it.id }) { "Duplicate collection list ID" }
        require(providerBindings.isUniqueBy { it.id }) { "Duplicate provider binding ID" }
        require(readerPreferences.isUniqueBy { it.canonicalTitleId }) { "Duplicate reader preference" }
        require(chapterOverrides.isUniqueBy { it.id }) { "Duplicate chapter override" }
        require(providerBindings.isUniqueBy { Triple(it.providerId, it.facetId, it.externalWorkId) }) {
            "Duplicate provider binding external identity"
        }
        require(libraryCategories.isUniqueBy { it.canonicalTitleId to it.name }) {
            "Ambiguous category names for the same title"
        }
        require(contentPreferences.isUniqueBy { it.canonicalTitleId }) { "Duplicate content preference" }
        require(sourcePreferences.isUniqueBy { it.language to it.sourceId }) { "Duplicate source priority" }
        require(sourcePreferences.isUniqueBy { it.language to it.position }) { "Duplicate source order" }
        require(continueReadingStates.isUniqueBy { it.canonicalTitleId }) { "Duplicate continue reading state" }
        require(chapterUpdateStates.isUniqueBy { it.canonicalChapterId }) { "Duplicate chapter update state" }
        require(titleArtwork.isUniqueBy { it.canonicalTitleId to it.provider }) { "Duplicate title artwork" }
        require(titleFormats.isUniqueBy { it.canonicalTitleId to it.provider }) { "Duplicate title format" }
        require(titleNames.isUniqueBy { listOf(it.canonicalTitleId, it.provider, it.sourceKey, it.value) }) {
            "Duplicate title name observation"
        }
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
        val collectionsById = collections.associateBy { it.id }
        val foldersById = folders.associateBy { it.id }
        val collectionIds = collectionsById.keys
        val mappingsById = sourceMappings.associateBy { it.id }
        val chaptersById = chapters.associateBy { it.id }
        val mappingIds = mappingsById.keys
        val variantsById = variants.associateBy { it.id }
        require(titleIds.none(String::isBlank) && chapterIds.none(String::isBlank)) {
            "Canonical ID must not be blank"
        }
        require(
            collections.all {
                it.id.isNotBlank() && it.title.isNotBlank() && it.origin in setOf("USER", "SYSTEM") &&
                    it.sortOrder >= 0 && it.schemaVersion > 0 && it.revision >= 0
            },
        ) { "Invalid collection metadata" }
        require(
            folders.all {
                it.id.isNotBlank() && it.title.isNotBlank() && it.collectionId in collectionIds &&
                    it.origin in setOf("USER", "SYSTEM") && it.sortOrder >= 0 &&
                    it.schemaVersion > 0 && it.revision >= 0
            },
        ) { "Invalid collection folder or orphan collection" }
        require(
            folders.all {
                it.parentFolderId == null ||
                    foldersById[it.parentFolderId]?.collectionId == it.collectionId
            },
        ) { "Folder parent must exist inside its collection" }
        // Reject both self-loops and multi-folder cycles before any database writes.
        for (folder in folders) {
            var cursor: BackupCoreFolder? = folder
            val visited = mutableSetOf<String>()
            while (cursor != null) {
                require(visited.add(cursor.id)) { "Cyclic collection folder hierarchy" }
                cursor = cursor.parentFolderId?.let(foldersById::get)
            }
        }
        require(
            lists.all {
                it.id.isNotBlank() && it.title.isNotBlank() && it.providerId.isNotBlank() &&
                    foldersById[it.folderId]?.collectionId == it.collectionId &&
                    it.collectionId in collectionIds &&
                    it.origin in setOf("USER", "SYSTEM") && it.sortOrder >= 0 &&
                    it.schemaVersion > 0 && it.revision >= 0
            },
        ) { "Collection list must reference a folder in the same collection" }
        require(
            providerBindings.all {
                it.id.isNotBlank() && it.canonicalTitleId in titleIds &&
                    it.providerId.isNotBlank() && it.facetId.isNotBlank() && it.externalWorkId.isNotBlank()
            },
        ) { "Invalid provider binding or missing canonical title" }
        require(readerPreferences.all { it.canonicalTitleId in titleIds }) {
            "Reader preference references a missing title"
        }
        require(
            chapterOverrides.all {
                it.id.isNotBlank() && it.canonicalTitleId in titleIds &&
                    it.kind.isNotBlank() && it.payloadJson.isNotBlank() &&
                    (it.sourceId == null) == (it.sourceTitleUrl == null) &&
                    (it.sourceChapterId == null || it.sourceId != null) &&
                    (it.canonicalChapterKey == null || it.canonicalChapterKey.isNotBlank()) &&
                    it.schemaVersion > 0 && it.revision >= 0
            },
        ) { "Invalid chapter override or missing canonical title" }
        require(contentPreferences.all { it.canonicalTitleId in titleIds }) {
            "Content preference refers to absent title"
        }
        require(
            libraryCategories.all {
                it.canonicalTitleId in titleIds && it.name.isNotBlank()
            },
        ) { "Invalid library category reference" }
        require(sourcePreferences.all { it.language.isNotBlank() && it.position >= 0 }) {
            "Invalid source preference"
        }
        require(continueReadingStates.all { it.canonicalTitleId in titleIds }) {
            "Continue reading state refers to absent title"
        }
        require(
            chapterUpdateStates.all {
                it.canonicalChapterId in chapterIds &&
                    chaptersById[it.canonicalChapterId]?.canonicalTitleId == it.canonicalTitleId
            },
        ) { "Chapter update state refers to an unrelated title or missing chapter" }
        require(titleArtwork.all { it.canonicalTitleId in titleIds && it.provider.isNotBlank() }) {
            "Artwork observation refers to missing title"
        }
        require(
            titleFormats.all {
                it.canonicalTitleId in titleIds && it.provider.isNotBlank() && it.format.isNotBlank()
            },
        ) { "Invalid title format observation" }
        require(
            titleNames.all {
                it.canonicalTitleId in titleIds && it.provider.isNotBlank() &&
                    it.sourceKey.isNotBlank() && it.value.isNotBlank()
            },
        ) { "Invalid title name observation" }
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
data class BackupCoreCollection(
    val id: String,
    val title: String,
    val origin: String,
    val sortOrder: Long,
    val schemaVersion: Long,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Serializable
data class BackupCoreFolder(
    val id: String,
    val collectionId: String,
    val parentFolderId: String?,
    val title: String,
    val origin: String,
    val sortOrder: Long,
    val schemaVersion: Long,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Serializable
data class BackupCoreList(
    val id: String,
    val collectionId: String,
    val folderId: String,
    val title: String,
    val providerId: String,
    val queryJson: String?,
    val sort: String,
    val sortDirection: String?,
    val layoutType: String?,
    val sortOrder: Long,
    val enabled: Boolean,
    val origin: String,
    val schemaVersion: Long,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Serializable
data class BackupCoreProviderBinding(
    val id: String,
    val canonicalTitleId: String,
    val providerId: String,
    val facetId: String,
    val externalWorkId: String,
    val verification: String,
    val availability: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreReaderPreference(
    val canonicalTitleId: String,
    val automaticFallback: Boolean,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreChapterOverride(
    val id: String,
    val canonicalTitleId: String,
    val canonicalChapterKey: String?,
    val sourceId: Long?,
    val sourceTitleUrl: String?,
    val sourceChapterId: String?,
    val kind: String,
    val payloadJson: String,
    val schemaVersion: Long,
    val revision: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

@Serializable
data class BackupCoreContentPreference(
    val canonicalTitleId: String,
    val preferredAddonId: String?,
    val preferredLanguage: String?,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreSourcePreference(
    val language: String,
    val sourceId: Long,
    val position: Long,
)

@Serializable
data class BackupCoreLibraryCategory(
    val canonicalTitleId: String,
    val name: String,
    val order: Long,
    val flags: Long,
)

@Serializable
data class BackupCoreContinueReadingState(
    val canonicalTitleId: String,
    val hiddenAt: Long?,
)

@Serializable
data class BackupCoreChapterUpdateState(
    val canonicalChapterId: String,
    val canonicalTitleId: String,
    val firstSeenAt: Long,
    val acknowledgedAt: Long?,
)

@Serializable
data class BackupCoreTitleArtwork(
    val canonicalTitleId: String,
    val provider: String,
    val coverUrl: String?,
    val bannerUrl: String?,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreTitleFormat(
    val canonicalTitleId: String,
    val provider: String,
    val format: String,
    val updatedAt: Long,
)

@Serializable
data class BackupCoreTitleName(
    val canonicalTitleId: String,
    val provider: String,
    val sourceKey: String,
    val value: String,
    val updatedAt: Long,
)

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
