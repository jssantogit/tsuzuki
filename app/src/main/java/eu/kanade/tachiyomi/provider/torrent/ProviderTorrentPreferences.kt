package eu.kanade.tachiyomi.provider.torrent

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionPreference

/**
 * User-owned acquisition policy. Direct P2P stays disabled until explicit opt-in.
 */
@Inject
@SingleIn(AppScope::class)
class ProviderTorrentPreferences(
    preferenceStore: PreferenceStore,
) {
    val acquisitionPreference: Preference<TorrentAcquisitionPreference> =
        preferenceStore.getEnum(
            key = "provider_torrent_acquisition_preference",
            defaultValue = TorrentAcquisitionPreference.DEBRID_THEN_P2P,
        )

    val directP2pAllowed: Preference<Boolean> =
        preferenceStore.getBoolean(
            key = "provider_torrent_direct_p2p_allowed",
            defaultValue = false,
        )
}
