package eu.kanade.tachiyomi.provider.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.frostwire.jlibtorrent.LibTorrent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import tachiyomi.core.provider.runtime.ProviderP2pFailureCode
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticEvent
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
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
            val recorder = RecordingRecorder()
            ProviderP2pDiagnostics.installRecorder(recorder)
            val result = try {
                engine.download(
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
            } finally {
                ProviderP2pDiagnostics.installRecorder(null)
            }
            assertTrue(
                recorder.lines.any {
                    "event=METADATA_START" in it && "phase=FETCH_MAGNET" in it &&
                        "source=MAGNET" in it && "timeoutMs=1000" in it
                },
            )
            assertTrue(
                recorder.lines.any {
                    "event=METADATA_FAILED" in it && "reason=FETCH_RETURNED_NULL" in it
                },
            )
            assertTrue(
                "without discovery, production fetchMagnet must fail closed: $result",
                result is ProviderP2pDownloadResult.Failure,
            )
            assertEquals(
                ProviderP2pFailureCode.METADATA_UNAVAILABLE,
                (result as ProviderP2pDownloadResult.Failure).reason,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exceptionWhileFetchingMetadata_isNotMisclassifiedAsTransferFailure() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "native-metadata-probe-exception").apply {
            deleteRecursively()
            mkdirs()
        }
        val recorder = RecordingRecorder()
        ProviderP2pDiagnostics.installRecorder(recorder)
        try {
            val engine = JlibtorrentProviderP2pDownloadEngine(
                metadataResolver = { _, _, _ -> throw IllegalStateException("private magnet URI must not be logged") },
                sessionParamsFactory = { ProviderTorrentAcceptanceFixtures.localOnlyParams(49406) },
                nativeSupport = { true },
            )
            val result = engine.download(
                request = ProviderP2pAcquireRequest(
                    operationId = "private-content-identity-should-be-salted",
                    magnetUri = "magnet:?xt=urn:btih:0123456789012345678901234567890123456789",
                    selectedFileIndex = 0,
                    selectedFilePath = "Acceptance Series 012.cbz",
                ),
                workingDirectory = File(root, "work"),
            )
            assertTrue(result is ProviderP2pDownloadResult.Failure)
            assertEquals(
                ProviderP2pFailureCode.NETWORK_ERROR,
                (result as ProviderP2pDownloadResult.Failure).reason,
            )
            assertTrue(recorder.lines.any {
                "event=METADATA_FAILED" in it && "stage=METADATA" in it &&
                    "failure=NETWORK_ERROR" in it && "exceptionClass=java.lang.IllegalStateException" in it
            })
            assertFalse(recorder.lines.any { "event=TRANSFER_FAILED" in it })
            assertFalse(
                recorder.lines.any {
                    "private" in it || "magnet:?" in it || "01234567890123456789" in it
                },
            )
        } finally {
            ProviderP2pDiagnostics.installRecorder(null)
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
            val recorder = RecordingRecorder()
            ProviderP2pDiagnostics.installRecorder(recorder)
            val result = try {
                engine.download(
                    request = ProviderP2pAcquireRequest(
                        operationId = "native-metadata-url-only",
                        torrentUrl = "http://127.0.0.1:49405/unreachable.torrent",
                        selectedFileIndex = fixture.selectedFileIndex,
                        selectedFilePath = fixture.selectedFilePath,
                        selectedFileSizeBytes = fixture.selectedArchiveBytes.size.toLong(),
                    ),
                    workingDirectory = File(root, "work"),
                )
            } finally {
                ProviderP2pDiagnostics.installRecorder(null)
            }
            assertTrue(
                recorder.lines.any {
                    "event=METADATA_FAILED" in it && "source=TORRENT_URL_UNUSED" in it &&
                        "reason=NO_MAGNET_SOURCE" in it
                },
            )
            assertTrue(
                "torrentUrl without a magnet/hash must not silently fetch: $result",
                result is ProviderP2pDownloadResult.Failure,
            )
            assertEquals(
                ProviderP2pFailureCode.METADATA_UNAVAILABLE,
                (result as ProviderP2pDownloadResult.Failure).reason,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private class RecordingRecorder : StructuredDiagnosticRecorder {
        override val sessionId = "11111111-1111-1111-1111-111111111111"
        val lines = mutableListOf<String>()

        override fun canonicalTitleReference(canonicalTitleId: String): String? = null

        override fun mihonMangaReference(mihonMangaId: Long): String? = null

        override fun record(event: StructuredDiagnosticEvent) {
            (event.attributes["provider_p2p_line"] as? DiagnosticAttributeValue.Text)
                ?.value
                ?.let(lines::add)
        }
    }
}
