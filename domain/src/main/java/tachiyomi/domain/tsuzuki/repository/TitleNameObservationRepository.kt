package tachiyomi.domain.tsuzuki.repository

import tachiyomi.domain.tsuzuki.model.TitleNameObservation

interface TitleNameObservationRepository {
    suspend fun getByTitle(canonicalTitleId: String): List<TitleNameObservation>

    suspend fun upsert(observation: TitleNameObservation)
}
