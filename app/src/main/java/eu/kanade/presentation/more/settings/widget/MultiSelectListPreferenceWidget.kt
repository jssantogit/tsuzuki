package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSecondaryButton
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun <T> MultiSelectListPreferenceWidget(
    values: Set<T>,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    entries: Map<out T, String>,
    onValuesChange: (Set<T>) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        val selected = remember {
            entries.keys
                .filter { values.contains(it) }
                .toMutableStateList()
        }
        val state = rememberLazyListState()
        PreferenceControlSheet(
            title = title,
            onDismissRequest = { isDialogShown = false },
            actions = {
                DefaultSkinSecondaryButton(
                    text = stringResource(MR.strings.action_cancel),
                    onClick = { isDialogShown = false },
                )
                DefaultSkinPrimaryButton(
                    text = stringResource(MR.strings.action_ok),
                    onClick = {
                        onValuesChange(selected.toMutableSet())
                        isDialogShown = false
                    },
                )
            },
        ) {
            ScrollbarLazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((entries.size * 72).coerceIn(56, 420).dp),
                state = state,
            ) {
                entries.entries.forEachIndexed { index, current ->
                    item {
                        val isSelected = selected.contains(current.key)
                        PreferenceOptionRow(
                            label = current.value,
                            selected = isSelected,
                            toggleable = true,
                            showDivider = index < entries.size - 1,
                            onSelectionChange = { selectedValue ->
                                if (selectedValue) {
                                    selected.add(current.key)
                                } else {
                                    selected.remove(current.key)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
