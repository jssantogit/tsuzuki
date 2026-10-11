package eu.kanade.tachiyomi.data.tsuzuki.profile

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tachiyomi.domain.tsuzuki.profile.model.ProfileTrackerState
import tachiyomi.domain.tsuzuki.profile.service.ProfileTrackerSource

@Inject
@ContributesBinding(AppScope::class)
class ProfileTrackerSourceImpl(
    private val trackerManager: TrackerManager,
) : ProfileTrackerSource {
    override fun observe(): Flow<List<ProfileTrackerState>> {
        val trackers = trackerManager.trackers
        if (trackers.isEmpty()) return flowOf(emptyList())
        return combine(
            trackers.map { tracker ->
                tracker.isLoggedInFlow.map { isLoggedIn ->
                    ProfileTrackerState(tracker.id, tracker.name, isLoggedIn)
                }
            },
        ) { states -> states.toList() }.distinctUntilChanged()
    }
}
