package tachiyomi.domain.tsuzuki.reader.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.chapter.model.orderCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

@Inject
class GetCanonicalReadingStart(
    private val canonicalChapterRepository: CanonicalChapterRepository,
    private val canonicalReadingRepository: CanonicalReadingRepository,
) {

    suspend fun execute(canonicalTitleId: String): CanonicalReadingStart {
        // Consumers and test repositories must use the same order as chapter
        // navigation, including volumes whose numbering restarts from one.
        val chapters = orderCanonicalChapters(canonicalChapterRepository.getByCanonicalTitleId(canonicalTitleId))
        if (chapters.isEmpty()) return CanonicalReadingStart.Unavailable(canonicalTitleId)

        val progressByChapter = canonicalReadingRepository
            .getProgressByCanonicalTitleId(canonicalTitleId)
            .associateBy { it.canonicalChapterId }

        // Timestamp remains authoritative. On an exact tie, prefer the later
        // chapter in canonical reading order, not the legacy numeric sortKey:
        // Vol. 2 Ch. 1 follows Vol. 1 Ch. 2 when volume numbering resets.
        val active = chapters.asReversed()
            .mapNotNull { chapter ->
                progressByChapter[chapter.id]
                    ?.takeIf { !it.read }
                    ?.let { progress -> chapter to progress }
            }
            .maxByOrNull { it.second.updatedAt }
            ?.first

        val target = active
            ?: chapters.firstOrNull { progressByChapter[it.id]?.read != true }
            ?: chapters.last()

        return CanonicalReadingStart.Ready(target.id)
    }
}
