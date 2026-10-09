package eu.kanade.tachiyomi.provider.torrent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.provider.torrent.TorrentAcquisitionFailure

class ProviderTorrentAcquisitionExceptionTest {

    @Test
    fun `direct p2p consent failure points to the existing acquisition setting`() {
        val error = ProviderTorrentAcquisitionException(
            TorrentAcquisitionFailure.P2P_CONSENT_REQUIRED,
        )

        error.message shouldBe
            "Direct P2P consent is required. Enable Direct P2P in " +
            "Settings > Advanced > Provider torrent acquisition."
    }

    @Test
    fun `other acquisition failures keep the generic typed reason`() {
        val error = ProviderTorrentAcquisitionException(
            TorrentAcquisitionFailure.ACQUISITION_FAILED,
        )

        error.message shouldBe "Provider torrent acquisition failed: ACQUISITION_FAILED"
    }
}
