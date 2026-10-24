package eu.kanade.presentation.reader

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import eu.kanade.tachiyomi.ui.reader.ReaderRecovery
import eu.kanade.tachiyomi.ui.reader.ReaderVisualProtection
import eu.kanade.tachiyomi.ui.reader.readerChapterPosition
import eu.kanade.tachiyomi.ui.reader.resolveReaderChapterJump
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Check
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderContextCard(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val view = LocalView.current
        LaunchedEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0.32f)
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 6.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        androidx.compose.material3.TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Text(stringResource(MR.strings.tsuzuki_reader_close))
                        }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
fun ColumnScope.ReaderChaptersCard(
    chapters: List<CanonicalChapter>,
    currentChapterId: String?,
    loading: Boolean,
    onSelect: (CanonicalChapter) -> Unit,
) {
    var jump by remember { mutableStateOf("") }
    var jumpNotFound by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val currentIndex = readerChapterPosition(chapters, currentChapterId)
    LaunchedEffect(chapters, currentChapterId) {
        if (chapters.isNotEmpty()) listState.scrollToItem(currentIndex)
    }

    if (loading && chapters.isEmpty()) {
        Text(stringResource(MR.strings.tsuzuki_reader_loading))
    } else if (chapters.isEmpty()) {
        Text(stringResource(MR.strings.tsuzuki_reader_no_chapters))
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 280.dp),
        ) {
            items(chapters, key = CanonicalChapter::id) { chapter ->
                val selected = chapter.id == currentChapterId
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected, enabled = !loading, role = Role.Button) { onSelect(chapter) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    chapter.title?.takeIf(String::isNotBlank)?.let { Text(it) }
                    Text(
                        chapter.displayNumber,
                        style = if (chapter.title.isNullOrBlank()) {
                            MaterialTheme.typography.bodyLarge
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (chapter.title.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (selected) {
                        Text(
                            stringResource(MR.strings.tsuzuki_reader_current_chapter),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = jump,
                onValueChange = {
                    jump = it
                    jumpNotFound = false
                },
                label = { Text(stringResource(MR.strings.tsuzuki_reader_jump)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(enabled = !loading, onClick = {
                val chapter = resolveReaderChapterJump(chapters, jump)
                jumpNotFound = chapter == null
                chapter?.let(onSelect)
            }) {
                Text(stringResource(MR.strings.tsuzuki_reader_jump))
            }
        }
        if (jumpNotFound) {
            Text(
                stringResource(MR.strings.tsuzuki_reader_jump_not_found),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun ReaderBrightnessCard(
    custom: Boolean,
    value: Int,
    onCustomChange: (Boolean) -> Unit,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCustomChange(!custom) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(MR.strings.tsuzuki_reader_system_brightness))
        Switch(checked = !custom, onCheckedChange = { onCustomChange(!it) })
    }
    if (custom) {
        Slider(
            value = value.coerceIn(-75, 100).toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = -75f..100f,
        )
    }
}

@Composable
fun ColumnScope.ReaderFitCard(options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    LazyColumn(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = 280.dp),
    ) {
        items(options, key = { it.first }) { (value, label) ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = value == selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(value) },
                ).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(label)
                if (value == selected) {
                    Icon(
                        MaterialSymbols.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderCropCard(enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(MR.strings.tsuzuki_reader_crop))
        Switch(checked = enabled, onCheckedChange = { onToggle() })
    }
}

@Composable
fun ColumnScope.ReaderProtectionCard(mode: ReaderVisualProtection, onSelect: (ReaderVisualProtection) -> Unit) {
    LazyColumn(
        modifier = Modifier.weight(1f, fill = false).heightIn(max = 280.dp),
    ) {
        items(ReaderVisualProtection.entries, key = { it.name }) { option ->
            val label = when (option) {
                ReaderVisualProtection.OFF -> MR.strings.tsuzuki_reader_off
                ReaderVisualProtection.WARM -> MR.strings.tsuzuki_reader_warm
                ReaderVisualProtection.COOL -> MR.strings.tsuzuki_reader_cool
                ReaderVisualProtection.SOFT -> MR.strings.tsuzuki_reader_soft
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                    selected = option == mode,
                    role = Role.RadioButton,
                    onClick = { onSelect(option) },
                ).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(label))
                if (option == mode) {
                    Icon(
                        MaterialSymbols.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderRecoveryCard(recovery: ReaderRecovery, onRetry: () -> Unit, onSources: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (recovery) {
                    ReaderRecovery.SOURCE_UNAVAILABLE -> MR.strings.tsuzuki_reader_source_failed
                    ReaderRecovery.P2P_REQUIRED -> MR.strings.tsuzuki_reader_p2p_required
                },
            ),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRetry) { Text(stringResource(MR.strings.tsuzuki_reader_retry)) }
            Button(onClick = onSources) { Text(stringResource(MR.strings.tsuzuki_reader_source)) }
        }
    }
}
