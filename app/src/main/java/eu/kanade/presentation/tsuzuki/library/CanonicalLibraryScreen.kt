package eu.kanade.presentation.tsuzuki.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.tsuzuki.components.DefaultSkinChoiceChip
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPageTitle
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.presentation.tsuzuki.components.TsuzukiDefaultSkinCover
import eu.kanade.presentation.tsuzuki.components.TsuzukiDefaultSkinCoverSize
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.tsuzuki.components.defaultSkinWorkGridColumnCount
import eu.kanade.presentation.tsuzuki.recordArtworkLoad
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryCardModel
import eu.kanade.tachiyomi.ui.tsuzuki.library.CanonicalLibraryScreenState
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.FilterList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.library.model.LOCAL_LIBRARY_ORIGIN
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun CanonicalLibraryScreen(
    state: CanonicalLibraryScreenState,
    navigateUp: (() -> Unit)? = null,
    title: String = stringResource(MR.strings.label_library),
    onUpdateStatus: (String, LibraryStatus) -> Unit,
    onRemoveItem: (String) -> Unit,
    onSearchQueryChange: (String?) -> Unit = {},
    categories: List<Category> = emptyList(),
    onSetCategories: (String, List<Long>) -> Unit = { _, _ -> },
    onEditCategories: () -> Unit = {},
    onCategoryFilterChange: (Long?) -> Unit = {},
    onStatusFilterChange: (LibraryStatus?) -> Unit = {},
    onOriginFilterChange: (String?) -> Unit = {},
    onProviderListFilterChange: (String?) -> Unit = {},
    onToggleFormatFilter: (CatalogItemFormat) -> Unit = {},
    onClearAdvancedFilters: () -> Unit = {},
    onRead: (CanonicalLibraryCardModel) -> Unit = {},
    onOpenItem: (CanonicalLibraryCardModel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var filterSheetVisible by remember { mutableStateOf(false) }
    val colors = defaultSkinColors()
    val successState = state as? CanonicalLibraryScreenState.Success
    val advancedFilterCount = successState?.filters?.let { filters ->
        (if (filters.origin != null) 1 else 0) +
            (if (filters.listKey != null) 1 else 0) +
            filters.formats.size +
            (if (filters.categoryId != null) 1 else 0)
    } ?: 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        CanonicalLibraryHeader(
            title = title,
            query = successState?.searchQuery,
            navigateUp = navigateUp,
            onQueryChange = onSearchQueryChange,
            advancedFilterCount = advancedFilterCount,
            onShowFilters = { filterSheetVisible = true },
        )

        when (state) {
            CanonicalLibraryScreenState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { LoadingScreen() }

            is CanonicalLibraryScreenState.Success -> {
                CanonicalLibraryStatusFilters(
                    selectedStatus = state.filters.status,
                    onStatusFilterChange = onStatusFilterChange,
                    searchMode = successState?.searchQuery != null,
                )
                CanonicalLibraryActiveFilters(
                    state = state,
                    categories = categories,
                    onClearOrigin = { onOriginFilterChange(null) },
                    onClearProviderList = { onProviderListFilterChange(null) },
                    onToggleFormat = onToggleFormatFilter,
                    onClearCategory = { onCategoryFilterChange(null) },
                    onShowAll = { filterSheetVisible = true },
                )
                if (state.items.isEmpty()) {
                    LibraryEmptyState(
                        state = state,
                        advancedFilterCount = advancedFilterCount,
                        onSearchQueryChange = onSearchQueryChange,
                        onClearAdvancedFilters = onClearAdvancedFilters,
                        onStatusFilterChange = onStatusFilterChange,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    CanonicalLibraryList(
                        items = state.items,
                        onOpenItem = onOpenItem,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (filterSheetVisible && successState != null) {
        CanonicalLibraryFilterSheet(
            state = successState,
            categories = categories,
            onDismiss = { filterSheetVisible = false },
            onOriginFilterChange = onOriginFilterChange,
            onProviderListFilterChange = onProviderListFilterChange,
            onToggleFormatFilter = onToggleFormatFilter,
            onCategoryFilterChange = onCategoryFilterChange,
            onClearAdvancedFilters = onClearAdvancedFilters,
        )
    }
}

@Composable
private fun CanonicalLibraryHeader(
    title: String,
    query: String?,
    navigateUp: (() -> Unit)?,
    onQueryChange: (String?) -> Unit,
    advancedFilterCount: Int,
    onShowFilters: () -> Unit,
) {
    val colors = defaultSkinColors()
    BackHandler(enabled = query != null) { onQueryChange(null) }
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(
                start = DefaultSkinSpacing.PageHorizontal,
                end = DefaultSkinSpacing.PageHorizontal,
                top = 14.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (query != null || navigateUp != null) {
            IconButton(onClick = { if (query != null) onQueryChange(null) else navigateUp?.invoke() }) {
                Icon(
                    MaterialSymbols.AutoMirroredRounded.ArrowBack,
                    stringResource(MR.strings.action_close),
                    tint = colors.foreground,
                )
            }
        }
        if (query == null) {
            DefaultSkinPageTitle(title, modifier = Modifier.weight(1f))
            IconButton(onClick = { onQueryChange("") }) {
                Icon(
                    painterResource(R.drawable.ic_search_24dp),
                    stringResource(MR.strings.tsuzuki_library_search),
                    tint = colors.foreground,
                )
            }
        } else {
            val searchLabel = stringResource(MR.strings.tsuzuki_library_search)
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            Surface(
                modifier = Modifier.weight(1f),
                shape = DefaultSkinShape.Control,
                color = colors.surface,
                contentColor = colors.foreground,
                border = BorderStroke(1.dp, colors.outline),
                shadowElevation = 0.dp,
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_24dp),
                        contentDescription = null,
                        tint = colors.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = searchLabel },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foreground),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = searchLabel,
                                        color = colors.secondary,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close_24dp),
                                contentDescription = stringResource(MR.strings.tsuzuki_library_clear_search),
                                tint = colors.secondary,
                            )
                        }
                    }
                }
            }
        }
        IconButton(onClick = onShowFilters) {
            BadgedBox(
                badge = {
                    if (advancedFilterCount > 0) {
                        Badge(
                            containerColor = colors.accent,
                            contentColor = colors.onAccent,
                        ) { Text(advancedFilterCount.toString()) }
                    }
                },
            ) {
                Icon(
                    MaterialSymbols.Rounded.FilterList,
                    stringResource(MR.strings.tsuzuki_library_filters),
                    tint = colors.foreground,
                )
            }
        }
    }
}

