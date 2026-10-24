package eu.kanade.presentation.tsuzuki.collections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tachiyomi.domain.tsuzuki.home.model.HomeFolderTile

@Composable
internal fun FolderTile(
    folder: HomeFolderTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 200.dp,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val backgroundColor = remember(folder.folderId, dark) {
        folderTileColor(folder.folderId, dark)
    }
    val markColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

    Column(
        modifier = modifier
            .then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width))
            .clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(FOLDER_TILE_ASPECT_RATIO)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(backgroundColor),
        ) {
            val plane = Path().apply {
                moveTo(size.width * 0.13f, size.height * 0.22f)
                lineTo(size.width * 0.70f, size.height * 0.14f)
                lineTo(size.width * 0.83f, size.height * 0.73f)
                lineTo(size.width * 0.25f, size.height * 0.83f)
                close()
            }
            drawPath(plane, markColor.copy(alpha = 0.10f))
            for (position in listOf(0.40f, 0.52f, 0.64f)) {
                drawLine(
                    markColor,
                    Offset(size.width * 0.32f, size.height * position),
                    Offset(size.width * 0.67f, size.height * position),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        Text(
            text = folder.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun folderTileColor(folderId: String, dark: Boolean = false): Color {
    val hue = Math.floorMod(folderId.hashCode(), 360).toFloat()
    return Color.hsl(
        hue = hue,
        saturation = 0.20f,
        lightness = if (dark) 0.25f else 0.90f,
    )
}

private const val FOLDER_TILE_ASPECT_RATIO = 16f / 9f
