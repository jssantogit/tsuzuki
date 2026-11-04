package eu.kanade.tachiyomi.provider.runtime

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class P2pChapterImageArchiveBuilderTest {

    @Test
    fun twoVerifiedChapterPagesBecomeOneOrderedCbzWhileOtherChapterStaysOut() {
        withFiles { root, download ->
            val first = "Tokyo Ghoul Manga/Chapter 01/001.jpg"
            val second = "Tokyo Ghoul Manga/Chapter 01/002.png"
            val other = "Tokyo Ghoul Manga/Chapter 02/001.jpg"
            val jpg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            write(download, first, jpg)
            write(download, second, png)
            write(download, other, jpg)

            val selected = listOf(
                TorrentVerifiedImageFile(0, first, jpg.size.toLong()),
                TorrentVerifiedImageFile(1, second, png.size.toLong()),
            )
            val archive = P2pChapterImageArchiveBuilder().build(download, root, selected)
            (archive?.isFile) shouldBe true
            ZipFile(archive!!).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                names shouldBe listOf("0001.jpg", "0002.png")
                zip.getInputStream(zip.getEntry("0001.jpg")).use { it.readBytes().toList() } shouldBe jpg.toList()
                zip.getInputStream(zip.getEntry("0002.png")).use { it.readBytes().toList() } shouldBe png.toList()
            }
        }
    }

    @Test
    fun missingImageFailsClosedWithoutPublishingPartialCbz() {
        withFiles { root, download ->
            val missing = "Tokyo Ghoul Manga/Chapter 01/001.jpg"
            P2pChapterImageArchiveBuilder().build(
                download,
                root,
                listOf(TorrentVerifiedImageFile(0, missing, 100)),
            ) shouldBe null
            root.listFiles().orEmpty().none { it.extension == "cbz" } shouldBe true
        }
    }

    @Test
    fun imageWithMismatchedMetadataSizeIsRejected() {
        withFiles { root, download ->
            val path = "Tokyo Ghoul Manga/Chapter 01/001.jpg"
            write(download, path, byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00))
            P2pChapterImageArchiveBuilder().build(
                download,
                root,
                listOf(TorrentVerifiedImageFile(0, path, 999)),
            ) shouldBe null
        }
    }

    @Test
    fun unsafeImagePathsNeverEscapeTheDownloadRoot() {
        withFiles { root, download ->
            P2pChapterImageArchiveBuilder().build(
                download,
                root,
                listOf(TorrentVerifiedImageFile(0, "../outside.jpg", 4)),
            ) shouldBe null
        }
    }

    @Test
    fun invalidImageSignaturesCannotBecomeReadableChapterArchives() {
        withFiles { root, download ->
            val path = "Tokyo Ghoul Manga/Chapter 01/001.jpg"
            write(download, path, "not-an-image".toByteArray())
            P2pChapterImageArchiveBuilder().build(
                download,
                root,
                listOf(TorrentVerifiedImageFile(0, path, "not-an-image".length.toLong())),
            ) shouldBe null
        }
    }

    @Test
    fun symlinkedParentDirectoryCannotSupplyUnselectedTorrentImages() {
        withFiles { root, download ->
            val selectedPath = "Tokyo Ghoul Manga/Chapter 01/001.jpg"
            val otherPath = "other-release/Chapter 01/001.jpg"
            val jpg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00)
            write(download, otherPath, jpg)
            Files.createSymbolicLink(
                File(download, "Tokyo Ghoul Manga").toPath(),
                File(download, "other-release").toPath(),
            )

            P2pChapterImageArchiveBuilder().build(
                download,
                root,
                listOf(TorrentVerifiedImageFile(0, selectedPath, jpg.size.toLong())),
            ) shouldBe null
            root.listFiles().orEmpty().none { it.extension == "cbz" } shouldBe true
        }
    }

    private fun write(root: File, path: String, bytes: ByteArray) {
        File(root, path).apply {
            parentFile!!.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun withFiles(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("tsuzuki-p2p-chapter-").toFile()
        try {
            val download = File(root, "torrent-files").apply { mkdirs() }
            test(root, download)
        } finally {
            root.deleteRecursively()
        }
    }
}
