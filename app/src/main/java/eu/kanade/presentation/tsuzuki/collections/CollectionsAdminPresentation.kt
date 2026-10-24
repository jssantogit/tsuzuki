package eu.kanade.presentation.tsuzuki.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPageTitle
import eu.kanade.presentation.tsuzuki.components.DefaultSkinPrimaryButton
import eu.kanade.presentation.tsuzuki.components.DefaultSkinSectionLabel
import eu.kanade.presentation.tsuzuki.components.DefaultSkinShape
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextAction
import eu.kanade.presentation.tsuzuki.components.DefaultSkinTextField
import eu.kanade.presentation.tsuzuki.components.defaultSkinColors
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

internal data class CollectionsAdminMenuAction(
    val title: String,
    val onClick: () -> Unit,
)

@Composable
internal fun CollectionsAdminRootPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: List<CollectionsAdminMenuAction>,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        CollectionsAdminRootHeader(
            title = title,
            onBack = onBack,
            actions = actions,
        )
        content()
    }
}

@Composable
internal fun CollectionsAdminRootHeader(
    title: String,
    onBack: () -> Unit,
    actions: List<CollectionsAdminMenuAction>,
) {
    val colors = defaultSkinColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                contentDescription = stringResource(MR.strings.tsuzuki_collections_admin_back),
                tint = colors.foreground,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        var menuExpanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.MoreVert,
                    contentDescription = stringResource(MR.strings.tsuzuki_collections_actions),
                    tint = colors.foreground,
                )
            }
            CollectionsAdminOverflowMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                actions = actions.map { action ->
                    action.copy(
                        onClick = {
                            menuExpanded = false
                            action.onClick()
                        },
                    )
                },
            )
        }
    }
    DefaultSkinPageTitle(
        text = title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
internal fun CollectionsAdminEditorPage(
    title: String,
    onBack: () -> Unit,
    footerLabel: String,
    footerEnabled: Boolean,
    onFooterClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                    contentDescription = stringResource(MR.strings.tsuzuki_collections_admin_back),
                    tint = colors.foreground,
                )
            }
            DefaultSkinPageTitle(
                text = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
        content()
        CollectionsAdminEditorFooter(
            label = footerLabel,
            enabled = footerEnabled,
            onClick = onFooterClick,
        )
    }
}

@Composable
internal fun CollectionsAdminEditorFooter(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = defaultSkinColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.canvas),
    ) {
        androidx.compose.material3.HorizontalDivider(color = colors.outline)
        DefaultSkinPrimaryButton(
            text = label,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            enabled = enabled,
        )
    }
}

@Composable
internal fun CollectionsAdminNameField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    DefaultSkinTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
    )
}

@Composable
internal fun CollectionsAdminSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = defaultSkinColors()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DefaultSkinSectionLabel(text = title)
        Spacer(modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Add,
                    contentDescription = null,
                    tint = colors.accent,
                )
                Text(
                    text = actionLabel,
                    color = colors.foreground,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun CollectionsAdminSurface(
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = defaultSkinColors()
    val clickableModifier = if (onClick == null) {
        modifier
    } else {
        modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Surface(
        modifier = clickableModifier.fillMaxWidth(),
        shape = DefaultSkinShape.Card,
        color = colors.surface,
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.outline),
        shadowElevation = if (isDragging) 5.dp else 0.dp,
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun CollectionsAdminOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<CollectionsAdminMenuAction>,
) {
    if (!expanded) return
    val colors = defaultSkinColors()
    val shape = RoundedCornerShape(14.dp)
    Popup(
        alignment = Alignment.TopEnd,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 176.dp, max = 280.dp)
                .shadow(8.dp, shape)
                .background(colors.surface, shape)
                .border(1.dp, colors.outline, shape)
                .padding(vertical = 4.dp),
        ) {
            actions.forEach { action ->
                Text(
                    text = action.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = action.onClick)
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    color = colors.foreground,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionsAdminSheet(
    title: String,
    message: String,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
    busy: Boolean = false,
    onConfirm: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val colors = defaultSkinColors()
    val allowDismiss = !busy
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { destination ->
            allowDismiss || destination != SheetValue.Hidden
        },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = colors.surface,
        contentColor = colors.foreground,
        scrimColor = colors.foreground.copy(alpha = 0.32f),
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 34.dp, height = 4.dp)
                    .background(colors.outline, RoundedCornerShape(2.dp)),
            )
            Text(
                text = title,
                color = colors.foreground,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (busy) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                    Text(
                        text = message,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                Text(
                    text = message,
                    color = colors.secondary,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (confirmLabel != null && onConfirm != null) {
                if (dismissLabel != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DefaultSkinTextAction(
                            text = dismissLabel,
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        )
                        DefaultSkinPrimaryButton(
                            text = confirmLabel,
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    DefaultSkinPrimaryButton(
                        text = confirmLabel,
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
