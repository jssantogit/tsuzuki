package eu.kanade.presentation.tsuzuki.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionBrowseScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.FolderCatalogContent
import eu.kanade.tachiyomi.ui.tsuzuki.collections.FolderCatalogScreenState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun CollectionBrowseScreen(
    state: CollectionBrowseScreenState,
    navigateUp: () -> Unit,
    onFolder: (folderId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = (state as? CollectionBrowseScreenState.Ready)?.collection?.title
        ?: stringResource(MR.strings.tsuzuki_collection_title)
    BrowseScaffold(title, navigateUp, modifier) { contentModifier ->
        when (state) {
            CollectionBrowseScreenState.Loading -> BrowseLoading(contentModifier)
            CollectionBrowseScreenState.Missing, is CollectionBrowseScreenState.Error -> BrowseMessage(
                stringResource(MR.strings.tsuzuki_collection_unavailable),
                contentModifier,
            )
            is CollectionBrowseScreenState.Ready -> {
                if (state.collection.folders.isEmpty()) {
                    BrowseMessage(stringResource(MR.strings.tsuzuki_collection_empty), contentModifier)
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = contentModifier,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        items(state.collection.folders, key = { it.folderId }) { folder ->
                            FolderTile(folder, { onFolder(folder.folderId) }, width = null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FolderCatalogScreen(
    state: FolderCatalogScreenState,
    navigateUp: () -> Unit,
    onChildFolder: (folderId: String) -> Unit,
    onSelectList: (listId: String) -> Unit,
    onCatalogItem: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    preferredListId: String? = null,
) {
    val ready = state as? FolderCatalogScreenState.Ready
    val requestedList = preferredListId ?: ready?.preferredListId
    val direct = requestedList != null
    val title = if (direct) {
        ready?.folder?.lists?.firstOrNull { it.id == requestedList }?.title
            ?: stringResource(MR.strings.tsuzuki_direct_list_title)
    } else {
        ready?.folder?.title ?: stringResource(MR.strings.tsuzuki_folder_title)
    }
    BrowseScaffold(title, navigateUp, modifier) { contentModifier ->
        when (state) {
            FolderCatalogScreenState.Loading -> BrowseLoading(contentModifier)
            FolderCatalogScreenState.Missing, is FolderCatalogScreenState.Error -> BrowseMessage(
                stringResource(
                    if (direct) MR.strings.tsuzuki_direct_list_unavailable else MR.strings.tsuzuki_folder_unavailable,
                ),
                contentModifier,
            )
            is FolderCatalogScreenState.Ready -> FolderCatalogReady(
                state,
                onChildFolder,
                onSelectList,
                onCatalogItem,
                onLoadMore,
                onRetry,
                contentModifier,
            )
        }
    }
}

@Composable
private fun FolderCatalogReady(
    state: FolderCatalogScreenState.Ready,
    onChildFolder: (String) -> Unit,
    onSelectList: (String) -> Unit,
    onCatalogItem: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val direct = state.preferredListId != null
    val enabledLists = state.folder.lists.filter { it.enabled }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (!direct && state.folder.childFolders.isNotEmpty()) {
            item(key = "child_folders", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(state.folder.childFolders, key = { it.folderId }) { child ->
                        FolderTile(child, { onChildFolder(child.folderId) })
                    }
                }
            }
        }
        if (state.showListSelector) {
            item(key = "list_selector", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    items(enabledLists, key = { it.id }) { list ->
                        val selected = state.selectedListId == list.id
                        Column(
                            modifier = Modifier.width(IntrinsicSize.Min).heightIn(min = 48.dp)
                                .selectable(selected, role = Role.Tab, onClick = { onSelectList(list.id) }),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Text(
                                list.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.padding(vertical = 12.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(
                                Modifier.fillMaxWidth().height(2.dp).background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
        when (val content = state.content) {
            FolderCatalogContent.Idle -> item(key = "idle", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(
                        if (direct) MR.strings.tsuzuki_direct_list_unavailable else MR.strings.tsuzuki_folder_no_lists,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FolderCatalogContent.Loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                BrowseLoading(Modifier.fillMaxWidth().padding(24.dp))
            }
            is FolderCatalogContent.Error -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(MR.strings.tsuzuki_catalog_error),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(MR.strings.action_retry)) }
                }
            }
            is FolderCatalogContent.Content -> {
                if (content.items.isEmpty()) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(MR.strings.tsuzuki_catalog_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(content.items, key = { "${it.provider}:${it.providerId}" }) { item ->
                        CatalogGridItem(item, { onCatalogItem(item) })
                    }
                }
                if (content.nextCursor != null) {
                    item(key = "load_more", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = onLoadMore) {
                                Text(stringResource(MR.strings.tsuzuki_catalog_load_more))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseScaffold(
    title: String,
    navigateUp: () -> Unit,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(modifier = modifier, topBar = {
        AppBar(titleContent = {}, navigateUp = navigateUp)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                title,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Box(Modifier.weight(1f)) {
                content(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CatalogGridItem(item: CatalogItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MangaCover.Book(
            data = item.coverUrl,
            contentDescription = item.title,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BrowseLoading(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun BrowseMessage(message: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
