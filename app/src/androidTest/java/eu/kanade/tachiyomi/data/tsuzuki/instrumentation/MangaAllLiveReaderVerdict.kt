package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

/** No Reader success can be inferred from a URL, raw bytes or bitmap dimensions alone. */
internal object MangaAllLiveReaderVerdict {
    /**
     * A successful PixelCopy of a black/blank window is not proof of a manga
     * page. Sample the actual Reader image-view rectangle, not other UI chrome.
     * A legitimate uniform image stays inconclusive rather than falsely READY.
     */
    fun hasImageContrast(bitmap: android.graphics.Bitmap): Boolean {
        if (bitmap.width < 2 || bitmap.height < 2) return false
        val background = bitmap.getPixel(0, 0)
        for (y in 0..7) {
            for (x in 0..7) {
                val px = x * (bitmap.width - 1) / 7
                val py = y * (bitmap.height - 1) / 7
                if (bitmap.getPixel(px, py) != background) return true
            }
        }
        return false
    }


    fun classify(
        pageMetadata: Boolean,
        downloaded: Boolean,
        decodable: Boolean,
        viewerLoaded: Boolean,
        windowPixels: Boolean,
    ): String = when {
        !pageMetadata -> "PAGES_NOT_OBTAINED"
        !downloaded -> "PAGE_METADATA_ONLY"
        !decodable -> "BITMAP_DECODE_FAILED"
        !viewerLoaded -> "VIEWER_NOT_RENDERED"
        !windowPixels -> "WINDOW_PIXELS_MISSING"
        else -> "RENDERED_PIXELS"
    }
}
