package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.tsuzuki.MihonCanonicalDownloadGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import java.io.File
import java.util.UUID

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

            archive.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            assertTrue(MihonCanonicalDownloadGateway.isArtifactReadable(context, archiveArtifact))

            val directory = File(root, "pages")
            assertTrue(directory.mkdirs())
            val directoryArtifact = artifact(directory, "DIRECTORY")
            assertFalse(MihonCanonicalDownloadGateway.isArtifactReadable(context, directoryArtifact))

            File(directory, "001.jpg").writeBytes(byteArrayOf(0x01))
            assertTrue(MihonCanonicalDownloadGateway.isArtifactReadable(context, directoryArtifact))

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
