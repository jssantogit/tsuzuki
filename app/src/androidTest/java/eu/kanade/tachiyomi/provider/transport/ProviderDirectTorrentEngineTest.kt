package eu.kanade.tachiyomi.provider.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.frostwire.jlibtorrent.LibTorrent
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TcpEndpoint
import com.frostwire.jlibtorrent.TorrentBuilder
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.torrent_flags_t
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProviderDirectTorrentEngineTest {

    @Test
    fun directTorrent_selectsOneChapterFromLocalPackOverLoopbackP2p() {
        assertTrue("native libtorrent failed to load", LibTorrent.version().isNotBlank())

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "provider-torrent-spike").apply {
            deleteRecursively()
            mkdirs()
        }
        val seedRoot = File(root, "seed").apply { mkdirs() }
        val pack = File(seedRoot, "pack").apply { mkdirs() }
        val ignoredBytes = ByteArray(64 * 1024) { index -> (index % 251).toByte() }
        val selectedBytes = ByteArray(96 * 1024) { index -> ((index * 7) % 251).toByte() }
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

        val seedPort = 49101
        val leecherPort = 49102
        val seeder = SessionManager()
        val leecher = SessionManager()

        try {
            seeder.start(localOnlyParams(seedPort))
            seeder.download(torrent, seedRoot)
            await("seeder ready") {
                seeder.find(torrent)?.status(true)?.isSeeding == true
            }

            val leechRoot = File(root, "leech").apply { mkdirs() }
            leecher.start(localOnlyParams(leecherPort))
            leecher.download(
                torrent,
                leechRoot,
                null,
                null,
                listOf(TcpEndpoint("127.0.0.1", seedPort)),
                TorrentFlags.PAUSED,
            )

            val handle = awaitValue("leecher handle") { leecher.find(torrent) }
            val priorities = Priority.array(Priority.IGNORE, torrent.numFiles())
            priorities[selectedIndex] = Priority.NORMAL
            priorities[ignoredIndex] = Priority.IGNORE
            handle.prioritizeFiles(priorities)
            handle.resume()

            val selectedPath = File(leechRoot, files.filePath(selectedIndex))
            val ignoredPath = File(leechRoot, files.filePath(ignoredIndex))
            await("selected chapter download") {
                selectedPath.isFile && selectedPath.length() == selectedBytes.size.toLong()
            }

            assertArrayEquals(selectedBytes, selectedPath.readBytes())
            assertFalse(
                "ignored chapter should not be fully downloaded",
                ignoredPath.isFile && ignoredPath.length() == ignoredBytes.size.toLong(),
            )
        } finally {
            Thread { leecher.stop() }.apply { start(); join(10_000) }
            Thread { seeder.stop() }.apply { start(); join(10_000) }
            root.deleteRecursively()
        }
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
        timeoutMs: Long = 30_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        error("Timed out waiting for $label")
    }

    private fun <T : Any> awaitValue(
        label: String,
        timeoutMs: Long = 30_000,
        value: () -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        error("Timed out waiting for $label")
    }
}