@Composable
private fun CanonicalLibraryStatusFilters(
    selectedStatus: LibraryStatus?,
    onStatusFilterChange: (LibraryStatus?) -> Unit,
    searchMode: Boolean,
) {
    val colors = defaultSkinColors()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(
                    start = DefaultSkinSpacing.PageHorizontal,
                    end = 32.dp,
                    top = if (searchMode) DefaultSkinSpacing.Row else 0.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val statuses = listOf<LibraryStatus?>(null) + LibraryStatus.entries
            statuses.forEach { status ->
                val selected = selectedStatus == status
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onStatusFilterChange(status) },
                        ),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        text = status?.libraryLabel() ?: stringResource(MR.strings.all),
                        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                        style = if (searchMode) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.labelLarge
                        },
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) colors.foreground else colors.secondary,
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(
                                if (selected) colors.foreground else androidx.compose.ui.graphics.Color.Transparent,
                            ),
                    )
                }
            }
        }
        HorizontalDivider(color = colors.outline.copy(alpha = 0.65f))
    }
}

private data class ActiveFilter(val label: String, val clear: () -> Unit)

@Composable
private fun CanonicalLibraryActiveFilters(
    state: CanonicalLibraryScreenState.Success,
    categories: List<Category>,
    onClearOrigin: () -> Unit,
    onClearProviderList: () -> Unit,
    onToggleFormat: (CatalogItemFormat) -> Unit,
    onClearCategory: () -> Unit,
    onShowAll: () -> Unit,
) {
    val filters = state.filters
    val active = buildList {
        filters.origin?.let { add(ActiveFilter(it.libraryOriginLabel(), onClearOrigin)) }
        filters.listKey?.let { listKey ->
            add(
                ActiveFilter(
                    state.availableProviderLists.firstOrNull { it.key == listKey }?.title ?: listKey,
                    onClearProviderList,
                ),
            )
        }
        filters.formats.sortedBy(CatalogItemFormat::ordinal).forEach { format ->
            add(ActiveFilter(format.libraryFormatLabel()) { onToggleFormat(format) })
        }
        filters.categoryId?.let { categoryId ->
            val label = categories.firstOrNull { it.id == categoryId }?.visualName
                ?: if (categoryId == Category.UNCATEGORIZED_ID) {
                    stringResource(MR.strings.tsuzuki_library_uncategorized)
                } else {
                    stringResource(MR.strings.tsuzuki_library_category)
                }
            add(ActiveFilter(label, onClearCategory))
        }
    }
    if (active.isEmpty()) return

    val visible = active.take(2)
    val hiddenCount = active.size - visible.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DefaultSkinSpacing.PageHorizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { filter ->
            DefaultSkinChoiceChip(
                text = filter.label,
                selected = false,
                onClick = filter.clear,
            )
        }
        if (hiddenCount > 0) {
            DefaultSkinChoiceChip(text = "+$hiddenCount", selected = false, onClick = onShowAll)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CanonicalLibraryFilterSheet(
    state: CanonicalLibraryScreenState.Success,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onOriginFilterChange: (String?) -> Unit,
    onProviderListFilterChange: (String?) -> Unit,
    onToggleFormatFilter: (CatalogItemFormat) -> Unit,
    onCategoryFilterChange: (Long?) -> Unit,
    onClearAdvancedFilters: () -> Unit,
) {
    val colors = defaultSkinColors()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.canvas,
        contentColor = colors.foreground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DefaultSkinSpacing.PageHorizontal)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(DefaultSkinSpacing.Section),
        ) {
            DefaultSkinPageTitle(stringResource(MR.strings.tsuzuki_library_filters))

            FilterSection(stringResource(MR.strings.tsuzuki_library_origin)) {
                DefaultSkinChoiceChip(
                    text = stringResource(MR.strings.all),
                    selected = state.filters.origin == null,
                    onClick = { onOriginFilterChange(null) },
                )
                state.availableOrigins.sortedBy(String::libraryOriginLabel).forEach { origin ->
                    DefaultSkinChoiceChip(
                        text = origin.libraryOriginLabel(),
                        selected = state.filters.origin == origin,
                        onClick = { onOriginFilterChange(origin) },
                    )
                }
            }

            if (state.availableProviderLists.isNotEmpty()) {
                FilterSection(stringResource(MR.strings.tsuzuki_library_list)) {
                    DefaultSkinChoiceChip(
                        text = stringResource(MR.strings.all),
                        selected = state.filters.listKey == null,
                        onClick = { onProviderListFilterChange(null) },
                    )
                    state.availableProviderLists.forEach { list ->
                        DefaultSkinChoiceChip(
                            text = list.title,
                            selected = state.filters.listKey == list.key,
                            onClick = { onProviderListFilterChange(list.key) },
                        )
                    }
                }
            }

            FilterSection(stringResource(MR.strings.tsuzuki_library_format)) {
                state.availableFormats.sortedBy(CatalogItemFormat::ordinal).forEach { format ->
                    DefaultSkinChoiceChip(
                        text = format.libraryFormatLabel(),
                        selected = format in state.filters.formats,
                        onClick = { onToggleFormatFilter(format) },
                    )
                }
            }

            FilterSection(stringResource(MR.strings.tsuzuki_library_category)) {
                DefaultSkinChoiceChip(
                    text = stringResource(MR.strings.all),
                    selected = state.filters.categoryId == null,
                    onClick = { onCategoryFilterChange(null) },
                )
                DefaultSkinChoiceChip(
                    text = stringResource(MR.strings.tsuzuki_library_uncategorized),
                    selected = state.filters.categoryId == Category.UNCATEGORIZED_ID,
                    onClick = { onCategoryFilterChange(Category.UNCATEGORIZED_ID) },
                )
                categories.filter { it.id != Category.UNCATEGORIZED_ID }.forEach { category ->
                    DefaultSkinChoiceChip(
                        text = category.visualName,
                        selected = state.filters.categoryId == category.id,
                        onClick = { onCategoryFilterChange(category.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_library_clear_filters),
                    onClick = onClearAdvancedFilters,
                )
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_library_done),
                    onClick = onDismiss,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DefaultSkinSectionLabel(title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun LibraryEmptyState(
    state: CanonicalLibraryScreenState.Success,
    advancedFilterCount: Int,
    onSearchQueryChange: (String?) -> Unit,
    onClearAdvancedFilters: () -> Unit,
    onStatusFilterChange: (LibraryStatus?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                if (state.totalItemCount == 0) {
                    MR.strings.tsuzuki_library_empty
                } else {
                    MR.strings.tsuzuki_library_no_results
                },
            ),
            color = colors.foreground,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        if (state.totalItemCount > 0) {
            if (!state.searchQuery.isNullOrEmpty()) {
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_library_clear_search),
                    onClick = { onSearchQueryChange("") },
                )
            }
            if (advancedFilterCount > 0) {
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_library_clear_filters),
                    onClick = onClearAdvancedFilters,
                )
            }
            if (state.filters.status != null) {
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.all),
                    onClick = { onStatusFilterChange(null) },
                )
            }
        }
    }
}

