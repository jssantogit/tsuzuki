package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun PreferenceScaffold(
    titleRes: StringResource,
    actions: @Composable RowScope.() -> Unit = {},
    onBackPressed: (() -> Unit)? = null,
    itemsProvider: @Composable () -> List<Preference>,
) {
    PreferenceScreen(
        title = stringResource(titleRes),
        items = itemsProvider(),
        navigateUp = onBackPressed,
        actions = actions,
        contentPadding = PaddingValues(bottom = 16.dp),
    )
}
