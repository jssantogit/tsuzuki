package eu.kanade.tachiyomi.ui.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import java.io.InputStream

private const val LAST_PAGE_DECODE_DIMENSION_LIMIT = 512

/**
 * Archive entries become Ready before the Reader has decoded their image bytes.
 * Verify a bounded, sampled decode of the selected final page before completing
 * a canonical local chapter. Persist the page index even if this returns false.
 *
 * Some extended image formats (JPEG XL/JPEG 2000) use external decoders rather
 * than Android BitmapFactory; retain the existing header-only policy for those.
 */
internal suspend fun isCanonicalLocalPageReadable(page: ReaderPage): Boolean = withIOContext {
    val openStream = page.stream ?: return@withIOContext false
    try {
        val type = openStream().use { ImageUtil.findImageType(it) } ?: return@withIOContext false
        if (
            type == ImageUtil.ImageType.JXL ||
            type == ImageUtil.ImageType.JP2 ||
            type == ImageUtil.ImageType.JPX
        ) {
            return@withIOContext true
        }

        hasDecodableSample(openStream)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

private fun hasDecodableSample(openStream: () -> InputStream): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream().use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

    var sampleSize = 1
    val largestDimension = maxOf(bounds.outWidth, bounds.outHeight)
    while (largestDimension / sampleSize > LAST_PAGE_DECODE_DIMENSION_LIMIT && sampleSize < (1 shl 30)) {
        sampleSize *= 2
    }

    val sampled = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val bitmap = openStream().use { BitmapFactory.decodeStream(it, null, sampled) } ?: return false
    bitmap.recycle()
    return true
}
