package eu.kanade.tachiyomi.data.tsuzuki.profile

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.profile.model.ProfileTrackerState

class ProfileTrackerSourceImplTest {

    @Test
    fun `projects the actual registered trackers including disconnected services`() = runTest {
        val registered = TrackerManager().trackers
        val trackers = registered.map { service -> tracker(service.id, service.name, MutableStateFlow(false)) }
        val manager = mockk<TrackerManager> { every { this@mockk.trackers } returns trackers }

        ProfileTrackerSourceImpl(manager).observe().first() shouldBe registered.map {
            ProfileTrackerState(it.id, it.name, false)
        }
    }

    @Test
    fun `login and logout follow tracker connection flows without loading other tracker data`() = runTest {
        val connection = MutableStateFlow(false)
        val trackers = listOf(tracker(1L, "MyAnimeList", connection))
        val manager = mockk<TrackerManager> { every { this@mockk.trackers } returns trackers }
        val snapshots = mutableListOf<List<ProfileTrackerState>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            ProfileTrackerSourceImpl(manager).observe().collect { snapshots.add(it) }
        }
        runCurrent()
        snapshots.last() shouldBe listOf(ProfileTrackerState(1L, "MyAnimeList", false))
        connection.value = true
        runCurrent()
        snapshots.last() shouldBe listOf(ProfileTrackerState(1L, "MyAnimeList", true))
        connection.value = false
        runCurrent()
        snapshots.last() shouldBe listOf(ProfileTrackerState(1L, "MyAnimeList", false))
    }

    @Test
    fun `empty registry emits a measured empty tracker list`() = runTest {
        val manager = mockk<TrackerManager> { every { trackers } returns emptyList() }
        ProfileTrackerSourceImpl(manager).observe().first() shouldBe emptyList()
    }

    private fun tracker(id: Long, name: String, connection: MutableStateFlow<Boolean>): Tracker = mockk {
        every { this@mockk.id } returns id
        every { this@mockk.name } returns name
        every { isLoggedInFlow } returns connection
    }
}
