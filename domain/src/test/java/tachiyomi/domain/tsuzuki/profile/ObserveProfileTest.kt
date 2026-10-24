package tachiyomi.domain.tsuzuki.profile

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.account.model.TsuzukiAccount
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.library.interactor.ObserveUnifiedLibrary
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.profile.interactor.ObserveProfile
import tachiyomi.domain.tsuzuki.profile.model.ProfileSnapshot
import tachiyomi.domain.tsuzuki.profile.model.ProfileTrackerState
import tachiyomi.domain.tsuzuki.profile.service.ProfileTrackerSource
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

class ObserveProfileTest {

    @Test
    fun `authenticated identity is the existing account without invented identity or capabilities`() = runTest {
        val account = TsuzukiAccount("account-id", "reader@example.org")
        val fixture = Fixture(AccountState.Authenticated(account))
        val snapshot = fixture.interactor.subscribe().first()

        snapshot.account shouldBe AccountState.Authenticated(account)
        snapshot.displayName shouldBe null
        snapshot.avatarUrl shouldBe null
        snapshot.reading.currentlyReading shouldBe 0
        snapshot.reading.completedTitles shouldBe 0
        snapshot.reading.chaptersRead shouldBe 0L
        snapshot.reading.pagesRead shouldBe null
        snapshot.supportsPersonalFavorites shouldBe false
        snapshot.supportsCompletionHistory shouldBe false
    }

    @Test
    fun `guest and pending email confirmation retain existing account semantics`() = runTest {
        val fixture = Fixture()
        fixture.interactor.subscribe().first().account shouldBe AccountState.LoggedOut
        fixture.account.value = AccountState.EmailConfirmationRequired("reader@example.org")
        fixture.interactor.subscribe().first().account shouldBe fixture.account.value
    }

    @Test
    fun `logout updates profile to guest without restarting library or progress subscriptions`() = runTest {
        val fixture = Fixture(AccountState.Authenticated(TsuzukiAccount("id", "reader@example.org")))
        val snapshots = mutableListOf<ProfileSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.interactor.subscribe().collect { snapshots.add(it) }
        }
        runCurrent()
        fixture.account.value = AccountState.LoggedOut
        runCurrent()

        snapshots.last().account shouldBe AccountState.LoggedOut
        snapshots.last().displayName shouldBe null
        fixture.librarySubscriptions shouldBe 1
        fixture.progressSubscriptions shouldBe 1
    }

    @Test
    fun `snapshot reacts independently to library progress and tracker changes`() = runTest {
        val fixture = Fixture()
        val snapshots = mutableListOf<ProfileSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.interactor.subscribe().collect { snapshots.add(it) }
        }
        runCurrent()
        fixture.library.value = listOf(
            UnifiedLibraryTitle(
                title = CanonicalTitle("title", "Title", CanonicalIdentityState.RESOLVED, 0L, 0L),
                localEntry = CanonicalLibraryEntry("title", LibraryStatus.READING, true, 0L, 0L),
                externalMemberships = emptyList(),
            ),
        )
        fixture.chapters.value = 7L
        fixture.trackers.value = listOf(
            ProfileTrackerState(1L, "MyAnimeList", true),
            ProfileTrackerState(3L, "Kitsu", false),
        )
        runCurrent()

        snapshots.last().reading.currentlyReading shouldBe 1
        snapshots.last().reading.chaptersRead shouldBe 7L
        snapshots.last().trackers shouldBe fixture.trackers.value
        snapshots.last().reading.pagesRead shouldBe null
        fixture.trackers.value = listOf(ProfileTrackerState(1L, "MyAnimeList", false))
        runCurrent()
        snapshots.last().trackers shouldBe fixture.trackers.value
        fixture.librarySubscriptions shouldBe 1
        fixture.progressSubscriptions shouldBe 1
    }

    @Test
    fun `completion changes update snapshot without changing reading or chapters`() = runTest {
        val fixture = Fixture()
        val snapshots = mutableListOf<ProfileSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.interactor.subscribe().collect { snapshots.add(it) }
        }
        runCurrent()
        fixture.library.value = listOf(
            UnifiedLibraryTitle(
                title = CanonicalTitle("title", "Title", CanonicalIdentityState.RESOLVED, 0L, 0L),
                localEntry = CanonicalLibraryEntry("title", LibraryStatus.COMPLETED, true, 0L, 0L),
                externalMemberships = emptyList(),
            ),
        )
        runCurrent()
        snapshots.last().reading.completedTitles shouldBe 1
        snapshots.last().reading.currentlyReading shouldBe 0
        snapshots.last().reading.chaptersRead shouldBe 0L
        fixture.library.value = emptyList()
        runCurrent()
        snapshots.last().reading.completedTitles shouldBe 0
        fixture.librarySubscriptions shouldBe 1
    }

    @Test
    fun `cancelling profile collection releases upstream subscriptions`() = runTest {
        val fixture = Fixture()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fixture.interactor.subscribe().collect {}
        }
        runCurrent()
        job.cancel()
        runCurrent()
        fixture.activeLibrarySubscriptions shouldBe 0
        fixture.activeProgressSubscriptions shouldBe 0
    }

    private class Fixture(initialAccount: AccountState = AccountState.LoggedOut) {
        val account = MutableStateFlow(initialAccount)
        val library = MutableStateFlow<List<UnifiedLibraryTitle>>(emptyList())
        val chapters = MutableStateFlow(0L)
        val trackers = MutableStateFlow<List<ProfileTrackerState>>(emptyList())
        var librarySubscriptions = 0
        var progressSubscriptions = 0
        var activeLibrarySubscriptions = 0
        var activeProgressSubscriptions = 0
        private val accountRepository = mockk<AccountRepository> { every { state } returns account }
        private val unifiedLibrary = mockk<ObserveUnifiedLibrary> {
            every { subscribe() } returns flow {
                librarySubscriptions++
                activeLibrarySubscriptions++
                try {
                    library.collect { emit(it) }
                } finally {
                    activeLibrarySubscriptions--
                }
            }
        }
        private val readingRepository = mockk<CanonicalReadingRepository> {
            every { observeReadChapterCount() } returns flow {
                progressSubscriptions++
                activeProgressSubscriptions++
                try {
                    chapters.collect { emit(it) }
                } finally {
                    activeProgressSubscriptions--
                }
            }
        }
        private val trackerSource = mockk<ProfileTrackerSource> { every { observe() } returns trackers }
        val interactor = ObserveProfile(accountRepository, unifiedLibrary, readingRepository, trackerSource)
    }
}
