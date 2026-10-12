package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSecondaryButton
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun <T> ListPreferenceWidget(
    value: T,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    entries: Map<out T, String>,
    onValueChange: (T) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        val state = rememberLazyListState()
        PreferenceControlSheet(
            title = title,
            onDismissRequest = { isDialogShown = false },
            actions = {
                DefaultSkinSecondaryButton(
                    text = stringResource(MR.strings.action_cancel),
                    onClick = { isDialogShown = false },
                )
            },
        ) {
            ScrollbarLazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .height((entries.size * 72).coerceIn(56, 420).dp),
                state = state,
            ) {
                entries.entries.forEachIndexed { index, current ->
                    item {
                        PreferenceOptionRow(
                            label = current.value,
                            selected = value == current.key,
                            showDivider = index < entries.size - 1,
                            onSelectionChange = { isSelected ->
                                if (isSelected) {
                                    onValueChange(current.key!!)
                                    isDialogShown = false
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
