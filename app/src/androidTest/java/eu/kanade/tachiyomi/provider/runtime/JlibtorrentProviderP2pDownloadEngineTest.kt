package eu.kanade.tachiyomi.provider.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frostwire.jlibtorrent.LibTorrent
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TcpEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import java.io.File

@RunWith(AndroidJUnit4::class)
class JlibtorrentProviderP2pDownloadEngineTest {

    @Test
    fun directP2p_downloadsOnlySelectedArchiveFromPack() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "provider-p2p-production-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
        val seedPort = 49201
        val leecherPort = 49202
        val seeder = SessionManager()

        try {
            ProviderTorrentAcceptanceFixtures.startLoopbackSeeder(
                seeder = seeder,
                fixture = fixture,
                port = seedPort,
            )

            val engine = JlibtorrentProviderP2pDownloadEngine(
                metadataResolver = { _, _, _ -> fixture.torrent },
                initialPeers = {
                    listOf(TcpEndpoint("127.0.0.1", seedPort))
                },
                sessionParamsFactory = {
                    ProviderTorrentAcceptanceFixtures.localOnlyParams(leecherPort)
                },
                downloadTimeoutMs = 30_000L,
                nativeSupport = { true },
            )
            val work = File(root, "work").apply { mkdirs() }
            val result = engine.download(
                request = ProviderP2pAcquireRequest(
                    operationId = "read:chapter-012",
                    magnetUri = fixture.torrent.makeMagnetUri(),
                    infoHash = fixture.torrent.infoHashV1()?.toHex(),
                    selectedFileIndex = fixture.selectedFileIndex,
                    selectedFilePath = fixture.selectedFilePath,
                    selectedFileSizeBytes = fixture.selectedArchiveBytes.size.toLong(),
                ),
                workingDirectory = work,
            ) as ProviderP2pDownloadResult.Ready

            result.format shouldBeFormat ProviderManagedResourceFormat.CBZ
            assertTrue(
                "selected archive must remain inside the engine working directory",
                result.file.canonicalPath.startsWith(work.canonicalPath + File.separator),
            )
            assertArrayEquals(fixture.selectedArchiveBytes, result.file.readBytes())
            assertTrue(
                "selected archive size must match the torrent metadata",
                result.file.length() == fixture.selectedArchiveBytes.size.toLong(),
            )

            fixture.unselectedFileIndexes.forEach { ignoredIndex ->
                val ignoredPath = File(
                    File(work, JlibtorrentProviderP2pDownloadEngine.DOWNLOAD_DIRECTORY),
                    fixture.torrent.files().filePath(ignoredIndex),
                )
                assertFalse(
                    "unselected chapter must not be fully downloaded",
                    ignoredPath.isFile && ignoredPath.length() == fixture.torrent.files().fileSize(ignoredIndex),
                )
            }
        } finally {
            ProviderTorrentAcceptanceFixtures.stopLoopbackSeeder(seeder)
            root.deleteRecursively()
        }
    }

    private infix fun ProviderManagedResourceFormat.shouldBeFormat(
        expected: ProviderManagedResourceFormat,
    ) {
        assertTrue("expected $expected but was $this", this == expected)
    }
}
