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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCoverFrame
import eu.kanade.presentation.manga.components.MangaCoverLoadEvent
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSpacing
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
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
import tachiyomi.domain.tsuzuki.integration.model.metadataStartYear
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
    onVisualHeightChanged: (Int) -> Unit = {},
    onArtworkLoadEvent: ((MangaCoverLoadEvent) -> Unit)? = null,
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
    val ratingsState = rememberLazyListState()
    val layoutDirection = LocalLayoutDirection.current
    val skinColors = defaultSkinColors()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val toolbarLeadHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() } + 56.dp
    val usefulViewportHeight = configuration.screenHeightDp.dp - with(density) {
        (WindowInsets.statusBars.getTop(this) + WindowInsets.navigationBars.getBottom(this)).toDp()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("canonical_details_hero_content")) {
        val artworkLead = canonicalDetailsArtworkLead(usefulViewportHeight, configuration.fontScale)
        Column(Modifier.fillMaxWidth().background(background).padding(bottom = 16.dp)) {
            Box(
                Modifier.fillMaxWidth().background(background)
                    .onSizeChanged { onVisualHeightChanged(it.height) }
                    .testTag("canonical_details_hero_visual")
                    .then(if (hasArtwork) Modifier.heightIn(min = artworkLead) else Modifier),
            ) {
                if (hasArtwork) {
                    Box(
                        Modifier
                            .matchParentSize()
                            .clipToBounds()
                            .testTag("canonical_details_hero_frame")
                            .clearAndSetSemantics {},
                    ) {
                        MangaCoverFrame(
                            data = state.coverUrl ?: state.sourceCover,
                            fallbackData = listOf(state.sourceCover, state.sourceCover?.url),
                            modifier = Modifier.fillMaxSize().testTag("canonical_details_hero_artwork"),
                            shape = RectangleShape,
                            contentScale = ContentScale.Crop,
                            onLoadEvent = { event ->
                                trace.recordArtworkLoad(event)
                                if (event is MangaCoverLoadEvent.Failed && !event.willFallback) artworkFailed = true
                                onArtworkLoadEvent?.invoke(event)
                            },
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        *canonicalDetailsHeroFadeStops(background).toTypedArray(),
                                    ),
                                )
                                .testTag("canonical_details_hero_fade"),
                        )
                    }
                }
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, top = toolbarLeadHeight, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        state.title.displayTitle,
                        Modifier.fillMaxWidth().semantics { heading() }.testTag("canonical_details_hero_title"),
                        color = skinColors.foreground,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    val genres = detailsHeroGenres(state.genres)
                    if (genres.isNotEmpty()) {
                        Text(
                            genres.joinToString(" · "),
                            Modifier.fillMaxWidth().testTag("canonical_details_hero_genres"),
                            color = skinColors.secondary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val year = metadataStartYear(state.startDate)
            val format = editorialFormatLabel(state.editorialFormat)
            if (year != null || format != null) {
                FlowRow(
                    Modifier.fillMaxWidth().testTag("canonical_details_editorial_metadata"),
                    horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    year?.let {
                        Text(it, color = skinColors.foreground, style = MaterialTheme.typography.bodyLarge)
                    }
                    format?.let {
                        Text(it, color = skinColors.foreground, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (state.tsuzukiRating != null || state.ratings.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp).drawWithContent {
                        drawContent()
                        val cueWidth = 12.dp.toPx()
                        val leftCue = if (layoutDirection == LayoutDirection.Ltr) {
                            ratingsState.canScrollBackward
                        } else {
                            ratingsState.canScrollForward
                        }
                        val rightCue = if (layoutDirection == LayoutDirection.Ltr) {
                            ratingsState.canScrollForward
                        } else {
                            ratingsState.canScrollBackward
                        }
                        if (leftCue) {
                            drawRect(
                                Brush.horizontalGradient(listOf(background, Color.Transparent), 0f, cueWidth),
                                size = Size(cueWidth, size.height),
                            )
                        }
                        if (rightCue) {
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, background),
                                    size.width - cueWidth,
                                    size.width,
                                ),
                                topLeft = Offset(size.width - cueWidth, 0f),
                                size = Size(cueWidth, size.height),
                            )
                        }
                    },
                    state = ratingsState,
                    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
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
                                    stringResource(MR.strings.tsuzuki_details_tsuzuki_rating),
                                    Modifier.size(20.dp),
                                )
                                Text(
                                    ratingScaleLabel("tsuzuki", rating.value, rating.maxValue),
                                    color = skinColors.foreground,
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
                                color = skinColors.foreground,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
            editorialStatusLabel(state.editorialStatus)?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    color = skinColors.secondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InlineSynopsis(state.title.id, state.description)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DefaultSkinSpacing.Row),
                ) {
                    if (state.hasReadableChapters) {
                        DefaultSkinPrimaryButton(
                            text = stringResource(
                                if (state.hasReadingProgress) {
                                    MR.strings.tsuzuki_details_continue_reading
                                } else {
                                    MR.strings.tsuzuki_details_start_reading
                                },
                            ),
                            onClick = onReadTitle,
                            enabled = !state.readingStartInProgress,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    IconButton(
                        onClick = if (state.libraryEntry == null) onAddToLibrary else onRemoveFromLibrary,
                        enabled = !state.libraryMutationInProgress,
                        modifier = Modifier.size(48.dp).background(
                            if (state.libraryEntry ==
                                null
                            ) {
                                skinColors.surface
                            } else {
                                skinColors.surfaceMuted
                            },
                            DefaultSkinShape.Control,
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
                            tint = skinColors.foreground,
                        )
                    }
                }
                if (state.isRefreshing) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = skinColors.accent,
                        trackColor = skinColors.surfaceMuted,
                    )
                }
                if (state.refreshError != null) {
                    HorizontalDivider(color = skinColors.outline)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(MR.strings.tsuzuki_details_refresh_error),
                            Modifier.weight(1f),
                            color = skinColors.secondary,
                        )
                        DefaultSkinTextAction(
                            text = stringResource(MR.strings.action_retry),
                            onClick = onRefresh,
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
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

internal fun canonicalDetailsArtworkLead(usefulViewportHeight: Dp, fontScale: Float): Dp =
    (usefulViewportHeight * (0.64f / fontScale.coerceAtLeast(1f))).coerceIn(320.dp, 560.dp)

internal fun canonicalDetailsHeroFadeStops(background: Color): List<Pair<Float, Color>> = listOf(
    0f to background.copy(alpha = 0f),
    0.44f to background.copy(alpha = 0f),
    0.58f to background.copy(alpha = 0.12f),
    0.72f to background.copy(alpha = 0.60f),
    0.88f to background.copy(alpha = 0.94f),
    1f to background,
)

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
    "WEBTOON" -> stringResource(MR.strings.tsuzuki_details_format_webtoon)
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

/** Keep real genres in their editorial order without empty labels or excessive wrapping. */
internal fun detailsHeroGenres(genres: List<String>): List<String> = genres.filter(String::isNotBlank).take(3)
