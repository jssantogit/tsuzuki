package eu.kanade.presentation.tsuzuki.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.tsuzuki.components.TsuzukiDefaultSkinCover
import eu.kanade.presentation.tsuzuki.components.TsuzukiDefaultSkinCoverSize
import eu.kanade.presentation.tsuzuki.components.defaultSkinWorkGridColumnCount
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionBrowseScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.FolderCatalogContent
import eu.kanade.tachiyomi.ui.tsuzuki.collections.FolderCatalogScreenState
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.model.metadataStartYear
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
    val hasScrollableTitle = state is CollectionBrowseScreenState.Ready && state.collection.folders.isNotEmpty()
    BrowseScaffold(navigateUp, modifier, statusTitle = title.takeUnless { hasScrollableTitle }) { contentModifier ->
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "title", span = { GridItemSpan(maxLineSpan) }) { BrowseTitle(title) }
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
    BrowseScaffold(
        navigateUp,
        modifier,
        statusTitle = title.takeUnless { state is FolderCatalogScreenState.Ready },
    ) { contentModifier ->
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
                title,
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
    title: String,
    onChildFolder: (String) -> Unit,
    onSelectList: (String) -> Unit,
    onCatalogItem: (CatalogItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    val direct = state.preferredListId != null
    val enabledLists = state.folder.lists.filter { it.enabled }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
    BoxWithConstraints(modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(defaultSkinWorkGridColumnCount(maxWidth)),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) { BrowseTitle(title) }
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
                            if (direct) {
                                MR.strings.tsuzuki_direct_list_unavailable
                            } else {
                                MR.strings.tsuzuki_folder_no_lists
                            },
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
}

@Composable
private fun BrowseScaffold(
    navigateUp: () -> Unit,
    modifier: Modifier,
    statusTitle: String? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(modifier = modifier, topBar = {
        AppBar(
            titleContent = {
                statusTitle?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            },
            navigateUp = navigateUp,
        )
    }) { padding ->
        content(Modifier.fillMaxSize().padding(padding))
    }
}

@Composable
private fun CatalogGridItem(item: CatalogItem, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
        propagateMinConstraints = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TsuzukiDefaultSkinCover(
                data = item.coverUrl,
                contentDescription = item.title,
                size = TsuzukiDefaultSkinCoverSize.Grid,
            )
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                metadataStartYear(item.startDate).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
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

@Composable
private fun BrowseTitle(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
    )
}
