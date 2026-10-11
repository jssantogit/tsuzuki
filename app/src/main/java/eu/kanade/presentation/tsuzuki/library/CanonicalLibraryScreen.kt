package eu.kanade.presentation.tsuzuki.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.manga.components.MangaCover
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
import tachiyomi.presentation.core.components.material.Scaffold
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

    val successState = state as? CanonicalLibraryScreenState.Success
    val advancedFilterCount = successState?.filters?.let { filters ->
        (if (filters.origin != null) 1 else 0) +
            (if (filters.listKey != null) 1 else 0) +
            filters.formats.size +
            (if (filters.categoryId != null) 1 else 0)
    } ?: 0

    Scaffold(
        modifier = modifier,
        topBar = {
            CanonicalLibraryHeader(
                title = title,
                query = successState?.searchQuery,
                navigateUp = navigateUp,
                onQueryChange = onSearchQueryChange,
                advancedFilterCount = advancedFilterCount,
                onShowFilters = { filterSheetVisible = true },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (state) {
                CanonicalLibraryScreenState.Loading -> LoadingScreen()
                is CanonicalLibraryScreenState.Success -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        CanonicalLibraryStatusFilters(
                            selectedStatus = state.filters.status,
                            onStatusFilterChange = onStatusFilterChange,
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(28.dp),
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
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center,
                                    )
                                    if (state.totalItemCount > 0) {
                                        if (!state.searchQuery.isNullOrEmpty()) {
                                            TextButton(onClick = { onSearchQueryChange("") }) {
                                                Text(stringResource(MR.strings.tsuzuki_library_clear_search))
                                            }
                                        }
                                        if (advancedFilterCount > 0) {
                                            TextButton(onClick = onClearAdvancedFilters) {
                                                Text(stringResource(MR.strings.tsuzuki_library_clear_filters))
                                            }
                                        }
                                        if (state.filters.status != null) {
                                            TextButton(onClick = { onStatusFilterChange(null) }) {
                                                Text(stringResource(MR.strings.all))
                                            }
                                        }
                                    }
                                }
                            }
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
    BackHandler(enabled = query != null) { onQueryChange(null) }
    Row(
        modifier = Modifier.statusBarsPadding().fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (query != null || navigateUp != null) {
            IconButton(onClick = { if (query != null) onQueryChange(null) else navigateUp?.invoke() }) {
                Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, stringResource(MR.strings.action_close))
            }
        }
        if (query == null) {
            Text(
                title,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onQueryChange("") }) {
                Icon(painterResource(R.drawable.ic_search_24dp), stringResource(MR.strings.tsuzuki_library_search))
            }
        } else {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text(stringResource(MR.strings.tsuzuki_library_search)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                painterResource(R.drawable.ic_close_24dp),
                                stringResource(MR.strings.tsuzuki_library_clear_search),
                            )
                        }
                    }
                } else {
                    null
                },
            )
        }
        IconButton(onClick = onShowFilters) {
            BadgedBox(
                badge = {
                    if (advancedFilterCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.onBackground,
                            contentColor = MaterialTheme.colorScheme.background,
                        ) { Text(advancedFilterCount.toString()) }
                    }
                },
            ) {
                Icon(MaterialSymbols.Rounded.FilterList, stringResource(MR.strings.tsuzuki_library_filters))
            }
        }
    }
}

