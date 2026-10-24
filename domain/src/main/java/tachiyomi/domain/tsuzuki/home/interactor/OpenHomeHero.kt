package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.interactor.OpenCanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.interactor.buildCanonicalChapterOutline
import tachiyomi.domain.tsuzuki.chapter.interactor.isVisibleCanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.home.model.isResumableCheckpoint
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

@Inject
class OpenHomeHero(
    private val materializeTitle: MaterializeCanonicalTitleFromCatalog,
    private val chapters: CanonicalChapterRepository,
    private val reading: CanonicalReadingRepository,
    private val counts: ReportedChapterCountRepository,
    private val openChapter: OpenCanonicalChapter,
    private val evidence: ChapterEvidenceRepository,
    private val downloads: CanonicalDownloadRepository,
) {
    suspend fun execute(item: CatalogItem): String? {
        val title = materializeTitle.execute(item)
        val progress = reading.getProgressByCanonicalTitleId(title.id)
        val progressIds = progress.mapTo(hashSetOf()) { it.canonicalChapterId }
        val supportedIds = evidence.getSupportSnapshot(title.id).mappedCanonicalChapterIds
        val downloadedIds = downloads.getChapterIdsByCanonicalTitle(title.id)
        val visible = chapters.getByCanonicalTitleId(title.id).filter { chapter ->
            isVisibleCanonicalChapter(
                chapter,
                hasProgress = chapter.id in progressIds,
                supported = chapter.id in supportedIds,
                downloaded = chapter.id in downloadedIds,
            )
        }
        val outline = buildCanonicalChapterOutline(title.id, visible, counts.getByTitle(title.id))
        val byId = outline.associateBy { it.chapter.id }
        val resumed = progress.asSequence()
            .filter { isResumableCheckpoint(it.read, it.updatedAt, it.lastPageRead, it.lastVariantId) }
            .filter { it.canonicalChapterId in byId }
            .maxByOrNull { it.updatedAt }
            ?.let { byId[it.canonicalChapterId] }
        val selected = resumed ?: outline.firstOrNull() ?: return null
        return openChapter.execute(selected.chapter).id
    }
}
