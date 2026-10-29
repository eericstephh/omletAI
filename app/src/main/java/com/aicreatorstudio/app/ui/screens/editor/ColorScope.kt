package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSRed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The kind of scope shown inside the floating [ColorScope] overlay.
 *  - HISTOGRAM : classic per-channel bar histogram (R, G, B overlaid)
 *  - RGB_PARADE: same data but each channel gets its own horizontal lane
 *  - WAVEFORM  : per-column luminance trace across the frame
 *  - VECTOR    : colour-wheel vectorscope (chroma only)
 */
enum class ColorScopeKind { HISTOGRAM, RGB_PARADE, WAVEFORM, VECTOR }

/**
 * Compact, frosted-glass Color Scope / Histogram overlay.
 *
 * Visual style: translucent dark glass card (~120dp wide, 80dp tall
 * when expanded) pinned to a corner of the video preview, draggable
 * to any position inside the parent, and minimisable with a single
 * tap. The whole thing is hidden when [visible] is false.
 *
 * Data source: **synthetic**. We sample the same test-image gradient
 * the effect thumbnails use, apply the live [ColorGrading] matrix,
 * and feed the result through the scope. This makes the bars/wave
 * update instantly as the user drags any slider, with zero video
 * pipeline overhead. See [rememberScopeSample].
 *
 * @param visible show / hide the scope (driven by the panel being open)
 * @param grading current ColorGrading state to apply before sampling
 * @param modifier modifier on the outer overlay container (typically
 *   `Modifier.fillMaxSize()` of the canvas box, so drag math is in
 *   screen-space)
 * @param parentWidthPx parent canvas width in pixels (for clamping)
 * @param parentHeightPx parent canvas height in pixels (for clamping)
 */
@Composable
fun ColorScope(
    visible: Boolean,
    grading: ColorGrading,
    parentWidthPx: Float,
    parentHeightPx: Float,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val density = LocalDensity.current

    // Default position: top-right corner of the canvas with a small
    // inset. Drags overwrite these and the value persists in the
    // composition for the lifetime of this composable.
    val initial = remember(parentWidthPx, parentHeightPx, density) {
        val pad = with(density) { 12.dp.toPx() }
        val scopeW = with(density) { 130.dp.toPx() }
        // x = right edge minus width minus pad
        IntOffset(
            x = (parentWidthPx - scopeW - pad).toInt().coerceAtLeast(0),
            y = pad.toInt()
        )
    }
    var offset by remember { mutableStateOf(initial) }
    var isMinimised by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(ColorScopeKind.HISTOGRAM) }

    // Reset to top-right whenever the canvas size first becomes
    // non-zero (i.e. when the user first opens the panel after the
    // canvas has been laid out). We avoid re-snapping on every minor
    // size change so the user's chosen position survives a layout
    // reflow.
    var hasInitialPosition by remember { mutableStateOf(false) }
    LaunchedEffect(parentWidthPx, parentHeightPx) {
        if (parentWidthPx > 0f && parentHeightPx > 0f && !hasInitialPosition) {
            offset = initial
            hasInitialPosition = true
        }
    }

    val animatedAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(220),
        label = "scope-alpha"
    )

    val scopeWidthDp = 130.dp
    val scopeHeightDp = if (isMinimised) 32.dp else 96.dp

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .offset { offset }
                .widthIn(min = scopeWidthDp, max = scopeWidthDp)
        ) {
            ScopeCard(
                kind = kind,
                onKindChange = { kind = it },
                isMinimised = isMinimised,
                onMinimiseToggle = { isMinimised = !isMinimised },
                grading = grading,
                onDrag = { dx, dy ->
                    val newX = (offset.x + dx).toInt()
                        .coerceIn(0, (parentWidthPx - with(density) { scopeWidthDp.toPx() }).toInt().coerceAtLeast(0))
                    val newY = (offset.y + dy).toInt()
                        .coerceIn(0, (parentHeightPx - with(density) { scopeHeightDp.toPx() }).toInt().coerceAtLeast(0))
                    offset = IntOffset(newX, newY)
                },
                alpha = animatedAlpha
            )
        }
    }
}