@Composable
private fun CanonicalLibraryStatusFilters(
    selectedStatus: LibraryStatus?,
    onStatusFilterChange: (LibraryStatus?) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val statuses = listOf<LibraryStatus?>(null) + LibraryStatus.entries
            statuses.forEach { status ->
                val selected = selectedStatus == status
                Column(
                    modifier = Modifier.width(IntrinsicSize.Min).heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onStatusFilterChange(status) }),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        text = status?.libraryLabel() ?: stringResource(MR.strings.all),
                        modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onBackground
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                    Box(
                        Modifier.fillMaxWidth().height(2.dp).background(
                            if (selected) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        ),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private data class ActiveFilter(
    val label: String,
    val clear: () -> Unit,
)

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
        filters.origin?.let { origin ->
            add(ActiveFilter(origin.libraryOriginLabel(), onClearOrigin))
        }
        filters.listKey?.let { listKey ->
            val title = state.availableProviderLists
                .firstOrNull { it.key == listKey }
                ?.title
                ?: listKey
            add(ActiveFilter(title, onClearProviderList))
        }
        filters.formats
            .sortedBy(CatalogItemFormat::ordinal)
            .forEach { format ->
                add(ActiveFilter(format.libraryFormatLabel()) { onToggleFormat(format) })
            }
        filters.categoryId?.let { categoryId ->
            val label = categories
                .firstOrNull { it.id == categoryId }
                ?.visualName
                ?: if (categoryId ==
                    Category.UNCATEGORIZED_ID
                ) {
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
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEach { filter ->
            SuggestionChip(
                modifier = Modifier.weight(1f, fill = false),
                onClick = filter.clear,
                label = { Text(filter.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_24dp),
                        contentDescription = stringResource(MR.strings.action_remove),
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        if (hiddenCount > 0) {
            SuggestionChip(
                onClick = onShowAll,
                label = { Text("+$hiddenCount") },
            )
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(MR.strings.tsuzuki_library_filters), style = MaterialTheme.typography.titleLarge)

            FilterSection(stringResource(MR.strings.tsuzuki_library_origin)) {
                FilterChip(
                    selected = state.filters.origin == null,
                    onClick = { onOriginFilterChange(null) },
                    label = { Text(stringResource(MR.strings.all)) },
                )
                state.availableOrigins
                    .sortedBy(String::libraryOriginLabel)
                    .forEach { origin ->
                        FilterChip(
                            selected = state.filters.origin == origin,
                            onClick = { onOriginFilterChange(origin) },
                            label = { Text(origin.libraryOriginLabel()) },
                        )
                    }
            }

            if (state.availableProviderLists.isNotEmpty()) {
                FilterSection(stringResource(MR.strings.tsuzuki_library_list)) {
                    FilterChip(
                        selected = state.filters.listKey == null,
                        onClick = { onProviderListFilterChange(null) },
                        label = { Text(stringResource(MR.strings.all)) },
                    )
                    state.availableProviderLists.forEach { list ->
                        FilterChip(
                            selected = state.filters.listKey == list.key,
                            onClick = { onProviderListFilterChange(list.key) },
                            label = { Text(list.title) },
                        )
                    }
                }
            }

            FilterSection(stringResource(MR.strings.tsuzuki_library_format)) {
                state.availableFormats
                    .sortedBy(CatalogItemFormat::ordinal)
                    .forEach { format ->
                        FilterChip(
                            selected = format in state.filters.formats,
                            onClick = { onToggleFormatFilter(format) },
                            label = { Text(format.libraryFormatLabel()) },
                        )
                    }
            }

            FilterSection(stringResource(MR.strings.tsuzuki_library_category)) {
                FilterChip(
                    selected = state.filters.categoryId == null,
                    onClick = { onCategoryFilterChange(null) },
                    label = { Text(stringResource(MR.strings.all)) },
                )
                FilterChip(
                    selected = state.filters.categoryId == Category.UNCATEGORIZED_ID,
                    onClick = { onCategoryFilterChange(Category.UNCATEGORIZED_ID) },
                    label = { Text(stringResource(MR.strings.tsuzuki_library_uncategorized)) },
                )
                categories
                    .filter { it.id != Category.UNCATEGORIZED_ID }
                    .forEach { category ->
                        FilterChip(
                            selected = state.filters.categoryId == category.id,
                            onClick = { onCategoryFilterChange(category.id) },
                            label = { Text(category.visualName) },
                        )
                    }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        onClearAdvancedFilters()
                    },
                ) {
                    Text(stringResource(MR.strings.tsuzuki_library_clear_filters))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.tsuzuki_library_done))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
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
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = items,
            key = CanonicalLibraryCardModel::canonicalTitleId,
        ) { item ->
            val trace = remember(item.canonicalTitleId, recorder.sessionId) {
                DiagnosticTrace.start(
                    recorder = recorder,
                    workflow = DiagnosticWorkflow.OPEN_TITLE,
                    canonicalTitleId = item.canonicalTitleId,
                    subsystem = DiagnosticSubsystem.IMAGE,
                )
            }
            MangaCover.Book(
                data = item.coverUrl ?: item.sourceCover ?: item.localCoverUrl,
                fallbackData = listOf(item.sourceCover, item.sourceCover?.url, item.localCoverUrl),
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpenItem(item) },
                onLoadEvent = { trace.recordArtworkLoad(it) },
            )
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
