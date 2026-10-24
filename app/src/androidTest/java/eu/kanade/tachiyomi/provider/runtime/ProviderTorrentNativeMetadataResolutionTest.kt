package eu.kanade.tachiyomi.provider.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.frostwire.jlibtorrent.LibTorrent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import java.io.File

/**
 * Exercises the production metadata-resolution branch instead of injecting a hydrated
 * TorrentInfo as the positive loopback acquisition tests do.
 *
 * Networking is deliberately local-only with DHT/LSD disabled and no tracker or peer.
 * This is a fail-closed regression, not a public swarm test or a proof of the live bug.
 */
@RunWith(AndroidJUnit4::class)
class ProviderTorrentNativeMetadataResolutionTest {

    @Test
    fun previouslyInspectedTorrent_isNotReusedByMagnetOnlyProductionResolver() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-metadata-probe-magnet").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
            // The inspected torrent exists in the search fixture, but the P2P Host receives only
            // magnet + hash + selected-file evidence. No local peer, tracker, or DHT can hydrate it.
            val engine = JlibtorrentProviderP2pDownloadEngine(
                metadataTimeoutSeconds = 1,
                sessionParamsFactory = { ProviderTorrentAcceptanceFixtures.localOnlyParams(49402) },
                nativeSupport = { true },
            )
            val result = engine.download(
                request = ProviderP2pAcquireRequest(
                    operationId = "native-metadata-magnet-no-discovery",
                    magnetUri = fixture.torrent.makeMagnetUri(),
                    infoHash = fixture.torrent.infoHashV1()?.toHex(),
                    selectedFileIndex = fixture.selectedFileIndex,
                    selectedFilePath = fixture.selectedFilePath,
                    selectedFileSizeBytes = fixture.selectedArchiveBytes.size.toLong(),
                ),
                workingDirectory = File(root, "work"),
            )
            assertTrue("without discovery, production fetchMagnet must fail closed: $result", result is ProviderP2pDownloadResult.Failure)
            assertEquals(
                ProviderP2pFailureCode.METADATA_UNAVAILABLE,
                (result as ProviderP2pDownloadResult.Failure).reason,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun torrentUrlOnly_isNotTreatedAsNativeMetadataWithoutMagnetOrHash() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-metadata-probe-url-only").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
            val engine = JlibtorrentProviderP2pDownloadEngine(
                metadataTimeoutSeconds = 1,
                sessionParamsFactory = { ProviderTorrentAcceptanceFixtures.localOnlyParams(49404) },
                nativeSupport = { true },
            )
            val result = engine.download(
                request = ProviderP2pAcquireRequest(
                    operationId = "native-metadata-url-only",
                    torrentUrl = "http://127.0.0.1:49405/unreachable.torrent",
                    selectedFileIndex = fixture.selectedFileIndex,
                    selectedFilePath = fixture.selectedFilePath,
                    selectedFileSizeBytes = fixture.selectedArchiveBytes.size.toLong(),
                ),
                workingDirectory = File(root, "work"),
            )
            assertTrue("torrentUrl without a magnet/hash must not silently fetch: $result", result is ProviderP2pDownloadResult.Failure)
            assertEquals(
                ProviderP2pFailureCode.METADATA_UNAVAILABLE,
                (result as ProviderP2pDownloadResult.Failure).reason,
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