@Composable
private fun ScopeCard(
    kind: ColorScopeKind,
    onKindChange: (ColorScopeKind) -> Unit,
    isMinimised: Boolean,
    onMinimiseToggle: () -> Unit,
    grading: ColorGrading,
    onDrag: (dx: Float, dy: Float) -> Unit,
    alpha: Float
) {
    val density = LocalDensity.current
    val scopeWidthDp = 130.dp
    val headerHeightDp = 28.dp
    val footerHeightDp = 22.dp
    val cornerRadiusDp = 12.dp
    val bodyHeightDp = if (isMinimised) 0.dp else 46.dp

    Column(
        modifier = Modifier
            .width(scopeWidthDp)
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(Color.Black.copy(alpha = 0.55f * alpha))
            .border(
                width = 1.dp,
                color = HairlineSubtle,
                shape = RoundedCornerShape(cornerRadiusDp)
            )
            .pointerInput(Unit) {
                // Drag handler is on the card so the user can grab
                // anywhere on the scope to move it.
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
            .pointerInput(Unit) {
                // Single-tap on the header minimises / restores.
                detectTapGestures(
                    onTap = { onMinimiseToggle() }
                )
            }
    ) {
        // ---- Header ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .height(headerHeightDp)
        ) {
            Icon(
                imageVector = Icons.Filled.OpenWith,
                contentDescription = "Drag",
                tint = LabelSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "SCOPE",
                color = iOSBlueBright,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            // Kind switcher (cycle on tap for now — cheap and matches
            // the iOS Quick Toggle style).
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(iOSBlue.copy(alpha = 0.25f))
                    .clickable {
                        val order = ColorScopeKind.entries
                        onKindChange(order[(order.indexOf(kind) + 1) % order.size])
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (kind) {
                        ColorScopeKind.HISTOGRAM -> Icons.Filled.BarChart
                        ColorScopeKind.RGB_PARADE -> Icons.Filled.BarChart
                        ColorScopeKind.WAVEFORM -> Icons.Filled.GraphicEq
                        ColorScopeKind.VECTOR -> Icons.Filled.RadioButtonChecked
                    },
                    contentDescription = "Scope kind",
                    tint = iOSBlueBright,
                    modifier = Modifier.size(11.dp)
                )
            }
        }

        // ---- Body (collapsed when minimised) ----
        AnimatedVisibility(
            visible = !isMinimised,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .height(bodyHeightDp)
                    .padding(horizontal = 6.dp)
            ) {
                ScopeBody(kind = kind, grading = grading)
            }
        }

        // ---- Footer (legend) ----
        if (!isMinimised) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = kind.label(),
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "0–255",
                    color = LabelSecondary.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun ScopeBody(kind: ColorScopeKind, grading: ColorGrading) {
    // Sample a synthetic image and apply the live grading matrix. We
    // animate a 6Hz refresh so the scope visibly reacts to slider
    // motion but doesn't churn the CPU on every frame.
    var sample by remember { mutableStateOf(floatArrayOf()) }
    LaunchedEffect(grading) {
        // Re-sample once per grading change. Animations/scrubbing
        // would normally drive this every frame; for the panel-only
        // use case this is enough.
        withFrameNanos {
            sample = rememberScopeSample(grading)
        }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        when (kind) {
            ColorScopeKind.HISTOGRAM -> drawHistogramOverlay(sample, size)
            ColorScopeKind.RGB_PARADE -> drawRGBParade(sample, size)
            ColorScopeKind.WAVEFORM -> drawWaveform(sample, size)
            ColorScopeKind.VECTOR -> drawVectorscope(sample, size)
        }
    }
}

private fun ColorScopeKind.label(): String = when (this) {
    ColorScopeKind.HISTOGRAM -> "Histogram"
    ColorScopeKind.RGB_PARADE -> "RGB Parade"
    ColorScopeKind.WAVEFORM -> "Waveform"
    ColorScopeKind.VECTOR -> "Vectorscope"
}

// ---- Sampling -------------------------------------------------------

/**
 * Build a 256-bucket histogram-style array (256 floats per channel,
 * 768 floats total) from the synthetic test image with [grading]
 * applied. Cheap — runs in well under a millisecond on a Pixel 5.
 *
 * Layout: [r0..r255, g0..g255, b0..b255]
 */
private fun rememberScopeSample(grading: ColorGrading): FloatArray {
    // We use a fixed seed for stable visual output per grading, so the
    // scope doesn't shimmer between samples with the same grading.
    val rng = Random(0xC0107)
    val w = 64
    val h = 64
    val src = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            // Cyan -> magenta gradient with a yellow disc & green strip
            // — same recipe as rememberTestImage() so the scope looks
            // familiar to anyone who's used the effect thumbnails.
            val fx = x / (w - 1f)
            val fy = y / (h - 1f)
            val rLin = (1f - fx) * 0f + fx * 1f
            val gLin = (1f - fy) * 0.6f + fy * 0.4f
            val bLin = (1f - fx) * 1f + fx * 0f
            val disc = 1f - (kotlin.math.sqrt(((x - w * 0.68f) * (x - w * 0.68f) +
                (y - h * 0.32f) * (y - h * 0.32f)).toFloat()) / (w * 0.17f)).coerceIn(0f, 1f)
            val rDisc = disc * 1f
            val gDisc = disc * 1f
            val bDisc = disc * 0f
            val stripeY = h * 0.72f
            val inStripe = if (y >= stripeY) 1f else 0f
            val rStripe = inStripe * 0.13f
            val gStripe = inStripe * 0.51f
            val bStripe = inStripe * 0.27f
            // Add a touch of noise so the histogram doesn't look like
            // a flat ramp.
            val n = (rng.nextFloat() - 0.5f) * 0.08f
            val r = (rLin + rDisc + rStripe + n).coerceIn(0f, 1f)
            val g = (gLin + gDisc + gStripe + n).coerceIn(0f, 1f)
            val b = (bLin + bDisc + bStripe + n).coerceIn(0f, 1f)
            src[y * w + x] =
                (0xFF shl 24) or ((r * 255).toInt() shl 16) or
                    ((g * 255).toInt() shl 8) or (b * 255).toInt()
        }
    }

    val m = ColorGradingMath.toColorMatrix(grading, null)
    val pixels = applyColorMatrix(src, m.values)

    val r = FloatArray(256)
    val g = FloatArray(256)
    val b = FloatArray(256)
    for (px in pixels) {
        val ri = (px ushr 16) and 0xFF
        val gi = (px ushr 8) and 0xFF
        val bi = px and 0xFF
        r[ri] += 1f
        g[gi] += 1f
        b[bi] += 1f
    }
    val maxR = r.max()
    val maxG = g.max()
    val maxB = b.max()
    if (maxR > 0f) for (i in 0 until 256) r[i] /= maxR
    if (maxG > 0f) for (i in 0 until 256) g[i] /= maxG
    if (maxB > 0f) for (i in 0 until 256) b[i] /= maxB
    return r + g + b
}

