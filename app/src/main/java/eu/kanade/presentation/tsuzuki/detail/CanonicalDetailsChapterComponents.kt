package eu.kanade.presentation.tsuzuki.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalChapterDetailItem
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import mihon.icons.materialsymbols.rounded.Download
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun ContextualChapterRail(
    state: CanonicalTitleScreenState.Loaded,
    onOpen: (String) -> Unit,
    onProviders: () -> Unit,
    onAll: () -> Unit,
    onChooseEdition: () -> Unit = {},
) {
    val chapters = contextualChapters(state.chapters, state.activeChapterId)
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(MR.strings.tsuzuki_details_chapters),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            if (chapters.isNotEmpty()) {
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_details_view_all),
                    onClick = onAll,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
        if (chapters.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(MR.strings.tsuzuki_details_no_chapters),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_edition_choose),
                    onClick = onChooseEdition,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                DefaultSkinTextAction(
                    text = stringResource(MR.strings.tsuzuki_details_providers),
                    onClick = onProviders,
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(chapters, key = { it.chapter.id }) { item ->
                    val progress = chapterProgressState(item)
                    val current =
                        state.activeChapterId == item.chapter.id && progress != CanonicalChapterProgressState.READ
                    val description = chapterStateDescription(item, current)
                    Column(
                        Modifier.width(128.dp).heightIn(min = 104.dp)
                            .background(
                                if (current) {
                                    MaterialTheme.colorScheme.surfaceContainerHigh
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                if (current) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .clickable(role = Role.Button, onClick = { onOpen(item.chapter.id) })
                            .semantics { stateDescription = description }.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            detailsChapterLabel(item),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (progress ==
                                CanonicalChapterProgressState.READ
                            ) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (current) {
                                Text(
                                    stringResource(MR.strings.tsuzuki_details_current),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            if (item.downloaded) Icon(MaterialSymbols.Rounded.Check, null, Modifier.size(16.dp))
                        }
                        if (progress == CanonicalChapterProgressState.PARTIAL) PartialChapterMarker()
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailsChapterRow(
    item: CanonicalChapterDetailItem,
    activeChapter: Boolean,
    downloading: Boolean,
    onOpen: (String) -> Unit,
    onDownload: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = chapterProgressState(item)
    val current = activeChapter && progress != CanonicalChapterProgressState.READ
    val description = chapterStateDescription(item, current)
    val downloadingLabel = stringResource(MR.strings.tsuzuki_details_downloading)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .background(if (current) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current) Box(Modifier.width(2.dp).height(48.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
            Column(
                Modifier.weight(1f).heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClick = { onOpen(item.chapter.id) })
                    .semantics { stateDescription = description }.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    detailsChapterLabel(item),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (progress ==
                        CanonicalChapterProgressState.READ
                    ) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
                item.chapter.title?.takeIf { it.isNotBlank() && it != item.chapter.displayNumber }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (current) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_current),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (item.confirmation == CanonicalChapterConfirmation.CONFLICTED) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_conflict),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (progress == CanonicalChapterProgressState.PARTIAL) PartialChapterMarker()
            }
            IconButton(
                onClick = { onDownload(item.chapter.id) },
                enabled = !item.downloaded && !downloading,
                modifier = Modifier.semantics {
                    if (downloading) {
                        contentDescription = downloadingLabel
                        stateDescription = downloadingLabel
                    }
                },
            ) {
                if (downloading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (item.downloaded) MaterialSymbols.Rounded.Check else MaterialSymbols.Rounded.Download,
                        stringResource(
                            if (item.downloaded) {
                                MR.strings.tsuzuki_details_downloaded
                            } else {
                                MR.strings.tsuzuki_details_download
                            },
                        ),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun detailsChapterLabel(item: CanonicalChapterDetailItem): String = when {
    item.chapter.displayNumber.isNotBlank() -> stringResource(
        MR.strings.tsuzuki_details_chapter_label,
        item.chapter.displayNumber,
    )
    !item.chapter.title.isNullOrBlank() -> item.chapter.title.orEmpty()
    else -> stringResource(MR.strings.tsuzuki_details_chapter_unnumbered)
}

@Composable
private fun PartialChapterMarker() {
    // Canonical progress provides no total page count: this marks a partial state, not a percentage.
    Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurfaceVariant))
}

@Composable
private fun chapterStateDescription(item: CanonicalChapterDetailItem, current: Boolean): String = buildList {
    add(
        stringResource(
            when (chapterProgressState(item)) {
                CanonicalChapterProgressState.READ -> MR.strings.tsuzuki_details_read
                CanonicalChapterProgressState.PARTIAL -> MR.strings.tsuzuki_details_partial
                CanonicalChapterProgressState.UNREAD -> MR.strings.tsuzuki_details_unread
            },
        ),
    )
    if (current) add(stringResource(MR.strings.tsuzuki_details_current))
    if (item.downloaded) add(stringResource(MR.strings.tsuzuki_details_downloaded))
}.joinToString(", ")
