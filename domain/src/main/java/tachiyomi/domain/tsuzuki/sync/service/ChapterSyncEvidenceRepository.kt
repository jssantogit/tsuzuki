package tachiyomi.domain.tsuzuki.sync.service

fun interface ChapterSyncEvidenceRepository {
    suspend fun getStableEvidenceKey(canonicalChapterId: String): String?

    /**
     * Edition ownership is local only, absent from v1 and experimental v2
     * progress keys. Never infer portability from a numbered chapter alone.
     * Default keeps legacy in-memory adapters source-compatible.
     */
    suspend fun isEditionScopedChapter(canonicalChapterId: String): Boolean = false
}
