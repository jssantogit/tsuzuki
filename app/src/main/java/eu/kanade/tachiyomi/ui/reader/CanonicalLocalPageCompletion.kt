package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil

/**
 * A local ReaderPage may be marked Ready as soon as its archive entry is listed:
 * that alone is not proof its stream contains an image.
 *
 * Check only the final page when deciding whether to mark a *canonical* local
 * chapter read. Preserve its checkpoint index if the page is damaged, without
 * falsely marking the chapter as completed or changing other Reader modes.
 *
 * This checks the image header; it is not a full-pixel/CRC integrity scan.
 */
internal suspend fun isCanonicalLocalPageReadable(page: ReaderPage): Boolean = withIOContext {
    val openStream = page.stream ?: return@withIOContext false
    try {
        openStream().use { stream -> ImageUtil.findImageType(stream) != null }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}
