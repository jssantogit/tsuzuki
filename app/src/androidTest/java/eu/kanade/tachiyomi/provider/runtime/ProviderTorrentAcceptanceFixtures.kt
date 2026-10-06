package eu.kanade.tachiyomi.provider.runtime

import android.graphics.Bitmap
import android.graphics.Color
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentBuilder
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object ProviderTorrentAcceptanceFixtures {

    data class ThreeChapterTorrentFixture(
        val torrent: TorrentInfo,
        val seedRoot: File,
        val selectedIndex: Int,
        val selectedPath: String,
        val selectedBytes: ByteArray,
        val ignoredIndexes: List<Int>,
    )

    fun createThreeChapterTorrent(root: File): ThreeChapterTorrentFixture {
        val seedRoot = File(root, "seed").apply { mkdirs() }
        val pack = File(seedRoot, "pack").apply { mkdirs() }

        File(pack, "chapter-011.cbz").writeBytes(
            cbz(
                "001.png" to png(Color.RED),
            ),
        )
        val selectedBytes = cbz(
            "001.png" to png(Color.RED),
            "002.png" to png(Color.GREEN),
            "003.png" to png(Color.BLUE),
        )
        File(pack, "chapter-012.cbz").writeBytes(selectedBytes)
        File(pack, "chapter-013.cbz").writeBytes(
            cbz(
                "001.png" to png(Color.YELLOW),
            ),
        )

        val torrent = TorrentBuilder()
            .path(pack)
            .pieceSize(16 * 1024)
            .generate()
            .entry()
            .bencode()
            .let(::TorrentInfo)
        val files = torrent.files()
        val selectedIndex = (0 until files.numFiles())
            .single { files.filePath(it).endsWith("chapter-012.cbz") }
        val ignoredIndexes = (0 until files.numFiles())
            .filter { it != selectedIndex }

        return ThreeChapterTorrentFixture(
            torrent = torrent,
            seedRoot = seedRoot,
            selectedIndex = selectedIndex,
            selectedPath = files.filePath(selectedIndex),
            selectedBytes = selectedBytes,
            ignoredIndexes = ignoredIndexes,
        )
    }

    fun localOnlyParams(port: Int): SessionParams {
        val settings = SettingsPack()
            .listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        return SessionParams(settings)
    }

    fun await(
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

    private fun cbz(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) {
                    bitmap.setPixel(x, y, color)
                }
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
