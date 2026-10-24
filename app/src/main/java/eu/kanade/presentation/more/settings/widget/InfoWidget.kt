package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Info
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun InfoWidget(text: String) {
    val colors = defaultSkinColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = LocalPreferenceMinHeight.current)
            .padding(horizontal = PrefsHorizontalPadding, vertical = PrefsVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.Info,
            contentDescription = null,
            tint = colors.secondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            color = colors.secondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@PreviewLightDark
@Composable
private fun InfoWidgetPreview() {
    TachiyomiPreviewTheme {
        Surface {
            InfoWidget(text = stringResource(MR.strings.download_ahead_info))
        }
    }
}
