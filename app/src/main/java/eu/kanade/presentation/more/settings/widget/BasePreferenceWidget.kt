package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.more.settings.LocalPreferenceEnabled
import eu.kanade.presentation.more.settings.LocalPreferenceHighlighted
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors

@Composable
internal fun BasePreferenceWidget(
    modifier: Modifier = Modifier,
    title: String? = null,
    subcomponent: @Composable (ColumnScope.() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    toggleableState: Boolean? = null,
    widget: @Composable (() -> Unit)? = null,
) {
    val highlighted = LocalPreferenceHighlighted.current
    val enabled = LocalPreferenceEnabled.current
    val minHeight = LocalPreferenceMinHeight.current
    val colors = defaultSkinColors()
    val interactionModifier = if (toggleableState != null) {
        Modifier.toggleable(
            value = toggleableState,
            enabled = enabled && onClick != null,
            role = Role.Switch,
            onValueChange = { onClick?.invoke() },
        )
    } else {
        Modifier.clickable(
            enabled = enabled && onClick != null,
            role = Role.Button,
            onClick = { onClick?.invoke() },
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .sizeIn(minHeight = minHeight)
            .clip(DefaultSkinShape.Control)
            .settingsPreferenceHighlightBackground(highlighted)
            .then(interactionModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .padding(start = PrefsHorizontalPadding, end = 8.dp)
                    .size(32.dp)
                    .clip(DefaultSkinShape.Control)
                    .background(colors.surfaceMuted),
                contentAlignment = Alignment.Center,
                content = { icon() },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = PrefsVerticalPadding),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    modifier = Modifier.padding(horizontal = PrefsHorizontalPadding),
                    text = title,
                    color = if (enabled) colors.foreground else colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            subcomponent?.invoke(this)
        }
        if (widget != null) {
            Box(
                modifier = Modifier.padding(end = PrefsHorizontalPadding),
                content = { widget() },
            )
        }
    }
}

internal val TrailingWidgetBuffer = 16.dp
internal val PrefsHorizontalPadding = 16.dp
internal val PrefsVerticalPadding = 12.dp
internal val TitleFontSize = 16.sp
