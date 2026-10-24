package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Preview

@Composable
fun TextPreferenceWidget(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    widget: @Composable (() -> Unit)? = null,
    onPreferenceClick: (() -> Unit)? = null,
    toggleableState: Boolean? = null,
) {
    val colors = defaultSkinColors()
    BasePreferenceWidget(
        modifier = modifier,
        title = title,
        subcomponent = if (!subtitle.isNullOrBlank()) {
            {
                Text(
                    text = subtitle,
                    modifier = Modifier
                        .padding(horizontal = PrefsHorizontalPadding),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            null
        },
        icon = if (icon != null) {
            {
                Icon(
                    imageVector = icon,
                    tint = if (iconTint == Color.Unspecified) colors.secondary else iconTint,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
        onClick = onPreferenceClick,
        toggleableState = toggleableState,
        widget = widget,
    )
}

@PreviewLightDark
@Composable
private fun TextPreferenceWidgetPreview() {
    TachiyomiPreviewTheme {
        Surface {
            Column {
                TextPreferenceWidget(
                    title = "Text preference with icon",
                    subtitle = "Text preference summary",
                    icon = MaterialSymbols.Rounded.Preview,
                    onPreferenceClick = {},
                )
                TextPreferenceWidget(
                    title = "Text preference",
                    subtitle = "Text preference summary",
                    onPreferenceClick = {},
                )
            }
        }
    }
}
