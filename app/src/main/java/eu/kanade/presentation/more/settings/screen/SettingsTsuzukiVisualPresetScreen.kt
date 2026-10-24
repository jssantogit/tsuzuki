package eu.kanade.presentation.more.settings.screen

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import eu.kanade.presentation.more.settings.widget.AppThemePalettePreview
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.CheckCircle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

object SettingsTsuzukiVisualPresetScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val uiPreferences = remember(context) { context.appGraph.uiPreferences }
        val appThemePref = uiPreferences.appTheme
        val appTheme by appThemePref.collectAsState()
        val amoled by uiPreferences.themeDarkAmoled.collectAsState()
        val availablePresets = remember {
            AppTheme.entries.filter { theme ->
                theme.titleRes != null && (theme != AppTheme.MONET || DeviceUtil.isDynamicColorAvailable)
            }
        }
        val colors = defaultSkinColors()

        SettingsCanvas {
            LazyColumn {
                item(key = "visual_preset_header") {
                    SettingsPageHeader(
                        title = stringResource(MR.strings.tsuzuki_visual_preset_title),
                        navigateUp = navigator::pop,
                    )
                }

                items(availablePresets, key = AppTheme::name) { theme ->
                    val selected = appTheme == theme
                    val title = if (theme == AppTheme.DEFAULT) {
                        stringResource(MR.strings.tsuzuki_visual_preset_name)
                    } else {
                        stringResource(theme.titleRes!!)
                    }
                    val selectPreset = {
                        if (!selected) {
                            appThemePref.set(theme)
                            (context as? Activity)?.let { ActivityCompat.recreate(it) }
                        }
                    }
                    SettingsGroup(
                        title = if (theme == availablePresets.first()) {
                            stringResource(MR.strings.tsuzuki_visual_preset_current)
                        } else {
                            null
                        },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.RadioButton, onClick = selectPreset)
                                .semantics { this.selected = selected }
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TachiyomiTheme(appTheme = theme, amoled = amoled) {
                                AppThemePalettePreview(
                                    stableThemeKey = theme.name.lowercase(),
                                    themeName = title,
                                    selected = selected,
                                    selectionColor = colors.accent,
                                    unselectedBorderColor = colors.outline,
                                    onClick = selectPreset,
                                    interactive = false,
                                    modifier = Modifier.width(112.dp),
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.foreground,
                                )
                                Text(
                                    text = stringResource(presetDescription(theme)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.secondary,
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = MaterialSymbols.RoundedFilled.CheckCircle,
                                    contentDescription = stringResource(MR.strings.selected),
                                    tint = colors.accent,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun presetDescription(theme: AppTheme): StringResource = when (theme) {
    AppTheme.DEFAULT -> MR.strings.tsuzuki_visual_preset_summary
    AppTheme.MONET -> MR.strings.tsuzuki_preset_monet_summary
    AppTheme.CATPPUCCIN -> MR.strings.tsuzuki_preset_catppuccin_summary
    AppTheme.GREEN_APPLE -> MR.strings.tsuzuki_preset_green_apple_summary
    AppTheme.LAVENDER -> MR.strings.tsuzuki_preset_lavender_summary
    AppTheme.MIDNIGHT_DUSK -> MR.strings.tsuzuki_preset_midnight_dusk_summary
    AppTheme.NORD -> MR.strings.tsuzuki_preset_nord_summary
    AppTheme.STRAWBERRY_DAIQUIRI -> MR.strings.tsuzuki_preset_strawberry_daiquiri_summary
    AppTheme.TAKO -> MR.strings.tsuzuki_preset_tako_summary
    AppTheme.TEALTURQUOISE -> MR.strings.tsuzuki_preset_tealturquoise_summary
    AppTheme.TIDAL_WAVE -> MR.strings.tsuzuki_preset_tidal_wave_summary
    AppTheme.YINYANG -> MR.strings.tsuzuki_preset_yinyang_summary
    AppTheme.YOTSUBA -> MR.strings.tsuzuki_preset_yotsuba_summary
    AppTheme.TOKYONIGHT -> MR.strings.tsuzuki_preset_tokyonight_summary
    AppTheme.MONOCHROME -> MR.strings.tsuzuki_preset_monochrome_summary
    else -> MR.strings.tsuzuki_visual_preset_summary
}
