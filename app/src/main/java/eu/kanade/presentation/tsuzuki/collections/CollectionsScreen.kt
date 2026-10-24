package eu.kanade.presentation.tsuzuki.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionCardAction
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionDraftPreviewState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFolderUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsAction
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsScreenState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsTransferState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.handleCollectionCardAction
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.MoreVert
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun CollectionsScreen(
    state: CollectionsScreenState,
    navigateUp: () -> Unit,
    onAction: (CollectionsAction) -> Unit,
    onImportRequest: () -> Unit,
    onExportRequest: () -> Unit,
    onDismissTransferState: () -> Unit,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editor by remember { mutableStateOf<EditorDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }
    val readyState = state as? CollectionsScreenState.Ready

    val currentEditor = editor
    val editedCollectionGraph = (currentEditor as? EditorDialog.RenameCollection)
        ?.let { target ->
            readyState?.collections?.firstOrNull { it.collection.id == target.collection.id }
        }
    val editedFolderModel = (currentEditor as? EditorDialog.RenameFolder)
        ?.let { target ->
            readyState?.collections
                ?.asSequence()
                ?.flatMap { it.folders.asSequence() }
                ?.firstOrNull { it.folder.id == target.folder.id }
        }

    val fullScreenEditorRendered = when (currentEditor) {
        EditorDialog.CreateCollection -> {
            CollectionEditorScreen(
                graph = null,
                providerDescriptors = readyState?.providerDescriptors.orEmpty(),
                providerCapabilities = readyState?.providerCapabilities.orEmpty(),
                draftPreviewState = readyState?.draftPreviewState ?: CollectionDraftPreviewState.Idle,
                filterLookupStates = readyState?.filterLookupStates.orEmpty(),
                onFilterLookup = { providerId, lookupId, query ->
                    onAction(
                        CollectionsAction.FilterLookupRequested(
                            providerId = providerId,
                            lookupId = lookupId,
                            query = query,
                        ),
                    )
                },
                onAction = onAction,
                onDelete = { deleteTarget = it },
                onClose = { editor = null },
            )
            true
        }

        is EditorDialog.RenameCollection -> {
            editedCollectionGraph?.let { graph ->
                CollectionEditorScreen(
                    graph = graph,
                    providerDescriptors = readyState?.providerDescriptors.orEmpty(),
                    providerCapabilities = readyState?.providerCapabilities.orEmpty(),
                    draftPreviewState = readyState?.draftPreviewState ?: CollectionDraftPreviewState.Idle,
                    filterLookupStates = readyState?.filterLookupStates.orEmpty(),
                    onFilterLookup = { providerId, lookupId, query ->
                        onAction(
                            CollectionsAction.FilterLookupRequested(
                                providerId = providerId,
                                lookupId = lookupId,
                                query = query,
                            ),
                        )
                    },
                    onAction = onAction,
                    onDelete = { deleteTarget = it },
                    onClose = { editor = null },
                )
            } != null
        }

        is EditorDialog.CreateFolder -> {
            FolderEditorScreen(
                collectionId = currentEditor.collectionId,
                parentFolderId = currentEditor.parentFolderId,
                existing = null,
                initialDraft = null,
                onSubmitDraft = null,
                providerDescriptors = readyState?.providerDescriptors.orEmpty(),
                providerCapabilities = readyState?.providerCapabilities.orEmpty(),
                draftPreviewState = readyState?.draftPreviewState ?: CollectionDraftPreviewState.Idle,
                filterLookupStates = readyState?.filterLookupStates.orEmpty(),
                onFilterLookup = { providerId, lookupId, query ->
                    onAction(
                        CollectionsAction.FilterLookupRequested(
                            providerId = providerId,
                            lookupId = lookupId,
                            query = query,
                        ),
                    )
                },
                onAction = onAction,
                onDelete = { deleteTarget = it },
                onClose = { editor = null },
            )
            true
        }

        is EditorDialog.RenameFolder -> {
            editedFolderModel?.let { model ->
                FolderEditorScreen(
                    collectionId = model.folder.collectionId,
                    parentFolderId = model.folder.parentFolderId,
                    existing = model,
                    initialDraft = null,
                    onSubmitDraft = null,
                    providerDescriptors = readyState?.providerDescriptors.orEmpty(),
                    providerCapabilities = readyState?.providerCapabilities.orEmpty(),
                    draftPreviewState = readyState?.draftPreviewState ?: CollectionDraftPreviewState.Idle,
                    filterLookupStates = readyState?.filterLookupStates.orEmpty(),
                    onFilterLookup = { providerId, lookupId, query ->
                        onAction(
                            CollectionsAction.FilterLookupRequested(
                                providerId = providerId,
                                lookupId = lookupId,
                                query = query,
                            ),
                        )
                    },
                    onAction = onAction,
                    onDelete = { deleteTarget = it },
                    onClose = { editor = null },
                )
            } != null
        }

        is EditorDialog.CreateList -> {
            ListBuilderScreen(
                mode = ListBuilderMode.CREATE,
                initial = ListEditorState.empty(),
                providerDescriptors = readyState?.providerDescriptors.orEmpty(),
                providerCapabilities = readyState?.providerCapabilities.orEmpty(),
                previewState = readyState?.draftPreviewState ?: CollectionDraftPreviewState.Idle,
                filterLookupStates = readyState?.filterLookupStates.orEmpty(),
                onFilterLookup = { providerId, lookupId, query ->
                    onAction(
                        CollectionsAction.FilterLookupRequested(
                            providerId = providerId,
                            lookupId = lookupId,
                            query = query,
                        ),
                    )
                },
                onPreviewDraft = { draft ->
                    onAction(CollectionsAction.PreviewDraftChanged(draft))
                },
                onClose = { editor = null },
                onConfirm = { draft ->
                    onAction(
                        CollectionsAction.CreateList(
                            collectionId = currentEditor.collectionId,
                            folderId = currentEditor.folderId,
                            draft = draft,
                        ),
                    )
                    editor = null
                },
            )
            true
        }

        is EditorDialog.EditList -> {
            ListBuilderScreen(
                mode = ListBuilderMode.EDIT,
                initial = ListEditorState.from(currentEditor.list),
                providerDescriptors = readyState?.providerDescriptors.orEmpty(),
                providerCapabilities = readyState?.providerCapabilities.orEmpty(),
                previewState = readyState?.draftPreviewState ?: CollectionDraftPreviewState.Idle,
                filterLookupStates = readyState?.filterLookupStates.orEmpty(),
                onFilterLookup = { providerId, lookupId, query ->
                    onAction(
                        CollectionsAction.FilterLookupRequested(
                            providerId = providerId,
                            lookupId = lookupId,
                            query = query,
                        ),
                    )
                },
                onPreviewDraft = { draft ->
                    onAction(CollectionsAction.PreviewDraftChanged(draft))
                },
                onClose = { editor = null },
                onConfirm = { draft ->
                    onAction(CollectionsAction.UpdateList(currentEditor.list, draft))
                    editor = null
                },
            )
            true
        }

        else -> false
    }

    if (!fullScreenEditorRendered) {
        CollectionsAdminRootPage(
            title = stringResource(MR.strings.tsuzuki_collections_admin_title),
            onBack = navigateUp,
            modifier = modifier,
            actions = listOf(
                CollectionsAdminMenuAction(
                    title = stringResource(MR.strings.tsuzuki_collections_import),
                    onClick = onImportRequest,
                ),
                CollectionsAdminMenuAction(
                    title = stringResource(MR.strings.export),
                    onClick = onExportRequest,
                ),
            ),
        ) {
            when (state) {
                CollectionsScreenState.Loading -> {
                    val colors = defaultSkinColors()
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }

                is CollectionsScreenState.Error -> {
                    val colors = defaultSkinColors()
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = state.message, color = colors.secondary)
                    }
                }

                is CollectionsScreenState.Ready -> {
                    CollectionsReadyContent(
                        collections = state.collections,
                        onAction = onAction,
                        onEdit = { editor = it },
                        onDelete = { deleteTarget = it },
                        onOpenCollection = onOpenCollection,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )

                    when (val transfer = state.transferState) {
                        CollectionsTransferState.Idle,
                        is CollectionsTransferState.ExportReady,
                        -> Unit

                        CollectionsTransferState.Working -> {
                            CollectionsAdminSheet(
                                title = stringResource(MR.strings.tsuzuki_collections_admin_title),
                                message = stringResource(MR.strings.tsuzuki_collections_admin_working),
                                busy = true,
                                onDismiss = {},
                            )
                        }

                        is CollectionsTransferState.ImportSucceeded -> {
                            CollectionsAdminSheet(
                                title = stringResource(MR.strings.tsuzuki_collections_admin_import_success_title),
                                message = stringResource(
                                    MR.strings.tsuzuki_collections_admin_import_success_message,
                                    transfer.result.collectionIds.size,
                                    transfer.result.folderCount,
                                    transfer.result.listCount,
                                    transfer.result.remappedIdCount,
                                ),
                                confirmLabel = stringResource(MR.strings.tsuzuki_collections_admin_ok),
                                onConfirm = onDismissTransferState,
                                onDismiss = onDismissTransferState,
                            )
                        }

                        is CollectionsTransferState.Error -> {
                            CollectionsAdminSheet(
                                title = stringResource(MR.strings.tsuzuki_collections_admin_transfer_error_title),
                                message = transfer.message,
                                confirmLabel = stringResource(MR.strings.tsuzuki_collections_admin_ok),
                                onConfirm = onDismissTransferState,
                                onDismiss = onDismissTransferState,
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        val collectionTarget = target.action is CollectionsAction.DeleteCollection
        val folderTarget = target.action is CollectionsAction.DeleteFolder
        val title = when {
            collectionTarget && target.systemOwned -> stringResource(MR.strings.tsuzuki_collections_hide_title)
            collectionTarget -> stringResource(MR.strings.tsuzuki_collections_delete_title)
            folderTarget && target.systemOwned -> stringResource(MR.strings.tsuzuki_collections_admin_hide_folder_title)
            folderTarget -> stringResource(MR.strings.tsuzuki_collections_admin_delete_folder_title)
            target.systemOwned -> stringResource(MR.strings.tsuzuki_collections_admin_hide_list_title)
            else -> stringResource(MR.strings.tsuzuki_collections_admin_delete_list_title)
        }
        val message = when {
            collectionTarget && target.systemOwned -> stringResource(MR.strings.tsuzuki_collections_hide_message)
            collectionTarget -> stringResource(MR.strings.tsuzuki_collections_delete_message)
            folderTarget && target.systemOwned -> stringResource(
                MR.strings.tsuzuki_collections_admin_hide_folder_message,
            )
            folderTarget -> stringResource(MR.strings.tsuzuki_collections_admin_delete_folder_message)
            target.systemOwned -> stringResource(MR.strings.tsuzuki_collections_admin_hide_list_message)
            else -> stringResource(MR.strings.tsuzuki_collections_admin_delete_list_message)
        }
        CollectionsAdminSheet(
            title = title,
            message = message,
            confirmLabel = stringResource(
                if (target.systemOwned) MR.strings.tsuzuki_collections_hide else MR.strings.action_delete,
            ),
            dismissLabel = stringResource(MR.strings.action_cancel),
            onConfirm = {
                onAction(target.action)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun CollectionsReadyContent(
    collections: List<CollectionUiModel>,
    onAction: (CollectionsAction) -> Unit,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    onOpenCollection: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayedCollections by remember(collections) { mutableStateOf(collections) }
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = from.index - COLLECTION_LIST_HEADER_COUNT
        val toIndex = to.index - COLLECTION_LIST_HEADER_COUNT
        if (fromIndex in displayedCollections.indices && toIndex in displayedCollections.indices) {
            displayedCollections = displayedCollections.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }
    LazyColumn(
        modifier = modifier,
        state = lazyListState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "collections_overview") {
            CollectionsOverview(collections = collections, onCreate = { onEdit(EditorDialog.CreateCollection) })
        }
        if (collections.isEmpty()) {
            item(key = "empty") {
                val colors = defaultSkinColors()
                Text(
                    stringResource(MR.strings.tsuzuki_collections_empty),
                    color = colors.secondary,
                )
            }
        }
        items(items = displayedCollections, key = { "collection:${it.collection.id}" }) { graph ->
            val itemKey = "collection:${graph.collection.id}"
            val reorderEnabled = displayedCollections.size > 1
            ReorderableItem(state = reorderableState, key = itemKey, enabled = reorderEnabled) { isDragging ->
                CollectionCard(
                    graph = graph,
                    isDragging = isDragging,
                    reorderEnabled = reorderEnabled,
                    reorderHandleModifier = if (reorderEnabled) {
                        Modifier.draggableHandle(onDragStopped = {
                            val persistedOrder = collections.map { it.collection.id }
                            val displayedOrder = displayedCollections.map { it.collection.id }
                            if (displayedOrder != persistedOrder) {
                                onAction(CollectionsAction.ReorderCollections(displayedOrder))
                            }
                        })
                    } else {
                        Modifier
                    },
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onDuplicate = { onAction(CollectionsAction.DuplicateCollection(graph.collection.id)) },
                    onOpen = {
                        handleCollectionCardAction(
                            collection = graph.collection,
                            action = CollectionCardAction.OPEN,
                            onOpen = onOpenCollection,
                            onEdit = { onEdit(EditorDialog.RenameCollection(it)) },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CollectionsOverview(
    collections: List<CollectionUiModel>,
    onCreate: () -> Unit,
) {
    val folderCount = collections.sumOf { it.folders.size }

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DefaultSkinSectionLabel(text = stringResource(MR.strings.tsuzuki_collections_overview))
        val colors = defaultSkinColors()
        Text(
            stringResource(MR.strings.tsuzuki_collections_totals, collections.size, folderCount),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondary,
        )
        DefaultSkinPrimaryButton(
            text = stringResource(MR.strings.tsuzuki_collections_new),
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
            leadingContent = {
                Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null)
            },
        )
    }
}

@Composable
private fun CollectionCard(
    graph: CollectionUiModel,
    isDragging: Boolean,
    reorderEnabled: Boolean,
    reorderHandleModifier: Modifier,
    onEdit: (EditorDialog) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    onDuplicate: () -> Unit,
    onOpen: () -> Unit,
) {
    val collection = graph.collection
    val previewFolders = graph.folders.take(COLLECTION_PREVIEW_FOLDER_COUNT)
    val hiddenFolderCount = graph.folders.size - previewFolders.size
    var menuExpanded by remember(collection.id) { mutableStateOf(false) }
    val colors = defaultSkinColors()
    val newFolderAction = if (collection.origin == CollectionOrigin.USER) {
        listOf(
            CollectionsAdminMenuAction(
                title = stringResource(MR.strings.tsuzuki_collections_new_folder),
                onClick = {
                    onEdit(
                        EditorDialog.CreateFolder(
                            collectionId = collection.id,
                            parentFolderId = null,
                        ),
                    )
                },
            ),
        )
    } else {
        emptyList()
    }
    val menuActions = newFolderAction + listOf(
        CollectionsAdminMenuAction(
            title = stringResource(MR.strings.tsuzuki_collections_duplicate),
            onClick = onDuplicate,
        ),
        CollectionsAdminMenuAction(
            title = stringResource(
                if (collection.origin == CollectionOrigin.SYSTEM) {
                    MR.strings.tsuzuki_collections_hide
                } else {
                    MR.strings.action_delete
                },
            ),
            onClick = {
                onDelete(
                    DeleteTarget(
                        action = CollectionsAction.DeleteCollection(collection.id),
                        systemOwned = collection.origin == CollectionOrigin.SYSTEM,
                    ),
                )
            },
        ),
    ).map { action ->
        action.copy(
            onClick = {
                menuExpanded = false
                action.onClick()
            },
        )
    }

    CollectionsAdminSurface(
        isDragging = isDragging,
        onClick = onOpen,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = collection.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = colors.foreground,
                    )
                    Text(
                        text = pluralStringResource(
                            MR.plurals.tsuzuki_collections_admin_folder_count,
                            graph.folders.size,
                            graph.folders.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondary,
                    )
                }

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.MoreVert,
                            contentDescription = stringResource(MR.strings.tsuzuki_collections_actions),
                            tint = colors.foreground,
                        )
                    }
                    CollectionsAdminOverflowMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        actions = menuActions,
                    )
                }
            }

            if (previewFolders.isEmpty()) {
                Text(
                    text = stringResource(MR.strings.tsuzuki_collections_no_folders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.secondary,
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = previewFolders.joinToString(separator = " · ") { it.folder.title },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondary,
                    )
                    if (hiddenFolderCount > 0) {
                        Text(
                            text = stringResource(MR.strings.tsuzuki_collections_more_folders, hiddenFolderCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.secondary,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CollectionReorderHandle(
                    modifier = reorderHandleModifier,
                    enabled = reorderEnabled,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (collection.origin == CollectionOrigin.USER) {
                    IconButton(
                        onClick = {
                            handleCollectionCardAction(collection, CollectionCardAction.EDIT, onOpen = {
                            }, onEdit = { onEdit(EditorDialog.RenameCollection(it)) })
                        },
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Edit,
                            contentDescription = stringResource(MR.strings.tsuzuki_collections_edit),
                            tint = colors.foreground,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionReorderHandle(
    modifier: Modifier,
    enabled: Boolean,
) {
    val colors = defaultSkinColors()
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.DragHandle,
            contentDescription = stringResource(MR.strings.tsuzuki_collections_reorder),
            tint = if (enabled) {
                colors.secondary
            } else {
                colors.secondary.copy(alpha = 0.38f)
            },
        )
    }
}

internal data class ListEditorState(
    val title: String,
    val sort: CollectionSortSelection,
    val layoutType: String?,
    val providerId: String,
    val status: String?,
    val format: String?,
    val includeGenre: String,
    val excludeGenre: String,
    val includeTag: String,
    val excludeTag: String,
    val minScore: String,
    val minChapters: String,
    val minVolumes: String,
    val extraTerms: List<QueryExpression>,
    val preservedQuery: QueryExpression?,
    val filtersEditable: Boolean,
) {
    fun toDraftOrNull(): CollectionListDraft? {
        if (title.isBlank()) return null

        return CollectionListDraft(
            title = title.trim(),
            query = currentQuery(),
            sort = sort,
            layoutType = layoutType,
            providerId = providerId,
        )
    }

    internal fun currentQuery(): QueryExpression? {
        return if (!filtersEditable) {
            preservedQuery
        } else {
            val predicates = buildList<QueryExpression> {
                status?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.STATUS,
                            QueryOperator.EQUALS,
                            QueryValue.of(it),
                        ),
                    )
                }
                format?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.WORK_TYPE,
                            QueryOperator.EQUALS,
                            QueryValue.of(it),
                        ),
                    )
                }
                includeGenre.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.GENRE,
                            QueryOperator.CONTAINS,
                            QueryValue.of(it),
                        ),
                    )
                }
                excludeGenre.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Not(
                            QueryExpression.Predicate(
                                QueryField.GENRE,
                                QueryOperator.CONTAINS,
                                QueryValue.of(it),
                            ),
                        ),
                    )
                }
                includeTag.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.TAG,
                            QueryOperator.CONTAINS,
                            QueryValue.of(it),
                        ),
                    )
                }
                excludeTag.trim().takeIf(String::isNotEmpty)?.let {
                    add(
                        QueryExpression.Not(
                            QueryExpression.Predicate(
                                QueryField.TAG,
                                QueryOperator.CONTAINS,
                                QueryValue.of(it),
                            ),
                        ),
                    )
                }
                minScore.toDoubleOrNull()?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.SCORE,
                            QueryOperator.GREATER_OR_EQUAL,
                            QueryValue.of(it),
                        ),
                    )
                }
                minChapters.toLongOrNull()?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.CHAPTER_COUNT,
                            QueryOperator.GREATER_OR_EQUAL,
                            QueryValue.of(it),
                        ),
                    )
                }
                minVolumes.toLongOrNull()?.let {
                    add(
                        QueryExpression.Predicate(
                            QueryField.VOLUME_COUNT,
                            QueryOperator.GREATER_OR_EQUAL,
                            QueryValue.of(it),
                        ),
                    )
                }
                addAll(extraTerms)
            }

            when (predicates.size) {
                0 -> null
                1 -> predicates.single()
                else -> QueryExpression.All(predicates)
            }
        }
    }

    companion object {
        fun empty(): ListEditorState = ListEditorState(
            title = "",
            sort = CollectionSortSelection.DEFAULT,
            layoutType = null,
            providerId = "kitsu",
            status = null,
            format = null,
            includeGenre = "",
            excludeGenre = "",
            includeTag = "",
            excludeTag = "",
            minScore = "",
            minChapters = "",
            minVolumes = "",
            extraTerms = emptyList(),
            preservedQuery = null,
            filtersEditable = true,
        )

        fun from(list: CollectionList): ListEditorState {
            val parsed = parseSimpleQuery(list.query)
            return ListEditorState(
                title = list.title,
                sort = list.sort,
                layoutType = list.layoutType,
                providerId = list.providerId,
                status = parsed?.status,
                format = parsed?.format,
                includeGenre = parsed?.includeGenre.orEmpty(),
                excludeGenre = parsed?.excludeGenre.orEmpty(),
                includeTag = parsed?.includeTag.orEmpty(),
                excludeTag = parsed?.excludeTag.orEmpty(),
                minScore = parsed?.minScore.orEmpty(),
                minChapters = parsed?.minChapters.orEmpty(),
                minVolumes = parsed?.minVolumes.orEmpty(),
                extraTerms = parsed?.extraTerms.orEmpty(),
                preservedQuery = if (parsed == null) list.query else null,
                filtersEditable = parsed != null,
            )
        }

        fun fromDraft(draft: CollectionListDraft): ListEditorState {
            val parsed = parseSimpleQuery(draft.query)
            return ListEditorState(
                title = draft.title,
                sort = draft.sort,
                layoutType = draft.layoutType,
                providerId = draft.providerId,
                status = parsed?.status,
                format = parsed?.format,
                includeGenre = parsed?.includeGenre.orEmpty(),
                excludeGenre = parsed?.excludeGenre.orEmpty(),
                includeTag = parsed?.includeTag.orEmpty(),
                excludeTag = parsed?.excludeTag.orEmpty(),
                minScore = parsed?.minScore.orEmpty(),
                minChapters = parsed?.minChapters.orEmpty(),
                minVolumes = parsed?.minVolumes.orEmpty(),
                extraTerms = parsed?.extraTerms.orEmpty(),
                preservedQuery = if (parsed == null) draft.query else null,
                filtersEditable = parsed != null,
            )
        }
    }
}

