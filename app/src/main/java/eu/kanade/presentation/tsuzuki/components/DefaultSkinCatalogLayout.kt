package eu.kanade.presentation.tsuzuki.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Phone work grids always have three columns; navigation folder tiles are a different surface. */
internal fun defaultSkinWorkGridColumnCount(width: Dp): Int {
    if (width < 600.dp) return 3
    val usableWidth = (width - 32.dp).coerceAtLeast(0.dp)
    return ((usableWidth + 12.dp) / (126.dp + 12.dp)).toInt().coerceAtLeast(3)
}
