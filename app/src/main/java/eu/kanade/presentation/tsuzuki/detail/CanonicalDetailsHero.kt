package eu.kanade.presentation.tsuzuki.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MangaCoverLoadEvent
import eu.kanade.presentation.tsuzuki.integration.IntegrationBrandIcon
import eu.kanade.presentation.tsuzuki.integration.ratingScaleLabel
import eu.kanade.presentation.tsuzuki.recordArtworkLoad
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.tsuzuki.detail.CanonicalTitleScreenState
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.BookmarkAdd
import mihon.icons.materialsymbols.rounded.Check
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CanonicalDetailsHero(
    state: CanonicalTitleScreenState.Loaded,
    background: Color,
    onReadTitle: () -> Unit,
    onAddToLibrary: () -> Unit,
    onRemoveFromLibrary: () -> Unit,
    onRefresh: () -> Unit,
    onSynopsis: () -> Unit,
) {
    val recorder = LocalContext.current.appGraph.structuredDiagnosticRecorder
    val trace = remember(state.title.id, recorder.sessionId) {
        DiagnosticTrace.start(
            recorder = recorder,
            workflow = DiagnosticWorkflow.OPEN_TITLE,
            canonicalTitleId = state.title.id,
            subsystem = DiagnosticSubsystem.IMAGE,
        )
    }
    var artworkFailed by remember(state.coverUrl, state.sourceCover) { mutableStateOf(false) }
    val hasArtwork = !artworkFailed && (!state.coverUrl.isNullOrBlank() || state.sourceCover != null)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (hasArtwork) {
            Box(Modifier.matchParentSize().clearAndSetSemantics {}) {
                MangaCover.Square(
                    data = state.coverUrl ?: state.sourceCover,
                    fallbackData = listOf(state.sourceCover, state.sourceCover?.url),
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    contentScale = ContentScale.Crop,
                    onLoadEvent = { event ->
                        trace.recordArtworkLoad(event)
                        if (event is MangaCoverLoadEvent.Failed && !event.willFallback) artworkFailed = true
                    },
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to background.copy(alpha = 0.08f),
                            0.30f to background.copy(alpha = 0.15f),
                            0.50f to background.copy(alpha = 0.50f),
                            0.74f to background.copy(alpha = 0.86f),
                            0.98f to background,
                            1f to background,
                        ),
                    ),
                )
            }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 56.dp, bottom = 16.dp)) {
            Spacer(Modifier.height((maxWidth * 0.43f).coerceIn(120.dp, 224.dp)))
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.title.displayTitle,
                    Modifier.fillMaxWidth().semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                val year = state.startDate?.takeIf { it.length >= 4 && it.take(4).all(Char::isDigit) }?.take(4)
                val format = editorialFormatLabel(state.editorialFormat)
                if (year != null || format != null) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        year?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        format?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                editorialStatusLabel(state.editorialStatus)?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.genres.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        state.genres.forEach { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            if (state.tsuzukiRating != null || state.ratings.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                ) {
                    state.tsuzukiRating?.let { rating ->
                        item(key = "tsuzuki") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Image(
                                    painterResource(R.drawable.tsuzuki_rating),
                                    "Tsuzuki Rating",
                                    Modifier.size(20.dp),
                                )
                                Text(
                                    ratingScaleLabel("tsuzuki", rating.value, rating.maxValue),
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    items(state.ratings.size) { index ->
                        val rating = state.ratings[index]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            IntegrationBrandIcon(providerId = rating.providerId, size = 20.dp)
                            Text(
                                ratingScaleLabel(rating.providerId, rating.value, rating.maxValue),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.description?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onSynopsis) { Text(stringResource(MR.strings.tsuzuki_details_read_synopsis)) }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.hasReadableChapters) {
                        Button(
                            onClick = onReadTitle,
                            enabled = !state.readingStartInProgress,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.onSurface,
                                contentColor = MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Text(
                                stringResource(
                                    if (state.hasReadingProgress) {
                                        MR.strings.tsuzuki_details_continue_reading
                                    } else {
                                        MR.strings.tsuzuki_details_start_reading
                                    },
                                ),
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(
                        onClick = if (state.libraryEntry == null) onAddToLibrary else onRemoveFromLibrary,
                        enabled = !state.libraryMutationInProgress,
                        modifier = Modifier.size(48.dp).background(
                            if (state.libraryEntry ==
                                null
                            ) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            RoundedCornerShape(12.dp),
                        ).semantics { selected = state.libraryEntry != null },
                    ) {
                        Icon(
                            if (state.libraryEntry ==
                                null
                            ) {
                                MaterialSymbols.Rounded.BookmarkAdd
                            } else {
                                MaterialSymbols.Rounded.Check
                            },
                            stringResource(
                                if (state.libraryEntry ==
                                    null
                                ) {
                                    MR.strings.tsuzuki_details_add_library
                                } else {
                                    MR.strings.tsuzuki_details_remove_library
                                },
                            ),
                        )
                    }
                }
                if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                if (state.refreshError != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(MR.strings.tsuzuki_details_refresh_error),
                            Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRefresh) { Text(stringResource(MR.strings.action_retry)) }
                    }
                }
                if (state.libraryMutationError !=
                    null
                ) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_library_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.downloadError !=
                    null
                ) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_download_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (state.chapterActionError !=
                    null
                ) {
                    Text(
                        stringResource(MR.strings.tsuzuki_details_chapter_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DetailsEditorial(state: CanonicalTitleScreenState.Loaded) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        state.description?.takeIf(String::isNotBlank)?.let {
            EditorialSection(stringResource(MR.strings.tsuzuki_details_synopsis), it)
        }
        state.author?.takeIf(String::isNotBlank)?.let {
            EditorialSection(stringResource(MR.strings.tsuzuki_details_creation), it)
        }
        val publication = listOfNotNull(
            editorialFormatLabel(state.editorialFormat),
            editorialStatusLabel(state.editorialStatus),
            state.startDate?.takeIf(String::isNotBlank),
            state.endDate?.takeIf(String::isNotBlank),
            state.editorialVolumeCount?.let { stringResource(MR.strings.tsuzuki_details_volumes, it) },
        )
        if (publication.isNotEmpty()) {
            EditorialSection(
                stringResource(MR.strings.tsuzuki_details_publication),
                publication.joinToString("\n"),
            )
        }
        if (state.genres.isNotEmpty()) {
            EditorialSection(
                stringResource(MR.strings.tsuzuki_details_genres),
                state.genres.joinToString(" · "),
            )
        }
        if (state.tags.isNotEmpty()) {
            EditorialSection(
                stringResource(MR.strings.tsuzuki_details_tags),
                state.tags.joinToString(" · "),
            )
        }
    }
}

@Composable
private fun EditorialSection(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun editorialFormatLabel(value: String?): String? = when (value) {
    "MANGA" -> stringResource(MR.strings.tsuzuki_details_format_manga)
    "ONE_SHOT" -> stringResource(MR.strings.tsuzuki_details_format_one_shot)
    "MANHWA" -> stringResource(MR.strings.tsuzuki_details_format_manhwa)
    "MANHUA" -> stringResource(MR.strings.tsuzuki_details_format_manhua)
    "DOUJIN" -> stringResource(MR.strings.tsuzuki_details_format_doujin)
    "NOVEL" -> stringResource(MR.strings.tsuzuki_details_format_novel)
    else -> null
}

@Composable
private fun editorialStatusLabel(value: String?): String? = when (value) {
    "ONGOING" -> stringResource(MR.strings.tsuzuki_details_status_ongoing)
    "COMPLETED", "FINISHED" -> stringResource(MR.strings.tsuzuki_details_status_finished)
    "ON_HIATUS", "HIATUS" -> stringResource(MR.strings.tsuzuki_details_status_hiatus)
    "CANCELLED" -> stringResource(MR.strings.tsuzuki_details_status_cancelled)
    "UPCOMING", "NOT_YET_RELEASED" -> stringResource(MR.strings.tsuzuki_details_status_upcoming)
    else -> null
}
