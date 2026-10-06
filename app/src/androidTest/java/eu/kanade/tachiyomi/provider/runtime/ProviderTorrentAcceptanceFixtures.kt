package eu.kanade.tachiyomi.provider.runtime

import android.graphics.Bitmap
import android.graphics.Color
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentBuilder
import com.frostwire.jlibtorrent.TorrentInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProviderTorrentAcceptanceFixtures {

    data class ThreeChapterTorrent(
        val torrent: TorrentInfo,
        val seedRoot: File,
        val selectedFileIndex: Int,
        val selectedFilePath: String,
        val selectedArchiveBytes: ByteArray,
        val unselectedFileIndexes: List<Int>,
    )

    fun createThreeChapterTorrent(root: File): ThreeChapterTorrent {
        val seedRoot = File(root, "seed").apply { mkdirs() }
        val pack = File(seedRoot, "pack").apply { mkdirs() }

        val chapter011 = cbz(
            listOf("001.png" to tinyPng(Color.RED)),
        )
        val chapter012 = cbz(
            listOf(
                "003.png" to tinyPng(Color.BLUE),
                "001.png" to tinyPng(Color.RED),
                "002.png" to tinyPng(Color.GREEN),
            ),
        )
        val chapter013 = cbz(
            listOf("001.png" to tinyPng(Color.BLUE)),
        )

        File(pack, "chapter-011.cbz").writeBytes(chapter011)
        File(pack, "chapter-012.cbz").writeBytes(chapter012)
        File(pack, "chapter-013.cbz").writeBytes(chapter013)

        val torrent = TorrentBuilder()
            .path(pack)
            .pieceSize(16 * 1024)
            .generate()
            .entry()
            .bencode()
            .let(::TorrentInfo)
        val files = torrent.files()
        val selectedFileIndex = (0 until files.numFiles())
            .single { files.filePath(it).endsWith("chapter-012.cbz") }
        val selectedFilePath = files.filePath(selectedFileIndex)
        val unselectedFileIndexes = (0 until files.numFiles())
            .filterNot { it == selectedFileIndex }

        return ThreeChapterTorrent(
            torrent = torrent,
            seedRoot = seedRoot,
            selectedFileIndex = selectedFileIndex,
            selectedFilePath = selectedFilePath,
            selectedArchiveBytes = chapter012,
            unselectedFileIndexes = unselectedFileIndexes,
        )
    }

    fun localOnlyParams(port: Int): SessionParams {
        val settings = SettingsPack()
            .listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        return SessionParams(settings)
    }

    fun startLoopbackSeeder(
        seeder: SessionManager,
        fixture: ThreeChapterTorrent,
        port: Int,
    ) {
        seeder.start(localOnlyParams(port))
        seeder.download(fixture.torrent, fixture.seedRoot)
        await("loopback seeder ready") {
            seeder.find(fixture.torrent)?.status(true)?.isSeeding == true
        }
    }

    fun stopLoopbackSeeder(seeder: SessionManager) {
        Thread { seeder.stop() }.apply {
            start()
            join(10_000)
        }
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

    private fun cbz(entries: List<Pair<String, ByteArray>>): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun tinyPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, color)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