@Composable
private fun CanonicalLibraryList(
    items: List<CanonicalLibraryCardModel>,
    onOpenItem: (CanonicalLibraryCardModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recorder = LocalContext.current.appGraph.structuredDiagnosticRecorder
    val colors = defaultSkinColors()
    BoxWithConstraints(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(defaultSkinWorkGridColumnCount(maxWidth)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = items, key = CanonicalLibraryCardModel::canonicalTitleId) { item ->
                val trace = remember(item.canonicalTitleId, recorder.sessionId) {
                    DiagnosticTrace.start(
                        recorder = recorder,
                        workflow = DiagnosticWorkflow.OPEN_TITLE,
                        canonicalTitleId = item.canonicalTitleId,
                        subsystem = DiagnosticSubsystem.IMAGE,
                    )
                }
                Column(
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenItem(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.TopCenter,
                        propagateMinConstraints = false,
                    ) {
                        TsuzukiDefaultSkinCover(
                            data = item.coverUrl ?: item.sourceCover ?: item.localCoverUrl,
                            fallbackData = listOf(item.sourceCover, item.sourceCover?.url, item.localCoverUrl),
                            contentDescription = item.title,
                            size = TsuzukiDefaultSkinCoverSize.Grid,
                            onLoadEvent = { trace.recordArtworkLoad(it) },
                        )
                    }
                    Text(
                        text = item.title,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    run {
                        val year = item.startYear.orEmpty()
                        Text(
                            text = year,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryStatus.libraryLabel(): String = stringResource(
    when (this) {
        LibraryStatus.READING -> MR.strings.tsuzuki_library_reading
        LibraryStatus.PLANNING -> MR.strings.tsuzuki_library_planning
        LibraryStatus.COMPLETED -> MR.strings.tsuzuki_library_completed
        LibraryStatus.ON_HOLD -> MR.strings.tsuzuki_library_on_hold
        LibraryStatus.DROPPED -> MR.strings.tsuzuki_library_dropped
    },
)

private fun String.libraryOriginLabel(): String = when (this) {
    LOCAL_LIBRARY_ORIGIN -> "Tsuzuki"
    "mal" -> "MAL"
    "kitsu" -> "Kitsu"
    "mangaupdates" -> "MangaUpdates"
    "bangumi" -> "Bangumi"
    "shikimori" -> "Shikimori"
    "hikka" -> "Hikka"
    else -> replaceFirstChar { it.uppercase() }
}

private fun CatalogItemFormat.libraryFormatLabel(): String = when (this) {
    CatalogItemFormat.MANGA -> "Manga"
    CatalogItemFormat.NOVEL -> "Novel"
    CatalogItemFormat.ONE_SHOT -> "One-shot"
    CatalogItemFormat.MANHWA -> "Manhwa"
    CatalogItemFormat.MANHUA -> "Manhua"
    CatalogItemFormat.DOUJIN -> "Doujinshi"
    CatalogItemFormat.WEBTOON -> "Webtoon"
    CatalogItemFormat.UNKNOWN -> "Unknown"
}
