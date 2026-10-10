package eu.kanade.tachiyomi.provider.runtime

import com.frostwire.jlibtorrent.alerts.AlertType
import io.kotest.matchers.maps.shouldContainExactly
import org.junit.jupiter.api.Test

class ProviderP2pAlertCountersTest {

    @Test
    fun `native alert counters expose only allowlisted aggregate counts`() {
        val counters = ProviderP2pAlertCounters()

        counters.recordTypeForTest(AlertType.METADATA_FAILED)
        counters.recordTypeForTest(AlertType.METADATA_FAILED)
        counters.recordTypeForTest(AlertType.TRACKER_ERROR)
        counters.recordTypeForTest(AlertType.PEER_CONNECT)
        counters.recordTypeForTest(AlertType.PEER_DISCONNECTED)
        counters.recordTypeForTest(AlertType.UNKNOWN)

        counters.snapshotForTest() shouldContainExactly mapOf(
            "alertMetadataFailed" to 2L,
            "alertTrackerError" to 1L,
            "alertPeerConnect" to 1L,
            "alertPeerDisconnected" to 1L,
        )
    }
}
