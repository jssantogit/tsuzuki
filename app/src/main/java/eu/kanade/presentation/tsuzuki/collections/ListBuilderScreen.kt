package eu.kanade.presentation.tsuzuki.collections

// Structural List Builder V2: Quick Builder + Advanced.

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.tsuzuki.components.DefaultSkinChoiceChip
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextField
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionDraftPreviewState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFilterLookupKey
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionFilterLookupState
import eu.kanade.tachiyomi.ui.tsuzuki.collections.CollectionListDraft
import tachiyomi.domain.tsuzuki.collections.capability.CollectionFilterCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderSwitchPlan
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderSwitchPlanner
import tachiyomi.domain.tsuzuki.collections.capability.FilterPlacement
import tachiyomi.domain.tsuzuki.collections.capability.FilterValueSource
import tachiyomi.domain.tsuzuki.collections.capability.MultiValueMode
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.SortDirectionMode
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.text.Normalizer

internal enum class ListBuilderMode {
    CREATE,
    EDIT,
}

@Composable
internal fun ListBuilderScreen(
    mode: ListBuilderMode,
    initial: ListEditorState,
    providerDescriptors: List<CollectionProviderDescriptor> = emptyList(),
    providerCapabilities: Map<String, ProviderQueryCapabilities> = emptyMap(),
    previewState: CollectionDraftPreviewState = CollectionDraftPreviewState.Idle,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState> = emptyMap(),
    onPreviewDraft: (CollectionListDraft?) -> Unit = {},
    onFilterLookup: (String, String, String) -> Unit = { _, _, _ -> },
    onClose: () -> Unit,
    onConfirm: (CollectionListDraft) -> Unit,
) {
    var editor by remember(initial) { mutableStateOf(initial) }
    var advanced by remember(initial) { mutableStateOf(false) }
    var pendingSwitch by remember(initial) { mutableStateOf<CollectionProviderSwitchPlan.Ready?>(null) }
    var blockedSwitch by remember(initial) { mutableStateOf(false) }
    val draft = editor.toDraftOrNull()

    LaunchedEffect(draft) {
        onPreviewDraft(draft)
    }
    DisposableEffect(Unit) {
        onDispose {
            onPreviewDraft(null)
        }
    }

    BackHandler {
        if (advanced) {
            advanced = false
        } else {
            onClose()
        }
    }

    fun applyProviderSwitch(plan: CollectionProviderSwitchPlan.Ready) {
        val target = providerCapabilities[plan.targetProviderId] ?: return
        val targetSort = plan.sort
            ?: target.descriptor.uiSortSelections().firstOrNull()
            ?: return
        editor = ListEditorState.fromDraft(
            CollectionListDraft(
                title = editor.title,
                query = plan.query,
                sort = targetSort,
                layoutType = editor.layoutType,
                providerId = plan.targetProviderId,
            ),
        )
    }

    fun requestProviderSwitch(targetProviderId: String) {
        if (targetProviderId == editor.providerId) return
        val target = providerCapabilities[targetProviderId] ?: return
        when (
            val plan = CollectionProviderSwitchPlanner.plan(
                sourceProviderId = editor.providerId,
                target = target,
                query = editor.currentQuery(),
                sort = editor.sort,
            )
        ) {
            is CollectionProviderSwitchPlan.Blocked -> {
                blockedSwitch = true
            }
            is CollectionProviderSwitchPlan.Ready -> {
                if (plan.requiresConfirmation) {
                    pendingSwitch = plan
                } else {
                    applyProviderSwitch(plan)
                }
            }
        }
    }

    pendingSwitch?.let { plan ->
        val targetName = providerDescriptors
            .firstOrNull { it.providerId == plan.targetProviderId }
            ?.displayName
            ?: providerDisplayName(plan.targetProviderId)
        val sourceDescriptor = providerDescriptors.firstOrNull { it.providerId == editor.providerId }
        val losses = plan.removedExpressions.map { it.localizedSummary(sourceDescriptor) } +
            listOfNotNull(plan.removedSort?.let { sourceDescriptor.localizedSortDisplayName(it) })
        CollectionsAdminSheet(
            title = stringResource(MR.strings.tsuzuki_list_builder_switch_title),
            message = stringResource(
                MR.strings.tsuzuki_list_builder_switch_message,
                targetName,
                losses.joinToString(separator = ", "),
            ),
            confirmLabel = stringResource(MR.strings.tsuzuki_list_builder_switch_action),
            dismissLabel = stringResource(MR.strings.tsuzuki_list_builder_cancel),
            onConfirm = {
                pendingSwitch = null
                applyProviderSwitch(plan)
            },
            onDismiss = { pendingSwitch = null },
        )
    }

    if (blockedSwitch) {
        CollectionsAdminSheet(
            title = stringResource(MR.strings.tsuzuki_list_builder_switch_blocked_title),
            message = stringResource(MR.strings.tsuzuki_list_builder_switch_blocked_message),
            confirmLabel = stringResource(MR.strings.tsuzuki_list_builder_ok),
            onConfirm = { blockedSwitch = false },
            onDismiss = { blockedSwitch = false },
        )
    }

    val headerTitle = if (advanced) {
        stringResource(MR.strings.tsuzuki_list_builder_advanced_title)
    } else {
        stringResource(
            when (mode) {
                ListBuilderMode.CREATE -> MR.strings.tsuzuki_list_builder_create_title
                ListBuilderMode.EDIT -> MR.strings.tsuzuki_list_builder_edit_title
            },
        )
    }
    val footerLabel = when {
        advanced -> stringResource(MR.strings.tsuzuki_list_builder_apply_filters)
        mode == ListBuilderMode.CREATE -> stringResource(MR.strings.tsuzuki_list_builder_create_action)
        else -> stringResource(MR.strings.tsuzuki_list_builder_save_action)
    }

    CollectionsAdminEditorPage(
        title = headerTitle,
        onBack = {
            if (advanced) advanced = false else onClose()
        },
        footerLabel = footerLabel,
        footerEnabled = advanced || draft != null,
        onFooterClick = {
            if (advanced) {
                advanced = false
            } else {
                draft?.let(onConfirm)
            }
        },
    ) {
        if (advanced) {
            AdvancedListFilters(
                editor = editor,
                onEditorChange = { editor = it },
                descriptor = providerDescriptors.firstOrNull { it.providerId == editor.providerId },
                filterLookupStates = filterLookupStates,
                onFilterLookup = onFilterLookup,
                modifier = Modifier
                    .weight(1f),
            )
        } else {
            QuickListBuilder(
                editor = editor,
                onEditorChange = { editor = it },
                onAdvanced = { advanced = true },
                providerDescriptors = providerDescriptors,
                onProviderSelect = ::requestProviderSwitch,
                previewState = previewState,
                filterLookupStates = filterLookupStates,
                onFilterLookup = onFilterLookup,
                modifier = Modifier
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun QuickListBuilder(
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    onAdvanced: () -> Unit,
    providerDescriptors: List<CollectionProviderDescriptor>,
    onProviderSelect: (String) -> Unit,
    previewState: CollectionDraftPreviewState,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState>,
    onFilterLookup: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val descriptor = providerDescriptors.firstOrNull { it.providerId == editor.providerId }
    val quickFilters = descriptor?.filters?.filter { it.placement == FilterPlacement.QUICK }.orEmpty()
    val sortOptions = descriptor?.uiSortSelections().orEmpty()
    val hasAdvanced = !editor.filtersEditable ||
        descriptor?.filters?.any { it.placement == FilterPlacement.ADVANCED } == true

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "basic") {
            BuilderGroup(title = stringResource(MR.strings.tsuzuki_list_builder_basic)) {
                DefaultSkinTextField(
                    value = editor.title,
                    label = stringResource(MR.strings.tsuzuki_list_builder_name),
                    onValueChange = { onEditorChange(editor.copy(title = it)) },
                )
                Text(
                    text = stringResource(MR.strings.tsuzuki_list_builder_display),
                    style = MaterialTheme.typography.labelMedium,
                )
                ListBuilderSegmentControl(
                    options = listOf(
                        "default" to stringResource(MR.strings.tsuzuki_list_builder_display_default),
                        "grid" to stringResource(MR.strings.tsuzuki_list_builder_display_grid),
                        "list" to stringResource(MR.strings.tsuzuki_list_builder_display_list),
                    ),
                    selectedValue = editor.layoutType ?: "default",
                    onSelected = { option ->
                        onEditorChange(editor.copy(layoutType = option.takeUnless { it == "default" }))
                    },
                )
            }
        }

        item(key = "source") {
            BuilderGroup(title = stringResource(MR.strings.tsuzuki_list_builder_catalog_source)) {
                val sources = listBuilderProviderIds(providerDescriptors, editor.providerId)
                if (editor.filtersEditable) {
                    BuilderDropdown(
                        label = stringResource(MR.strings.tsuzuki_list_builder_catalog_source),
                        current = editor.providerId,
                        options = sources,
                        display = { providerId ->
                            providerDescriptors
                                .firstOrNull { it.providerId == providerId }
                                ?.displayName
                                ?: providerDisplayName(providerId)
                        },
                        onSelect = onProviderSelect,
                        enabled = sources.isNotEmpty(),
                    )
                } else {
                    BuilderReadOnlyValue(
                        label = stringResource(MR.strings.tsuzuki_list_builder_catalog_source),
                        value = descriptor?.displayName
                            ?: providerDisplayName(editor.providerId),
                    )
                }
                if (descriptor == null) {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_list_builder_unregistered_source),
                        style = MaterialTheme.typography.bodySmall,
                        color = defaultSkinColors().secondary,
                    )
                }
            }
        }

        if (!editor.filtersEditable) {
            item(key = "preserved_query_notice") {
                BuilderSurface {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_list_builder_preserved_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(MR.strings.tsuzuki_list_builder_preserved_quick_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = defaultSkinColors().secondary,
                    )
                }
            }
        }

        if (quickFilters.isNotEmpty() && editor.filtersEditable) {
            item(key = "quick_filters") {
                BuilderGroup(title = stringResource(MR.strings.tsuzuki_list_builder_available_filters)) {
                    quickFilters.forEach { capability ->
                        DescriptorFilterControl(
                            capability = capability,
                            editor = editor,
                            onEditorChange = onEditorChange,
                            filterLookupStates = filterLookupStates,
                            onFilterLookup = onFilterLookup,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (sortOptions.isNotEmpty()) {
            item(key = "sort") {
                BuilderGroup(title = stringResource(MR.strings.tsuzuki_list_builder_sort)) {
                    val selectedSort = editor.sort.takeIf { it in sortOptions } ?: sortOptions.first()
                    BuilderDropdown(
                        label = stringResource(MR.strings.tsuzuki_list_builder_sort),
                        current = selectedSort,
                        options = sortOptions,
                        display = { sort -> descriptor.localizedSortDisplayName(sort) },
                        onSelect = { onEditorChange(editor.copy(sort = it)) },
                    )
                }
            }
        }

        item(key = "active_filters") {
            ActiveFiltersCard(
                editor = editor,
                descriptor = descriptor,
                onEditorChange = onEditorChange,
            )
        }

        if (hasAdvanced) {
            item(key = "advanced") {
                CollectionsAdminSurface(onClick = onAdvanced) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(MR.strings.tsuzuki_list_builder_advanced_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                if (editor.filtersEditable) {
                                    MR.strings.tsuzuki_list_builder_advanced_description
                                } else {
                                    MR.strings.tsuzuki_list_builder_advanced_preserved_description
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = defaultSkinColors().secondary,
                        )
                    }
                }
            }
        }

        item(key = "preview") {
            BuilderGroup(title = stringResource(MR.strings.tsuzuki_list_builder_preview)) {
                val activeCount = editor.currentQuery()?.predicateCount() ?: 0
                if (activeCount == 0) {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_list_builder_no_filters),
                        style = MaterialTheme.typography.titleSmall,
                    )
                } else {
                    Text(
                        text = pluralStringResource(
                            MR.plurals.tsuzuki_list_builder_active_filter_count,
                            activeCount,
                            activeCount,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                when (previewState) {
                    CollectionDraftPreviewState.Idle -> {
                        Text(
                            text = stringResource(MR.strings.tsuzuki_list_builder_preview_idle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = defaultSkinColors().secondary,
                        )
                    }
                    CollectionDraftPreviewState.Loading -> {
                        Text(
                            text = stringResource(MR.strings.tsuzuki_list_builder_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = defaultSkinColors().secondary,
                        )
                    }
                    is CollectionDraftPreviewState.Content -> {
                        if (previewState.items.isEmpty()) {
                            Text(
                                text = stringResource(MR.strings.tsuzuki_list_builder_preview_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = defaultSkinColors().secondary,
                            )
                        } else {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(previewState.items.take(PREVIEW_TITLE_LIMIT)) { item ->
                                    Column(
                                        modifier = Modifier.width(88.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        MangaCover.Book(
                                            data = item.coverUrl,
                                            contentDescription = item.title,
                                            modifier = Modifier.fillMaxWidth(),
                                            contentScale = ContentScale.Fit,
                                        )
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        if (previewState.scanBudgetReason != null) {
                            Text(
                                text = stringResource(MR.strings.tsuzuki_list_builder_preview_scan_limited),
                                style = MaterialTheme.typography.bodySmall,
                                color = defaultSkinColors().secondary,
                            )
                        }
                    }
                    is CollectionDraftPreviewState.Error -> {
                        Text(
                            text = previewState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedListFilters(
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    descriptor: CollectionProviderDescriptor?,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState>,
    onFilterLookup: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!editor.filtersEditable) {
            item(key = "locked") {
                BuilderSurface {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_list_builder_preserved_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(MR.strings.tsuzuki_list_builder_preserved_advanced_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = defaultSkinColors().secondary,
                    )
                    editor.preservedQuery?.let {
                        Text(
                            text = it.toCanonicalString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = defaultSkinColors().secondary,
                        )
                    }
                }
            }
        } else {
            val advancedCapabilities = descriptor?.filters
                ?.filter { it.placement == FilterPlacement.ADVANCED }
                .orEmpty()

            if (advancedCapabilities.isNotEmpty()) {
                item(key = "provider_options") {
                    AdvancedSection(stringResource(MR.strings.tsuzuki_list_builder_advanced_filters)) {
                        advancedCapabilities.forEachIndexed { index, capability ->
                            DescriptorFilterControl(
                                capability = capability,
                                editor = editor,
                                onEditorChange = onEditorChange,
                                filterLookupStates = filterLookupStates,
                                onFilterLookup = onFilterLookup,
                                modifier = if (index == 0) Modifier else Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CollectionsAdminSectionHeader(title)
        BuilderSurface(content = content)
    }
}

@Composable
private fun ActiveFiltersCard(
    editor: ListEditorState,
    descriptor: CollectionProviderDescriptor?,
    onEditorChange: (ListEditorState) -> Unit,
) {
    val filters = buildList {
        editor.status?.let {
            add(
                ActiveFilter(
                    "${stringResource(MR.strings.tsuzuki_list_builder_field_status)}: ${it.prettyEnumName()}",
                ) {
                    onEditorChange(editor.copy(status = null))
                },
            )
        }
        editor.format?.let {
            add(
                ActiveFilter(
                    "${stringResource(MR.strings.tsuzuki_list_builder_field_work_type)}: ${it.prettyEnumName()}",
                ) {
                    onEditorChange(editor.copy(format = null))
                },
            )
        }
        editor.includeGenre.takeIf(String::isNotBlank)?.let {
            add(
                ActiveFilter("${stringResource(MR.strings.tsuzuki_list_builder_field_genre)}: $it") {
                    onEditorChange(editor.copy(includeGenre = ""))
                },
            )
        }
        editor.excludeGenre.takeIf(String::isNotBlank)?.let {
            add(
                ActiveFilter(
                    stringResource(
                        MR.strings.tsuzuki_list_builder_excluded_value,
                        stringResource(MR.strings.tsuzuki_list_builder_field_genre),
                        it,
                    ),
                ) { onEditorChange(editor.copy(excludeGenre = "")) },
            )
        }
        editor.includeTag.takeIf(String::isNotBlank)?.let {
            add(
                ActiveFilter("${stringResource(MR.strings.tsuzuki_list_builder_field_tag)}: $it") {
                    onEditorChange(editor.copy(includeTag = ""))
                },
            )
        }
        editor.excludeTag.takeIf(String::isNotBlank)?.let {
            add(
                ActiveFilter(
                    stringResource(
                        MR.strings.tsuzuki_list_builder_excluded_value,
                        stringResource(MR.strings.tsuzuki_list_builder_field_tag),
                        it,
                    ),
                ) { onEditorChange(editor.copy(excludeTag = "")) },
            )
        }
        editor.minScore.takeIf(String::isNotBlank)?.let {
            add(
                ActiveFilter("${stringResource(MR.strings.tsuzuki_list_builder_field_rating)} ≥ $it") {
                    onEditorChange(editor.copy(minScore = ""))
                },
            )
        }
        editor.minChapters.takeIf(String::isNotBlank)?.let {
            add(
                ActiveFilter("${stringResource(MR.strings.tsuzuki_list_builder_field_chapters)} ≥ $it") {
                    onEditorChange(editor.copy(minChapters = ""))
                },
            )
        }
        editor.minVolumes.takeIf(String::isNotBlank)?.let {
            add(
                ActiveFilter("${stringResource(MR.strings.tsuzuki_list_builder_field_volumes)} ≥ $it") {
                    onEditorChange(editor.copy(minVolumes = ""))
                },
            )
        }
        editor.extraTerms.forEach { expression ->
            add(
                ActiveFilter(expression.localizedSummary(descriptor)) {
                    onEditorChange(editor.copy(extraTerms = editor.extraTerms - expression))
                },
            )
        }
        if (!editor.filtersEditable) {
            editor.preservedQuery?.let { expression ->
                add(ActiveFilter(expression.localizedSummary(descriptor)) {})
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CollectionsAdminSectionHeader(
            title = stringResource(MR.strings.tsuzuki_list_builder_active_filters),
            actionLabel = if (filters.isNotEmpty() && editor.filtersEditable) {
                stringResource(MR.strings.tsuzuki_list_builder_clear_all)
            } else {
                null
            },
            onAction = if (filters.isNotEmpty() && editor.filtersEditable) {
                {
                    onEditorChange(
                        editor.copy(
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
                        ),
                    )
                }
            } else {
                null
            },
        )
        BuilderSurface {
            if (filters.isEmpty()) {
                Text(
                    text = stringResource(
                        if (editor.filtersEditable) {
                            MR.strings.tsuzuki_list_builder_no_active_filters
                        } else {
                            MR.strings.tsuzuki_list_builder_preserved_title
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = defaultSkinColors().secondary,
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filters, key = ActiveFilter::label) { filter ->
                        if (editor.filtersEditable) {
                            DefaultSkinChoiceChip(
                                text = filter.label,
                                selected = true,
                                onClick = filter.onClear,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .semantics { selected = true },
                            )
                        } else {
                            Text(
                                text = filter.label,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = defaultSkinColors().secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DescriptorFilterControl(
    capability: CollectionFilterCapability,
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    filterLookupStates: Map<CollectionFilterLookupKey, CollectionFilterLookupState>,
    onFilterLookup: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positivePredicates = editor.simplePredicates(capability.field, negated = false)
    val negativePredicates = editor.simplePredicates(capability.field, negated = true)
    val positivePredicate = positivePredicates.firstOrNull()
    val negativePredicate = negativePredicates.firstOrNull()

    when (val source = capability.valueSource) {
        is FilterValueSource.Static -> {
            val options = source.values
                .mapNotNull { option ->
                    val value = option.value as? QueryValue.StringValue ?: return@mapNotNull null
                    option.label to value.value
                }
            if (options.isNotEmpty()) {
                if (capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE) {
                    Column(modifier = modifier) {
                        DescriptorStaticDropdown(
                            label = stringResource(
                                MR.strings.tsuzuki_list_builder_include_field,
                                capability.localizedLabel(),
                            ),
                            options = options,
                            predicate = positivePredicate,
                            editor = editor,
                            capability = capability,
                            negated = false,
                            onEditorChange = onEditorChange,
                        )
                        DescriptorStaticDropdown(
                            label = stringResource(
                                MR.strings.tsuzuki_list_builder_exclude_field,
                                capability.localizedLabel(),
                            ),
                            options = options,
                            predicate = negativePredicate,
                            editor = editor,
                            capability = capability,
                            negated = true,
                            onEditorChange = onEditorChange,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else {
                    DescriptorStaticDropdown(
                        label = capability.localizedLabel(),
                        options = options,
                        predicate = positivePredicate,
                        editor = editor,
                        capability = capability,
                        negated = false,
                        onEditorChange = onEditorChange,
                        modifier = modifier,
                    )
                }
            }
        }

        FilterValueSource.BooleanToggle -> {
            val selected = (positivePredicate?.value as? QueryValue.BooleanValue)?.value == true
            if (editor.filtersEditable) {
                DefaultSkinChoiceChip(
                    text = capability.localizedLabel(),
                    selected = selected,
                    onClick = {
                        onEditorChange(
                            editor.replaceFieldTerm(
                                field = capability.field,
                                negated = false,
                                predicate = if (selected) {
                                    null
                                } else {
                                    QueryExpression.Predicate(
                                        field = capability.field,
                                        operator = capability.preferredScalarOperator(),
                                        value = QueryValue.of(true),
                                    )
                                },
                            ),
                        )
                    },
                    modifier = modifier
                        .heightIn(min = 48.dp)
                        .semantics {
                            role = Role.Button
                            this.selected = selected
                        },
                )
            } else {
                BuilderReadOnlyValue(
                    label = capability.localizedLabel(),
                    value = positivePredicate?.value?.localizedLabel()
                        ?: stringResource(MR.strings.tsuzuki_list_builder_any),
                )
            }
        }

        FilterValueSource.IntegerRange,
        FilterValueSource.DecimalRange,
        -> {
            val range = positivePredicate.toNumericBounds()
            Column(modifier = modifier) {
                Text(capability.localizedLabel(), style = MaterialTheme.typography.labelMedium)
                Text(
                    stringResource(MR.strings.tsuzuki_list_builder_range_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = defaultSkinColors().secondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BuilderNumericField(
                        label = stringResource(MR.strings.tsuzuki_list_builder_minimum),
                        value = range.first,
                        enabled = editor.filtersEditable,
                        allowDecimal = source == FilterValueSource.DecimalRange,
                        onValueChange = { value ->
                            onEditorChange(
                                editor.replaceFieldTerm(
                                    field = capability.field,
                                    negated = false,
                                    predicate = capability.rangeExpression(
                                        minimum = value,
                                        maximum = range.second,
                                        decimal = source == FilterValueSource.DecimalRange,
                                    ),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                    BuilderNumericField(
                        label = stringResource(MR.strings.tsuzuki_list_builder_maximum),
                        value = range.second,
                        enabled = editor.filtersEditable,
                        allowDecimal = source == FilterValueSource.DecimalRange,
                        onValueChange = { value ->
                            onEditorChange(
                                editor.replaceFieldTerm(
                                    field = capability.field,
                                    negated = false,
                                    predicate = capability.rangeExpression(
                                        minimum = range.first,
                                        maximum = value,
                                        decimal = source == FilterValueSource.DecimalRange,
                                    ),
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        FilterValueSource.DateRange -> {
            val range = positivePredicate.toStringBounds()
            Column(modifier = modifier) {
                Text(capability.localizedLabel(), style = MaterialTheme.typography.labelMedium)
                Text(
                    stringResource(MR.strings.tsuzuki_list_builder_date_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = defaultSkinColors().secondary,
                )
                if (editor.filtersEditable) {
                    DefaultSkinTextField(
                        value = range.first,
                        onValueChange = { value ->
                            onEditorChange(
                                editor.replaceFieldTerm(
                                    field = capability.field,
                                    negated = false,
                                    predicate = capability.stringRangeExpression(value, range.second),
                                ),
                            )
                        },
                        label = stringResource(MR.strings.tsuzuki_list_builder_from_date),
                        singleLine = true,
                    )
                    DefaultSkinTextField(
                        modifier = Modifier.padding(top = 8.dp),
                        value = range.second,
                        onValueChange = { value ->
                            onEditorChange(
                                editor.replaceFieldTerm(
                                    field = capability.field,
                                    negated = false,
                                    predicate = capability.stringRangeExpression(range.first, value),
                                ),
                            )
                        },
                        label = stringResource(MR.strings.tsuzuki_list_builder_to_date),
                        singleLine = true,
                    )
                } else {
                    BuilderReadOnlyValue(
                        label = stringResource(MR.strings.tsuzuki_list_builder_from_date),
                        value = range.first,
                    )
                    BuilderReadOnlyValue(
                        label = stringResource(MR.strings.tsuzuki_list_builder_to_date),
                        value = range.second,
                    )
                }
            }
        }

        FilterValueSource.FreeText -> {
            if (capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE) {
                Column(modifier = modifier) {
                    DescriptorTextField(
                        label = stringResource(
                            MR.strings.tsuzuki_list_builder_include_field,
                            capability.localizedLabel(),
                        ),
                        value = (positivePredicate?.value as? QueryValue.StringValue)?.value.orEmpty(),
                        editor = editor,
                        capability = capability,
                        negated = false,
                        onEditorChange = onEditorChange,
                    )
                    DescriptorTextField(
                        label = stringResource(
                            MR.strings.tsuzuki_list_builder_exclude_field,
                            capability.localizedLabel(),
                        ),
                        value = (negativePredicate?.value as? QueryValue.StringValue)?.value.orEmpty(),
                        editor = editor,
                        capability = capability,
                        negated = true,
                        onEditorChange = onEditorChange,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                DescriptorTextField(
                    label = capability.localizedLabel(),
                    value = (positivePredicate?.value as? QueryValue.StringValue)?.value.orEmpty(),
                    editor = editor,
                    capability = capability,
                    negated = false,
                    onEditorChange = onEditorChange,
                    modifier = modifier,
                )
            }
        }

        is FilterValueSource.RemoteLookup -> {
            RemoteLookupFilterControl(
                providerId = editor.providerId,
                capability = capability,
                source = source,
                positivePredicates = positivePredicates,
                negativePredicates = negativePredicates,
                editor = editor,
                onEditorChange = onEditorChange,
                lookupState = filterLookupStates[
                    CollectionFilterLookupKey(editor.providerId, source.lookupId),
                ],
                onFilterLookup = onFilterLookup,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DescriptorStaticDropdown(
    label: String,
    options: List<Pair<String, String>>,
    predicate: QueryExpression.Predicate?,
    editor: ListEditorState,
    capability: CollectionFilterCapability,
    negated: Boolean,
    onEditorChange: (ListEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = (predicate?.value as? QueryValue.StringValue)?.value
    Column(modifier = modifier) {
        BuilderDropdown(
            label = label,
            current = current,
            options = listOf<String?>(null) + options.map { it.second },
            optionText = { value -> options.firstOrNull { it.second == value }?.first.orEmpty() },
        display = { value ->
            if (value == null) {
                stringResource(MR.strings.tsuzuki_list_builder_any)
            } else {
                options.firstOrNull { it.second == value }?.first ?: value
            }
        },
        onSelect = { selected ->
            val predicateExpression = selected?.let { value ->
                QueryExpression.Predicate(
                    field = capability.field,
                    operator = capability.preferredScalarOperator(),
                    value = QueryValue.of(value),
                )
            }
            onEditorChange(
                editor.replaceFieldTerm(
                    field = capability.field,
                    negated = negated,
                    predicate = predicateExpression,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = editor.filtersEditable,
    )
        if (current == null && !negated && options.isNotEmpty()) {
            Text(
                text = stringResource(
                    MR.strings.tsuzuki_list_builder_option_preview,
                    optionPreviewLabels(options, 3).joinToString(" · "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = defaultSkinColors().secondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DescriptorTextField(
    label: String,
    value: String,
    editor: ListEditorState,
    capability: CollectionFilterCapability,
    negated: Boolean,
    onEditorChange: (ListEditorState) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (editor.filtersEditable) {
        DefaultSkinTextField(
            modifier = modifier.fillMaxWidth(),
            value = value,
            onValueChange = { candidate ->
                val predicate = candidate.trim().takeIf(String::isNotEmpty)?.let {
                    QueryExpression.Predicate(
                        field = capability.field,
                        operator = capability.preferredScalarOperator(),
                        value = QueryValue.of(it),
                    )
                }
                onEditorChange(
                    editor.replaceFieldTerm(
                        field = capability.field,
                        negated = negated,
                        predicate = predicate,
                    ),
                )
            },
            label = label,
            singleLine = true,
        )
        Text(
            stringResource(MR.strings.tsuzuki_list_builder_text_hint),
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = defaultSkinColors().secondary,
        )
    } else {
        BuilderReadOnlyValue(label, value)
    }
}

@Composable
private fun RemoteLookupFilterControl(
    providerId: String,
    capability: CollectionFilterCapability,
    source: FilterValueSource.RemoteLookup,
    positivePredicates: List<QueryExpression.Predicate>,
    negativePredicates: List<QueryExpression.Predicate>,
    editor: ListEditorState,
    onEditorChange: (ListEditorState) -> Unit,
    lookupState: CollectionFilterLookupState?,
    onFilterLookup: (String, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var transaction by remember(providerId, source.lookupId) {
        mutableStateOf<RemoteLookupSheetState?>(null)
    }
    val includeValues = positivePredicates.map { it.value }
    val excludeValues = negativePredicates.map { it.value }
    val label = capability.localizedLabel()
    val colors = defaultSkinColors()

    LaunchedEffect(providerId, source.lookupId, transaction?.query, transaction?.isOpen) {
        transaction?.takeIf(RemoteLookupSheetState::isOpen)?.let { open ->
            if (open.query.isNotBlank()) kotlinx.coroutines.delay(250L)
            onFilterLookup(providerId, source.lookupId, open.query)
        }
    }
    BackHandler(enabled = transaction?.isOpen == true) {
        transaction = transaction?.cancel()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(DefaultSkinShape.Control)
                .background(colors.surfaceMuted)
                .border(1.dp, colors.outline, DefaultSkinShape.Control)
                .clickable(
                    enabled = editor.filtersEditable,
                    role = Role.Button,
                    onClick = { transaction = RemoteLookupSheetState.open(editor, capability) },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val selectedCount = includeValues.size + excludeValues.size
            Text(
                text = if (selectedCount == 0) {
                    stringResource(MR.strings.tsuzuki_list_builder_lookup_empty_selection)
                } else {
                    pluralStringResource(
                        MR.plurals.tsuzuki_list_builder_selected_count,
                        selectedCount,
                        selectedCount,
                    )
                },
                modifier = Modifier.weight(1f),
                color = if (editor.filtersEditable) colors.foreground else colors.secondary,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(MR.strings.tsuzuki_list_builder_change_action),
                color = colors.accent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        if (editor.filtersEditable) {
            Text(
                stringResource(MR.strings.tsuzuki_list_builder_lookup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondary,
            )
        }
    }

    transaction?.takeIf(RemoteLookupSheetState::isOpen)?.let { open ->
        RemoteLookupFilterSheet(
            title = label,
            capability = capability,
            state = open,
            lookupState = lookupState,
            onStateChange = { transaction = it },
            onDismiss = { transaction = null },
            onApply = {
                val current = transaction?.takeIf(RemoteLookupSheetState::isOpen)
                if (current != null) {
                    transaction = null
                    current.apply(editor, capability, onEditorChange)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteLookupFilterSheet(
    title: String,
    capability: CollectionFilterCapability,
    state: RemoteLookupSheetState,
    lookupState: CollectionFilterLookupState?,
    onStateChange: (RemoteLookupSheetState) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val colors = defaultSkinColors()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canExclude = capability.multiValueMode == MultiValueMode.INCLUDE_EXCLUDE

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.surface,
        contentColor = colors.foreground,
        scrimColor = colors.foreground.copy(alpha = 0.32f),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val contentMaxHeight = minOf(maxHeight * 0.9f, 640.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = contentMaxHeight)
                    .imePadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = colors.outline,
                    shape = RoundedCornerShape(2.dp),
                ) {
                    Spacer(Modifier.size(width = 34.dp, height = 4.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    DefaultSkinTextAction(
                        text = stringResource(MR.strings.tsuzuki_list_builder_clear),
                        onClick = { onStateChange(state.clearDraft()) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
                DefaultSkinTextField(
                    value = state.query,
                    onValueChange = { onStateChange(state.search(it)) },
                    label = stringResource(
                        MR.strings.tsuzuki_list_builder_lookup_search,
                        capability.localizedLabel(),
                    ),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .heightIn(max = 360.dp),
                ) {
                    when (lookupState) {
                        null,
                        CollectionFilterLookupState.Idle,
                        -> Text(
                            text = stringResource(MR.strings.tsuzuki_list_builder_lookup_search_prompt),
                            color = colors.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is CollectionFilterLookupState.Loading -> Text(
                            text = stringResource(MR.strings.tsuzuki_list_builder_lookup_loading),
                            color = colors.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is CollectionFilterLookupState.Error -> Text(
                            text = lookupState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is CollectionFilterLookupState.Ready -> {
                            if (lookupState.options.isEmpty()) {
                                Text(
                                    text = stringResource(MR.strings.tsuzuki_list_builder_lookup_no_results),
                                    color = colors.secondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(lookupState.options, key = { option -> option.id }) { option ->
                                        val included = option.value in state.draft.includeValues
                                        val excluded = option.value in state.draft.excludeValues
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(
                                                text = option.label,
                                                modifier = Modifier.padding(vertical = 8.dp),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            ListBuilderSegmentControl(
                                                options = listOf(
                                                    "neutral" to stringResource(
                                                        MR.strings.tsuzuki_list_builder_neutral,
                                                    ),
                                                    "include" to stringResource(
                                                        MR.strings.tsuzuki_list_builder_include,
                                                    ),
                                                ) + if (canExclude) {
                                                    listOf(
                                                        "exclude" to stringResource(
                                                            MR.strings.tsuzuki_list_builder_exclude,
                                                        ),
                                                    )
                                                } else {
                                                    emptyList()
                                                },
                                                selectedValue = when {
                                                    included -> "include"
                                                    excluded -> "exclude"
                                                    else -> "neutral"
                                                },
                                                onSelected = { selected ->
                                                    val selection = when (selected) {
                                                        "include" -> RemoteLookupValueSelection.INCLUDE
                                                        "exclude" -> RemoteLookupValueSelection.EXCLUDE
                                                        else -> RemoteLookupValueSelection.NEUTRAL
                                                    }
                                                    onStateChange(
                                                        state.selectValue(option.value, selection, capability),
                                                    )
                                                },
                                            )
                                            HorizontalDivider(color = colors.outline)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DefaultSkinTextAction(
                        text = stringResource(MR.strings.tsuzuki_list_builder_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    )
                    DefaultSkinPrimaryButton(
                        text = stringResource(MR.strings.tsuzuki_list_builder_apply),
                        onClick = onApply,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

internal data class RemoteLookupSelection(
    val value: QueryValue,
    val operator: QueryOperator,
)

internal enum class RemoteLookupValueSelection {
    NEUTRAL,
    INCLUDE,
    EXCLUDE,
}

internal data class RemoteLookupDraft(
    val includes: List<RemoteLookupSelection> = emptyList(),
    val excludes: List<RemoteLookupSelection> = emptyList(),
) {
    val includeValues: List<QueryValue>
        get() = includes.map(RemoteLookupSelection::value)

    val excludeValues: List<QueryValue>
        get() = excludes.map(RemoteLookupSelection::value)

    fun neutral(value: QueryValue): RemoteLookupDraft = copy(
        includes = includes.filterNot { it.value == value },
        excludes = excludes.filterNot { it.value == value },
    )

    fun include(value: QueryValue, capability: CollectionFilterCapability): RemoteLookupDraft {
        val operator = capability.preferredScalarOperator()
        return if (capability.multiValueMode == MultiValueMode.ALL) {
            val selected = includes.any { it.value == value && it.operator == operator }
            copy(
                includes = if (selected) {
                    includes.filterNot { it.value == value && it.operator == operator }
                } else {
                    includes + RemoteLookupSelection(value, operator)
                },
            )
        } else if (includes.any { it.value == value }) {
            copy(includes = emptyList())
        } else {
            copy(includes = listOf(RemoteLookupSelection(value, operator)))
        }
    }

    fun exclude(value: QueryValue, capability: CollectionFilterCapability): RemoteLookupDraft {
        if (capability.multiValueMode != MultiValueMode.INCLUDE_EXCLUDE) return this
        return if (excludes.any { it.value == value }) {
            copy(excludes = emptyList())
        } else {
            copy(excludes = listOf(RemoteLookupSelection(value, capability.preferredScalarOperator())))
        }
    }

    fun selectValue(
        value: QueryValue,
        selection: RemoteLookupValueSelection,
        capability: CollectionFilterCapability,
    ): RemoteLookupDraft {
        val currentSelection = when {
            value in includeValues -> RemoteLookupValueSelection.INCLUDE
            value in excludeValues -> RemoteLookupValueSelection.EXCLUDE
            else -> RemoteLookupValueSelection.NEUTRAL
        }
        if (selection == currentSelection) return this
        if (selection == RemoteLookupValueSelection.EXCLUDE &&
            capability.multiValueMode != MultiValueMode.INCLUDE_EXCLUDE
        ) {
            return this
        }

        val cleared = neutral(value)
        val selected = when (selection) {
            RemoteLookupValueSelection.NEUTRAL -> cleared
            RemoteLookupValueSelection.INCLUDE -> cleared.include(value, capability)
            RemoteLookupValueSelection.EXCLUDE -> cleared.exclude(value, capability)
        }
        if (capability.multiValueMode != MultiValueMode.INCLUDE_EXCLUDE) return selected

        return selected.copy(
            includes = cleared.includes + selected.includes.filter { it.value == value },
            excludes = cleared.excludes + selected.excludes.filter { it.value == value },
        )
    }

    fun clear(): RemoteLookupDraft = RemoteLookupDraft()

    companion object {
        fun from(
            includes: List<QueryExpression.Predicate>,
            excludes: List<QueryExpression.Predicate>,
        ): RemoteLookupDraft = RemoteLookupDraft(
            includes = includes.map { RemoteLookupSelection(it.value, it.operator) },
            excludes = excludes.map { RemoteLookupSelection(it.value, it.operator) },
        )
    }
}

internal data class RemoteLookupSheetState(
    val draft: RemoteLookupDraft,
    val query: String = "",
    val isOpen: Boolean = true,
) {
    fun search(query: String): RemoteLookupSheetState = if (isOpen) copy(query = query) else this

    fun neutral(value: QueryValue): RemoteLookupSheetState = if (isOpen) {
        copy(draft = draft.neutral(value))
    } else {
        this
    }

    fun include(value: QueryValue, capability: CollectionFilterCapability): RemoteLookupSheetState = if (isOpen) {
        copy(draft = draft.include(value, capability))
    } else {
        this
    }

    fun exclude(value: QueryValue, capability: CollectionFilterCapability): RemoteLookupSheetState = if (isOpen) {
        copy(draft = draft.exclude(value, capability))
    } else {
        this
    }

    fun selectValue(
        value: QueryValue,
        selection: RemoteLookupValueSelection,
        capability: CollectionFilterCapability,
    ): RemoteLookupSheetState = if (isOpen) {
        copy(draft = draft.selectValue(value, selection, capability))
    } else {
        this
    }

    fun clearDraft(): RemoteLookupSheetState = if (isOpen) copy(draft = draft.clear()) else this

    fun cancel(): RemoteLookupSheetState = copy(isOpen = false)

    fun apply(
        editor: ListEditorState,
        capability: CollectionFilterCapability,
        onEditorChange: (ListEditorState) -> Unit,
    ): RemoteLookupSheetState {
        if (!isOpen) return this
        onEditorChange(editor.withLookupDraft(capability, draft))
        return copy(isOpen = false)
    }

    companion object {
        fun open(editor: ListEditorState, capability: CollectionFilterCapability): RemoteLookupSheetState =
            RemoteLookupSheetState(
                draft = RemoteLookupDraft.from(
                    includes = editor.simplePredicates(capability.field, negated = false),
                    excludes = editor.simplePredicates(capability.field, negated = true),
                ),
            )
    }
}

private fun ListEditorState.withLookupDraft(
    capability: CollectionFilterCapability,
    draft: RemoteLookupDraft,
): ListEditorState = replaceFieldTerms(
    field = capability.field,
    negated = false,
    predicates = draft.includes.map { selection ->
        QueryExpression.Predicate(capability.field, selection.operator, selection.value)
    },
).replaceFieldTerms(
    field = capability.field,
    negated = true,
    predicates = draft.excludes.map { selection ->
        QueryExpression.Predicate(capability.field, selection.operator, selection.value)
    },
)

internal fun CollectionProviderDescriptor.uiSortSelections(): List<CollectionSortSelection> =
    sorts.flatMap { capability ->
        when (capability.directionMode) {
            SortDirectionMode.ASC_DESC -> {
                val default = requireNotNull(capability.defaultDirection)
                val alternate = if (default == CollectionSortDirection.DESC) {
                    CollectionSortDirection.ASC
                } else {
                    CollectionSortDirection.DESC
                }
                listOf(
                    CollectionSortSelection(capability.key, default),
                    CollectionSortSelection(capability.key, alternate),
                )
            }
            SortDirectionMode.ASC_ONLY -> listOf(
                CollectionSortSelection(capability.key, CollectionSortDirection.ASC),
            )
            SortDirectionMode.DESC_ONLY -> listOf(
                CollectionSortSelection(capability.key, CollectionSortDirection.DESC),
            )
            SortDirectionMode.FIXED_NATIVE -> listOf(
                CollectionSortSelection(capability.key, direction = null),
            )
        }
    }.distinct()

internal fun listBuilderProviderIds(
    descriptors: List<CollectionProviderDescriptor>,
    currentProviderId: String,
): List<String> = (
    descriptors.map(CollectionProviderDescriptor::providerId) +
        currentProviderId.takeIf { it.isNotBlank() }.orEmpty()
    ).filter(String::isNotBlank).distinct()

internal fun CollectionProviderDescriptor.visibleFilterFields(
    placement: FilterPlacement,
): List<QueryField> = filters
    .filter { it.placement == placement }
    .map(CollectionFilterCapability::field)

private fun CollectionFilterCapability.preferredScalarOperator(): QueryOperator = when {
    QueryOperator.EQUALS in operators -> QueryOperator.EQUALS
    QueryOperator.CONTAINS in operators -> QueryOperator.CONTAINS
    QueryOperator.GREATER_OR_EQUAL in operators -> QueryOperator.GREATER_OR_EQUAL
    else -> operators.first()
}

private fun ListEditorState.simplePredicates(
    field: QueryField,
    negated: Boolean,
): List<QueryExpression.Predicate> {
    val terms = when (val query = currentQuery()) {
        null -> emptyList()
        is QueryExpression.All -> query.expressions
        else -> listOf(query)
    }
    return terms.mapNotNull { term ->
        when {
            !negated && term is QueryExpression.Predicate && term.field == field -> term
            negated && term is QueryExpression.Not -> {
                (term.expression as? QueryExpression.Predicate)?.takeIf { it.field == field }
            }
            else -> null
        }
    }
}

private fun ListEditorState.simplePredicate(
    field: QueryField,
    negated: Boolean,
): QueryExpression.Predicate? = simplePredicates(field, negated).firstOrNull()

private fun ListEditorState.replaceFieldTerm(
    field: QueryField,
    negated: Boolean,
    predicate: QueryExpression.Predicate?,
): ListEditorState = replaceFieldTerms(
    field = field,
    negated = negated,
    predicates = listOfNotNull(predicate),
)

private fun ListEditorState.toggleFieldPredicate(
    field: QueryField,
    negated: Boolean,
    predicate: QueryExpression.Predicate,
): ListEditorState {
    val existing = simplePredicates(field, negated)
    val selected = existing.any { candidate ->
        candidate.operator == predicate.operator && candidate.value == predicate.value
    }
    val next = if (selected) {
        existing.filterNot { candidate ->
            candidate.operator == predicate.operator && candidate.value == predicate.value
        }
    } else {
        existing + predicate
    }
    return replaceFieldTerms(field, negated, next)
}

private fun ListEditorState.replaceFieldTerms(
    field: QueryField,
    negated: Boolean,
    predicates: List<QueryExpression.Predicate>,
): ListEditorState {
    var updated = this
    updated = when (field) {
        QueryField.STATUS -> if (!negated) updated.copy(status = null) else updated
        QueryField.WORK_TYPE -> if (!negated) updated.copy(format = null) else updated
        QueryField.GENRE -> if (negated) {
            updated.copy(excludeGenre = "")
        } else {
            updated.copy(includeGenre = "")
        }
        QueryField.TAG -> if (negated) {
            updated.copy(excludeTag = "")
        } else {
            updated.copy(includeTag = "")
        }
        QueryField.SCORE,
        QueryField.RATING,
        -> if (!negated) updated.copy(minScore = "") else updated
        QueryField.CHAPTER_COUNT -> if (!negated) updated.copy(minChapters = "") else updated
        QueryField.VOLUME_COUNT -> if (!negated) updated.copy(minVolumes = "") else updated
        else -> updated
    }

    val remaining = updated.extraTerms.filterNot { term ->
        term.matchesFieldPolarity(field = field, negated = negated)
    }
    val replacements = predicates.map { predicate ->
        if (negated) QueryExpression.Not(predicate) else predicate
    }

    return updated.copy(extraTerms = remaining + replacements)
}

private fun QueryExpression.matchesFieldPolarity(
    field: QueryField,
    negated: Boolean,
): Boolean = when {
    !negated && this is QueryExpression.Predicate -> this.field == field
    negated && this is QueryExpression.Not ->
        (expression as? QueryExpression.Predicate)?.field == field
    else -> false
}

private fun QueryExpression.Predicate?.toNumericBounds(): Pair<String, String> {
    if (this == null) return "" to ""
    return when (operator) {
        QueryOperator.GREATER_THAN,
        QueryOperator.GREATER_OR_EQUAL,
        QueryOperator.EQUALS,
        -> value.numericText().orEmpty() to ""
        QueryOperator.LESS_THAN,
        QueryOperator.LESS_OR_EQUAL,
        -> "" to value.numericText().orEmpty()
        QueryOperator.BETWEEN -> {
            val range = value as? QueryValue.RangeValue ?: return "" to ""
            range.lower.numericText().orEmpty() to range.upper.numericText().orEmpty()
        }
        else -> "" to ""
    }
}

private fun QueryExpression.Predicate?.toStringBounds(): Pair<String, String> {
    if (this == null) return "" to ""
    return when (operator) {
        QueryOperator.GREATER_THAN,
        QueryOperator.GREATER_OR_EQUAL,
        QueryOperator.EQUALS,
        -> (value as? QueryValue.StringValue)?.value.orEmpty() to ""
        QueryOperator.LESS_THAN,
        QueryOperator.LESS_OR_EQUAL,
        -> "" to (value as? QueryValue.StringValue)?.value.orEmpty()
        QueryOperator.BETWEEN -> {
            val range = value as? QueryValue.RangeValue ?: return "" to ""
            (range.lower as? QueryValue.StringValue)?.value.orEmpty() to
                (range.upper as? QueryValue.StringValue)?.value.orEmpty()
        }
        else -> "" to ""
    }
}

private fun CollectionFilterCapability.rangeExpression(
    minimum: String,
    maximum: String,
    decimal: Boolean,
): QueryExpression.Predicate? {
    val min = minimum.takeIf(String::isNotBlank)?.let { if (decimal) it.toDoubleOrNull() else it.toLongOrNull() }
    val max = maximum.takeIf(String::isNotBlank)?.let { if (decimal) it.toDoubleOrNull() else it.toLongOrNull() }
    val minValue = when (min) {
        is Double -> QueryValue.of(min)
        is Long -> QueryValue.of(min)
        else -> null
    }
    val maxValue = when (max) {
        is Double -> QueryValue.of(max)
        is Long -> QueryValue.of(max)
        else -> null
    }
    return when {
        minValue != null && maxValue != null && QueryOperator.BETWEEN in operators ->
            QueryExpression.Predicate(field, QueryOperator.BETWEEN, QueryValue.range(minValue, maxValue))
        minValue != null && QueryOperator.GREATER_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.GREATER_OR_EQUAL, minValue)
        maxValue != null && QueryOperator.LESS_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.LESS_OR_EQUAL, maxValue)
        else -> null
    }
}

private fun CollectionFilterCapability.stringRangeExpression(
    minimum: String,
    maximum: String,
): QueryExpression.Predicate? {
    val min = minimum.trim().takeIf(String::isNotEmpty)?.let { QueryValue.of(it) }
    val max = maximum.trim().takeIf(String::isNotEmpty)?.let { QueryValue.of(it) }
    return when {
        min != null && max != null && QueryOperator.BETWEEN in operators ->
            QueryExpression.Predicate(field, QueryOperator.BETWEEN, QueryValue.range(min, max))
        min != null && QueryOperator.GREATER_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.GREATER_OR_EQUAL, min)
        max != null && QueryOperator.LESS_OR_EQUAL in operators ->
            QueryExpression.Predicate(field, QueryOperator.LESS_OR_EQUAL, max)
        else -> null
    }
}

private fun QueryValue.numericText(): String? = when (this) {
    is QueryValue.IntegerValue -> value.toString()
    is QueryValue.DoubleValue -> value.toString()
    else -> null
}


/** Local suggestions come only from options declared by the selected metadata provider. */
internal fun optionPreviewLabels(options: List<Pair<String, String>>, limit: Int): List<String> =
    options.take(limit.coerceAtLeast(0)).map { it.first }

internal fun matchingFilterOptions(
    options: List<Pair<String, String>>,
    query: String,
): List<Pair<String, String>> = options.filter { matchesOptionQuery(it.first, query) }

private fun matchesOptionQuery(label: String, query: String): Boolean =
    filterSearchKey(label).contains(filterSearchKey(query))

private fun filterSearchKey(text: String): String =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()

@Composable
private fun <T> BuilderDropdown(
    label: String,
    current: T,
    options: List<T>,
    display: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionText: ((T) -> String)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember(label) { mutableStateOf("") }
    val colors = defaultSkinColors()
    val shape = eu.kanade.presentation.tsuzuki.components.DefaultSkinShape.Control

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.secondary,
        )
        androidx.compose.foundation.layout.Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(colors.surfaceMuted)
                    .border(1.dp, colors.outline, shape)
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClick = {
                            query = ""
                            expanded = true
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = display(current),
                    modifier = Modifier.weight(1f),
                    color = if (enabled) colors.foreground else colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(MR.strings.tsuzuki_list_builder_change_action),
                    color = colors.accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (expanded) {
                Popup(
                    alignment = Alignment.TopEnd,
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = 176.dp, max = 320.dp)
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                            .shadow(8.dp, shape)
                            .background(colors.surface, shape)
                            .border(1.dp, colors.outline, shape)
                            .padding(vertical = 4.dp),
                    ) {
                        if (optionText != null && options.size > 6) {
                            DefaultSkinTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = stringResource(MR.strings.tsuzuki_list_builder_search_options),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                singleLine = true,
                            )
                        }
                        val visibleOptions = if (optionText != null && query.isNotBlank()) {
                            options.filter { option -> matchesOptionQuery(optionText(option), query) }
                        } else {
                            options
                        }
                        if (visibleOptions.isEmpty()) {
                            Text(
                                stringResource(MR.strings.tsuzuki_list_builder_lookup_no_results),
                                modifier = Modifier.padding(16.dp),
                                color = colors.secondary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        visibleOptions.forEach { option ->
                            Text(
                                text = display(option),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(
                                        enabled = enabled,
                                        role = Role.Button,
                                        onClick = {
                                            expanded = false
                                            onSelect(option)
                                        },
                                    )
                                    .semantics { selected = option == current }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                color = colors.foreground,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListBuilderSegmentControl(
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DefaultSkinShape.Control,
        color = colors.surfaceMuted,
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.outline),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup().padding(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEachIndexed { index, (value, label) ->
                val isSelected = value == selectedValue
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) colors.surface else Color.Transparent)
                        .selectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { if (!isSelected) onSelected(value) },
                        )
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = colors.foreground,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (index < options.lastIndex) {
                    Surface(
                        modifier = Modifier.width(1.dp).heightIn(min = 24.dp),
                        color = colors.outline,
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun BuilderGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CollectionsAdminSectionHeader(title)
        BuilderSurface(content)
    }
}

@Composable
private fun BuilderSurface(content: @Composable ColumnScope.() -> Unit) {
    CollectionsAdminSurface {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun BuilderReadOnlyValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = defaultSkinColors().secondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = defaultSkinColors().foreground)
    }
}

@Composable
private fun BuilderNumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    allowDecimal: Boolean = false,
) {
    if (enabled) {
        DefaultSkinTextField(
            modifier = modifier.fillMaxWidth(),
            value = value,
            onValueChange = { candidate ->
                val valid = if (allowDecimal) {
                    candidate.isEmpty() || candidate.toDoubleOrNull() != null
                } else {
                    candidate.isEmpty() || candidate.toLongOrNull() != null
                }
                if (valid) onValueChange(candidate)
            },
            label = label,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number,
            ),
        )
    } else {
        BuilderReadOnlyValue(label, value)
    }
}

private data class ActiveFilter(
    val label: String,
    val onClear: () -> Unit,
)

@Composable
private fun providerDisplayName(providerId: String): String = when (providerId.lowercase()) {
    "kitsu" -> stringResource(MR.strings.tsuzuki_list_builder_provider_kitsu)
    "mangadex" -> stringResource(MR.strings.tsuzuki_list_builder_provider_mangadex)
    "mangaupdates" -> stringResource(MR.strings.tsuzuki_list_builder_provider_mangaupdates)
    "myanimelist", "mal" -> stringResource(MR.strings.tsuzuki_list_builder_provider_mal)
    "bangumi" -> stringResource(MR.strings.tsuzuki_list_builder_provider_bangumi)
    "shikimori" -> stringResource(MR.strings.tsuzuki_list_builder_provider_shikimori)
    else -> if (providerId.isNotBlank()) {
        providerId
    } else {
        stringResource(MR.strings.tsuzuki_list_builder_other_catalog)
    }
}

@Composable
private fun CollectionProviderDescriptor?.localizedSortDisplayName(
    selection: CollectionSortSelection,
): String {
    val capability = this?.sorts?.firstOrNull { it.key == selection.key }
    val label = capability?.label ?: when (selection.key) {
        CollectionSortKey.Standard.POPULARITY -> stringResource(MR.strings.tsuzuki_list_builder_sort_popularity)
        CollectionSortKey.Standard.RATING -> stringResource(MR.strings.tsuzuki_list_builder_sort_rating)
        CollectionSortKey.Standard.UPDATED -> stringResource(MR.strings.tsuzuki_list_builder_sort_updated)
        CollectionSortKey.Standard.RELEVANCE -> stringResource(MR.strings.tsuzuki_list_builder_sort_relevance)
        is CollectionSortKey.Provider -> stringResource(MR.strings.tsuzuki_list_builder_provider_sort)
    }
    if (capability != null && capability.directionMode != SortDirectionMode.ASC_DESC) return label
    return when (selection.direction) {
        CollectionSortDirection.DESC -> stringResource(
            MR.strings.tsuzuki_list_builder_sort_high_to_low,
            label,
        )
        CollectionSortDirection.ASC -> stringResource(
            MR.strings.tsuzuki_list_builder_sort_low_to_high,
            label,
        )
        null -> label
    }
}

@Composable
private fun CollectionFilterCapability.localizedLabel(): String = when (val queryField = field) {
    is QueryField.Standard -> queryField.localizedLabel()
    is QueryField.Custom -> id.substringAfterLast('.')
        .replace('_', ' ')
        .replace('-', ' ')
        .replaceFirstChar { it.titlecase() }
}

@Composable
private fun QueryField.Standard.localizedLabel(): String = when (this) {
    QueryField.Standard.STATUS -> stringResource(MR.strings.tsuzuki_list_builder_field_status)
    QueryField.Standard.WORK_TYPE -> stringResource(MR.strings.tsuzuki_list_builder_field_work_type)
    QueryField.Standard.START_YEAR,
    QueryField.Standard.RELEASE_YEAR,
    -> stringResource(MR.strings.tsuzuki_list_builder_field_year)
    QueryField.Standard.START_DATE -> stringResource(MR.strings.tsuzuki_list_builder_field_start_date)
    QueryField.Standard.END_DATE -> stringResource(MR.strings.tsuzuki_list_builder_field_end_date)
    QueryField.Standard.CHAPTER_COUNT -> stringResource(MR.strings.tsuzuki_list_builder_field_chapters)
    QueryField.Standard.VOLUME_COUNT -> stringResource(MR.strings.tsuzuki_list_builder_field_volumes)
    QueryField.Standard.GENRE -> stringResource(MR.strings.tsuzuki_list_builder_field_genre)
    QueryField.Standard.TAG -> stringResource(MR.strings.tsuzuki_list_builder_field_tag)
    QueryField.Standard.CATEGORY -> stringResource(MR.strings.tsuzuki_list_builder_field_category)
    QueryField.Standard.DEMOGRAPHIC -> stringResource(MR.strings.tsuzuki_list_builder_field_demographic)
    QueryField.Standard.COUNTRY -> stringResource(MR.strings.tsuzuki_list_builder_field_country)
    QueryField.Standard.AUTHOR -> stringResource(MR.strings.tsuzuki_list_builder_field_author)
    QueryField.Standard.ARTIST -> stringResource(MR.strings.tsuzuki_list_builder_field_artist)
    QueryField.Standard.PUBLISHER -> stringResource(MR.strings.tsuzuki_list_builder_field_publisher)
    QueryField.Standard.MAGAZINE -> stringResource(MR.strings.tsuzuki_list_builder_field_magazine)
    QueryField.Standard.SCORE,
    QueryField.Standard.RATING,
    -> stringResource(MR.strings.tsuzuki_list_builder_field_rating)
    QueryField.Standard.POPULARITY -> stringResource(MR.strings.tsuzuki_list_builder_field_popularity)
    QueryField.Standard.FAVORITES -> stringResource(MR.strings.tsuzuki_list_builder_field_favorites)
    QueryField.Standard.RANK -> stringResource(MR.strings.tsuzuki_list_builder_field_rank)
    QueryField.Standard.IN_LIBRARY -> stringResource(MR.strings.tsuzuki_list_builder_field_in_library)
    QueryField.Standard.UNREAD_CHAPTERS -> stringResource(MR.strings.tsuzuki_list_builder_field_unread_chapters)
    QueryField.Standard.HAS_NEW_CHAPTERS -> stringResource(MR.strings.tsuzuki_list_builder_field_new_chapters)
    QueryField.Standard.READ_STATUS -> stringResource(MR.strings.tsuzuki_list_builder_field_read_status)
    QueryField.Standard.HAS_READING_SOURCE -> stringResource(MR.strings.tsuzuki_list_builder_field_reading_source)
}

@Composable
private fun QueryExpression.localizedSummary(descriptor: CollectionProviderDescriptor?): String = when (this) {
    is QueryExpression.Predicate -> {
        val fieldLabel = descriptor?.filters?.firstOrNull { it.field == field }?.localizedLabel()
            ?: localizedFieldLabel(field)
        val valueLabel = value.localizedLabel()
        when (operator) {
            QueryOperator.EQUALS -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_equals,
                fieldLabel,
                valueLabel,
            )
            QueryOperator.NOT_EQUALS -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_not_equals,
                fieldLabel,
                valueLabel,
            )
            QueryOperator.IN -> stringResource(MR.strings.tsuzuki_list_builder_summary_in, fieldLabel, valueLabel)
            QueryOperator.CONTAINS -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_contains,
                fieldLabel,
                valueLabel,
            )
            QueryOperator.GREATER_THAN -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_greater,
                fieldLabel,
                valueLabel,
            )
            QueryOperator.GREATER_OR_EQUAL -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_at_least,
                fieldLabel,
                valueLabel,
            )
            QueryOperator.LESS_THAN -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_less,
                fieldLabel,
                valueLabel,
            )
            QueryOperator.LESS_OR_EQUAL -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_at_most,
                fieldLabel,
                valueLabel,
            )
            QueryOperator.BETWEEN -> stringResource(
                MR.strings.tsuzuki_list_builder_summary_between,
                fieldLabel,
                valueLabel,
            )
        }
    }
    is QueryExpression.Not -> stringResource(
        MR.strings.tsuzuki_list_builder_summary_excluded,
        expression.localizedSummary(descriptor),
    )
    is QueryExpression.All ->
        expressions
            .map { it.localizedSummary(descriptor) }
            .joinToString(", ")
    is QueryExpression.Any ->
        expressions
            .map { it.localizedSummary(descriptor) }
            .joinToString(
                separator = stringResource(MR.strings.tsuzuki_list_builder_or_separator),
            )
}

@Composable
private fun localizedFieldLabel(field: QueryField): String = when (field) {
    is QueryField.Standard -> field.localizedLabel()
    is QueryField.Custom -> field.identifier
}

@Composable
private fun QueryValue.localizedLabel(): String = when (this) {
    is QueryValue.StringValue -> value
    is QueryValue.IntegerValue -> value.toString()
    is QueryValue.DoubleValue -> value.toString()
    is QueryValue.BooleanValue -> stringResource(
        if (value) MR.strings.tsuzuki_list_builder_yes else MR.strings.tsuzuki_list_builder_no,
    )
    is QueryValue.ListValue ->
        values
            .map { it.localizedLabel() }
            .joinToString(", ")
    is QueryValue.RangeValue -> "${lower.localizedLabel()} – ${upper.localizedLabel()}"
    is QueryValue.RelativeTemporal -> stringResource(MR.strings.tsuzuki_list_builder_relative_date)
}

private fun QueryExpression.predicateCount(): Int = when (this) {
    is QueryExpression.Predicate -> 1
    is QueryExpression.Not -> expression.predicateCount()
    is QueryExpression.All -> expressions.sumOf(QueryExpression::predicateCount)
    is QueryExpression.Any -> expressions.sumOf(QueryExpression::predicateCount)
}

private fun String.prettyEnumName(): String = lowercase()
    .replace('_', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private const val PREVIEW_TITLE_LIMIT = 4
