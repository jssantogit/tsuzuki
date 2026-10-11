package tachiyomi.domain.tsuzuki.profile.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import tachiyomi.domain.tsuzuki.account.repository.AccountRepository
import tachiyomi.domain.tsuzuki.library.interactor.ObserveUnifiedLibrary
import tachiyomi.domain.tsuzuki.profile.model.ProfileReadingStats
import tachiyomi.domain.tsuzuki.profile.model.ProfileSnapshot
import tachiyomi.domain.tsuzuki.profile.model.countCompletedTitles
import tachiyomi.domain.tsuzuki.profile.model.countCurrentlyReading
import tachiyomi.domain.tsuzuki.profile.service.ProfileTrackerSource
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository

@Inject
class ObserveProfile(
    private val accountRepository: AccountRepository,
    private val observeUnifiedLibrary: ObserveUnifiedLibrary,
    private val readingRepository: CanonicalReadingRepository,
    private val trackerSource: ProfileTrackerSource,
) {
    /** Cold Flow: the caller owns collection and cancellation; no eager jobs or remote refreshes. */
    fun subscribe(): Flow<ProfileSnapshot> = combine(
        accountRepository.state,
        observeUnifiedLibrary.subscribe().map { titles ->
            countCurrentlyReading(titles) to countCompletedTitles(titles)
        }.distinctUntilChanged(),
        readingRepository.observeReadChapterCount().distinctUntilChanged(),
        trackerSource.observe(),
    ) { account, titleCounts, chaptersRead, trackers ->
        ProfileSnapshot(
            account = account,
            reading = ProfileReadingStats(
                currentlyReading = titleCounts.first,
                chaptersRead = chaptersRead,
                completedTitles = titleCounts.second,
            ),
            trackers = trackers,
        )
    }.distinctUntilChanged()
}
