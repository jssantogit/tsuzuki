package eu.kanade.presentation.tsuzuki.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCoverLoadEvent
import eu.kanade.presentation.tsuzuki.components.DefaultSkinCard
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPageTitle
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.TsuzukiDefaultSkinCover
import eu.kanade.presentation.tsuzuki.components.TsuzukiDefaultSkinCoverSize
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.presentation.tsuzuki.integration.IntegrationBrandIcon
import eu.kanade.presentation.tsuzuki.integration.ratingScaleLabel
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.tsuzuki.search.DiscoverBlock
import eu.kanade.tachiyomi.ui.tsuzuki.search.DiscoverKind
import eu.kanade.tachiyomi.ui.tsuzuki.search.SearchState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemFormat
import tachiyomi.domain.tsuzuki.catalog.model.CatalogScore
import tachiyomi.domain.tsuzuki.integration.model.TsuzukiRating
import tachiyomi.domain.tsuzuki.integration.model.metadataStartYear
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun TsuzukiSearchScreen(
    state: SearchState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRecentSearch: (String) -> Unit,
    onResultClick: (CatalogItem) -> Unit,
    onClearRecent: () -> Unit,
    onRetry: () -> Unit,
    onOpenIntegrations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    val observedFormats = remember((state as? SearchState.Results)?.query) { mutableStateListOf<CatalogItemFormat>() }
    val resultItems = (state as? SearchState.Results)?.items.orEmpty()
    val currentFormats = resultItems.map { it.format }.distinct()

    LaunchedEffect(observedFormats, currentFormats) {
        val additions = currentFormats.filterNot(observedFormats::contains)
        if (observedFormats.isEmpty()) {
            observedFormats.addAll(FORMAT_ORDER.filter(additions::contains))
            observedFormats.addAll(additions.filterNot(FORMAT_ORDER::contains))
        } else {
            observedFormats.addAll(additions)
        }
    }

    val resultGroups = observedFormats.mapNotNull { format ->
        resultItems.filter { it.format == format }
            .takeIf { it.isNotEmpty() }
            ?.let { format to it }
    }

    Scaffold(
        modifier = modifier,
        containerColor = colors.canvas,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { contentPadding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(contentPadding),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "search_header", contentType = "search_header") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DefaultSkinPageTitle(
                        text = stringResource(MR.strings.tsuzuki_navigation_search),
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                    )
                    SearchField(
                        query = query,
                        onQueryChange = onQueryChange,
                        onSearch = onSearch,
                        onClear = {
                            onQueryChange("")
                            onRetry()
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    )
                }
            }
            when (state) {
                SearchState.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.foreground, strokeWidth = 2.dp)
                    }
                }
                is SearchState.NeedsIntegration -> needsIntegrationContent(
                    state.recentSearches,
                    onRecentSearch,
                    onOpenIntegrations,
                )
                is SearchState.Discover -> discoverContent(state, onRecentSearch, onClearRecent, onResultClick)
                is SearchState.Results -> catalogItems(resultGroups, onResultClick)
                is SearchState.Empty -> item(key = "empty") {
                    MessageContent(message = stringResource(MR.strings.tsuzuki_search_empty))
                }
                is SearchState.Error -> item(key = "error") {
                    MessageContent(
                        message = stringResource(MR.strings.tsuzuki_search_error),
                        action = stringResource(MR.strings.tsuzuki_search_retry),
                        onAction = { state.query?.let(onSearch) ?: onRetry() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    Surface(
        modifier = modifier.heightIn(min = 54.dp),
        shape = DefaultSkinShape.Control,
        color = colors.surface,
        contentColor = colors.foreground,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foreground),
                cursorBrush = SolidColor(colors.foreground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = stringResource(MR.strings.tsuzuki_search_query_label),
                                color = colors.secondary,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_24dp),
                        contentDescription = stringResource(MR.strings.tsuzuki_search_clear),
                        tint = colors.secondary,
                    )
                }
            } else {
                Spacer(Modifier.size(42.dp))
            }
        }
    }
}

