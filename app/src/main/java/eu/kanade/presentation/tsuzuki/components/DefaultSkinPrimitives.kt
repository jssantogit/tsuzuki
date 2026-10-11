package eu.kanade.presentation.tsuzuki.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared visual language for the approved Default Skin.
 *
 * Material components remain implementation primitives, but public Tsuzuki surfaces should use
 * these semantic values/compositions instead of inheriting the default Material 3 presentation.
 */
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
    val scheme = MaterialTheme.colorScheme
    val light = scheme.surface.luminance() > 0.5f
    return remember(scheme, light) {
        if (light) {
            DefaultSkinColors(
                canvas = Color(0xFFF5F3EC),
                surface = Color(0xFFFFFFFF),
                surfaceMuted = Color(0xFFECEAE4),
                foreground = Color(0xFF0B0C0D),
                secondary = Color(0xFF5D6164),
                outline = Color(0xFFC9CCCE),
                accent = Color(0xFF242628),
                onAccent = Color(0xFFF5F3EC),
            )
        } else {
            DefaultSkinColors(
                canvas = Color(0xFF0B0C0D),
                surface = Color(0xFF151719),
                surfaceMuted = Color(0xFF242628),
                foreground = Color(0xFFF5F3EC),
                secondary = Color(0xFFC9CCCE),
                outline = Color(0xFF3A3D40),
                accent = Color(0xFFF5F3EC),
                onAccent = Color(0xFF0B0C0D),
            )
        }
    }
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
fun DefaultSkinPageTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
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
fun DefaultSkinSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
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
) {
    val colors = defaultSkinColors()
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = DefaultSkinShape.Control,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.surfaceMuted,
            disabledContentColor = colors.secondary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun DefaultSkinCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
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
        color = colors.surface,
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.65f)),
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
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = defaultSkinColors()
    DefaultSkinCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
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
