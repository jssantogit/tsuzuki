package tachiyomi.domain.tsuzuki.profile.model

import tachiyomi.domain.tsuzuki.account.model.AccountState
import tachiyomi.domain.tsuzuki.library.model.UnifiedLibraryTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus

/** A read model; the underlying account, library, progress and tracker owners remain authoritative. */
data class ProfileSnapshot(
    val account: AccountState,
    val reading: ProfileReadingStats,
    val trackers: List<ProfileTrackerState>,
) {
    // The account contract currently exposes only id/email, not public profile identity.
    val displayName: String? get() = null
    val avatarUrl: String? get() = null

    // These capabilities have no personal-favorite or completion-history contract yet.
    val supportsPersonalFavorites: Boolean get() = false
    val supportsCompletionHistory: Boolean get() = false
}

data class ProfileReadingStats(
    val currentlyReading: Int,
    val chaptersRead: Long,
) {
    /** Resume checkpoints cannot measure lifetime page consumption. Null means unavailable. */
    val pagesRead: Long? get() = null
}

data class ProfileTrackerState(
    val id: Long,
    val name: String,
    val isLoggedIn: Boolean,
)

/** UnifiedLibraryTitle already represents exactly one canonical title across all origins. */
fun countCurrentlyReading(titles: List<UnifiedLibraryTitle>): Int = titles.count {
    LibraryStatus.READING in it.statusesForOrigin(null)
}
