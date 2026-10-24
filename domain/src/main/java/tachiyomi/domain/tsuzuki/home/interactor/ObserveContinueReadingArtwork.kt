package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.tsuzuki.artwork.repository.TitleArtworkRepository
import tachiyomi.domain.tsuzuki.artwork.resolveCanonicalArtwork
import tachiyomi.domain.tsuzuki.home.model.HomeContinueReadingItem
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga

/** Shared Home/Profile artwork enrichment; progress, visibility and ordering stay upstream. */
@Inject
class ObserveContinueReadingArtwork(
    private val observeHomeContinueReading: ObserveHomeContinueReading,
    private val titleArtworkRepository: TitleArtworkRepository,
    private val resolveCanonicalSourceManga: ResolveCanonicalSourceManga,
) {
    fun subscribe(limit: Int = 10): Flow<List<HomeContinueReadingItem>> = combine(
        observeHomeContinueReading.subscribe(limit),
        titleArtworkRepository.observeAll(),
    ) { items, observations ->
        val artworkByTitle = observations.groupBy { it.canonicalTitleId }
        items.map { item ->
            val artwork = resolveCanonicalArtwork(artworkByTitle[item.canonicalTitleId].orEmpty())
            val sourceManga = try {
                resolveCanonicalSourceManga.execute(item.canonicalTitleId, allowNetwork = false)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            item.copy(coverUrl = artwork?.coverUrl, sourceCover = sourceManga?.asMangaCover())
        }
    }
}
