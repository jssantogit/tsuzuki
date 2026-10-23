package eu.kanade.presentation.tsuzuki.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.domain.ui.model.AppTheme
import mihon.app.di.appGraph

/** Shared visual language for the approved Default Skin. */
@Immutable
data class DefaultSkinColors(
    val canvas: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val foreground: Color,
    val secondary: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
)

@Composable
fun defaultSkinColors(): DefaultSkinColors {
    val context = LocalContext.current
    val uiPreferences = remember(context) { context.appGraph.uiPreferences }
    val scheme = MaterialTheme.colorScheme
    val light = scheme.surface.luminance() > 0.5f
    val appTheme = uiPreferences.appTheme.get()
    val amoled = uiPreferences.themeDarkAmoled.get()
    return remember(scheme, light, appTheme, amoled) {
        defaultSkinPalette(isLight = light, appTheme = appTheme, scheme = scheme, amoled = amoled)
    }
}

/** Neutral interface roles stay independent from the Ink/Paper brand identity. */
internal fun defaultSkinPalette(isLight: Boolean): DefaultSkinColors =
    if (isLight) {
        DefaultSkinColors(
            canvas = Color(0xFFF7F7F8),
            surface = Color(0xFFFFFFFF),
            surfaceMuted = Color(0xFFE8EAED),
            foreground = Color(0xFF17181A),
            secondary = Color(0xFF50565C),
            outline = Color(0xFFD6D9DC),
            accent = Color(0xFF17181A),
            onAccent = Color(0xFFFFFFFF),
        )
    } else {
        DefaultSkinColors(
            canvas = Color(0xFF0B0C0D),
            surface = Color(0xFF1C1E1F),
            surfaceMuted = Color(0xFF2B2D2E),
            foreground = Color(0xFFF5F5F5),
            secondary = Color(0xFFADB2B6),
            outline = Color(0xFF303234),
            accent = Color(0xFFF5F5F5),
            onAccent = Color(0xFF0B0C0D),
        )
    }

/**
 * Preserve Ink/Paper for Default; derive themed roles from the selected Material palette for
 * every other preset. This keeps the existing theme preference as the only source of truth.
 */
internal fun defaultSkinPalette(
    isLight: Boolean,
    appTheme: AppTheme,
    scheme: ColorScheme,
    amoled: Boolean = false,
): DefaultSkinColors {
    val palette = if (appTheme == AppTheme.DEFAULT) {
        defaultSkinPalette(isLight)
    } else {
        DefaultSkinColors(
            canvas = scheme.background,
            surface = scheme.surfaceContainerLow,
            surfaceMuted = scheme.surfaceContainerHigh,
            foreground = scheme.onBackground,
            secondary = scheme.onSurfaceVariant,
            outline = scheme.outlineVariant,
            accent = scheme.primary,
            onAccent = scheme.onPrimary,
        )
    }
    return if (!isLight && amoled) palette.copy(canvas = Color.Black) else palette
}

object DefaultSkinSpacing {
    val PageHorizontal = 20.dp
    val Section = 28.dp
    val Group = 14.dp
    val Row = 12.dp
}

object DefaultSkinShape {
    val Card = RoundedCornerShape(18.dp)
    val Control = RoundedCornerShape(14.dp)
    val Pill = RoundedCornerShape(999.dp)
}

@Composable
fun DefaultSkinPageTitle(text: String, modifier: Modifier = Modifier) {
    val colors = defaultSkinColors()
    Text(
        text = text,
        modifier = modifier,
        color = colors.foreground,
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun DefaultSkinSectionLabel(text: String, modifier: Modifier = Modifier) {
    val colors = defaultSkinColors()
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = colors.secondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun DefaultSkinPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    minimumHeight: Dp = 52.dp,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    val colors = defaultSkinColors()
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = minimumHeight),
        enabled = enabled,
        contentPadding = contentPadding,
        shape = DefaultSkinShape.Control,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.surfaceMuted,
            disabledContentColor = colors.secondary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leadingContent?.invoke(this)
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun DefaultSkinSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = defaultSkinColors()
    Surface(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = DefaultSkinShape.Control,
        color = if (enabled) colors.surface else colors.surfaceMuted,
        contentColor = if (enabled) colors.foreground else colors.secondary,
        border = BorderStroke(1.dp, colors.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun DefaultSkinTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = defaultSkinColors()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp),
            color = if (enabled) colors.foreground else colors.secondary.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun DefaultSkinChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.CenterStart,
    ) {
        Surface(
            modifier = Modifier.heightIn(min = 32.dp),
            shape = DefaultSkinShape.Pill,
            color = if (selected) colors.accent else colors.surface,
            contentColor = if (selected) colors.onAccent else colors.foreground,
            border = BorderStroke(1.dp, if (selected) colors.accent else colors.outline),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DefaultSkinTag(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = defaultSkinColors()
    Surface(
        modifier = modifier,
        shape = DefaultSkinShape.Pill,
        color = colors.surfaceMuted,
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.8f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun DefaultSkinTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
) {
    val colors = defaultSkinColors()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = colors.secondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DefaultSkinShape.Control,
            color = colors.surfaceMuted,
            border = BorderStroke(1.dp, colors.outline),
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foreground),
                cursorBrush = SolidColor(colors.foreground),
            )
        }
        supportingText?.takeIf(String::isNotBlank)?.let {
            Text(text = it, color = colors.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun DefaultSkinFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    passwordToggleLabel: String? = null,
    onTogglePassword: () -> Unit = {},
) {
    val colors = defaultSkinColors()
    val errorColor = MaterialTheme.colorScheme.error
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = if (error == null) colors.secondary else errorColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp),
            shape = DefaultSkinShape.Control,
            color = colors.surface,
            contentColor = colors.foreground,
            border = BorderStroke(1.dp, if (error == null) colors.outline else errorColor),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = if (isPassword) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = label
                            if (error != null) this.error(error)
                        },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.foreground),
                    cursorBrush = SolidColor(colors.foreground),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (isPassword && !passwordVisible) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                )
                if (isPassword && passwordToggleLabel != null) {
                    DefaultSkinTextAction(
                        text = passwordToggleLabel,
                        onClick = onTogglePassword,
                        modifier = Modifier.heightIn(min = 48.dp),
                        enabled = enabled,
                    )
                }
            }
        }
        error?.let {
            Text(
                text = it,
                color = errorColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(start = 4.dp),
            )
        }
    }
}

@Composable
fun DefaultSkinCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = defaultSkinColors()
    val clickableModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
    }
    Surface(
        modifier = modifier.then(clickableModifier),
        shape = DefaultSkinShape.Card,
        color = if (selected) colors.surfaceMuted else colors.surface,
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = if (selected) 1f else 0.65f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

@Composable
fun DefaultSkinNavigationRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = defaultSkinColors()
    DefaultSkinCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        selected = selected,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(colors.surfaceMuted, DefaultSkinShape.Control),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.foreground,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = colors.foreground,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        color = colors.secondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
        }
    }
}
