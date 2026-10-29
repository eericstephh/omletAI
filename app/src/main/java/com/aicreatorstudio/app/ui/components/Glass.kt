package com.aicreatorstudio.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.theme.GlassRegular
import com.aicreatorstudio.app.ui.theme.GlassThick
import com.aicreatorstudio.app.ui.theme.GlassThin
import com.aicreatorstudio.app.ui.theme.GlassUltraThin
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.AccentPrimary
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.CinematicSurface

/** Cinematic glass material densities. */
enum class GlassMaterial(val tint: Color, val blurRadius: Dp) {
    UltraThin(GlassUltraThin, 12.dp),
    Thin(GlassThin, 18.dp),
    Regular(GlassRegular, 24.dp),
    Thick(GlassThick, 30.dp)
}

/**
 * Premium frosted-glass surface — Apple restraint.
 *
 * - One translucent tint (no stacked blurs; blur is applied by the caller
 *   ONLY on top bars / modal sheets via [GlassChrome], max one per screen).
 * - 1dp low-contrast hairline + faint top highlight for layered depth.
 * - Set [accentGlow] ONLY for the single selected element in a group;
 *   it is a faint 8% wash, never a neon outer shadow (perf + taste).
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    material: GlassMaterial = GlassMaterial.Regular,
    accentGlow: Boolean = false,
    borderOpacity: Float = 1f,
    content: @Composable () -> Unit
) {
    val tint = material.tint
    val borderColor = remember(borderOpacity) {
        Hairline.copy(alpha = Hairline.alpha * borderOpacity)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(tint)
            .drawBehind {
                drawTopHighlight()
                if (accentGlow) drawAccentGlow()
            }
            .border(width = 1.dp, color = borderColor, shape = shape)
    ) {
        // NB: `fillMaxSize` is load-bearing here — at least one caller
        // (EditorTimeline's lane surface) relies on the inner box stretching
        // the surface to its height constraints. 2026-10-05: a change to
        // `matchParentSize` collapsed the timeline; reverted. Callers that
        // must NOT stretch (ToolGroupSheet) bound their height themselves
        // via `Modifier.height(IntrinsicSize.Min)`.
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Apple-style floating chrome: frosted-glass bar for AT MOST ONE floating
 * element per screen (home header, editor top bar).
 *
 * Honest engine note: Compose has no backdrop sampling, and Modifier.blur
 * would blur the bar's own content too — so chrome is a translucent tint +
 * hairline + top highlight (static, cheap, sharp content). It reads as
 * frosted glass wherever it floats over varied content (gradients, lists,
 * video). Sheets, cards and rows use plain [GlassSurface]; never add blur
 * modifiers to fake more frost.
 */
@Composable
fun GlassChrome(
    modifier: Modifier = Modifier,
    shape: Shape,
    material: GlassMaterial = GlassMaterial.Regular,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(material.tint)
            .drawBehind { drawTopHighlight() }
            .border(width = 1.dp, color = Hairline, shape = shape)
    ) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
/** Soft top-edge highlight for depth. */
private fun DrawScope.drawTopHighlight() {
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
            startY = 0f,
            endY = size.height * 0.16f
        )
    )
}

/** Faint accent wash for the single selected state in a group. */
private fun DrawScope.drawAccentGlow() {
    val glow = AccentPrimary.copy(alpha = 0.08f)
    drawRect(glow)
}

/** Cinematic blurred backdrop for behind glass surfaces. */
@Composable
fun GlassBackdrop(
    modifier: Modifier = Modifier,
    blurRadius: Dp = 20.dp
) {
    Box(
        modifier = modifier
            .blur(blurRadius)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        CinematicBlack,
                        CinematicSurface,
                        CinematicBlack
                    )
                )
            )
    )
}
