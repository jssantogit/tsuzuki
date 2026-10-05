package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.tsuzuki.model.TitleNameObservation
import tachiyomi.domain.tsuzuki.repository.TitleNameObservationRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TitleNameObservationRepositoryImpl(
    private val database: Database,
) : TitleNameObservationRepository {

    override suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation> =
        database.tsuzuki_title_name_observationsQueries
            .getTsuzukiTitleNameObservationsByTitle(canonicalTitleId) {
                    titleId,
                    provider,
                    sourceKey,
                    value,
                    updatedAt,
                ->
                TitleNameObservation(
                    canonicalTitleId = titleId,
                    provider = provider,
                    sourceKey = sourceKey,
                    value = value,
                    updatedAt = updatedAt,
                )
            }
            .awaitAsList()

    override suspend fun upsert(observation: TitleNameObservation) {
        database.tsuzuki_title_name_observationsQueries.upsertTsuzukiTitleNameObservation(
            canonicalTitleId = observation.canonicalTitleId,
            provider = observation.provider,
            sourceKey = observation.sourceKey,
            value = observation.value,
            updatedAt = observation.updatedAt,
        )
    }
}
