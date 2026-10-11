package eu.kanade.presentation.tsuzuki.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
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
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(contentPadding),
        ) {
            Text(
                text = stringResource(MR.strings.tsuzuki_navigation_search),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(MR.strings.tsuzuki_search_query_label)) },
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_search_24dp),
                        contentDescription = null,
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = {
                                onQueryChange("")
                                onRetry()
                            },
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close_24dp),
                                contentDescription = stringResource(MR.strings.tsuzuki_search_clear),
                            )
                        }
                    }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { onSearch(query) },
                ),
            )

            when (state) {
                SearchState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is SearchState.NeedsIntegration -> {
                    NeedsIntegrationContent(
                        recentSearches = state.recentSearches,
                        onRecentSearch = onRecentSearch,
                        onOpenIntegrations = onOpenIntegrations,
                    )
                }

                is SearchState.Discover -> {
                    DiscoverContent(
                        state = state,
                        onRecentSearch = onRecentSearch,
                        onClearRecent = onClearRecent,
                        onResultClick = onResultClick,
                    )
                }

                is SearchState.Results -> {
                    CatalogItems(
                        query = state.query,
                        items = state.items,
                        onResultClick = onResultClick,
                    )
                }

                is SearchState.Empty -> {
                    MessageContent(
                        message = stringResource(MR.strings.tsuzuki_search_empty),
                    )
                }

                is SearchState.Error -> {
                    MessageContent(
                        message = stringResource(MR.strings.tsuzuki_search_error),
                        action = stringResource(MR.strings.tsuzuki_search_retry),
                        onAction = {
                            val retryQuery = state.query
                            if (retryQuery == null) {
                                onRetry()
                            } else {
                                onSearch(retryQuery)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NeedsIntegrationContent(
    recentSearches: List<String>,
    onRecentSearch: (String) -> Unit,
    onOpenIntegrations: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            Text(
                text = stringResource(MR.strings.tsuzuki_search_no_integration),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item {
            Text(
                text = stringResource(MR.strings.tsuzuki_search_integration_prompt),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            Button(onClick = onOpenIntegrations) {
                Text(stringResource(MR.strings.tsuzuki_search_configure_integrations))
            }
        }
    }
}

@Composable
private fun DiscoverContent(
    state: SearchState.Discover,
    onRecentSearch: (String) -> Unit,
    onClearRecent: () -> Unit,
    onResultClick: (CatalogItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
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
            DiscoverSection(
                block = block,
                onResultClick = onResultClick,
            )
        }
    }
}

@Composable
private fun RecentSearches(
    searches: List<String>,
    onSearch: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Buscas recentes",
                style = MaterialTheme.typography.titleMedium,
            )
            if (onClear != null) {
                TextButton(onClick = onClear) {
                    Text("Limpar")
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = searches,
                key = { it },
            ) { search ->
                AssistChip(
                    onClick = { onSearch(search) },
                    label = { Text(search) },
                )
            }
        }
    }
}

@Composable
private fun DiscoverSection(
    block: DiscoverBlock,
    onResultClick: (CatalogItem) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        Text(
            text = when (block.kind) {
                DiscoverKind.TRENDING -> "Em alta"
                DiscoverKind.POPULAR -> "Populares"
                DiscoverKind.TOP_RATED -> "Mais bem avaliados"
                DiscoverKind.FAVORITES -> "Mais favoritados"
                DiscoverKind.RECENTLY_UPDATED -> "Atualizados recentemente"
            },
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(
                items = block.items,
                key = { "${it.provider}:${it.providerId}" },
            ) { item ->
                SearchCompactCard(
                    item = item,
                    onClick = { onResultClick(item) },
                )
            }
        }
    }
}

@Composable
private fun CatalogItems(
    query: String,
    items: List<CatalogItem>,
    onResultClick: (CatalogItem) -> Unit,
) {
    val observedFormats = remember(query) { mutableStateListOf<CatalogItemFormat>() }
    val currentFormats = items.map { it.format }.distinct()

    LaunchedEffect(query, currentFormats) {
        val additions = currentFormats.filterNot(observedFormats::contains)
        if (observedFormats.isEmpty()) {
            observedFormats.addAll(FORMAT_ORDER.filter(additions::contains))
            observedFormats.addAll(additions.filterNot(FORMAT_ORDER::contains))
        } else {
            observedFormats.addAll(additions)
        }
    }

    val groups = observedFormats.mapNotNull { format ->
        items.filter { it.format == format }
            .takeIf { it.isNotEmpty() }
            ?.let { format to it }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        items(
            items = groups,
            key = { (format, _) -> "result_format_$format" },
        ) { item ->
            val (format, groupedItems) = item
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = format.displayName(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = groupedItems,
                        key = { "${it.provider}:${it.providerId}" },
                    ) { result ->
                        SearchCompactCard(
                            item = result,
                            year = result.startDate
                                ?.takeIf { it.length >= 4 }
                                ?.take(4),
                            onClick = { onResultClick(result) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchCompactCard(
    item: CatalogItem,
    onClick: () -> Unit,
    year: String? = null,
) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
    ) {
        MangaCover.Book(
            data = item.coverUrl,
            contentDescription = item.title,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        SearchCompactRatings(
            scores = item.scores.ifEmpty { listOfNotNull(item.score) },
            tsuzukiRating = item.tsuzukiRating,
            modifier = Modifier.padding(top = 4.dp),
        )
        year?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchCompactRatings(
    scores: List<CatalogScore>,
    tsuzukiRating: TsuzukiRating?,
    modifier: Modifier = Modifier,
) {
    if (scores.isEmpty() && tsuzukiRating == null) return

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
                    color = MaterialTheme.colorScheme.primary,
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
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (hiddenCount > 0) {
            Text(
                text = "+$hiddenCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action)
            }
        }
    }
}
