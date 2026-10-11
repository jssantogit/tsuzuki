package tachiyomi.domain.tsuzuki.profile.service

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.profile.model.ProfileTrackerState

/** Read-only adapter for the app's existing tracker registry and login state. */
interface ProfileTrackerSource {
    fun observe(): Flow<List<ProfileTrackerState>>
}
