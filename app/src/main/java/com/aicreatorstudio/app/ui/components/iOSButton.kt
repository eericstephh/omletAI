package com.aicreatorstudio.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.AccentPrimary
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicElevated
import com.aicreatorstudio.app.ui.theme.CinematicHighest
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.LabelToolbar
import com.aicreatorstudio.app.ui.theme.StateDisabledAlpha
import com.aicreatorstudio.app.ui.theme.StateSelectedBorder
import com.aicreatorstudio.app.ui.theme.StateSelectedFill
import com.aicreatorstudio.app.ui.theme.StatusErrorDeep
import com.aicreatorstudio.app.ui.theme.StatusSuccess
import com.aicreatorstudio.app.ui.theme.StatusSuccessDeep
import com.aicreatorstudio.app.ui.theme.ctaBrush
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * iOS-style pill (capsule) button. Used for primary CTAs like "Export".
 * Tinted with the iOS system blue gradient, with a soft press-down
 * spring animation and haptic feedback.
 */
@Composable
fun iOSPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    style: iOSButtonStyle = iOSButtonStyle.Filled
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Restrained Apple press: 0.97 scale, gentle spring — no bounce.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pill-scale"
    )

    val (bgBrush, borderColor, textColor) = when (style) {
        iOSButtonStyle.Filled -> Triple(
            // CTA gradient — the only gradient under white text (AA at
            // both ends). Subtle vertical falloff, premium not flashy.
            ctaBrush(),
            Color.Transparent,
            Color.White
        )
        iOSButtonStyle.Tinted -> Triple(
            Brush.verticalGradient(listOf(AccentPrimary.copy(alpha = 0.20f), AccentPrimary.copy(alpha = 0.10f))),
            AccentPrimary.copy(alpha = 0.38f),
            AccentBright
        )
        iOSButtonStyle.Gray -> Triple(
            Brush.verticalGradient(listOf(CinematicHighest, CinematicElevated)),
            BorderSubtle,
            LabelPrimary
        )
        iOSButtonStyle.Destructive -> Triple(
            // Deep-red gradient — white passes AA at both ends
            // (4.83 / 6.47); never put white on bright reds.
            Brush.verticalGradient(listOf(StatusErrorDeep, Color(0xFFB91C1C))),
            Color.Transparent,
            Color.White
        )
        iOSButtonStyle.Success -> Triple(
            // Green is bright enough to demand dark text (black ≈ 10:1).
            Brush.verticalGradient(listOf(StatusSuccess, StatusSuccessDeep)),
            Color.Transparent,
            Color.Black
        )
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(iOSShapes.capsule)
            .background(bgBrush, iOSShapes.capsule)
            .border(1.dp, borderColor, iOSShapes.capsule)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                color = textColor.copy(alpha = if (enabled) 1f else StateDisabledAlpha),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

enum class iOSButtonStyle { Filled, Tinted, Gray, Destructive, Success }

/**
 * iOS-style icon-only control. A rounded-square (iOS control center) or
 * circle (iOS camera shutter) button with a soft glass surface and a
 * "pressed" state that scales and brightens.
 */
@Composable
fun iOSIconControl(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    selected: Boolean = false,
    tint: Color = LabelPrimary,
    shape: Shape = RoundedCornerShape(14.dp),
    sizeDp: Int = 44
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "icon-scale"
    )

    val bgColor = when {
        selected -> StateSelectedFill
        pressed -> Color.White.copy(alpha = 0.10f)
        else -> Color.White.copy(alpha = 0.06f)
    }
    val borderColor = if (selected) StateSelectedBorder else HairlineSubtle

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .scale(scale)
            .clip(shape)
            .background(bgColor, shape)
            .border(1.dp, borderColor, shape)
            .drawBehind {
                // Top-edge highlight
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.4f
                    )
                )
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) iOSBlueBright else tint,
            modifier = Modifier.size((sizeDp * 0.42f).dp)
        )
    }
}

/**
 * Premium toolbar item — restrained Apple style.
 *
 * Visual states:
 *  - **Idle:** translucent surface with hairline border, high-contrast icon.
 *  - **Pressed:** 0.97 scale, no bounce.
 *  - **Selected:** 14% accent wash + accent hairline + semibold label.
 *    No neon outer glow / shadow (perf + premium restraint).
 */
