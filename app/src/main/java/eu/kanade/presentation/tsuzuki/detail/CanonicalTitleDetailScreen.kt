package eu.kanade.presentation.tsuzuki.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalChapterDetailItem
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private enum class DetailsSheet { SORT, JUMP, SOURCES }
private const val FULL_CHAPTER_FIRST_ITEM = 1

@Composable
fun CanonicalTitleDetailScreen(
    state: CanonicalTitleScreenState,
    navigateUp: () -> Unit,
    onAtualizar: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onReadTitle: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onBaixarChapter: (String) -> Unit,
    onOpenProvidersSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = defaultSkinColors().canvas
    Box(
        modifier.fillMaxSize().background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding(),
    ) {
        when (state) {
            CanonicalTitleScreenState.Loading -> {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                DetailsToolbar(navigateUp, onAtualizar, onOpenProvidersSettings)
            }
            is CanonicalTitleScreenState.Error -> {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_load_error),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    TextButton(onClick = onAtualizar) { Text(stringResource(MR.strings.action_retry)) }
                }
                DetailsToolbar(navigateUp, onAtualizar, onOpenProvidersSettings)
            }
            is CanonicalTitleScreenState.Loaded -> LoadedDetails(
                state, background, navigateUp, onAtualizar, onAddToLibrary, onRemoveFromLibrary,
                onReadTitle, onOpenChapter, onBaixarChapter, onOpenProvidersSettings,
            )
        }
    }
}

