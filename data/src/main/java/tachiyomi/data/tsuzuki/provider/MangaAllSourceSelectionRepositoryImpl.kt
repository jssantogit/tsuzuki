package tachiyomi.data.tsuzuki.provider

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelection
import tachiyomi.domain.tsuzuki.provider.reading.MangaAllSourceSelectionRepository
import tachiyomi.domain.tsuzuki.provider.reading.ProviderBindingRef

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class MangaAllSourceSelectionRepositoryImpl(
    private val database: Database,
) : MangaAllSourceSelectionRepository {

    override suspend fun get(canonicalTitleId: String, language: String): MangaAllSourceSelection? =
        database.tsuzuki_mangaall_source_selectionsQueries.getMangaAllSourceSelection(
            canonicalTitleId = canonicalTitleId,
            language = language,
        ) { titleId, facet, workId, selectedAt ->
            MangaAllSourceSelection(
                canonicalTitleId = titleId,
                ref = ProviderBindingRef(
                    providerId = ProviderId("app.tsuzuki.mangaall"),
                    facetId = facet,
                    externalWorkId = workId,
                ),
                selectedAt = selectedAt,
            )
        }.awaitAsOneOrNull()

    override suspend fun getLatest(canonicalTitleId: String): MangaAllSourceSelection? =
        database.tsuzuki_mangaall_source_selectionsQueries.getLatestMangaAllSourceSelection(
            canonicalTitleId = canonicalTitleId,
        ) { titleId, facet, workId, selectedAt ->
            MangaAllSourceSelection(
                canonicalTitleId = titleId,
                ref = ProviderBindingRef(
                    providerId = ProviderId("app.tsuzuki.mangaall"),
                    facetId = facet,
                    externalWorkId = workId,
                ),
                selectedAt = selectedAt,
            )
        }.awaitAsOneOrNull()

    override suspend fun select(choice: MangaAllSourceSelection): Boolean {
        val language = choice.ref.facetId ?: return false
        database.tsuzuki_mangaall_source_selectionsQueries.selectMangaAllSource(
            canonicalTitleId = choice.canonicalTitleId,
            language = language,
            externalWorkId = choice.ref.externalWorkId,
            selectedAt = choice.selectedAt,
        )
        // The atomic INSERT...SELECT checks that the work still belongs
        // to this canonical title. A competing reassignment cannot create a
        // cross-title preference and failed writes cannot replace a binding.
        return get(choice.canonicalTitleId, language)?.ref == choice.ref
    }
}
