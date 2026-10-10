package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.maps.shouldContainExactly
import org.junit.jupiter.api.Test

class ProviderP2pAlertCountersTest {

    @Test
    fun `native alert counters expose only allowlisted aggregate counts without native linkage`() {
        val counters = ProviderP2pAlertCounters()

        counters.recordFieldForTest("alertMetadataFailed")
        counters.recordFieldForTest("alertMetadataFailed")
        counters.recordFieldForTest("alertTrackerError")
        counters.recordFieldForTest("alertPeerConnect")
        counters.recordFieldForTest("alertPeerDisconnected")
        counters.recordFieldForTest("peerAddress")
        counters.recordFieldForTest("trackerUrl")

        counters.snapshotForTest() shouldContainExactly mapOf(
            "alertMetadataFailed" to 2L,
            "alertTrackerError" to 1L,
            "alertPeerConnect" to 1L,
            "alertPeerDisconnected" to 1L,
        )
    }
}
