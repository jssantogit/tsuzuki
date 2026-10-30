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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalChapterDetailItem
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.detail.InventoryWorkDiscoveryStatus
import eu.kanade.tachiyomi.ui.tsuzuki.detail.NativeInventoryRefreshState
import eu.kanade.tachiyomi.ui.tsuzuki.detail.ProviderEditionPickerState
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.domain.tsuzuki.chapter.inventory.IndependentInventorySnapshot
import tachiyomi.domain.tsuzuki.chapter.inventory.IndependentInventorySourceSnapshot
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchFailureReason
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryFetchOutcome
import tachiyomi.domain.tsuzuki.chapter.inventory.InventoryRefreshStop
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionCandidate
import tachiyomi.domain.tsuzuki.provider.reading.ProviderEditionConfirmation
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

private enum class DetailsSheet { SORT, JUMP, SOURCES, INVENTORY, EDITION }
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
    onImportEpub: () -> Unit = {},
    onReviewCandidates: (IndependentInventorySourceSnapshot) -> Unit = {},
    inventoryRefresh: NativeInventoryRefreshState,
    editionPicker: ProviderEditionPickerState = ProviderEditionPickerState(),
    onChooseEdition: () -> Unit = {},
    onEditionSearch: (String) -> Unit = {},
    onEditionConfirm: (ProviderEditionCandidate) -> Unit = {},
    onEditionDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    val background = colors.canvas
    Box(
        modifier.fillMaxSize().background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).navigationBarsPadding(),
    ) {
        when (state) {
            CanonicalTitleScreenState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = colors.accent)
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
                        color = colors.foreground,
                    )
                    DefaultSkinTextAction(
                        text = stringResource(MR.strings.action_retry),
                        onClick = onAtualizar,
                    )
                }
                DetailsToolbar(navigateUp, onAtualizar, onOpenProvidersSettings)
            }
            is CanonicalTitleScreenState.Loaded -> LoadedDetails(
                state, background, navigateUp, onAtualizar, onAddToLibrary, onRemoveFromLibrary,
                onReadTitle, onOpenChapter, onBaixarChapter, onOpenProvidersSettings,
                onImportEpub, onReviewCandidates, inventoryRefresh,
                editionPicker, onChooseEdition, onEditionSearch, onEditionConfirm, onEditionDismiss,
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
    onImportEpub: () -> Unit,
    onReviewCandidates: (IndependentInventorySourceSnapshot) -> Unit,
    inventoryRefresh: NativeInventoryRefreshState,
    editionPicker: ProviderEditionPickerState,
    onChooseEdition: () -> Unit,
    onEditionSearch: (String) -> Unit,
    onEditionConfirm: (ProviderEditionCandidate) -> Unit,
    onEditionDismiss: () -> Unit,
) {
    var mode by rememberSaveable(state.title.id) { mutableStateOf(CanonicalDetailsLowerMode.COMPACT) }
    var sort by rememberSaveable(state.title.id) { mutableStateOf(CanonicalChapterSort.ORIGINAL) }
    var sheet by remember { mutableStateOf<DetailsSheet?>(null) }
    var editionQuery by remember(state.title.id) { mutableStateOf(state.title.displayTitle) }
    var selectedEdition by remember(state.title.id) { mutableStateOf<ProviderEditionCandidate?>(null) }
    LaunchedEffect(editionPicker.open) {
        if (editionPicker.open) {
            editionQuery = editionPicker.query
            sheet = DetailsSheet.EDITION
        } else if (sheet == DetailsSheet.EDITION) {
            sheet = null
            selectedEdition = null
        }
    }
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
                CanonicalDetailsLowerMode.COMPACT -> {
                    item(key = "context") {
                        ContextualChapterRail(
                            state,
                            onOpenChapter,
                            onProviders,
                            onAll = { moveMode(CanonicalDetailsLowerAction.VIEW_ALL) },
                            onChooseEdition = onChooseEdition,
                        )
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
                            ContextualChapterRail(
                                state,
                                onOpenChapter,
                                onProviders,
                                onAll = {},
                                onChooseEdition = onChooseEdition,
                            )
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
            onImportEpub = onImportEpub,
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
            onInventory = { sheet = DetailsSheet.INVENTORY },
        )
    }
    sheet?.let { current ->
        val colors = defaultSkinColors()
        ModalBottomSheet(
            onDismissRequest = {
                if (current == DetailsSheet.EDITION) onEditionDismiss()
                sheet = null
            },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            containerColor = colors.surface,
            contentColor = colors.foreground,
            scrimColor = colors.canvas.copy(alpha = 0.55f),
            tonalElevation = 0.dp,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 12.dp, bottom = 8.dp)
                        .heightIn(min = 4.dp)
                        .fillMaxWidth(0.08f)
                        .background(colors.outline, DefaultSkinShape.Pill),
                )
            },
        ) {
            when (current) {
                DetailsSheet.SORT -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_sort),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.foreground,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    CanonicalChapterSort.entries.forEach { value ->
                        val isSelected = sort == value
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) colors.surfaceMuted else Color.Transparent,
                                    DefaultSkinShape.Control,
                                )
                                .selectable(
                                    selected = isSelected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        sort = value
                                        sheet = null
                                    },
                                )
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(
                                    if (value == CanonicalChapterSort.ORIGINAL) {
                                        MR.strings.tsuzuki_details_order_original
                                    } else {
                                        MR.strings.tsuzuki_details_order_reversed
                                    },
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.foreground,
                            )
                            if (isSelected) {
                                Text("✓", color = colors.accent, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                DetailsSheet.JUMP -> ChapterJumpSheet(chapters) { target ->
                    sheet = null
                    val index = chapters.indexOfFirst { it.chapter.id == target.chapter.id }
                    scope.launch { listState.animateScrollToItem(FULL_CHAPTER_FIRST_ITEM + index, -toolbarOffset) }
                }
                DetailsSheet.EDITION -> Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(MR.strings.tsuzuki_edition_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.foreground,
                        )
                        Text(
                            stringResource(MR.strings.tsuzuki_edition_description),
                            color = colors.secondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedTextField(
                            value = editionQuery,
                            onValueChange = { editionQuery = it },
                            label = { Text(stringResource(MR.strings.tsuzuki_edition_search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        DefaultSkinTextAction(
                            text = stringResource(MR.strings.tsuzuki_edition_find),
                            onClick = {
                                selectedEdition = null
                                onEditionSearch(editionQuery)
                            },
                            enabled = !editionPicker.searching &&
                                !editionPicker.confirming && editionQuery.isNotBlank(),
                        )
                        if (editionPicker.searching || editionPicker.confirming) {
                            CircularProgressIndicator(color = colors.accent)
                        }
                        if (editionPicker.failed) {
                            Text(stringResource(MR.strings.tsuzuki_edition_error), color = colors.secondary)
                        }
                        if (!editionPicker.searching && editionPicker.candidates.isEmpty()) {
                            Text(stringResource(MR.strings.tsuzuki_edition_none), color = colors.secondary)
                        }
                        editionPicker.candidates.forEach { candidate ->
                            val selected = candidate == selectedEdition
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (selected) colors.surfaceMuted else Color.Transparent,
                                        DefaultSkinShape.Control,
                                    )
                                    .selectable(
                                        selected = selected,
                                        enabled = !editionPicker.confirming,
                                        role = Role.RadioButton,
                                        onClick = { selectedEdition = candidate },
                                    )
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.title, color = colors.foreground)
                                    Text(
                                        candidate.providerName + " · " + (candidate.ref.facetId ?: "—"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.secondary,
                                    )
                                    candidate.url?.let { url ->
                                        Text(
                                            url,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.secondary,
                                        )
                                    }
                                }
                                if (selected) Text("✓", color = colors.accent)
                            }
                        }
                        editionPicker.confirmation?.let { result ->
                            val message = when (result) {
                                ProviderEditionConfirmation.BLOCKED -> MR.strings.tsuzuki_edition_conflict
                                ProviderEditionConfirmation.STALE -> MR.strings.tsuzuki_edition_stale
                                ProviderEditionConfirmation.UNAVAILABLE -> MR.strings.tsuzuki_edition_unavailable
                                else -> MR.strings.tsuzuki_edition_unavailable
                            }
                            Text(stringResource(message), color = colors.secondary)
                        }
                    }
                    HorizontalDivider(color = colors.outline)
                    DefaultSkinTextAction(
                        text = stringResource(MR.strings.tsuzuki_edition_confirm),
                        onClick = { selectedEdition?.let(onEditionConfirm) },
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp),
                        enabled = selectedEdition in editionPicker.candidates &&
                            !editionPicker.searching && !editionPicker.confirming,
                    )
                }
                DetailsSheet.INVENTORY -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    // Detailed evidence is available on demand, never in the chapter feed.
                    IndependentInventoryInfo(
                        state.independentInventory,
                        inventoryRefresh,
                        onReviewCandidates = { candidate ->
                            sheet = null
                            onReviewCandidates(candidate)
                        },
                    )
                }
                DetailsSheet.SOURCES -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_metadata_sources),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.foreground,
                    )
                    state.metadataSources.forEach {
                        Text(it, color = colors.secondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun IndependentInventoryInfo(
    snapshot: IndependentInventorySnapshot,
    refresh: NativeInventoryRefreshState,
    onReviewCandidates: (IndependentInventorySourceSnapshot) -> Unit,
) {
    val colors = defaultSkinColors()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(MR.strings.tsuzuki_inventory_title),
            style = MaterialTheme.typography.labelLarge,
            color = colors.foreground,
        )
        Text(
            stringResource(MR.strings.tsuzuki_inventory_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
        )
        Text(
            stringResource(MR.strings.tsuzuki_inventory_auto_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
        )
        if (refresh.refreshing) {
            Text(
                stringResource(MR.strings.tsuzuki_inventory_fetching),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }
        refresh.discoveryStatus.forEach { (producerId, status) ->
            val source = when (producerId) {
                "mangadex" -> "MangaDex"
                "mangaupdates" -> "MangaUpdates"
                else -> producerId
            }
            val label = when (status) {
                InventoryWorkDiscoveryStatus.SEARCHING -> MR.strings.tsuzuki_inventory_discovery_searching
                InventoryWorkDiscoveryStatus.LINKED -> MR.strings.tsuzuki_inventory_discovery_linked
                InventoryWorkDiscoveryStatus.NO_VERIFIED_MATCH -> MR.strings.tsuzuki_inventory_discovery_no_match
                InventoryWorkDiscoveryStatus.FAILED -> MR.strings.tsuzuki_inventory_discovery_failed
                InventoryWorkDiscoveryStatus.REQUIRES_MANGADEX -> MR.strings.tsuzuki_inventory_discovery_requires_md
                InventoryWorkDiscoveryStatus.INTERRUPTED -> MR.strings.tsuzuki_inventory_discovery_interrupted
            }
            Text(
                "$source · ${stringResource(label)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }
        refresh.refreshStops.forEach { (producerId, stop) ->
            val source = when (producerId) {
                "mangadex" -> "MangaDex"
                "mangaupdates" -> "MangaUpdates"
                else -> producerId
            }
            val label = when (stop) {
                InventoryRefreshStop.EXHAUSTED -> MR.strings.tsuzuki_inventory_refresh_exhausted
                InventoryRefreshStop.PARTIAL -> MR.strings.tsuzuki_inventory_refresh_partial
                InventoryRefreshStop.FAILED -> MR.strings.tsuzuki_inventory_refresh_failed
                InventoryRefreshStop.NO_VERIFIED_WORK -> MR.strings.tsuzuki_inventory_refresh_unlinked
                InventoryRefreshStop.DISABLED -> MR.strings.tsuzuki_inventory_refresh_disabled
            }
            Text(
                "$source · ${stringResource(MR.strings.tsuzuki_inventory_release_status, stringResource(label))}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
            if (stop == InventoryRefreshStop.FAILED) {
                refresh.refreshFailureReasons[producerId]?.let { InventoryFailureReasonText(it) }
            }
        }
        refresh.lastStop?.takeIf { refresh.refreshStops.isEmpty() }?.let { stop ->
            Text(
                stringResource(
                    when (stop) {
                        InventoryRefreshStop.EXHAUSTED -> MR.strings.tsuzuki_inventory_refresh_exhausted
                        InventoryRefreshStop.PARTIAL -> MR.strings.tsuzuki_inventory_refresh_partial
                        InventoryRefreshStop.FAILED -> MR.strings.tsuzuki_inventory_refresh_failed
                        InventoryRefreshStop.NO_VERIFIED_WORK -> MR.strings.tsuzuki_inventory_refresh_unlinked
                        InventoryRefreshStop.DISABLED -> MR.strings.tsuzuki_inventory_refresh_disabled
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }
        if (refresh.lastStop == InventoryRefreshStop.FAILED && refresh.refreshStops.isEmpty()) {
            refresh.producerId?.let { producerId ->
                refresh.refreshFailureReasons[producerId]?.let { InventoryFailureReasonText(it) }
            }
        }
        if (snapshot.sources.isEmpty()) {
            Text(
                stringResource(MR.strings.tsuzuki_inventory_unknown),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
            return@Column
        }
        Text(
            stringResource(MR.strings.tsuzuki_inventory_partial),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondary,
        )
        snapshot.sources.forEach { source ->
            val providerName = when (source.scope.producerId) {
                "mangadex" -> "MangaDex"
                "mangaupdates" -> "MangaUpdates"
                else -> source.scope.producerId
            }
            Text(providerName, style = MaterialTheme.typography.labelMedium, color = colors.foreground)
            val editionKey = source.scope.editionKey?.takeIf(String::isNotBlank)
            Text(
                stringResource(
                    MR.strings.tsuzuki_inventory_scope,
                    source.scope.externalWorkId,
                    editionKey?.let {
                        stringResource(
                            MR.strings.tsuzuki_inventory_edition_scope_value,
                            it,
                            stringResource(
                                if (source.scope.editionIdentityVerified) {
                                    MR.strings.tsuzuki_inventory_edition_verified
                                } else {
                                    MR.strings.tsuzuki_inventory_edition_unverified
                                },
                            ),
                        )
                    } ?: stringResource(MR.strings.tsuzuki_inventory_edition_unrecorded),
                    source.scope.language?.takeIf(String::isNotBlank)
                        ?: stringResource(MR.strings.tsuzuki_inventory_language_unrecorded),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
            val latestAttempt = source.latestAttemptOutcomes
                .sortedBy { it.name }
                .map { outcome ->
                    stringResource(
                        when (outcome) {
                            InventoryFetchOutcome.PARTIAL -> MR.strings.tsuzuki_inventory_outcome_partial
                            InventoryFetchOutcome.EXHAUSTED -> MR.strings.tsuzuki_inventory_outcome_exhausted
                            InventoryFetchOutcome.FAILED -> MR.strings.tsuzuki_inventory_outcome_failed
                            InventoryFetchOutcome.NOT_ATTEMPTED -> MR.strings.tsuzuki_inventory_outcome_not_attempted
                        },
                    )
                }
                .ifEmpty {
                    listOf(
                        stringResource(
                            if (source.latestAttemptAt == null) {
                                MR.strings.tsuzuki_inventory_no_attempt
                            } else {
                                MR.strings.tsuzuki_inventory_outcome_unrecorded
                            },
                        ),
                    )
                }
                .joinToString("; ")
            if (source.latestAttemptAt == null && source.latestAttemptOutcomes.isEmpty()) {
                Text(latestAttempt, style = MaterialTheme.typography.bodySmall, color = colors.secondary)
            } else {
                val attemptTime = source.latestAttemptAt?.let { relativeTimeSpanString(it) }
                    ?: stringResource(MR.strings.tsuzuki_inventory_time_unrecorded)
                Text(
                    stringResource(MR.strings.tsuzuki_inventory_latest_attempt, latestAttempt, attemptTime),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            val successfulFetchTime = source.latestSuccessfulFetchAt?.let { relativeTimeSpanString(it) }
            val claimObservationTime = source.latestClaimObservedAt?.let { relativeTimeSpanString(it) }
            Text(
                stringResource(
                    MR.strings.tsuzuki_inventory_success_and_claim_times,
                    successfulFetchTime ?: stringResource(MR.strings.tsuzuki_inventory_no_successful_fetch),
                    claimObservationTime ?: stringResource(MR.strings.tsuzuki_inventory_no_claim_observation),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
            if (source.knownIndividualClaims > 0) {
                Text(
                    "${source.knownIndividualClaims} " + stringResource(MR.strings.tsuzuki_inventory_individual),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            if (source.candidateObservations > 0) {
                Text(
                    "${source.candidateObservations} " + stringResource(MR.strings.tsuzuki_inventory_candidates),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            if (source.releaseObservations > 0) {
                Text(
                    "${source.releaseObservations} " + stringResource(MR.strings.tsuzuki_inventory_releases),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            if (source.numberedReleaseCandidateCount > 0) {
                Text(
                    "${source.numberedReleaseCandidateCount} " +
                        stringResource(MR.strings.tsuzuki_inventory_numbered_release_candidates),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
                source.numberedReleaseCandidates.forEach { candidate ->
                    Text(
                        stringResource(
                            MR.strings.tsuzuki_inventory_numbered_release_example,
                            candidate.label,
                            candidate.language ?: stringResource(MR.strings.tsuzuki_inventory_language_unrecorded),
                            candidate.releaseCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                    )
                }
            } else if (source.releaseObservations == 0 && source.latestSuccessfulFetchAt != null) {
                Text(
                    stringResource(MR.strings.tsuzuki_inventory_empty_release_feed),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            } else if (source.releaseObservations > 0) {
                Text(
                    stringResource(MR.strings.tsuzuki_inventory_no_numbered_release_candidates),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            if (source.knownEditorialLabels.isNotEmpty()) {
                Text(
                    stringResource(MR.strings.tsuzuki_inventory_editorial_examples) + ": " +
                        source.knownEditorialLabels.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            if (source.candidateExampleLabels.isNotEmpty()) {
                Text(
                    stringResource(MR.strings.tsuzuki_inventory_candidate_examples) + ": " +
                        source.candidateExampleLabels.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            if (
                source.candidateObservations > 0 &&
                !source.scope.editionKey.isNullOrBlank() &&
                !source.scope.language.isNullOrBlank()
            ) {
                DefaultSkinTextAction(
                    text = "Revisar capítulos desta edição",
                    onClick = { onReviewCandidates(source) },
                )
            }
            if (source.releaseExampleLabels.isNotEmpty()) {
                Text(
                    stringResource(MR.strings.tsuzuki_inventory_release_examples) + ": " +
                        source.releaseExampleLabels.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            if (source.declaredTotals.isNotEmpty()) {
                Text(
                    stringResource(MR.strings.tsuzuki_inventory_totals) + ": " +
                        source.declaredTotals.sorted().joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
        }
    }
}

@Composable
private fun InventoryFailureReasonText(reason: InventoryFetchFailureReason) {
    val label = when (reason) {
        InventoryFetchFailureReason.ACCESS_DENIED -> MR.strings.tsuzuki_inventory_error_access
        InventoryFetchFailureReason.RATE_LIMITED -> MR.strings.tsuzuki_inventory_error_rate_limit
        InventoryFetchFailureReason.HTTP_ERROR -> MR.strings.tsuzuki_inventory_error_http
        InventoryFetchFailureReason.RESPONSE_TOO_LARGE -> MR.strings.tsuzuki_inventory_error_size
        InventoryFetchFailureReason.NETWORK_ERROR -> MR.strings.tsuzuki_inventory_error_network
        InventoryFetchFailureReason.INVALID_RESPONSE -> MR.strings.tsuzuki_inventory_error_response
        InventoryFetchFailureReason.UNKNOWN -> MR.strings.tsuzuki_inventory_error_unknown
    }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.bodySmall,
        color = defaultSkinColors().secondary,
    )
}

@Composable
private fun DetailsModes(mode: CanonicalDetailsLowerMode, onAction: (CanonicalDetailsLowerAction) -> Unit) {
    val colors = defaultSkinColors()
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
                            colors.foreground
                        } else {
                            colors.secondary
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = if (selected) colors.foreground else Color.Transparent,
                    )
                }
            }
        }
        HorizontalDivider(color = colors.outline)
    }
}

@Composable
private fun DetailsToolbar(
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onProviders: () -> Unit,
    background: Color = Color.Transparent,
    onSources: (() -> Unit)? = null,
    onInventory: (() -> Unit)? = null,
    onImportEpub: (() -> Unit)? = null,
) {
    val colors = defaultSkinColors()
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
                colors.surface.copy(alpha = 0.94f),
                DefaultSkinShape.Control,
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
                    colors.surface.copy(alpha = 0.94f),
                    DefaultSkinShape.Control,
                ),
            ) {
                Icon(MaterialSymbols.Rounded.MoreVert, stringResource(MR.strings.tsuzuki_details_actions))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = DefaultSkinShape.Card,
                containerColor = colors.surface,
                tonalElevation = 0.dp,
            ) {
                DropdownMenuItem(text = {
                    Text(stringResource(MR.strings.tsuzuki_details_refresh), color = colors.foreground)
                }, onClick = {
                    expanded =
                        false
                    onRefresh()
                })
                DropdownMenuItem(text = {
                    Text(stringResource(MR.strings.tsuzuki_details_providers), color = colors.foreground)
                }, onClick = {
                    expanded =
                        false
                    onProviders()
                })
                onImportEpub?.let { action ->
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(MR.strings.tsuzuki_epub_import_action), color = colors.foreground)
                        },
                        onClick = {
                            expanded = false
                            action()
                        },
                    )
                }
                onSources?.let { action ->
                    DropdownMenuItem(text = {
                        Text(stringResource(MR.strings.tsuzuki_details_metadata_sources), color = colors.foreground)
                    }, onClick = {
                        expanded =
                            false
                        action()
                    })
                }
                onInventory?.let { action ->
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(MR.strings.tsuzuki_inventory_title), color = colors.foreground)
                        },
                        onClick = {
                            expanded = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterJumpSheet(chapters: List<CanonicalChapterDetailItem>, onJump: (CanonicalChapterDetailItem) -> Unit) {
    val colors = defaultSkinColors()
    var input by rememberSaveable { mutableStateOf("") }
    var notFound by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(Modifier.fillMaxWidth().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(MR.strings.tsuzuki_details_jump),
            style = MaterialTheme.typography.titleMedium,
            color = colors.foreground,
        )
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
            shape = DefaultSkinShape.Control,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.outline,
                focusedTextColor = colors.foreground,
                unfocusedTextColor = colors.foreground,
                focusedLabelColor = colors.secondary,
                unfocusedLabelColor = colors.secondary,
                cursorColor = colors.accent,
                focusedContainerColor = colors.surfaceMuted,
                unfocusedContainerColor = colors.surfaceMuted,
            ),
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
        eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton(
            text = stringResource(MR.strings.tsuzuki_details_jump_go),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val chapter = resolveChapterJump(chapters, input)
                if (chapter == null) notFound = true else onJump(chapter)
            },
        )
    }
}

/** Fade only as the visual hero's bottom crosses the toolbar; no pixel-threshold color switch. */
internal fun detailsToolbarFraction(heroHeight: Int, toolbarHeight: Int, itemIndex: Int, scrollOffset: Int): Float {
    if (itemIndex > 0) return 1f
    if (heroHeight <= 0) return 0f
    val transition = toolbarHeight.coerceAtLeast(1)
    return ((scrollOffset - heroHeight + transition * 2).toFloat() / transition).coerceIn(0f, 1f)
}