private data class ParsedSimpleQuery(
    val status: String? = null,
    val format: String? = null,
    val includeGenre: String? = null,
    val excludeGenre: String? = null,
    val includeTag: String? = null,
    val excludeTag: String? = null,
    val minScore: String? = null,
    val minChapters: String? = null,
    val minVolumes: String? = null,
    val extraTerms: List<QueryExpression> = emptyList(),
)

private data class ParsedSimpleTerm(
    val predicate: QueryExpression.Predicate,
    val negated: Boolean,
)

private fun parseSimpleQuery(expression: QueryExpression?): ParsedSimpleQuery? {
    if (expression == null) return ParsedSimpleQuery()

    fun parseTerm(candidate: QueryExpression): ParsedSimpleTerm? {
        return when (candidate) {
            is QueryExpression.Predicate -> ParsedSimpleTerm(candidate, negated = false)
            is QueryExpression.Not -> {
                val predicate = candidate.expression as? QueryExpression.Predicate ?: return null
                ParsedSimpleTerm(predicate, negated = true)
            }
            else -> null
        }
    }

    val terms = when (expression) {
        is QueryExpression.Predicate,
        is QueryExpression.Not,
        -> listOf(parseTerm(expression) ?: return null)
        is QueryExpression.All -> expression.expressions.map { parseTerm(it) ?: return null }
        else -> return null
    }

    var result = ParsedSimpleQuery()
    val seen = mutableSetOf<Pair<QueryField, Boolean>>()

    for (term in terms) {
        val predicate = term.predicate
        val value = predicate.value
        val repeatableExactGenre =
            !term.negated &&
                predicate.field == QueryField.GENRE &&
                predicate.operator == QueryOperator.EQUALS &&
                value is QueryValue.StringValue
        if (!seen.add(predicate.field to term.negated) && !repeatableExactGenre) return null

        result = when {
            !term.negated &&
                predicate.field == QueryField.STATUS &&
                predicate.operator == QueryOperator.EQUALS &&
                value is QueryValue.StringValue -> {
                result.copy(status = value.value)
            }

            !term.negated &&
                predicate.field == QueryField.WORK_TYPE &&
                predicate.operator == QueryOperator.EQUALS &&
                value is QueryValue.StringValue -> {
                result.copy(format = value.value)
            }

            predicate.field == QueryField.GENRE &&
                predicate.operator == QueryOperator.CONTAINS &&
                value is QueryValue.StringValue -> {
                if (term.negated) {
                    result.copy(excludeGenre = value.value)
                } else {
                    result.copy(includeGenre = value.value)
                }
            }

            predicate.field == QueryField.TAG &&
                predicate.operator == QueryOperator.CONTAINS &&
                value is QueryValue.StringValue -> {
                if (term.negated) {
                    result.copy(excludeTag = value.value)
                } else {
                    result.copy(includeTag = value.value)
                }
            }

            !term.negated &&
                predicate.field == QueryField.SCORE &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL -> {
                result.copy(minScore = numericText(value) ?: return null)
            }

            !term.negated &&
                predicate.field == QueryField.CHAPTER_COUNT &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL &&
                value is QueryValue.IntegerValue -> {
                result.copy(minChapters = value.value.toString())
            }

            !term.negated &&
                predicate.field == QueryField.VOLUME_COUNT &&
                predicate.operator == QueryOperator.GREATER_OR_EQUAL &&
                value is QueryValue.IntegerValue -> {
                result.copy(minVolumes = value.value.toString())
            }

            else -> {
                result.copy(
                    extraTerms = result.extraTerms + if (term.negated) {
                        QueryExpression.Not(predicate)
                    } else {
                        predicate
                    },
                )
            }
        }
    }

    return result
}

private fun numericText(value: QueryValue): String? = when (value) {
    is QueryValue.IntegerValue -> value.value.toString()
    is QueryValue.DoubleValue -> value.value.toString()
    else -> null
}

private sealed interface EditorDialog {
    data object CreateCollection : EditorDialog
    data class RenameCollection(val collection: TsuzukiCollection) : EditorDialog
    data class CreateFolder(
        val collectionId: String,
        val parentFolderId: String?,
    ) : EditorDialog
    data class RenameFolder(val folder: CollectionFolder) : EditorDialog
    data class CreateList(
        val collectionId: String,
        val folderId: String,
    ) : EditorDialog
    data class EditList(val list: CollectionList) : EditorDialog
}

internal data class DeleteTarget(
    val action: CollectionsAction,
    val systemOwned: Boolean,
)

private const val COLLECTION_LIST_HEADER_COUNT = 1
private const val COLLECTION_PREVIEW_FOLDER_COUNT = 3
