package eu.kanade.presentation.tsuzuki.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionDraftPreviewState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFilterLookupKey
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFilterLookupState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFolderDraft
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFolderUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionUiModel
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionsAction
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.DragHandle
import mihon.icons.materialsymbols.rounded.Edit
import mihon.icons.materialsymbols.rounded.MoreVert
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.util.UUID

@Composable
internal fun CollectionEditorScreen(
    graph: CollectionUiModel?,
    providerDescriptors: List<CollectionProviderDescriptor> = emptyList(),
    providerCapabilities: Map<String, ProviderQueryCapabilities> = emptyMap(),
    draftPreviewState: CollectionDraftPreviewState = CollectionDraftPreviewState.Idle,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState> = emptyMap(),
    onFilterLookup: (String, String, String) -> Unit = { _, _, _ -> },
    onAction: (CollectionsAction) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    onClose: () -> Unit,
) {
    val collection = graph?.collection
    var title by remember(collection?.id) { mutableStateOf(collection?.title.orEmpty()) }
    var draftFolders by remember(collection?.id) { mutableStateOf(emptyList<DraftFolderEntry>()) }
    var folderEditor by remember(collection?.id) { mutableStateOf<CollectionFolderEditorRoute?>(null) }

    val rootFolders = graph?.folders
        ?.filter { it.depth == 0 }
        .orEmpty()
    var displayedFolders by remember(rootFolders) { mutableStateOf(rootFolders) }

    folderEditor?.let { route ->
        when (route) {
            CollectionFolderEditorRoute.NewDraft -> {
                FolderEditorScreen(
                    collectionId = null,
                    parentFolderId = null,
                    existing = null,
                    initialDraft = null,
                    providerDescriptors = providerDescriptors,
                    providerCapabilities = providerCapabilities,
                    draftPreviewState = draftPreviewState,
                    filterLookupStates = filterLookupStates,
                    onFilterLookup = onFilterLookup,
                    onSubmitDraft = { draft ->
                        draftFolders = draftFolders + DraftFolderEntry(
                            key = UUID.randomUUID().toString(),
                            draft = draft,
                        )
                        folderEditor = null
                    },
                    onAction = onAction,
                    onDelete = onDelete,
                    onClose = { folderEditor = null },
                )
            }

            is CollectionFolderEditorRoute.EditDraft -> {
                val entry = draftFolders.firstOrNull { it.key == route.key }
                if (entry == null) {
                    folderEditor = null
                } else {
                    FolderEditorScreen(
                        collectionId = null,
                        parentFolderId = null,
                        existing = null,
                        initialDraft = entry.draft,
                        providerDescriptors = providerDescriptors,
                        providerCapabilities = providerCapabilities,
                        draftPreviewState = draftPreviewState,
                        filterLookupStates = filterLookupStates,
                        onFilterLookup = onFilterLookup,
                        onSubmitDraft = { draft ->
                            draftFolders = draftFolders.map {
                                if (it.key == route.key) it.copy(draft = draft) else it
                            }
                            folderEditor = null
                        },
                        onAction = onAction,
                        onDelete = onDelete,
                        onClose = { folderEditor = null },
                    )
                }
            }

            CollectionFolderEditorRoute.NewPersisted -> {
                val collectionId = collection?.id
                if (collectionId == null) {
                    folderEditor = null
                } else {
                    FolderEditorScreen(
                        collectionId = collectionId,
                        parentFolderId = null,
                        existing = null,
                        initialDraft = null,
                        onSubmitDraft = null,
                        providerDescriptors = providerDescriptors,
                        providerCapabilities = providerCapabilities,
                        draftPreviewState = draftPreviewState,
                        filterLookupStates = filterLookupStates,
                        onFilterLookup = onFilterLookup,
                        onAction = onAction,
                        onDelete = onDelete,
                        onClose = { folderEditor = null },
                    )
                }
            }

            is CollectionFolderEditorRoute.EditPersisted -> {
                val model = graph?.folders?.firstOrNull { it.folder.id == route.folderId }
                if (model == null) {
                    folderEditor = null
                } else {
                    FolderEditorScreen(
                        collectionId = model.folder.collectionId,
                        parentFolderId = model.folder.parentFolderId,
                        existing = model,
                        initialDraft = null,
                        onSubmitDraft = null,
                        providerDescriptors = providerDescriptors,
                        providerCapabilities = providerCapabilities,
                        draftPreviewState = draftPreviewState,
                        filterLookupStates = filterLookupStates,
                        onFilterLookup = onFilterLookup,
                        onAction = onAction,
                        onDelete = onDelete,
                        onClose = { folderEditor = null },
                    )
                }
            }
        }
        return
    }

    BackHandler(onBack = onClose)

    val listState = rememberLazyListState()
    val persistentMode = collection != null
    val folderCount = if (persistentMode) displayedFolders.size else draftFolders.size
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = from.index - COLLECTION_EDITOR_HEADER_ITEMS
        val toIndex = to.index - COLLECTION_EDITOR_HEADER_ITEMS
        if (persistentMode) {
            if (fromIndex in displayedFolders.indices && toIndex in displayedFolders.indices) {
                displayedFolders = displayedFolders.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
            }
        } else if (fromIndex in draftFolders.indices && toIndex in draftFolders.indices) {
            draftFolders = draftFolders.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }

    CollectionsAdminEditorPage(
        title = stringResource(
            if (persistentMode) {
                MR.strings.tsuzuki_collections_admin_edit_collection
            } else {
                MR.strings.tsuzuki_collections_admin_new_collection
            },
        ),
        onBack = onClose,
        footerLabel = stringResource(
            if (persistentMode) {
                MR.strings.tsuzuki_collections_admin_save_changes
            } else {
                MR.strings.tsuzuki_collections_admin_create_collection
            },
        ),
        footerEnabled = title.isNotBlank(),
        onFooterClick = {
            if (persistentMode) {
                if (title.trim() != collection.title) {
                    onAction(
                        CollectionsAction.RenameCollection(
                            collection = collection,
                            title = title.trim(),
                        ),
                    )
                }
            } else {
                onAction(
                    CollectionsAction.CreateCollection(
                        title = title.trim(),
                        folders = draftFolders.map(DraftFolderEntry::draft),
                    ),
                )
            }
            onClose()
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "basic") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CollectionsAdminSectionHeader(
                        title = stringResource(MR.strings.tsuzuki_collections_admin_basic),
                    )
                    CollectionsAdminSurface {
                        Column(modifier = Modifier.padding(16.dp)) {
                            CollectionsAdminNameField(
                                value = title,
                                onValueChange = { title = it },
                                label = stringResource(MR.strings.tsuzuki_collections_admin_collection_name),
                            )
                        }
                    }
                }
            }

            item(key = "folders_header") {
                CollectionsAdminSectionHeader(
                    title = stringResource(MR.strings.tsuzuki_collections_admin_folders),
                    actionLabel = stringResource(MR.strings.tsuzuki_collections_admin_add_folder),
                    onAction = {
                        folderEditor = if (persistentMode) {
                            CollectionFolderEditorRoute.NewPersisted
                        } else {
                            CollectionFolderEditorRoute.NewDraft
                        }
                    },
                )
            }

            if (folderCount == 0) {
                item(key = "empty_folders") {
                    val colors = defaultSkinColors()
                    Text(
                        text = stringResource(MR.strings.tsuzuki_collections_no_folders),
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (persistentMode) {
                items(
                    items = displayedFolders,
                    key = { it.folder.id },
                ) { model ->
                    val reorderEnabled = displayedFolders.size > 1
                    ReorderableItem(
                        state = reorderableState,
                        key = model.folder.id,
                        enabled = reorderEnabled,
                    ) { isDragging ->
                        EditorFolderCard(
                            title = model.folder.title,
                            listCount = model.lists.size,
                            deleteLabel = stringResource(
                                if (model.folder.origin == CollectionOrigin.SYSTEM) {
                                    MR.strings.tsuzuki_collections_hide
                                } else {
                                    MR.strings.action_delete
                                },
                            ),
                            isDragging = isDragging,
                            reorderEnabled = reorderEnabled,
                            reorderHandleModifier = if (reorderEnabled) {
                                Modifier.draggableHandle(
                                    onDragStopped = {
                                        val original = rootFolders.map { it.folder.id }
                                        val displayed = displayedFolders.map { it.folder.id }
                                        if (displayed != original) {
                                            onAction(
                                                CollectionsAction.ReorderFolders(
                                                    collectionId = collection.id,
                                                    parentFolderId = null,
                                                    orderedFolderIds = displayed,
                                                ),
                                            )
                                        }
                                    },
                                )
                            } else {
                                Modifier
                            },
                            onEdit = {
                                folderEditor = CollectionFolderEditorRoute.EditPersisted(model.folder.id)
                            },
                            onDelete = {
                                onDelete(
                                    DeleteTarget(
                                        action = CollectionsAction.DeleteFolder(model.folder.id),
                                        systemOwned = model.folder.origin == CollectionOrigin.SYSTEM,
                                    ),
                                )
                            },
                        )
                    }
                }
            } else {
                items(
                    items = draftFolders,
                    key = DraftFolderEntry::key,
                ) { entry ->
                    val reorderEnabled = draftFolders.size > 1
                    ReorderableItem(
                        state = reorderableState,
                        key = entry.key,
                        enabled = reorderEnabled,
                    ) { isDragging ->
                        EditorFolderCard(
                            title = entry.draft.title,
                            listCount = entry.draft.lists.size,
                            deleteLabel = stringResource(MR.strings.tsuzuki_collections_admin_remove_draft),
                            isDragging = isDragging,
                            reorderEnabled = reorderEnabled,
                            reorderHandleModifier = if (reorderEnabled) {
                                Modifier.draggableHandle()
                            } else {
                                Modifier
                            },
                            onEdit = {
                                folderEditor = CollectionFolderEditorRoute.EditDraft(entry.key)
                            },
                            onDelete = {
                                draftFolders = draftFolders.filterNot { it.key == entry.key }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FolderEditorScreen(
    collectionId: String?,
    parentFolderId: String?,
    existing: CollectionFolderUiModel?,
    initialDraft: CollectionFolderDraft?,
    onSubmitDraft: ((CollectionFolderDraft) -> Unit)?,
    providerDescriptors: List<CollectionProviderDescriptor> = emptyList(),
    providerCapabilities: Map<String, ProviderQueryCapabilities> = emptyMap(),
    draftPreviewState: CollectionDraftPreviewState = CollectionDraftPreviewState.Idle,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState> = emptyMap(),
    onFilterLookup: (String, String, String) -> Unit = { _, _, _ -> },
    onAction: (CollectionsAction) -> Unit,
    onDelete: (DeleteTarget) -> Unit,
    onClose: () -> Unit,
) {
    val editingPersisted = existing != null
    val creatingPersisted = existing == null && collectionId != null && onSubmitDraft == null
    val titleSeed = existing?.folder?.title ?: initialDraft?.title.orEmpty()
    var title by remember(existing?.folder?.id, initialDraft) { mutableStateOf(titleSeed) }
    var draftLists by remember(existing?.folder?.id, initialDraft) {
        mutableStateOf(
            initialDraft?.lists.orEmpty().map { draft ->
                DraftListEntry(
                    key = UUID.randomUUID().toString(),
                    draft = draft,
                )
            },
        )
    }
    var listEditor by remember(existing?.folder?.id, initialDraft) {
        mutableStateOf<FolderListEditorTarget?>(null)
    }

    val persistentLists = existing?.lists.orEmpty()
    var displayedLists by remember(persistentLists) { mutableStateOf(persistentLists) }

    BackHandler(onBack = onClose)

    listEditor?.let { target ->
        val initial = when (target) {
            FolderListEditorTarget.New -> ListEditorState.empty()
            is FolderListEditorTarget.EditDraft -> ListEditorState.fromDraft(draftLists[target.index].draft)
            is FolderListEditorTarget.EditPersisted -> ListEditorState.from(target.list)
        }
        ListBuilderScreen(
            mode = if (target is FolderListEditorTarget.New) ListBuilderMode.CREATE else ListBuilderMode.EDIT,
            initial = initial,
            providerDescriptors = providerDescriptors,
            providerCapabilities = providerCapabilities,
            previewState = draftPreviewState,
            filterLookupStates = filterLookupStates,
            onFilterLookup = onFilterLookup,
            onPreviewDraft = { draft ->
                onAction(CollectionsAction.PreviewDraftChanged(draft))
            },
            onClose = { listEditor = null },
            onConfirm = { draft ->
                when (target) {
                    FolderListEditorTarget.New -> {
                        if (editingPersisted) {
                            onAction(
                                CollectionsAction.CreateList(
                                    collectionId = requireNotNull(collectionId),
                                    folderId = existing.folder.id,
                                    draft = draft,
                                ),
                            )
                        } else {
                            draftLists = draftLists + DraftListEntry(
                                key = UUID.randomUUID().toString(),
                                draft = draft,
                            )
                        }
                    }

                    is FolderListEditorTarget.EditDraft -> {
                        draftLists = draftLists.mapIndexed { index, current ->
                            if (index == target.index) current.copy(draft = draft) else current
                        }
                    }

                    is FolderListEditorTarget.EditPersisted -> {
                        onAction(CollectionsAction.UpdateList(target.list, draft))
                    }
                }
                listEditor = null
            },
        )
    }
    if (listEditor != null) return

    val listState = rememberLazyListState()
    val renderedListCount = if (editingPersisted) displayedLists.size else draftLists.size
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = from.index - FOLDER_EDITOR_HEADER_ITEMS
        val toIndex = to.index - FOLDER_EDITOR_HEADER_ITEMS
        if (editingPersisted) {
            if (fromIndex in displayedLists.indices && toIndex in displayedLists.indices) {
                displayedLists = displayedLists.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
            }
        } else if (fromIndex in draftLists.indices && toIndex in draftLists.indices) {
            draftLists = draftLists.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }

    val screenTitle = when {
        editingPersisted -> stringResource(MR.strings.tsuzuki_collections_admin_edit_folder)
        parentFolderId != null -> stringResource(MR.strings.tsuzuki_collections_admin_new_subfolder)
        else -> stringResource(MR.strings.tsuzuki_collections_admin_new_folder)
    }

    CollectionsAdminEditorPage(
        title = screenTitle,
        onBack = onClose,
        footerLabel = stringResource(
            if (editingPersisted) {
                MR.strings.tsuzuki_collections_admin_save_changes
            } else {
                MR.strings.tsuzuki_collections_admin_create_folder
            },
        ),
        footerEnabled = title.isNotBlank(),
        onFooterClick = {
            val normalizedTitle = title.trim()
            when {
                editingPersisted -> {
                    if (normalizedTitle != existing.folder.title) {
                        onAction(
                            CollectionsAction.RenameFolder(
                                folder = existing.folder,
                                title = normalizedTitle,
                            ),
                        )
                    }
                }

                creatingPersisted -> {
                    onAction(
                        CollectionsAction.CreateFolder(
                            collectionId = requireNotNull(collectionId),
                            parentFolderId = parentFolderId,
                            title = normalizedTitle,
                            lists = draftLists.map(DraftListEntry::draft),
                        ),
                    )
                }

                else -> {
                    onSubmitDraft?.invoke(
                        CollectionFolderDraft(
                            title = normalizedTitle,
                            lists = draftLists.map(DraftListEntry::draft),
                        ),
                    )
                }
            }
            if (editingPersisted || creatingPersisted) onClose()
        },
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = 12.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "basic") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CollectionsAdminSectionHeader(
                        title = stringResource(MR.strings.tsuzuki_collections_admin_basic),
                    )
                    CollectionsAdminSurface {
                        Column(modifier = Modifier.padding(16.dp)) {
                            CollectionsAdminNameField(
                                value = title,
                                onValueChange = { title = it },
                                label = stringResource(MR.strings.tsuzuki_collections_admin_folder_name),
                            )
                        }
                    }
                }
            }

            item(key = "lists_header") {
                CollectionsAdminSectionHeader(
                    title = stringResource(MR.strings.tsuzuki_collections_admin_lists),
                    actionLabel = stringResource(MR.strings.tsuzuki_collections_admin_add_list),
                    onAction = { listEditor = FolderListEditorTarget.New },
                )
            }

            if (renderedListCount == 0) {
                item(key = "empty_lists") {
                    val colors = defaultSkinColors()
                    Text(
                        text = stringResource(MR.strings.tsuzuki_collections_admin_no_lists),
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (editingPersisted) {
                items(
                    items = displayedLists,
                    key = CollectionList::id,
                ) { list ->
                    val reorderEnabled = displayedLists.size > 1
                    ReorderableItem(
                        state = reorderableState,
                        key = list.id,
                        enabled = reorderEnabled,
                    ) { isDragging ->
                        EditorListCard(
                            title = list.title,
                            deleteLabel = stringResource(
                                if (list.origin == CollectionOrigin.SYSTEM) {
                                    MR.strings.tsuzuki_collections_hide
                                } else {
                                    MR.strings.action_delete
                                },
                            ),
                            isDragging = isDragging,
                            reorderEnabled = reorderEnabled,
                            reorderHandleModifier = if (reorderEnabled) {
                                Modifier.draggableHandle(
                                    onDragStopped = {
                                        val original = persistentLists.map(CollectionList::id)
                                        val displayed = displayedLists.map(CollectionList::id)
                                        if (displayed != original) {
                                            onAction(
                                                CollectionsAction.ReorderLists(
                                                    folderId = existing.folder.id,
                                                    orderedListIds = displayed,
                                                ),
                                            )
                                        }
                                    },
                                )
                            } else {
                                Modifier
                            },
                            onEdit = {
                                listEditor = FolderListEditorTarget.EditPersisted(list)
                            },
                            onDuplicate = {
                                onAction(CollectionsAction.DuplicateList(list.id))
                            },
                            onDelete = {
                                onDelete(
                                    DeleteTarget(
                                        action = CollectionsAction.DeleteList(list.id),
                                        systemOwned = list.origin == CollectionOrigin.SYSTEM,
                                    ),
                                )
                            },
                        )
                    }
                }
            } else {
                items(
                    items = draftLists,
                    key = DraftListEntry::key,
                ) { entry ->
                    val index = draftLists.indexOfFirst { it.key == entry.key }
                    val draft = entry.draft
                    val copiedDraftTitle = stringResource(
                        MR.strings.tsuzuki_collections_admin_copy_name,
                        draft.title,
                    )
                    val reorderEnabled = draftLists.size > 1
                    ReorderableItem(
                        state = reorderableState,
                        key = entry.key,
                        enabled = reorderEnabled,
                    ) { isDragging ->
                        EditorListCard(
                            title = draft.title,
                            deleteLabel = stringResource(MR.strings.tsuzuki_collections_admin_remove_draft),
                            isDragging = isDragging,
                            reorderEnabled = reorderEnabled,
                            reorderHandleModifier = if (reorderEnabled) {
                                Modifier.draggableHandle()
                            } else {
                                Modifier
                            },
                            onEdit = {
                                listEditor = FolderListEditorTarget.EditDraft(index)
                            },
                            onDuplicate = {
                                draftLists = draftLists.toMutableList().apply {
                                    add(
                                        index + 1,
                                        DraftListEntry(
                                            key = UUID.randomUUID().toString(),
                                            draft = draft.copy(title = copiedDraftTitle),
                                        ),
                                    )
                                }
                            },
                            onDelete = {
                                draftLists = draftLists.toMutableList().apply {
                                    removeAt(index)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorFolderCard(
    title: String,
    listCount: Int,
    deleteLabel: String,
    isDragging: Boolean,
    reorderEnabled: Boolean,
    reorderHandleModifier: Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = defaultSkinColors()
    val menuActions = listOf(
        CollectionsAdminMenuAction(
            title = deleteLabel,
            onClick = {
                menuExpanded = false
                onDelete()
            },
        ),
    )

    CollectionsAdminSurface(isDragging = isDragging) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground,
                )
                Text(
                    text = pluralStringResource(
                        MR.plurals.tsuzuki_collections_admin_list_count,
                        listCount,
                        listCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorDragHandle(
                    modifier = reorderHandleModifier,
                    enabled = reorderEnabled,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Edit,
                        contentDescription = stringResource(MR.strings.tsuzuki_collections_admin_edit_folder),
                        tint = colors.foreground,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.MoreVert,
                            contentDescription = stringResource(MR.strings.tsuzuki_collections_admin_folder_actions),
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
        }
    }
}

@Composable
private fun EditorListCard(
    title: String,
    deleteLabel: String,
    isDragging: Boolean,
    reorderEnabled: Boolean,
    reorderHandleModifier: Modifier,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colors = defaultSkinColors()
    val menuActions = listOf(
        CollectionsAdminMenuAction(
            title = stringResource(MR.strings.tsuzuki_collections_duplicate),
            onClick = {
                menuExpanded = false
                onDuplicate()
            },
        ),
        CollectionsAdminMenuAction(
            title = deleteLabel,
            onClick = {
                menuExpanded = false
                onDelete()
            },
        ),
    )

    CollectionsAdminSurface(isDragging = isDragging) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.foreground,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorDragHandle(
                    modifier = reorderHandleModifier,
                    enabled = reorderEnabled,
                )
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Edit,
                        contentDescription = stringResource(MR.strings.tsuzuki_collections_admin_edit_list),
                        tint = colors.foreground,
                    )
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.MoreVert,
                            contentDescription = stringResource(MR.strings.tsuzuki_collections_admin_list_actions),
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
        }
    }
}

@Composable
private fun EditorDragHandle(
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
            contentDescription = stringResource(MR.strings.tsuzuki_collections_admin_reorder),
            tint = if (enabled) {
                colors.secondary
            } else {
                colors.secondary.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun EditorSectionHeader(
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorSectionLabel(title)
        Spacer(modifier = Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = onAction) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Add,
                contentDescription = null,
            )
            Text(
                text = actionLabel,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
internal fun EditorSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun EditorSurface(
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun EditorEmptyCard(
    title: String,
    body: String,
) {
    EditorSurface {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun EditorBottomBar(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            enabled = enabled,
            onClick = onClick,
        ) {
            Text(label)
        }
    }
}

private fun editorCountLabel(
    count: Int,
    singular: String,
): String = if (count == 1) {
    "1 $singular"
} else {
    "$count ${singular}s"
}

private data class DraftFolderEntry(
    val key: String,
    val draft: CollectionFolderDraft,
)

private data class DraftListEntry(
    val key: String,
    val draft: CollectionListDraft,
)

private sealed interface CollectionFolderEditorRoute {
    data object NewDraft : CollectionFolderEditorRoute
    data class EditDraft(val key: String) : CollectionFolderEditorRoute
    data object NewPersisted : CollectionFolderEditorRoute
    data class EditPersisted(val folderId: String) : CollectionFolderEditorRoute
}

private sealed interface FolderListEditorTarget {
    data object New : FolderListEditorTarget
    data class EditDraft(val index: Int) : FolderListEditorTarget
    data class EditPersisted(val list: CollectionList) : FolderListEditorTarget
}

private const val COLLECTION_EDITOR_HEADER_ITEMS = 2
private const val FOLDER_EDITOR_HEADER_ITEMS = 3
