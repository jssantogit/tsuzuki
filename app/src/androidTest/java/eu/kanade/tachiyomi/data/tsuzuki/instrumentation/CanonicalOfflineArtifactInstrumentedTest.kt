package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.tsuzuki.MihonCanonicalDownloadGateway
import eu.kanade.tachiyomi.ui.reader.isCanonicalLocalPageReadable
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real Android file/Uri preflight, confined to the disposable debug emulator's cache. */
@RunWith(AndroidJUnit4::class)
class CanonicalOfflineArtifactInstrumentedTest {

    @Test(timeout = 60_000L)
    fun missingEmptyAndReadableOfflineArtifactsAreDistinguished() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("true", InstrumentationRegistry.getArguments().getString("offlineArtifactOptIn"))
        val context = instrumentation.targetContext
        assertEquals("This fixture must not touch production storage", "app.tsuzuki.dev", context.packageName)

        val root = File(context.cacheDir, "tsuzuki-offline-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        try {
            val archive = File(root, "chapter.cbz")
            val archiveArtifact = artifact(archive, "CBZ")
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))

            archive.writeBytes(byteArrayOf())
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))

            // Truncated ZIP header and a valid but image-free ZIP are not readable chapters.
            archive.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))
            writeZip(archive, mapOf("README.txt" to "no pages".toByteArray()))
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))
            writeZip(archive, mapOf("001.jpg" to byteArrayOf(0x01)))
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))

            val page = pngPage()
            val validLastPage = ReaderPage(1).apply {
                stream = { ByteArrayInputStream(page) }
            }
            val damagedLastPage = ReaderPage(1).apply {
                stream = { ByteArrayInputStream(byteArrayOf(0x00, 0x01)) }
            }
            assertTrue(runBlocking { isCanonicalLocalPageReadable(validLastPage) })
            val headerOnlyLastPage = ReaderPage(1).apply {
                stream = { ByteArrayInputStream(page.copyOf(40)) }
            }
            assertFalse(
                "A valid PNG header with a truncated pixel payload must not finish a chapter",
                runBlocking { isCanonicalLocalPageReadable(headerOnlyLastPage) },
            )
            assertFalse(
                "A corrupt last image cannot qualify a canonical chapter for read completion",
                runBlocking { isCanonicalLocalPageReadable(damagedLastPage) },
            )
            assertFalse(
                "An unavailable image stream cannot qualify a chapter for completion",
                runBlocking { isCanonicalLocalPageReadable(ReaderPage(1)) },
            )
            writeZip(archive, mapOf("001.png" to page))
            assertTrue(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))
            writeZip(archive, mapOf("000.jpg" to byteArrayOf(0x01), "001.png" to page))
            assertTrue(
                "A malformed image must not mask another readable image in the archive",
                MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact),
            )

            val directory = File(root, "pages")
            assertTrue(directory.mkdirs())
            val directoryArtifact = artifact(directory, "DIRECTORY")
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, directoryArtifact))
            File(directory, "README.txt").writeText("not an image")
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, directoryArtifact))
            File(directory, "001.jpg").writeBytes(byteArrayOf(0x01))
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, directoryArtifact))
            File(directory, "002.png").writeBytes(page)
            assertTrue(MihonCanonicalDownloadGateway.isArtifactReadable(context, directoryArtifact))

            val epub = File(root, "chapter.epub")
            val epubArtifact = artifact(epub, "EPUB")
            writeZip(epub, mapOf("001.png" to page))
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, epubArtifact))
            val epubContainer = """<container><rootfiles>""" +
                """<rootfile full-path="OEBPS/content.opf"/>""" +
                """</rootfiles></container>"""
            val epubManifest = """<package><manifest>""" +
                """<item id="page" href="page.xhtml" media-type="application/xhtml+xml"/>""" +
                """</manifest><spine><itemref idref="page"/></spine></package>"""
            writeZip(
                epub,
                mapOf(
                    "META-INF/container.xml" to epubContainer.toByteArray(),
                    "OEBPS/content.opf" to epubManifest.toByteArray(),
                    "OEBPS/page.xhtml" to """<html><body><img src="001.png"/></body></html>""".toByteArray(),
                    "OEBPS/001.png" to page,
                ),
            )
            assertTrue(MihonCanonicalDownloadGateway.isArtifactReadable(context, epubArtifact))

            assertFalse(
                "An empty/missing file must not be a readable cached download",
                MihonCanonicalDownloadGateway.isArtifactReadable(context, artifact(directory, "CBZ")),
            )

            assertTrue(archive.delete())
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pngPage(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { bytes ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeZip(target: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun artifact(file: File, format: String) = CanonicalDownloadArtifact(
        canonicalChapterId = "fixture-canonical-chapter",
        localUri = Uri.fromFile(file).toString(),
        format = format,
        originatingAddonId = null,
        originatingOptionKey = null,
        completedAt = 1L,
        checksum = null,
    )
}
