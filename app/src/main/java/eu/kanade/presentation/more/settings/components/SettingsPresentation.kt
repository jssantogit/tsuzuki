package eu.kanade.presentation.more.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPageTitle
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.ArrowRight
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun SettingsCanvas(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = defaultSkinColors()
    CompositionLocalProvider(LocalContentColor provides colors.foreground) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(color = colors.canvas)
                .windowInsetsPadding(WindowInsets.systemBars),
            content = content,
        )
    }
}

@Composable
internal fun SettingsPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigateUp: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = defaultSkinColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = DefaultSkinSpacing.Section),
    ) {
        CompositionLocalProvider(LocalContentColor provides colors.foreground) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                navigateUp?.let { onBack ->
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                            contentDescription = stringResource(MR.strings.action_bar_up_description),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                actions()
            }
        }
        DefaultSkinPageTitle(
            text = title,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    surfaceTestTag: String? = null,
    headingTestTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = DefaultSkinSpacing.Section),
    ) {
        title?.takeIf(String::isNotBlank)?.let { groupTitle ->
            DefaultSkinSectionLabel(
                text = groupTitle,
                modifier = Modifier
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp)
                    .then(headingTestTag?.let(Modifier::testTag) ?: Modifier),
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(surfaceTestTag?.let(Modifier::testTag) ?: Modifier),
            shape = DefaultSkinShape.Card,
            color = colors.surface,
            contentColor = colors.foreground,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
internal fun SettingsGroupDivider(modifier: Modifier = Modifier) {
    val colors = defaultSkinColors()
    HorizontalDivider(
        modifier = modifier.padding(start = 16.dp),
        color = colors.outline.copy(alpha = 0.6f),
    )
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    selected: Boolean = false,
    showChevron: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = defaultSkinColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(DefaultSkinShape.Control)
            .background(if (selected) colors.surfaceMuted else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.let { leadingIcon ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = colors.foreground,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = colors.foreground,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.takeIf(String::isNotBlank)?.let { supportingText ->
                Text(
                    text = supportingText,
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing(this)
        } else if (showChevron) {
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowRight,
                contentDescription = null,
                tint = colors.secondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
