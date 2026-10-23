package eu.kanade.presentation.tsuzuki.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCoverFrame
import eu.kanade.presentation.manga.components.MangaCoverLoadEvent

internal const val TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO: Float = 2f / 3f

internal enum class TsuzukiDefaultSkinCoverSize(val width: Dp) {
    Standard(126.dp),
    Library(126.dp),
}

/** A Tsuzuki-owned catalog cover frame; the shared MangaCover renderer keeps image loading/fallbacks. */
@Composable
internal fun TsuzukiDefaultSkinCover(
    data: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: TsuzukiDefaultSkinCoverSize = TsuzukiDefaultSkinCoverSize.Standard,
    fallbackData: List<Any?> = emptyList(),
    foregroundModifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLoadEvent: ((MangaCoverLoadEvent) -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .widthIn(max = size.width)
            .fillMaxWidth()
            .aspectRatio(TSUZUKI_DEFAULT_SKIN_COVER_ASPECT_RATIO),
    ) {
        MangaCoverFrame(
            data = data,
            fallbackData = fallbackData,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize().then(foregroundModifier),
            shape = DefaultSkinShape.Control,
            onClick = onClick,
            onLoadEvent = onLoadEvent,
            contentScale = ContentScale.Crop,
        )
    }
}