@Composable
private fun LoadedDetails(
    state: CanonicalTitleScreenState.Loaded,
    background: Color,
    navigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onReadTitle: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onDownload: (String) -> Unit,
    onProviders: () -> Unit,
) {
    var mode by rememberSaveable(state.title.id) { mutableStateOf(CanonicalDetailsLowerMode.COMPACT) }
    var sort by rememberSaveable(state.title.id) { mutableStateOf(CanonicalChapterSort.ORIGINAL) }
    var sheet by remember { mutableStateOf<DetailsSheet?>(null) }
    val chapters = remember(state.chapters, sort) { sortDetailChapters(state.chapters, sort) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var heroVisualHeight by remember(state.title.id) { mutableIntStateOf(0) }
    val toolbarOffset = WindowInsets.statusBars.getTop(density) + with(density) { 56.dp.roundToPx() }
    val moveMode: (CanonicalDetailsLowerAction) -> Unit = { action ->
        val target = detailsModeAfterAction(mode, action)
        if (target == null) {
            navigateUp()
        } else {
            mode = target
            scope.launch {
                listState.scrollToItem(
                    if (target == CanonicalDetailsLowerMode.FULL) 0 else 1,
                    if (target == CanonicalDetailsLowerMode.FULL) 0 else -toolbarOffset,
                )
            }
        }
    }
    BackHandler(enabled = mode != CanonicalDetailsLowerMode.COMPACT) { moveMode(CanonicalDetailsLowerAction.BACK) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = if (mode == CanonicalDetailsLowerMode.FULL) {
                PaddingValues(top = with(density) { toolbarOffset.toDp() }, bottom = 24.dp)
            } else {
                PaddingValues(bottom = 24.dp)
            },
        ) {
            if (mode != CanonicalDetailsLowerMode.FULL) {
                item(key = "hero") {
                CanonicalDetailsHero(
                    state,
                    background,
                    onReadTitle,
                    onAddToLibrary,
                    onRemoveFromLibrary,
                    onRefresh,
                    onVisualHeightChanged = { heroVisualHeight = it },
                )
            }
                item(key = "modes") { DetailsModes(mode, moveMode) }
            }
            when (mode) {
                CanonicalDetailsLowerMode.COMPACT -> item(key = "context") {
                    ContextualChapterRail(state, onOpenChapter, onProviders) {
                        moveMode(CanonicalDetailsLowerAction.VIEW_ALL)
                    }
                }
                CanonicalDetailsLowerMode.EDITORIAL -> item(key = "editorial") { DetailsEditorial(state) }
                CanonicalDetailsLowerMode.FULL -> {
                    item(key = "full-header") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                stringResource(MR.strings.tsuzuki_details_all_chapters),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            IconButton(onClick = { sheet = DetailsSheet.SORT }) {
                                Icon(
                                    MaterialSymbols.AutoMirroredRounded.Sort,
                                    stringResource(MR.strings.tsuzuki_details_sort),
                                )
                            }
                            IconButton(onClick = { sheet = DetailsSheet.JUMP }) {
                                Icon(MaterialSymbols.Rounded.Search, stringResource(MR.strings.tsuzuki_details_jump))
                            }
                        }
                    }
                    if (chapters.isEmpty()) {
                        item(key = "empty-chapters") {
                            ContextualChapterRail(state, onOpenChapter, onProviders) {}
                        }
                    }
                    items(chapters, key = { it.chapter.id }) { item ->
                        DetailsChapterRow(
                            item,
                            state.activeChapterId == item.chapter.id,
                            state.downloadInProgressChapterId == item.chapter.id,
                            onOpenChapter,
                            onDownload,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
        DetailsToolbar(
            onBack = { moveMode(CanonicalDetailsLowerAction.BACK) },
            onRefresh = onRefresh,
            onProviders = onProviders,
            background = if (mode == CanonicalDetailsLowerMode.FULL) {
                background
            } else {
                background.copy(
                    alpha = detailsToolbarFraction(
                        heroVisualHeight,
                        toolbarOffset,
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                    ),
                )
            },
            onSources = if (state.metadataSources.isNotEmpty()) ({ sheet = DetailsSheet.SOURCES }) else null,
        )
    }
    sheet?.let { current ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = background) {
            when (current) {
                DetailsSheet.SORT -> Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    Text(stringResource(MR.strings.tsuzuki_details_sort), style = MaterialTheme.typography.titleLarge)
                    CanonicalChapterSort.entries.forEach { value ->
                        TextButton(modifier = Modifier.semantics { selected = sort == value }, onClick = {
                            sort = value
                            sheet = null
                        }) {
                            Text(
                                stringResource(
                                    if (value ==
                                        CanonicalChapterSort.ORIGINAL
                                    ) {
                                        MR.strings.tsuzuki_details_order_original
                                    } else {
                                        MR.strings.tsuzuki_details_order_reversed
                                    },
                                ),
                            )
                        }
                    }
                }
                DetailsSheet.JUMP -> ChapterJumpSheet(chapters) { target ->
                    sheet = null
                    val index = chapters.indexOfFirst { it.chapter.id == target.chapter.id }
                    scope.launch { listState.animateScrollToItem(FULL_CHAPTER_FIRST_ITEM + index, -toolbarOffset) }
                }
                DetailsSheet.SOURCES -> Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                ) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_metadata_sources),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    state.metadataSources.forEach { Text(it, Modifier.padding(vertical = 8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DetailsModes(mode: CanonicalDetailsLowerMode, onAction: (CanonicalDetailsLowerAction) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(CanonicalDetailsLowerAction.CHAPTERS, CanonicalDetailsLowerAction.EDITORIAL).forEach { action ->
                val selected = if (action ==
                    CanonicalDetailsLowerAction.EDITORIAL
                ) {
                    mode == CanonicalDetailsLowerMode.EDITORIAL
                } else {
                    mode !=
                        CanonicalDetailsLowerMode.EDITORIAL
                }
                Column(
                    Modifier.weight(1f).selectable(selected, role = Role.Tab, onClick = { onAction(action) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(
                            if (action ==
                                CanonicalDetailsLowerAction.EDITORIAL
                            ) {
                                MR.strings.tsuzuki_details_editorial
                            } else {
                                MR.strings.tsuzuki_details_chapters
                            },
                        ),
                        Modifier.heightIn(min = 48.dp).padding(vertical = 14.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun DetailsToolbar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onProviders: () -> Unit,
    background: Color = Color.Transparent,
    onSources: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(
            background,
        ).statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                MaterialTheme.shapes.large,
            ),
        ) {
            Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, stringResource(MR.strings.tsuzuki_details_back))
        }
        Box {
            IconButton(
                onClick = {
                    expanded = true
                },
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    MaterialTheme.shapes.large,
                ),
            ) {
                Icon(MaterialSymbols.Rounded.MoreVert, stringResource(MR.strings.tsuzuki_details_actions))
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(MR.strings.tsuzuki_details_refresh)) }, onClick = {
                    expanded =
                        false
                    onRefresh()
                })
                DropdownMenuItem(text = { Text(stringResource(MR.strings.tsuzuki_details_providers)) }, onClick = {
                    expanded =
                        false
                    onProviders()
                })
                onSources?.let { action ->
                    DropdownMenuItem(text = {
                        Text(stringResource(MR.strings.tsuzuki_details_metadata_sources))
                    }, onClick = {
                        expanded =
                            false
                        action()
                    })
                }
            }
        }
    }
}

@Composable
private fun ChapterJumpSheet(chapters: List<CanonicalChapterDetailItem>, onJump: (CanonicalChapterDetailItem) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var notFound by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(Modifier.fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(MR.strings.tsuzuki_details_jump), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                notFound = false
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            label = { Text(stringResource(MR.strings.tsuzuki_details_jump_label)) },
            singleLine = true,
            isError = notFound,
            supportingText = if (notFound) {
                (
                    {
                        Text(stringResource(MR.strings.tsuzuki_details_jump_not_found))
                    }
                    )
            } else {
                null
            },
        )
        TextButton(onClick = {
            val chapter = resolveChapterJump(chapters, input)
            if (chapter == null) notFound = true else onJump(chapter)
        }) { Text(stringResource(MR.strings.tsuzuki_details_jump_go)) }
    }
}

/** Fade only as the visual hero's bottom crosses the toolbar; no pixel-threshold color switch. */
internal fun detailsToolbarFraction(heroHeight: Int, toolbarHeight: Int, itemIndex: Int, scrollOffset: Int): Float {
    if (itemIndex > 0) return 1f
    if (heroHeight <= 0) return 0f
    val transition = toolbarHeight.coerceAtLeast(1)
    return ((scrollOffset - heroHeight + transition * 2).toFloat() / transition).coerceIn(0f, 1f)
}
