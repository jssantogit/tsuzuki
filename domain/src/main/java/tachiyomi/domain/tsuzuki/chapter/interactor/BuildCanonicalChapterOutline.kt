package tachiyomi.domain.tsuzuki.chapter.interactor

import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.orderCanonicalChapters
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount

/**
 * An existing, individually materialized chapter keeps its stable identity.
 * Reported totals are metadata: they do not enumerate chapters, fill ordinal
 * gaps or create new count-derived identities (issue #155).
 */
data class CanonicalChapterOutlineEntry(
    val chapter: CanonicalChapter,
    val inferredFromReportedCount: Boolean,
)

/**
 * Legacy identifier contract. Existing persisted references may still use
 * these IDs even though no new slots are synthesized from totals.
 */
fun inferredChapterId(canonicalTitleId: String, number: Int): String =
    "tsuzuki:count:$canonicalTitleId:$number"

fun isInferredChapter(chapter: CanonicalChapter): Boolean =
    chapter.type == CanonicalChapterType.REGULAR &&
        chapter.baseNumber?.let { it > 0 && chapter.id == inferredChapterId(chapter.canonicalTitleId, it) } == true &&
        chapter.part == null &&
        chapter.alphaSuffix == null

fun buildCanonicalChapterOutline(
    canonicalTitleId: String,
    observedChapters: List<CanonicalChapter>,
    @Suppress("UNUSED_PARAMETER") reportedCounts: List<ReportedChapterCount>,
): List<CanonicalChapterOutlineEntry> {
    require(observedChapters.all { it.canonicalTitleId == canonicalTitleId })
    val existingById = observedChapters.associateBy { it.id }

    // Keep historical count IDs, including rows with user progress or later
    // corroborating evidence. Hiding or deleting them could affect v1 sync.
    // Never use a total to produce additional CanonicalChapter instances.
    return orderCanonicalChapters(observedChapters).map { chapter ->
        val existing = existingById.getValue(chapter.id)
        CanonicalChapterOutlineEntry(
            chapter = existing,
            // The legacy count prefix is lineage, not proof that the row is
            // still uncorroborated after later authoritative observations.
            inferredFromReportedCount = isInferredChapter(existing) &&
                existing.confirmation != CanonicalChapterConfirmation.CONFIRMED,
        )
    }
}
