package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsGroupDivider
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

object SettingsTsuzukiVisualPresetScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val colors = defaultSkinColors()

        SettingsCanvas {
            LazyColumn {
                item(key = "visual_preset_header") {
                    SettingsPageHeader(
                        title = stringResource(MR.strings.tsuzuki_visual_preset_title),
                        navigateUp = navigator::pop,
                    )
                }

                item(key = "visual_preset_group") {
                    SettingsGroup(title = stringResource(MR.strings.tsuzuki_visual_preset_current)) {
                        DefaultSkinSchematicPreview(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            colors = colors,
                        )
                        SettingsGroupDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { selected = true }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    text = stringResource(MR.strings.tsuzuki_visual_preset_name),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.foreground,
                                )
                                Text(
                                    text = stringResource(MR.strings.tsuzuki_visual_preset_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.secondary,
                                )
                            }
                            Icon(
                                imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
                                contentDescription = stringResource(MR.strings.selected),
                                tint = colors.accent,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultSkinSchematicPreview(
    modifier: Modifier = Modifier,
    colors: eu.kanade.presentation.tsuzuki.components.DefaultSkinColors,
) {
    Column(
        modifier = modifier
            .background(colors.canvas, DefaultSkinShape.Control)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.46f)
                .height(12.dp)
                .background(colors.foreground.copy(alpha = 0.82f), RoundedCornerShape(5.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.28f)
                .height(6.dp)
                .background(colors.secondary.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, DefaultSkinShape.Control)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreviewRow(long = true, colors = colors)
            PreviewRow(long = false, colors = colors)
        }
    }
}

@Composable
private fun PreviewRow(
    long: Boolean,
    colors: eu.kanade.presentation.tsuzuki.components.DefaultSkinColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(colors.surfaceMuted, DefaultSkinShape.Control),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (long) 0.72f else 0.52f)
                    .height(6.dp)
                    .background(colors.foreground.copy(alpha = 0.72f), RoundedCornerShape(4.dp)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (long) 0.48f else 0.36f)
                    .height(5.dp)
                    .background(colors.secondary.copy(alpha = 0.42f), RoundedCornerShape(4.dp)),
            )
        }
    }
}
