package eu.kanade.presentation.more.settings.widget

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun Modifier.highlightBackground(highlighted: Boolean): Modifier {
    val highlightColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceTint.copy(alpha = .12f)
    return highlightBackground(highlighted, highlightColor, restartOnChange = false)
}

@Composable
internal fun Modifier.settingsPreferenceHighlightBackground(highlighted: Boolean): Modifier {
    val colors = defaultSkinColors()
    return highlightBackground(highlighted, colors.surfaceMuted, restartOnChange = true)
}

@Composable
private fun Modifier.highlightBackground(
    highlighted: Boolean,
    highlightColor: Color,
    restartOnChange: Boolean,
): Modifier {
    var highlightFlag by remember { mutableStateOf(false) }
    val effectKey: Any = if (restartOnChange) highlighted else Unit
    LaunchedEffect(effectKey) {
        if (highlighted) {
            highlightFlag = true
            delay(3.seconds)
            highlightFlag = false
        } else {
            highlightFlag = false
        }
    }
    val highlight by animateColorAsState(
        targetValue = if (highlightFlag) highlightColor else Color.Transparent,
        animationSpec = if (highlightFlag) {
            repeatable(
                iterations = 5,
                animation = tween(durationMillis = 200),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(
                    offsetMillis = 600,
                    offsetType = StartOffsetType.Delay,
                ),
            )
        } else {
            tween(200)
        },
        label = "highlight",
    )
    return this.background(color = highlight)
}