@Composable
fun iOSToolbarItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    accentColor: Color = iOSBlue,
    /**
     * Floor for the chip width. The default (64dp) keeps the original dock
     * sizing. The contextual toolbar passes `0.dp` because its row divides the
     * screen width between up to seven chips — at 64dp each they would
     * overflow a 360dp phone and force the horizontal scroll the IA
     * explicitly removed.
     */
    minWidth: Dp = 64.dp
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Restrained press — 0.97, no bounce.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "toolbar-scale"
    )

    // Selected state uses the brand accent wash — not neon cyan — so the
    // legacy dock matches the contextual toolbar and the design tokens.
    val selectedFill: Color = if (accentColor == iOSBlue) StateSelectedFill
        else accentColor.copy(alpha = 0.14f)
    val selectedBorder: Color = if (accentColor == iOSBlue) StateSelectedBorder
        else accentColor.copy(alpha = 0.45f)
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> selectedBorder
            else -> HairlineSubtle
        },
        label = "toolbar-border-color"
    )
    val innerFillColor by animateColorAsState(
        targetValue = if (selected) selectedFill else Color.Transparent,
        label = "toolbar-inner-fill"
    )
    val surfaceColor by animateColorAsState(
        targetValue = GlassIdleSurface,
        label = "toolbar-surface"
    )
    // Label alpha: 1.0 when selected, 0.95 when idle. Kept bright
    // for high contrast against dark glass.
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.95f,
        label = "toolbar-label-alpha"
    )

    // The contextual toolbar divides a single row between up to seven chips,
    // so a weighted chip can be as narrow as ~30dp on a 360dp phone (the full
    // clip toolbar is 6–7 chips *plus* the destructive cluster). The icon tile
    // used to be a fixed 36dp, which then overflowed the chip and collided
    // with its neighbour; measuring the real chip width lets the tile shrink
    // to fit and never exceed it.
    BoxWithConstraints(modifier = modifier.semantics { selected }) {
        val tileSize = (maxWidth - 6.dp).coerceIn(22.dp, 36.dp)
        val glyphSize = (tileSize * 0.55f).coerceIn(12.dp, 20.dp)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)
                // widthIn(min = minWidth) instead of a fixed width so
                // longer labels ("Transitions", "Subtitles", "Adjust")
                // can grow the chip instead of overflowing the rounded
                // background. The 64dp default floor keeps short-label chips
                // (e.g. "Mute", "BG") at the original iOS-icon size; the
                // contextual toolbar lowers it so chips can share the row.
                .widthIn(min = minWidth)
                .clip(RoundedCornerShape(16.dp))
                .background(surfaceColor)
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = label,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onClick()
                    }
                )
                .padding(vertical = 5.dp, horizontal = 3.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(tileSize)
                    .clip(RoundedCornerShape(10.dp))
                    .background(innerFillColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) iOSBlueBright else LabelPrimary.copy(alpha = 0.92f),
                    modifier = Modifier.size(glyphSize)
                )
            }
            Spacer(Modifier.height(2.dp))
            // Tool label — modern legible typography.
            //  - color: LabelToolbar (#E2E8F0 slate-200), uniform across
            //    selected/idle; the idle state still gets a slight alpha
            //    dimming for hierarchy.
            //  - size: 11sp (Material3 labelSmall = 11sp) for compactness.
            //  - weight: SemiBold when selected, Medium when idle.
            //  - letter-spacing: 0.2sp (tighter than the previous 0.5sp
            //    which caused labels to drift off-center on small screens).
            //  - maxLines = 1 + overflow = Ellipsis (was Visible): a chip in
            //    the shared row is width-constrained by `weight(1f)`, so
            //    Visible let a long Persian label spill into the neighbouring
            //    chip. Centred so a truncated word stays optically aligned.
            Text(
                text = label,
                color = LabelToolbar.copy(alpha = labelAlpha),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

// ---- Local-only colour tokens for the dock button -------------------------
// Translucent elevated surface shared by the legacy dock button.
private val GlassIdleSurface = CinematicHighest.copy(alpha = 0.70f)
