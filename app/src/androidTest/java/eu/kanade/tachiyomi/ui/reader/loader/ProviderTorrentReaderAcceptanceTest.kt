package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.provider.runtime.ProviderManagedFileStore
import eu.kanade.tachiyomi.provider.runtime.ProviderTorrentAcceptanceFixtures
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.runBlocking
import mihon.core.archive.ArchiveReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.provider.runtime.ProviderManagedResourceFormat
import tachiyomi.domain.tsuzuki.provider.ProviderId
import tachiyomi.domain.tsuzuki.provider.ProviderManagedFileFormat
import tachiyomi.domain.tsuzuki.provider.ProviderManagedResourceRef
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProviderTorrentReaderAcceptanceTest {

    @Test
    fun managedTorrentArchive_exposesThreeNaturallyOrderedReadyImagePages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-reader-acceptance").apply {
            deleteRecursively()
            mkdirs()
        }
        val fixture = ProviderTorrentAcceptanceFixtures.createThreeChapterTorrent(root)
        val managedRoot = File(root, "managed")
        val store = ProviderManagedFileStore(
            root = managedRoot,
            uriFactory = { file ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file,
                ).toString()
            },
        )

        try {
            Log.i(TAG, "stage=fixture archiveBytes=${fixture.selectedArchiveBytes.size}")
            val token = store.promote(
                providerId = PROVIDER_ID.value,
                bytes = fixture.selectedArchiveBytes,
                format = ProviderManagedResourceFormat.CBZ,
            )
            val uri = store.resolve(
                providerId = PROVIDER_ID,
                resource = ProviderManagedResourceRef(token),
                format = ProviderManagedFileFormat.CBZ,
            )
            assertNotNull("managed archive must resolve to a Reader URI", uri)
            Log.i(TAG, "stage=managed-resolve token=$token uri=$uri")

            val reader = context.contentResolver
                .openFileDescriptor(Uri.parse(uri), "r")
                .use { descriptor ->
                    val required = requireNotNull(descriptor)
                    Log.i(TAG, "stage=descriptor statSize=${required.statSize}")
                    ArchiveReader(required)
                }
            val loader = ArchivePageLoader(reader)

            try {
                val pages = loader.getPages()
                Log.i(
                    TAG,
                    "stage=pages count=${pages.size} indexes=${pages.map { it.index }} " +
                        "statuses=${pages.map { it.status }}",
                )

                assertEquals("Reader must expose exactly three image pages", 3, pages.size)
                assertEquals(
                    "Reader page indexes must reflect natural archive order",
                    listOf(0, 1, 2),
                    pages.map { it.index },
                )
                assertTrue(
                    "all Reader pages must be ready",
                    pages.all { it.status == Page.State.Ready },
                )

                val colors = pages.mapIndexed { index, page ->
                    val stream = requireNotNull(page.stream) {
                        "Reader page $index must expose a stream"
                    }.invoke()
                    stream.use {
                        val bitmap = BitmapFactory.decodeStream(it)
                        assertNotNull("Reader page $index stream must decode as an image", bitmap)
                        requireNotNull(bitmap).useBitmap { decoded ->
                            decoded.getPixel(0, 0).also { color ->
                                Log.i(TAG, "stage=decode index=$index color=$color")
                            }
                        }
                    }
                }
                Log.i(TAG, "stage=colors actual=$colors expected=${listOf(Color.RED, Color.GREEN, Color.BLUE)}")
                assertEquals(
                    "Reader page streams must preserve the naturally ordered fixture colors",
                    listOf(Color.RED, Color.GREEN, Color.BLUE),
                    colors,
                )
            } finally {
                loader.recycle()
            }
        } finally {
            store.clearAll()
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptManagedArchive_isRejectedBeforeReaderPagesAreExposed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "provider-torrent-reader-corrupt").apply {
            deleteRecursively()
            mkdirs()
        }
        val store = ProviderManagedFileStore(
            root = File(root, "managed"),
            uriFactory = { file -> file.toURI().toString() },
        )

        try {
            val failure = runCatching {
                store.promote(
                    providerId = PROVIDER_ID.value,
                    bytes = "not-a-zip".encodeToByteArray(),
                    format = ProviderManagedResourceFormat.CBZ,
                )
            }.exceptionOrNull()

            assertTrue(
                "corrupt archive must fail managed-file validation before Reader exposure",
                failure is IllegalStateException,
            )
            assertTrue(
                "rejected archive must not leave a managed file behind",
                root.walkTopDown().none { it.isFile },
            )
        } finally {
            store.clearAll()
            root.deleteRecursively()
        }
    }

    private inline fun <T> android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }

    private companion object {
        const val TAG = "ProviderTorrentReaderAcceptance"
        val PROVIDER_ID = ProviderId("org.example.torrent.reader.acceptance")
    }
}