private fun LazyListScope.needsIntegrationContent(
    recentSearches: List<String>,
    onRecentSearch: (String) -> Unit,
    onOpenIntegrations: () -> Unit,
) {
    if (recentSearches.isNotEmpty()) {
        item {
            RecentSearches(
                searches = recentSearches,
                onSearch = onRecentSearch,
                onClear = null,
            )
        }
    }
    item {
        val colors = defaultSkinColors()
        DefaultSkinCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(MR.strings.tsuzuki_search_no_integration),
                    color = colors.foreground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(MR.strings.tsuzuki_search_integration_prompt),
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                DefaultSkinPrimaryButton(
                    text = stringResource(MR.strings.tsuzuki_search_configure_integrations),
                    onClick = onOpenIntegrations,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun LazyListScope.discoverContent(
    state: SearchState.Discover,
    onRecentSearch: (String) -> Unit,
    onClearRecent: () -> Unit,
    onResultClick: (CatalogItem) -> Unit,
) {
    if (state.recentSearches.isNotEmpty()) {
        item {
            RecentSearches(
                searches = state.recentSearches,
                onSearch = onRecentSearch,
                onClear = onClearRecent,
            )
        }
    }
    items(
        items = state.blocks.filter { it.items.isNotEmpty() },
        key = DiscoverBlock::kind,
    ) { block ->
        DiscoverSection(block = block, onResultClick = onResultClick)
    }
}

@Composable
private fun RecentSearches(
    searches: List<String>,
    onSearch: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    val colors = defaultSkinColors()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DefaultSkinSectionLabel(text = "Buscas recentes")
            if (onClear != null) {
                Text(
                    text = "Limpar",
                    modifier = Modifier.clickable(role = Role.Button, onClick = onClear),
                    color = colors.secondary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = searches, key = { it }) { search ->
                Surface(
                    modifier = Modifier.clickable(role = Role.Button) { onSearch(search) },
                    shape = DefaultSkinShape.Pill,
                    color = colors.surface,
                    contentColor = colors.foreground,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Text(
                        text = search,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverSection(
    block: DiscoverBlock,
    onResultClick: (CatalogItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DefaultSkinSectionLabel(
            text = when (block.kind) {
                DiscoverKind.TRENDING -> "Em alta"
                DiscoverKind.POPULAR -> "Populares"
                DiscoverKind.TOP_RATED -> "Mais bem avaliados"
                DiscoverKind.FAVORITES -> "Mais favoritados"
                DiscoverKind.RECENTLY_UPDATED -> "Atualizados recentemente"
            },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = block.items,
                key = { "${it.provider}:${it.providerId}" },
            ) { item ->
                SearchCompactCard(item = item, onClick = { onResultClick(item) })
            }
        }
    }
}

private fun LazyListScope.catalogItems(
    groups: List<Pair<CatalogItemFormat, List<CatalogItem>>>,
    onResultClick: (CatalogItem) -> Unit,
) {
    items(
        items = groups,
        key = { (format, _) -> "result_format_$format" },
    ) { (format, groupedItems) ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DefaultSkinSectionLabel(
                text = format.displayName(),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(
                    items = groupedItems,
                    key = { "${it.provider}:${it.providerId}" },
                ) { result ->
                    SearchCompactCard(
                        item = result,
                        year = metadataStartYear(result.startDate),
                        onClick = { onResultClick(result) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchCompactCard(
    item: CatalogItem,
    onClick: () -> Unit,
    year: String? = null,
    onForegroundLoadEvent: ((MangaCoverLoadEvent) -> Unit)? = null,
    onAtmosphereLoadEvent: ((MangaCoverLoadEvent) -> Unit)? = null,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier
            .width(TsuzukiDefaultSkinCoverSize.Standard.width)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("search_compact_card"),
    ) {
        SearchCompactArtwork(
            data = item.coverUrl,
            title = item.title,
            modifier = Modifier.fillMaxWidth(),
            onForegroundLoadEvent = onForegroundLoadEvent,
            onAtmosphereLoadEvent = onAtmosphereLoadEvent,
        )
        Text(
            text = item.title,
            color = colors.foreground,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        SearchCompactRatings(
            scores = item.scores.ifEmpty { listOfNotNull(item.score) },
            tsuzukiRating = item.tsuzukiRating,
            modifier = Modifier.padding(top = 5.dp),
        )
        year?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
internal fun SearchCompactArtwork(
    data: String?,
    title: String,
    modifier: Modifier = Modifier,
    onForegroundLoadEvent: ((MangaCoverLoadEvent) -> Unit)? = null,
    // Kept for call-site compatibility; this surface has no decorative second-artwork request.
    @Suppress("UNUSED_PARAMETER")
    onAtmosphereLoadEvent: ((MangaCoverLoadEvent) -> Unit)? = null,
) {
    TsuzukiDefaultSkinCover(
        data = data,
        contentDescription = title,
        modifier = modifier.testTag("search_compact_artwork_frame"),
        size = TsuzukiDefaultSkinCoverSize.Standard,
        foregroundModifier = Modifier.testTag("search_compact_artwork_foreground"),
        onLoadEvent = onForegroundLoadEvent,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchCompactRatings(
    scores: List<CatalogScore>,
    tsuzukiRating: TsuzukiRating?,
    modifier: Modifier = Modifier,
) {
    if (scores.isEmpty() && tsuzukiRating == null) return
    val colors = defaultSkinColors()
    val visibleScores = scores.take(if (tsuzukiRating == null) 2 else 1)
    val hiddenCount = (scores.size - visibleScores.size).coerceAtLeast(0)
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        tsuzukiRating?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.tsuzuki_rating),
                    contentDescription = "Tsuzuki Rating",
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = String.format(java.util.Locale.US, "%.1f", rating.value),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.foreground,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        visibleScores.forEach { score ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IntegrationBrandIcon(providerId = score.provider, size = 16.dp)
                Text(
                    text = ratingScaleLabel(score.provider, score.value, score.maxValue),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.foreground,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (hiddenCount > 0) {
            Text(
                text = "+$hiddenCount",
                style = MaterialTheme.typography.labelSmall,
                color = colors.foreground,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun CatalogItemFormat.displayName(): String = when (this) {
    CatalogItemFormat.MANGA -> "Manga"
    CatalogItemFormat.MANHWA -> "Manhwa"
    CatalogItemFormat.MANHUA -> "Manhua"
    CatalogItemFormat.WEBTOON -> "Webtoon"
    CatalogItemFormat.ONE_SHOT -> "One-shot"
    CatalogItemFormat.NOVEL -> "Novel"
    CatalogItemFormat.DOUJIN -> "Doujinshi"
    CatalogItemFormat.UNKNOWN -> "Outros"
}

private val FORMAT_ORDER = listOf(
    CatalogItemFormat.MANGA,
    CatalogItemFormat.MANHWA,
    CatalogItemFormat.MANHUA,
    CatalogItemFormat.WEBTOON,
    CatalogItemFormat.ONE_SHOT,
    CatalogItemFormat.NOVEL,
    CatalogItemFormat.DOUJIN,
)

@Composable
private fun MessageContent(
    message: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = colors.secondary,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (action != null && onAction != null) {
            DefaultSkinPrimaryButton(
                text = action,
                onClick = onAction,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}
