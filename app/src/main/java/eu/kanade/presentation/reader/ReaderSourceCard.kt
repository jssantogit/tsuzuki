package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSecondaryButton
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import eu.kanade.tachiyomi.ui.reader.ReaderRecovery
import eu.kanade.tachiyomi.ui.reader.shouldOfferReaderEditionPicker
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentBindingLinkState
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentOptionPresentation
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.provider.reading.ProviderChapterReadingOption
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * An available chapter-scoped source makes the editorial-confirmation hint
 * redundant in this modal. Preserve the real source failure states.
 */
internal fun shouldShowReaderSourceRecovery(
    failure: ReaderRecovery?,
    hasProviderSourceOptions: Boolean,
): Boolean = failure != null &&
    (!hasProviderSourceOptions || failure != ReaderRecovery.EDITION_CONFIRMATION_REQUIRED)

@Composable
fun ColumnScope.ReaderSourceCard(
    state: ContentSelectorScreenState,
    activeOptionKey: String?,
    activeContentLabel: String?,
    failure: ReaderRecovery?,
    onSelect: (ContentOptionPresentation) -> Unit,
    providerOptions: List<ProviderChapterReadingOption> = emptyList(),
    activeProviderSourceKey: String? = null,
    onSelectProvider: (ProviderChapterReadingOption) -> Unit,
    onRetry: () -> Unit,
    onFindSource: (String) -> Unit,
    onChooseEdition: (String) -> Unit,
    onCancelDiscovery: () -> Unit,
    selectionInProgress: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        activeContentLabel?.takeIf(String::isNotBlank)?.let { label ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(MR.strings.tsuzuki_reader_current_source),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
            }
        }

        failure?.takeIf {
            shouldShowReaderSourceRecovery(it, providerOptions.isNotEmpty())
        }?.let {
            Text(
                text = stringResource(
                    when (it) {
                        ReaderRecovery.SOURCE_UNAVAILABLE -> MR.strings.tsuzuki_reader_source_failed
                        ReaderRecovery.P2P_REQUIRED -> MR.strings.tsuzuki_reader_p2p_required
                        ReaderRecovery.EDITION_CONFIRMATION_REQUIRED -> MR.strings.tsuzuki_edition_description
                    },
                ),
                color = if (shouldOfferReaderEditionPicker(it)) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (providerOptions.isNotEmpty()) {
            Text(
                text = "MangaAll",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(
                    providerOptions,
                    key = { it.readerSourceKey },
                ) { option ->
                    val sourceName = when (option.contentSourceId) {
                        "mangadex" -> "MangaDex"
                        "mangadot" -> "MangaDot"
                        "mangafire" -> "MangaFire"
                        else -> return@items
                    }
                    val active = activeProviderSourceKey == option.readerSourceKey
                    ListItem(
                        modifier = Modifier.selectable(
                            selected = active,
                            enabled = !selectionInProgress,
                            role = Role.RadioButton,
                            onClick = { onSelectProvider(option) },
                        ),
                        headlineContent = { Text(sourceName) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                option.facetId?.takeIf(String::isNotBlank)?.let { Text(it) }
                                option.scanlationGroup?.trim()?.takeIf(String::isNotBlank)?.let { group ->
                                    Text(
                                        text = stringResource(
                                            MR.strings.tsuzuki_reader_scanlation_group,
                                            group,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            RadioButton(
                                selected = active,
                                enabled = !selectionInProgress,
                                onClick = null,
                            )
                        },
                    )
                }
            }
        }

        when (state) {
            ContentSelectorScreenState.Loading -> LoadingState()
            is ContentSelectorScreenState.Discovering -> {
                LoadingState()
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCancelDiscovery,
                ) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
            is ContentSelectorScreenState.Ready -> {
                if (state.options.isEmpty() && providerOptions.isEmpty()) {
                    UnavailableActions(
                        onRetry = onRetry,
                        onFindSource = { onFindSource(state.canonicalTitleId) },
                        onChooseEdition = if (shouldOfferReaderEditionPicker(failure)) {
                            { onChooseEdition(state.canonicalTitleId) }
                        } else {
                            null
                        },
                        enabled = !selectionInProgress,
                    )
                } else {
                    if (state.options.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 280.dp),
                        ) {
                            items(state.options, key = { it.option.key }) { option ->
                                val active = activeOptionKey == option.option.key
                                ListItem(
                                    modifier = Modifier.selectable(
                                        selected = active,
                                        enabled = !selectionInProgress,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(option) },
                                    ),
                                    headlineContent = { Text(option.addonDisplayName) },
                                    supportingContent = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            option.language?.takeIf(String::isNotBlank)?.let { language ->
                                                Text(language)
                                            }
                                            option.scanlationGroup?.trim()?.takeIf(String::isNotBlank)?.let { group ->
                                                Text(
                                                    text = stringResource(
                                                        MR.strings.tsuzuki_reader_scanlation_group,
                                                        group,
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        RadioButton(
                                            selected = active,
                                            enabled = !selectionInProgress,
                                            onClick = null,
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (failure != null) {
                        Button(onClick = onRetry, enabled = !selectionInProgress) {
                            Text(stringResource(MR.strings.tsuzuki_reader_retry))
                        }
                    }
                    if (shouldOfferReaderEditionPicker(failure)) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !selectionInProgress,
                            onClick = { onChooseEdition(state.canonicalTitleId) },
                        ) {
                            Text(stringResource(MR.strings.tsuzuki_edition_choose))
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !selectionInProgress,
                        onClick = { onFindSource(state.canonicalTitleId) },
                    ) {
                        Text(stringResource(MR.strings.tsuzuki_reader_find_source))
                    }
                }
            }
            is ContentSelectorScreenState.Empty,
            is ContentSelectorScreenState.Error,
            -> {
                val canonicalTitleId = when (state) {
                    is ContentSelectorScreenState.Empty -> state.canonicalTitleId
                    is ContentSelectorScreenState.Error -> state.canonicalTitleId
                    else -> null
                }
                if (canonicalTitleId != null && providerOptions.isEmpty()) {
                    UnavailableActions(
                        onRetry = onRetry,
                        onFindSource = { onFindSource(canonicalTitleId) },
                        onChooseEdition = if (shouldOfferReaderEditionPicker(failure)) {
                            { onChooseEdition(canonicalTitleId) }
                        } else {
                            null
                        },
                        enabled = !selectionInProgress,
                    )
                }
            }
        }
    }
}

@Composable
fun ColumnScope.ReaderBindingCard(
    state: ContentBindingLinkState,
    onSelectProvider: (AddonId) -> Unit,
    onConfirm: (ScoredSourceCandidate) -> Unit,
    onSearchMore: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            ContentBindingLinkState.Idle,
            ContentBindingLinkState.Loading,
            -> LoadingState()

            is ContentBindingLinkState.Addons -> {
                if (state.enabled.isEmpty()) {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_reader_source_unavailable),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onBack,
                    ) {
                        Text(stringResource(MR.strings.tsuzuki_reader_retry))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 280.dp),
                    ) {
                        items(state.enabled, key = { it.id.value }) { addon ->
                            ListItem(
                                headlineContent = { Text(addon.displayName) },
                                modifier = Modifier.selectable(
                                    selected = false,
                                    role = Role.Button,
                                    onClick = { onSelectProvider(addon.id) },
                                ),
                            )
                        }
                    }
                }
            }

            is ContentBindingLinkState.SearchResults -> {
                if (state.isSearching || state.isConfirming) LoadingState()
                if (state.confirmationCandidates.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 280.dp),
                    ) {
                        items(
                            items = state.confirmationCandidates,
                            key = { "${it.candidate.sourceId}:${it.candidate.sourceUrl}" },
                        ) { scored ->
                            val candidate = scored.candidate
                            ListItem(
                                modifier = Modifier.selectable(
                                    selected = false,
                                    enabled = !state.isConfirming,
                                    role = Role.Button,
                                    onClick = { onConfirm(scored) },
                                ),
                                headlineContent = { Text(candidate.title) },
                                supportingContent = {
                                    candidate.language.takeIf(String::isNotBlank)?.let { language ->
                                        Text(language)
                                    }
                                },
                            )
                        }
                    }
                } else if (!state.isSearching && !state.isConfirming) {
                    Text(
                        text = stringResource(
                            if (state.boundCount > 0) {
                                MR.strings.tsuzuki_reader_sources_linked
                            } else {
                                MR.strings.tsuzuki_reader_source_unavailable
                            },
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.isSearching && !state.isConfirming && state.remainingSourceCount > 0) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSearchMore,
                    ) {
                        Text(stringResource(MR.strings.tsuzuki_reader_find_source))
                    }
                }
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBack,
                ) {
                    Text(stringResource(MR.strings.tsuzuki_reader_find_source))
                }
            }

            is ContentBindingLinkState.Error -> {
                Text(
                    text = stringResource(MR.strings.tsuzuki_reader_source_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBack,
                ) {
                    Text(stringResource(MR.strings.tsuzuki_reader_retry))
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(MR.strings.tsuzuki_reader_loading),
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun UnavailableActions(
    onRetry: () -> Unit,
    onFindSource: () -> Unit,
    onChooseEdition: (() -> Unit)?,
    enabled: Boolean,
) {
    val colors = defaultSkinColors()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onChooseEdition == null) {
            Text(
                text = stringResource(MR.strings.tsuzuki_reader_source_unavailable),
                color = colors.secondary,
            )
        }
        onChooseEdition?.let { choose ->
            DefaultSkinSecondaryButton(
                text = stringResource(MR.strings.tsuzuki_edition_choose),
                modifier = Modifier.fillMaxWidth(),
                onClick = choose,
                enabled = enabled,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultSkinPrimaryButton(
                text = stringResource(MR.strings.tsuzuki_reader_retry),
                onClick = onRetry,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            DefaultSkinSecondaryButton(
                text = stringResource(MR.strings.tsuzuki_reader_find_source),
                onClick = onFindSource,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
