package eu.kanade.presentation.more.settings.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.components.SettingsCanvas
import eu.kanade.presentation.more.settings.components.SettingsGroup
import eu.kanade.presentation.more.settings.components.SettingsGroupDivider
import eu.kanade.presentation.more.settings.components.SettingsNavigationRow
import eu.kanade.presentation.more.settings.components.SettingsPageHeader
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.runOnEnterKeyPressed
import cafe.adriel.voyager.core.screen.Screen as VoyagerScreen

class SettingsSearchScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val softKeyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        val focusRequester = remember { FocusRequester() }
        val listState = rememberLazyListState()
        val canPop = remember { navigator.canPop }
        val navigateUp: (() -> Unit)? = if (canPop) ({ navigator.pop() }) else null
        val colors = defaultSkinColors()

        DisposableEffect(Unit) {
            onDispose {
                softKeyboardController?.hide()
            }
        }

        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                focusManager.clearFocus()
            }
        }

        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }

        val textFieldState = rememberTextFieldState()
        SettingsCanvas {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsPageHeader(
                    title = stringResource(MR.strings.action_search_settings),
                    navigateUp = navigateUp,
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = DefaultSkinShape.Control,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.outline),
                    shadowElevation = 0.dp,
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BasicTextField(
                            state = textFieldState,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .runOnEnterKeyPressed(action = focusManager::clearFocus),
                            textStyle = MaterialTheme.typography.bodyLarge
                                .copy(color = colors.foreground),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            onKeyboardAction = { focusManager.clearFocus() },
                            cursorBrush = SolidColor(colors.accent),
                            decorator = {
                                if (textFieldState.text.isEmpty()) {
                                    Text(
                                        text = stringResource(MR.strings.action_search_settings),
                                        color = colors.secondary,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                it()
                            },
                        )
                        if (textFieldState.text.isNotEmpty()) {
                            IconButton(onClick = { textFieldState.clearText() }) {
                                Icon(
                                    imageVector = MaterialSymbols.Rounded.Close,
                                    contentDescription = null,
                                    tint = colors.secondary,
                                )
                            }
                        }
                    }
                }
                SearchResult(
                    modifier = Modifier.weight(1f),
                    searchKey = textFieldState.text.toString(),
                    listState = listState,
                ) { result ->
                    SearchableSettings.highlightKey = result.highlightKey
                    navigator.replace(result.route)
                }
            }
        }
    }
}

@Composable
private fun SearchResult(
    searchKey: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onItemClick: (SearchResultItem) -> Unit,
) {
    if (searchKey.isEmpty()) return

    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val colors = defaultSkinColors()

    val index = getIndex()
    val result by produceState<List<SearchResultItem>?>(initialValue = null, searchKey) {
        value = index.asSequence()
            .flatMap { settingsData ->
                settingsData.contents.asSequence()
                    .filter { it.enabled && it.title.isNotBlank() }
                    .flatMap { p ->
                        when (p) {
                            is Preference.PreferenceGroup -> {
                                if (p.enabled) {
                                    p.preferenceItems.asSequence()
                                        .filter { it.enabled && it.title.isNotBlank() }
                                        .map { p.title to it }
                                } else {
                                    emptySequence()
                                }
                            }
                            is Preference.PreferenceItem<*, *> -> sequenceOf(null to p)
                        }
                    }
                    .filterNot { it.second is Preference.PreferenceItem.InfoPreference }
                    .filter { (_, p) ->
                        val inTitle = p.title.contains(searchKey, true)
                        val inSummary = p.subtitle?.contains(searchKey, true) ?: false
                        inTitle || inSummary
                    }
                    .map { (categoryTitle, p) ->
                        SearchResultItem(
                            route = settingsData.route,
                            title = p.title,
                            breadcrumbs = getLocalizedBreadcrumb(
                                path = settingsData.title,
                                node = categoryTitle,
                                isLtr = isLtr,
                            ),
                            highlightKey = p.title,
                        )
                    }
            }
            .take(10)
            .toList()
    }

    Crossfade(
        targetState = result,
        modifier = modifier.fillMaxSize(),
        label = "results",
    ) { results ->
        when {
            results == null -> {}
            results.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(MR.strings.no_results_found),
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        vertical = DefaultSkinSpacing.Group,
                    ),
                ) {
                    item(key = "settings_search_results") {
                        SettingsGroup {
                            results.forEachIndexed { index, item ->
                                SettingsNavigationRow(
                                    title = item.title,
                                    subtitle = item.breadcrumbs,
                                    showChevron = false,
                                    onClick = { onItemClick(item) },
                                )
                                if (index < results.lastIndex) {
                                    SettingsGroupDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@NonRestartableComposable
private fun getIndex() = settingScreens
    .map { screen ->
        SettingsData(
            title = stringResource(screen.getTitleRes()),
            route = screen,
            contents = screen.getPreferences(),
        )
    }

private fun getLocalizedBreadcrumb(path: String, node: String?, isLtr: Boolean): String {
    return if (node == null) {
        path
    } else {
        if (isLtr) {
            "$path > $node"
        } else {
            "$node < $path"
        }
    }
}

private val settingScreens = listOf(
    SettingsAppearanceScreen,
    SettingsLibraryScreen,
    SettingsReaderScreen,
    SettingsDownloadScreen,
    SettingsTrackingScreen,
    SettingsDataScreen,
    SettingsSecurityScreen,
    SettingsTsuzukiLogsScreen,
    SettingsAdvancedScreen,
)

private data class SettingsData(
    val title: String,
    val route: VoyagerScreen,
    val contents: List<Preference>,
)

private data class SearchResultItem(
    val route: VoyagerScreen,
    val title: String,
    val breadcrumbs: String,
    val highlightKey: String,
)