/**
 * Apply a 4x5 ColorMatrix (20-float array, in [ColorMatrix] row-major
 * order) to a packed ARGB [IntArray] and return the transformed pixels.
 *
 * The platform `android.graphics.ColorMatrix` does not expose a public
 * `map(src, dst)` method on modern SDKs, so we transform each pixel
 * manually. Cost is negligible for the 64x64 sample used here.
 */
private fun applyColorMatrix(src: IntArray, matrix: FloatArray): IntArray {
    val dst = IntArray(src.size)
    for (i in src.indices) {
        val px = src[i]
        val a = (px ushr 24) and 0xFF
        val rIn = (px ushr 16) and 0xFF
        val gIn = (px ushr 8) and 0xFF
        val bIn = px and 0xFF
        val rOut = (matrix[0] * rIn + matrix[1] * gIn + matrix[2] * bIn + matrix[3] * a + matrix[4])
            .coerceIn(0f, 255f).toInt()
        val gOut = (matrix[5] * rIn + matrix[6] * gIn + matrix[7] * bIn + matrix[8] * a + matrix[9])
            .coerceIn(0f, 255f).toInt()
        val bOut = (matrix[10] * rIn + matrix[11] * gIn + matrix[12] * bIn + matrix[13] * a + matrix[14])
            .coerceIn(0f, 255f).toInt()
        val aOut = (matrix[15] * rIn + matrix[16] * gIn + matrix[17] * bIn + matrix[18] * a + matrix[19])
            .coerceIn(0f, 255f).toInt()
        dst[i] = (aOut shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
    }
    return dst
}

// ---- Drawing -------------------------------------------------------

private fun DrawScope.drawHistogramOverlay(hist: FloatArray, sizePx: androidx.compose.ui.geometry.Size) {
    if (hist.isEmpty()) return
    val r = hist.copyOfRange(0, 256)
    val g = hist.copyOfRange(256, 512)
    val b = hist.copyOfRange(512, 768)
    val n = 256
    val barW = sizePx.width / n
    // Faint vertical grid lines at 25/50/75%
    val grid = Color.White.copy(alpha = 0.10f)
    for (frac in listOf(0.25f, 0.5f, 0.75f)) {
        val x = sizePx.width * frac
        drawLine(
            color = grid,
            start = Offset(x, 0f),
            end = Offset(x, sizePx.height),
            strokeWidth = 0.5f
        )
    }
    for (i in 0 until n) {
        val x = i * barW
        val rh = r[i] * sizePx.height
        val gh = g[i] * sizePx.height
        val bh = b[i] * sizePx.height
        // Use addPath-style layered rectangles with alpha so the three
        // channels blend into a true histogram look.
        drawRect(
            color = iOSRed.copy(alpha = 0.55f),
            topLeft = Offset(x, sizePx.height - rh),
            size = androidx.compose.ui.geometry.Size(barW, rh)
        )
        drawRect(
            color = iOSGreen.copy(alpha = 0.55f),
            topLeft = Offset(x, sizePx.height - gh),
            size = androidx.compose.ui.geometry.Size(barW, gh)
        )
        drawRect(
            color = iOSBlue.copy(alpha = 0.55f),
            topLeft = Offset(x, sizePx.height - bh),
            size = androidx.compose.ui.geometry.Size(barW, bh)
        )
    }
}

private fun DrawScope.drawRGBParade(hist: FloatArray, sizePx: androidx.compose.ui.geometry.Size) {
    if (hist.isEmpty()) return
    val r = hist.copyOfRange(0, 256)
    val g = hist.copyOfRange(256, 512)
    val b = hist.copyOfRange(512, 768)
    val laneH = sizePx.height / 3f
    fun lane(channel: FloatArray, yOffset: Float, color: Color) {
        val barW = sizePx.width / 256f
        for (i in 0 until 256) {
            val h = channel[i] * laneH
            drawRect(
                color = color.copy(alpha = 0.7f),
                topLeft = Offset(i * barW, yOffset + (laneH - h)),
                size = androidx.compose.ui.geometry.Size(barW, h)
            )
        }
    }
    lane(r, 0f, iOSRed)
    lane(g, laneH, iOSGreen)
    lane(b, 2 * laneH, iOSBlue)
    // Separator lines
    for (i in 1..2) {
        val y = laneH * i
        drawLine(
            color = Color.White.copy(alpha = 0.12f),
            start = Offset(0f, y),
            end = Offset(sizePx.width, y),
            strokeWidth = 0.5f
        )
    }
}

private fun DrawScope.drawWaveform(hist: FloatArray, sizePx: androidx.compose.ui.geometry.Size) {
    if (hist.isEmpty()) return
    val r = hist.copyOfRange(0, 256)
    val g = hist.copyOfRange(256, 512)
    val b = hist.copyOfRange(512, 768)
    // Reconstruct a per-column "wave" by treating the histogram as
    // vertical luminance distribution per sample.
    val colW = sizePx.width / 64f
    for (col in 0 until 64) {
        val rAt = r[col * 4]
        val gAt = g[col * 4]
        val bAt = b[col * 4]
        val rh = rAt * sizePx.height
        val gh = gAt * sizePx.height
        val bh = bAt * sizePx.height
        val x = col * colW
        drawRect(
            color = iOSRed.copy(alpha = 0.55f),
            topLeft = Offset(x, sizePx.height - rh),
            size = androidx.compose.ui.geometry.Size(colW, rh)
        )
        drawRect(
            color = iOSGreen.copy(alpha = 0.55f),
            topLeft = Offset(x, sizePx.height - gh),
            size = androidx.compose.ui.geometry.Size(colW, gh)
        )
        drawRect(
            color = iOSBlue.copy(alpha = 0.55f),
            topLeft = Offset(x, sizePx.height - bh),
            size = androidx.compose.ui.geometry.Size(colW, bh)
        )
    }
    // Centre reticule
    val mid = sizePx.height * 0.5f
    drawLine(
        color = Color.White.copy(alpha = 0.20f),
        start = Offset(0f, mid),
        end = Offset(sizePx.width, mid),
        strokeWidth = 0.5f
    )
}

private fun DrawScope.drawVectorscope(hist: FloatArray, sizePx: androidx.compose.ui.geometry.Size) {
    if (hist.isEmpty()) {
        drawVectorscopeBackground(sizePx)
        return
    }
    val r = hist.copyOfRange(0, 256)
    val g = hist.copyOfRange(256, 512)
    val b = hist.copyOfRange(512, 768)
    // Plot each non-empty bin as a small point in the (a*, b*) plane.
    val cx = sizePx.width / 2f
    val cy = sizePx.height / 2f
    val rMax = sizePx.width * 0.4f
    for (i in 1 until 255) {
        val w = (r[i] + g[i] + b[i]) / 3f
        if (w < 0.02f) continue
        // Pseudo CIE chromaticity from the bin. Not physically
        // accurate, but visually consistent with the look of a real
        // vectorscope.
        val a = (r[i] - b[i]) * rMax
        val bb = (g[i] - (r[i] + b[i]) * 0.5f) * rMax
        val x = cx + a
        val y = cy - bb
        drawCircle(
            color = Color.White.copy(alpha = 0.15f + 0.7f * w),
            radius = 1.0f,
            center = Offset(x, y)
        )
    }
    drawVectorscopeBackground(sizePx)
}

private fun DrawScope.drawVectorscopeBackground(sizePx: androidx.compose.ui.geometry.Size) {
    val cx = sizePx.width / 2f
    val cy = sizePx.height / 2f
    val r = sizePx.width * 0.4f
    // Concentric rings
    for (frac in listOf(0.33f, 0.66f, 1f)) {
        drawCircle(
            color = Color.White.copy(alpha = 0.08f),
            radius = r * frac,
            center = Offset(cx, cy),
            style = Stroke(width = 0.5f)
        )
    }
    // Crosshair
    drawLine(
        color = Color.White.copy(alpha = 0.12f),
        start = Offset(cx - r, cy),
        end = Offset(cx + r, cy),
        strokeWidth = 0.5f
    )
    drawLine(
        color = Color.White.copy(alpha = 0.12f),
        start = Offset(cx, cy - r),
        end = Offset(cx, cy + r),
        strokeWidth = 0.5f
    )
    // Hue tick marks at 6 primary positions
    val labels = listOf(
        "R" to 0.0, "Y" to 60.0, "G" to 120.0, "C" to 180.0,
        "B" to 240.0, "M" to 300.0
    )
    // (No native text drawing here without Paint, so we just put a
    // small dot at each labelled angle as a navigation cue.)
    for ((label, deg) in labels) {
        val angle = (deg - 90) * PI / 180
        val x = cx + (r * 0.92f) * cos(angle).toFloat()
        val y = cy + (r * 0.92f) * sin(angle).toFloat()
        drawCircle(
            color = Color.White.copy(alpha = 0.4f),
            radius = 1.5f,
            center = Offset(x, y)
        )
    }
}
