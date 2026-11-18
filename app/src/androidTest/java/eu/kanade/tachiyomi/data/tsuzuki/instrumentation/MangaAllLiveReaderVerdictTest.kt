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
