package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.LocalPreferenceEnabled
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors

@Composable
internal fun SliderPreferenceWidget(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    title: String,
    subtitle: String?,
    valueString: String,
    onValueChange: (Float) -> Unit,
) {
    val enabled = LocalPreferenceEnabled.current
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = LocalPreferenceMinHeight.current)
            .padding(horizontal = PrefsHorizontalPadding, vertical = PrefsVerticalPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = if (enabled) colors.foreground else colors.secondary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = valueString,
                color = colors.secondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        subtitle?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                color = colors.secondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = title
                    stateDescription = valueString
                },
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                activeTickColor = colors.onAccent,
                inactiveTrackColor = colors.outline,
                inactiveTickColor = colors.surfaceMuted,
                disabledThumbColor = colors.secondary,
                disabledActiveTrackColor = colors.secondary,
                disabledActiveTickColor = colors.surface,
                disabledInactiveTrackColor = colors.outline.copy(alpha = 0.6f),
                disabledInactiveTickColor = colors.surfaceMuted,
            ),
        )
    }
}
