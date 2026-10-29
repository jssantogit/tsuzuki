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
import java.util.zip.ZipFile
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

    @Test
    fun directP2p_selectsOnlyOneVerifiedImageDirectoryAndMaterializesReaderCbz() = runBlocking {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "provider-p2p-image-directory-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = ProviderTorrentAcceptanceFixtures.createDirectoryChapterTorrent(root)
        val seeder = SessionManager()
        val seedPort = 49205
        val leecherPort = 49206
        try {
            ProviderTorrentAcceptanceFixtures.startLoopbackSeeder(seeder, fixture, seedPort)
            val engine = JlibtorrentProviderP2pDownloadEngine(
                metadataResolver = { _, _, _ -> fixture.torrent },
                initialPeers = { listOf(TcpEndpoint("127.0.0.1", seedPort)) },
                sessionParamsFactory = { ProviderTorrentAcceptanceFixtures.localOnlyParams(leecherPort) },
                downloadTimeoutMs = 30_000L,
                nativeSupport = { true },
            )
            val first = fixture.selectedImages.first()
            val work = File(root, "work").apply { mkdirs() }
            val result = engine.download(
                request = ProviderP2pAcquireRequest(
                    operationId = "read:chapter-folder-01",
                    infoHash = fixture.torrent.infoHashV1()?.toHex(),
                    magnetUri = fixture.torrent.makeMagnetUri(),
                    selectedFileIndex = first.index,
                    selectedFilePath = first.path,
                    selectedFileSizeBytes = first.sizeBytes,
                    selectedChapterImages = fixture.selectedImages,
                ),
                workingDirectory = work,
            ) as ProviderP2pDownloadResult.Ready

            result.format shouldBeFormat ProviderManagedResourceFormat.CBZ
            assertTrue("Host must publish a packaged CBZ", result.file.isFile)
            ZipFile(result.file).use { archive ->
                assertTrue("all chapter pages must be present", archive.size() == fixture.expectedPages.size)
                fixture.expectedPages.forEachIndexed { index, expected ->
                    val entry = archive.getEntry("${(index + 1).toString().padStart(4, '0')}.png")
                    assertTrue("missing page ${index + 1}", entry != null)
                    assertArrayEquals(expected, archive.getInputStream(entry).use { it.readBytes() })
                }
            }
            fixture.ignoredIndices.forEach { ignored ->
                val path = File(
                    File(work, JlibtorrentProviderP2pDownloadEngine.DOWNLOAD_DIRECTORY),
                    fixture.torrent.files().filePath(ignored),
                )
                assertFalse(
                    "other chapter must not be downloaded",
                    path.isFile && path.length() == fixture.torrent.files().fileSize(ignored),
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
