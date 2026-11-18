package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract-only: no network or authored manga data in these classification tests. */
class MangaAllLiveReaderVerdictTest {
    @Test
    fun pixelsRequireActualLoaderImageAndRendererAcknowledgement() {
        assertEquals(
            "RENDERED_PIXELS",
            MangaAllLiveReaderVerdict.classify(
                pageMetadata = true,
                downloaded = true,
                decodable = true,
                viewerLoaded = true,
                windowPixels = true,
            ),
        )
        assertEquals(
            "PAGE_METADATA_ONLY",
            MangaAllLiveReaderVerdict.classify(
                pageMetadata = true,
                downloaded = false,
                decodable = false,
                viewerLoaded = false,
                windowPixels = false,
            ),
        )
        assertEquals(
            "BITMAP_DECODE_FAILED",
            MangaAllLiveReaderVerdict.classify(
                pageMetadata = true,
                downloaded = true,
                decodable = false,
                viewerLoaded = false,
                windowPixels = false,
            ),
        )
        assertEquals(
            "VIEWER_NOT_RENDERED",
            MangaAllLiveReaderVerdict.classify(
                pageMetadata = true,
                downloaded = true,
                decodable = true,
                viewerLoaded = false,
                windowPixels = false,
            ),
        )
        assertEquals(
            "WINDOW_PIXELS_MISSING",
            MangaAllLiveReaderVerdict.classify(
                pageMetadata = true,
                downloaded = true,
                decodable = true,
                viewerLoaded = true,
                windowPixels = false,
            ),
        )
    }

    @Test
    fun uniformBlackScreenCannotQualifyAsReaderImagePixels() {
        val uniform = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val content = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            uniform.eraseColor(android.graphics.Color.BLACK)
            content.eraseColor(android.graphics.Color.WHITE)
            content.setPixel(3, 3, android.graphics.Color.BLACK)
            assertEquals(false, MangaAllLiveReaderVerdict.hasImageContrast(uniform))
            assertEquals(true, MangaAllLiveReaderVerdict.hasImageContrast(content))
        } finally {
            uniform.recycle()
            content.recycle()
        }
    }

    @Test
    fun downloadedBytesCanNeverProvePixelsWithoutActualViewer() {
        assertEquals(
            "PAGES_NOT_OBTAINED",
            MangaAllLiveReaderVerdict.classify(
                pageMetadata = false,
                downloaded = true,
                decodable = true,
                viewerLoaded = true,
                windowPixels = true,
            ),
        )
    }
}
