package eu.kanade.tachiyomi.provider.runtime

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frostwire.jlibtorrent.LibTorrent
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TcpEndpoint
import com.frostwire.jlibtorrent.TorrentBuilder
import com.frostwire.jlibtorrent.TorrentInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.core.provider.runtime.ProviderP2pAcquireRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        val seedRoot = File(root, "seed").apply { mkdirs() }
        val pack = File(seedRoot, "pack").apply { mkdirs() }
        val ignoredBytes = zipArchive(
            entryName = "page-001.bin",
            size = 64 * 1024,
            seed = 1L,
        )
        val selectedBytes = zipArchive(
            entryName = "page-002.bin",
            size = 96 * 1024,
            seed = 2L,
        )
        File(pack, "chapter-001.cbz").writeBytes(ignoredBytes)
        File(pack, "chapter-002.cbz").writeBytes(selectedBytes)

        val torrent = TorrentBuilder()
            .path(pack)
            .pieceSize(16 * 1024)
            .generate()
            .entry()
            .bencode()
            .let(::TorrentInfo)

        val files = torrent.files()
        val selectedIndex = (0 until files.numFiles())
            .single { files.filePath(it).endsWith("chapter-002.cbz") }
        val ignoredIndex = (0 until files.numFiles())
            .single { files.filePath(it).endsWith("chapter-001.cbz") }

        val seedPort = 49201
        val leecherPort = 49202
        val seeder = SessionManager()

        try {
            seeder.start(localOnlyParams(seedPort))
            seeder.download(torrent, seedRoot)
            await("seeder ready") {
                seeder.find(torrent)?.status(true)?.isSeeding == true
            }

            val engine = JlibtorrentProviderP2pDownloadEngine(
                metadataResolver = { _, _, _ -> torrent },
                initialPeers = {
                    listOf(TcpEndpoint("127.0.0.1", seedPort))
                },
                sessionParamsFactory = {
                    localOnlyParams(leecherPort)
                },
                downloadTimeoutMs = 30_000L,
            )
            val work = File(root, "work").apply { mkdirs() }
            val result = engine.download(
                request = ProviderP2pAcquireRequest(
                    operationId = "read:chapter-002",
                    magnetUri = torrent.makeMagnetUri(),
                    infoHash = torrent.infoHashV1()?.toHex(),
                    selectedFileIndex = selectedIndex,
                    selectedFilePath = files.filePath(selectedIndex),
                    selectedFileSizeBytes = files.fileSize(selectedIndex),
                ),
                workingDirectory = work,
            ) as ProviderP2pDownloadResult.Ready

            result.format shouldBeFormat ProviderManagedResourceFormat.CBZ
            assertTrue(
                "selected archive must remain inside the engine working directory",
                result.file.canonicalPath.startsWith(work.canonicalPath + File.separator),
            )
            assertArrayEquals(selectedBytes, result.file.readBytes())
            assertTrue(
                "selected archive size must match the torrent metadata",
                result.file.length() == selectedBytes.size.toLong(),
            )

            val ignoredPath = File(
                File(work, JlibtorrentProviderP2pDownloadEngine.DOWNLOAD_DIRECTORY),
                files.filePath(ignoredIndex),
            )
            assertFalse(
                "unselected chapter must not be fully downloaded",
                ignoredPath.isFile && ignoredPath.length() == ignoredBytes.size.toLong(),
            )
        } finally {
            Thread { seeder.stop() }.apply {
                start()
                join(10_000)
            }
            root.deleteRecursively()
        }
    }

    private infix fun ProviderManagedResourceFormat.shouldBeFormat(
        expected: ProviderManagedResourceFormat,
    ) {
        assertTrue("expected $expected but was $this", this == expected)
    }

    private fun localOnlyParams(port: Int): SessionParams {
        val settings = SettingsPack()
            .listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        return SessionParams(settings)
    }

    private fun await(
        label: String,
        timeoutMs: Long = 30_000L,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $label")
    }

    private fun zipArchive(
        entryName: String,
        size: Int,
        seed: Long,
    ): ByteArray {
        val bytes = ByteArray(size)
        Random(seed).nextBytes(bytes)
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
            output.toByteArray()
        }
    }
}
