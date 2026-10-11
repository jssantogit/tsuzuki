package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderRecovery
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentBindingLinkState
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentOptionPresentation
import eu.kanade.tachiyomi.ui.tsuzuki.content.ContentSelectorScreenState
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.source.model.ScoredSourceCandidate
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderSourceCard(
    state: ContentSelectorScreenState,
    activeOptionKey: String?,
    activeContentLabel: String?,
    failure: ReaderRecovery?,
    onSelect: (ContentOptionPresentation) -> Unit,
    onRetry: () -> Unit,
    onFindSource: (String) -> Unit,
    onCancelDiscovery: () -> Unit,
    selectionInProgress: Boolean = false,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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

        failure?.let {
            Text(
                text = stringResource(
                    when (it) {
                        ReaderRecovery.SOURCE_UNAVAILABLE -> MR.strings.tsuzuki_reader_source_failed
                        ReaderRecovery.P2P_REQUIRED -> MR.strings.tsuzuki_reader_p2p_required
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
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
                if (state.options.isEmpty()) {
                    UnavailableActions(
                        onRetry = onRetry,
                        onFindSource = { onFindSource(state.canonicalTitleId) },
                    )
                } else {
                    Text(
                        text = stringResource(MR.strings.tsuzuki_reader_available_source),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
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
                                        Text(
                                            stringResource(
                                                if (active) {
                                                    MR.strings.tsuzuki_reader_current_source
                                                } else {
                                                    MR.strings.tsuzuki_reader_available_source
                                                },
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
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
                    if (failure != null) {
                        Button(onClick = onRetry, enabled = !selectionInProgress) {
                            Text(stringResource(MR.strings.tsuzuki_reader_retry))
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
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
                if (canonicalTitleId != null) {
                    UnavailableActions(
                        onRetry = onRetry,
                        onFindSource = { onFindSource(canonicalTitleId) },
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderBindingCard(
    state: ContentBindingLinkState,
    onSelectProvider: (AddonId) -> Unit,
    onConfirm: (ScoredSourceCandidate) -> Unit,
    onSearchMore: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(MR.strings.tsuzuki_reader_source_unavailable),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) {
                Text(stringResource(MR.strings.tsuzuki_reader_retry))
            }
            OutlinedButton(onClick = onFindSource) {
                Text(stringResource(MR.strings.tsuzuki_reader_find_source))
            }
        }
    }
}
