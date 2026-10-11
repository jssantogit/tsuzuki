package eu.kanade.presentation.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.reader.ReaderSessionTool
import eu.kanade.tachiyomi.ui.reader.availableReaderTools
import eu.kanade.tachiyomi.ui.reader.readerPageProgress
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirrored.rounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Brush
import mihon.icons.materialsymbols.rounded.FlipToBack
import mihon.icons.materialsymbols.rounded.Image
import mihon.icons.materialsymbols.rounded.Newspaper
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.SkipPrevious
import mihon.icons.materialsymbols.rounded.StayCurrentPortrait
import mihon.icons.materialsymbols.rounded.Visibility
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

@Composable
fun ReaderDefaultSkinChrome(
    visible: Boolean,
    workTitle: String?,
    chapterTitle: String?,
    currentPage: Int,
    totalPages: Int,
    enabledPrevious: Boolean,
    enabledNext: Boolean,
    chaptersAvailable: Boolean,
    sourceAvailable: Boolean,
    fitAvailable: Boolean,
    cropEnabled: Boolean,
    keepScreenOn: Boolean,
    protectionActive: Boolean,
    onBack: () -> Unit,
    onTitle: () -> Unit,
    onTool: (ReaderSessionTool) -> Unit,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,
) {
    if (!visible) return

    val surface = MaterialTheme.colorScheme.surface
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val pageProgress = readerPageProgress(currentPage, totalPages)
    val pillShape = RoundedCornerShape(28.dp)
    val layoutDirection = LocalLayoutDirection.current
    val scrollState = rememberScrollState()
    val workDetailsLabel = stringResource(MR.strings.tsuzuki_reader_work_details)

    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChromeIconButton(
                icon = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                label = stringResource(MR.strings.action_webview_back),
                onClick = onBack,
            )
            Surface(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                onClick = onTitle,
                shape = pillShape,
                color = surface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, outline),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .semantics {
                            contentDescription = listOfNotNull(
                                workDetailsLabel,
                                workTitle,
                                chapterTitle,
                            ).joinToString(", ")
                        },
                    verticalArrangement = Arrangement.Center,
                ) {
                    workTitle?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            modifier = Modifier.semantics { heading() },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    chapterTitle?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            ChromeIconButton(
                icon = MaterialSymbols.Rounded.Visibility,
                label = stringResource(MR.strings.tsuzuki_reader_visual_protection),
                selected = protectionActive,
                onClick = { onTool(ReaderSessionTool.VISUAL_PROTECTION) },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (pageProgress != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = pillShape,
                    color = surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, outline),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pageProgress.total > 1) {
                            Slider(
                                modifier = Modifier.weight(1f),
                                value = pageProgress.current.toFloat(),
                                onValueChange = { onPageIndexChange(it.roundToInt() - 1) },
                                onValueChangeFinished = onPageIndexChangeFinished,
                                valueRange = 1f..pageProgress.total.toFloat(),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(
                            "${pageProgress.current}/${pageProgress.total}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.size(16.dp))
            }
            val tools = availableReaderTools(canonical = chaptersAvailable, fit = fitAvailable)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = pillShape,
                color = surface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
                border = BorderStroke(1.dp, outline),
            ) {
                Row(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            val fadeWidth = 12.dp.toPx()
                            val leftCue = if (layoutDirection == LayoutDirection.Ltr) {
                                scrollState.canScrollBackward
                            } else {
                                scrollState.canScrollForward
                            }
                            val rightCue = if (layoutDirection == LayoutDirection.Ltr) {
                                scrollState.canScrollForward
                            } else {
                                scrollState.canScrollBackward
                            }
                            if (leftCue) {
                                drawRect(
                                    Brush.horizontalGradient(listOf(surface, Color.Transparent), 0f, fadeWidth),
                                    size = Size(fadeWidth, size.height),
                                )
                            }
                            if (rightCue) {
                                drawRect(
                                    Brush.horizontalGradient(
                                        listOf(Color.Transparent, surface),
                                        size.width - fadeWidth,
                                        size.width,
                                    ),
                                    topLeft = Offset(size.width - fadeWidth, 0f),
                                    size = Size(fadeWidth, size.height),
                                )
                            }
                        }
                        .horizontalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tools.forEach { tool ->
                        val enabled = when (tool) {
                            ReaderSessionTool.CHAPTERS -> chaptersAvailable
                            ReaderSessionTool.PREVIOUS -> enabledPrevious
                            ReaderSessionTool.NEXT -> enabledNext
                            ReaderSessionTool.FIT -> fitAvailable
                            ReaderSessionTool.SOURCE -> sourceAvailable
                            else -> true
                        }
                        ReaderToolButton(
                            tool = tool,
                            enabled = enabled,
                            selected = when (tool) {
                                ReaderSessionTool.CROP -> cropEnabled
                                ReaderSessionTool.KEEP_SCREEN_ON -> keepScreenOn
                                ReaderSessionTool.VISUAL_PROTECTION -> protectionActive
                                else -> false
                            },
                            onClick = { onTool(tool) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChromeIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp).semantics {
                contentDescription = label
                this.selected = selected
            },
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ReaderToolButton(tool: ReaderSessionTool, enabled: Boolean, selected: Boolean, onClick: () -> Unit) {
    val label = when (tool) {
        ReaderSessionTool.CHAPTERS -> stringResource(MR.strings.tsuzuki_reader_chapters)
        ReaderSessionTool.PREVIOUS -> stringResource(MR.strings.tsuzuki_reader_previous)
        ReaderSessionTool.NEXT -> stringResource(MR.strings.tsuzuki_reader_next)
        ReaderSessionTool.BRIGHTNESS -> stringResource(MR.strings.tsuzuki_reader_brightness)
        ReaderSessionTool.FIT -> stringResource(MR.strings.tsuzuki_reader_fit)
        ReaderSessionTool.CROP -> stringResource(MR.strings.tsuzuki_reader_crop)
        ReaderSessionTool.SOURCE -> stringResource(MR.strings.tsuzuki_reader_source)
        ReaderSessionTool.KEEP_SCREEN_ON -> stringResource(MR.strings.tsuzuki_reader_keep_screen_on)
        ReaderSessionTool.VISUAL_PROTECTION -> stringResource(MR.strings.tsuzuki_reader_visual_protection)
    }
    val icon = when (tool) {
        ReaderSessionTool.CHAPTERS -> MaterialSymbols.Rounded.Newspaper
        ReaderSessionTool.PREVIOUS -> MaterialSymbols.Rounded.SkipPrevious
        ReaderSessionTool.NEXT -> MaterialSymbols.Rounded.SkipNext
        ReaderSessionTool.BRIGHTNESS -> MaterialSymbols.Rounded.Brush
        ReaderSessionTool.FIT -> MaterialSymbols.Rounded.FlipToBack
        ReaderSessionTool.CROP -> MaterialSymbols.Rounded.Image
        ReaderSessionTool.SOURCE -> MaterialSymbols.Rounded.Public
        ReaderSessionTool.KEEP_SCREEN_ON -> MaterialSymbols.Rounded.StayCurrentPortrait
        ReaderSessionTool.VISUAL_PROTECTION -> MaterialSymbols.Rounded.Visibility
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            contentDescription = label
            this.selected = selected
        },
        shape = RoundedCornerShape(4.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1)
            if (selected) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSecondaryContainer),
                )
            }
        }
    }
}
