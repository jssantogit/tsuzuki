package eu.kanade.tachiyomi.data.tsuzuki.instrumentation

/** No Reader success can be inferred from a URL, raw bytes or bitmap dimensions alone. */
internal object MangaAllLiveReaderVerdict {
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
