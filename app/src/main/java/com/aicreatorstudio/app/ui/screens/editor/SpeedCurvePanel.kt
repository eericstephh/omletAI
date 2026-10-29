package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedCurvePreset
import com.aicreatorstudio.app.model.SpeedPoint
import com.aicreatorstudio.app.model.util.SpeedCurveInterpolator
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSOrange
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.iOSRed
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Speed Ramping / Curves editor.
 *
 * ## What the user is manipulating
 *
 * A [SpeedCurve] is a list of knots over the clip's **source** timeline —
 * see `SpeedCurve`'s KDoc for why it is not output time. This panel is a
 * direct manipulation surface for that list: the X axis is the clip from
 * 0% to 100% of its source, the Y axis is the rate, and each dot is a
 * knot the user can drag.
 *
 * ## Why the panel shows the *duration*, prominently
 *
 * `∫dt/v` is a harmonic average, not an arithmetic one, so a ramp that
 * "looks balanced" can lengthen a clip by 30%. Users read a speed graph
 * as if 2× and 0.5× cancel out, and then the clip is the wrong length
 * and every caption after it is late. So the resulting duration is
 * computed with [SpeedCurveInterpolator] — the same engine the export
 * uses — and shown live, next to the source duration it is replacing.
 * That number is the feature's most important feedback, which is why it
 * is at the top rather than buried in a footer.
 *
 * ## Gestures
 *
 * - **Drag a dot** — move that knot. Y is the rate; X moves in time
 *   except for the two end knots, which are pinned to 0% and 100% so the
 *   ramp always covers the whole clip.
 * - **Tap empty graph** — add a knot there.
 * - **Double-tap a dot** — remove it. The two end knots cannot be
 *   removed: a curve needs ends.
 *
 * A drag or tap that would put two knots on the same X is resolved by
 * `SpeedCurve.sanitized()`, which keeps the later one, so dragging a
 * knot past its neighbour is legal and does the obvious thing rather
 * than being blocked.
 *
 * ## Live preview note
 *
 * The **shape** is honoured by the export engine through a curve-backed
 * Media3 `SpeedProvider` (see `SpeedCurveSpeedProvider`). The live
 * preview player is a single-item `ExoPlayer` retimed by
 * `PlaybackParameters`, which applies one constant rate and cannot
 * follow a ramp — so the preview shows the curve's effective average
 * rate and says so, in the same spirit as the matting panel's chip. It
 * is not silent: the badge below the graph states the current preview
 * rate explicitly.
 */
@Composable
fun SpeedCurvePanel(
    curve: SpeedCurve?,
    sourceDurationMs: Long,
    onCurveChanged: (SpeedCurve) -> Unit,
    onResetCurve: () -> Unit,
    modifier: Modifier = Modifier
) {
    val active = curve ?: SpeedCurve.Flat
    // `sanitized()` once, here, so every child composable and every
    // gesture handler reads the same normalised list. The model's
    // sanitising is idempotent, so re-sanitising on each edit is safe.
    val points = remember(active) { active.sanitized().points }

    // The predicted output length. Recomputed per edit — it is a
    // few-hundred-entry cumulative sum, which is nothing next to a
    // recomposition, and caching it would be a correctness risk the
    // moment an input is missed from the key.
    val outputDurationMs = remember(points, sourceDurationMs) {
        SpeedCurveInterpolator.outputDurationUs(
            sourceDurationMs.coerceAtLeast(0L) * 1_000L,
            active.takeIf { it.isActive }
        ) / 1_000L
    }

    Column(modifier = modifier.fillMaxWidth()) {

        // ---- Header + live duration ------------------------------------
        iOSPanelHeader(title = "Speed Ramping", eyebrow = "Curve")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "منبع: ${formatDuration(sourceDurationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = LabelSecondary
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = "خروجی: ${formatDuration(outputDurationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (abs(outputDurationMs - sourceDurationMs) > 50L) iOSOrange
                else LabelSecondary,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = durationSummary(sourceDurationMs, outputDurationMs),
            style = MaterialTheme.typography.labelSmall,
            color = LabelSecondary
        )

        Spacer(Modifier.height(12.dp))

        // ---- The graph -------------------------------------------------
        SpeedCurveGraph(
            points = points,
            onPointsChanged = { newPoints ->
                // Any edit drops the preset: a modified HERO is not
                // HERO, and leaving the chip lit would misreport state.
                onCurveChanged(
                    active.copy(points = newPoints, preset = SpeedCurvePreset.CUSTOM)
                )
            }
        )

        Spacer(Modifier.height(8.dp))

        // ---- Preview-rate disclosure ----------------------------------
        val previewRate = previewRateFor(sourceDurationMs, outputDurationMs, active)
        Text(
            text = if (active.isActive) {
                "پیش‌نمایش: ${formatRate(previewRate)}× (میانگین) — شیب منحنی در خروجی اعمال می‌شود."
            } else {
                "منحنی خنثی است؛ سرعت ثابت فعلی بدون تغییر باقی می‌ماند."
            },
            style = MaterialTheme.typography.labelSmall,
            color = LabelSecondary
        )

        Spacer(Modifier.height(14.dp))

        // ---- Presets ---------------------------------------------------
        Text(
            text = "پیش‌تنظیم",
            style = MaterialTheme.typography.labelSmall,
            color = LabelSecondary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        SpeedCurvePresetRows(
            current = active.preset,
            isModified = active.isActive,
            onPresetSelected = { preset ->
                onCurveChanged(SpeedCurve.ofPreset(preset))
            }
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SpeedCurveActionChip(
                label = "بازنشانی",
                onClick = onResetCurve,
                modifier = Modifier.weight(1f)
            )
            SpeedCurveActionChip(
                label = "+ نقطه",
                enabled = points.size < MAX_KNOTS,
                onClick = {
                    // Insert at the widest gap, so a tap-free "add"
                    // still produces something useful.
                    val at = widestGapMidpoint(points)
                    onCurveChanged(
                        active.copy(
                            points = points + SpeedPoint(at, averageSpeedAt(points)),
                            preset = SpeedCurvePreset.CUSTOM
                        )
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "برای افزودن نقطه روی نمودار بزنید، برای جابه‌جایی بکشید، و برای حذف دوبار بزنید.",
            style = MaterialTheme.typography.labelSmall,
            color = LabelSecondary
        )
    }
}

// ======================================================================
// The graph
// ======================================================================

/**
 * Height of the drawing area. Kept as a constant rather than a
 * `BoxWithConstraints` read because the gesture maths needs the same
 * number the layout used, and a constraint read would make the mapping
 * depend on composition order.
 */
private val GRAPH_HEIGHT = 168.dp

/**
 * The interactive curve canvas.
 *
 * Kept as its own composable so the gesture state does not recompose the
 * header, duration readout and preset rows on every drag frame — a drag
 * emits an update per pointer move, and only this canvas and the
 * duration text actually depend on it.
 */
@Composable
private fun SpeedCurveGraph(
    points: List<SpeedPoint>,
    onPointsChanged: (List<SpeedPoint>) -> Unit
) {
    // Which knot the current drag grabbed, or -1. Held in Compose state
    // rather than remembered per-gesture so `detectDragGestures`'s
    // onDragStart → onDrag sequence can share it.
    var draggingIndex by remember { mutableStateOf(-1) }
    // The working copy during a drag. Holding it here (rather than
    // re-reading the incoming `points` each move) keeps a fast drag from
    // fighting an in-flight recomposition of the parent.
    var working by remember { mutableStateOf(points) }
    // Track the incoming list so an edit from elsewhere (a preset tap,
    // a reset) is picked up, but never mid-drag.
    if (draggingIndex < 0 && working != points) {
        working = points
    }
    val displayed = if (draggingIndex >= 0) working else points

    val gridColor = HairlineSubtle
    val curveColor = iOSBlue
    val oneXColor = LabelSecondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GRAPH_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(GRAPH_HEIGHT)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            // Remove the nearest *interior* knot.
                            val idx = nearestKnot(displayed, offset, size.width.toFloat(), size.height.toFloat())
                            if (idx > 0 && idx < displayed.size - 1) {
                                onPointsChanged(displayed.filterIndexed { i, _ -> i != idx })
                            }
                        },
                        onTap = { offset ->
                            if (displayed.size >= MAX_KNOTS) return@detectTapGestures
                            // A tap near an existing knot would duplicate
                            // it, so treat that as a no-op instead of
                            // creating a tangle.
                            val near = nearestKnot(displayed, offset, size.width.toFloat(), size.height.toFloat())
                            if (near >= 0) return@detectTapGestures
                            val t = xToTime(offset.x, size.width.toFloat())
                            val v = yToSpeed(offset.y, size.height.toFloat())
                            onPointsChanged(
                                (displayed + SpeedPoint(t, v)).sortedBy { it.normalizedTime }
                            )
                        }
                    )
                }
                .pointerInput(displayed) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            draggingIndex = nearestKnot(
                                displayed, offset, size.width.toFloat(), size.height.toFloat()
                            )
                            working = displayed
                        },
                        onDragEnd = {
                            if (draggingIndex >= 0) {
                                onPointsChanged(working)
                            }
                            draggingIndex = -1
                        },
                        onDragCancel = {
                            draggingIndex = -1
                        },
                        onDrag = { change, _ ->
                            val i = draggingIndex
                            if (i < 0) return@detectDragGestures
                            change.consume()
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val pos = change.position
                            val rawTime = xToTime(pos.x, w)
                            val speed = yToSpeed(pos.y, h)
                            val isFirst = i == 0
                            val isLast = i == working.size - 1
                            // End knots stay pinned in time so the ramp
                            // always spans the whole clip; only their
                            // rate is draggable.
                            val time = when {
                                isFirst -> 0f
                                isLast -> 1f
                                else -> rawTime
                            }
                            val updated = working.toMutableList()
                            updated[i] = SpeedPoint(time, speed)
                            // Sort so the point's own index tracks the
                            // user's finger when they drag past a
                            // neighbour, and re-find which index it
                            // landed on.
                            val sorted = updated.sortedBy { it.normalizedTime }
                            working = sorted
                            draggingIndex = sorted.indexOfFirst {
                                it.normalizedTime == time && it.speed == speed
                            }.takeIf { it >= 0 } ?: i
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height

            // ---- horizontal grid at the octave rates ----------------
            // 0.25 / 0.5 / 1 / 2 / 4 are the rates that mean something
            // to an editor, so they are the lines worth drawing. A
            // uniform grid over the full 0.1..10 range would put the
            // interesting landmarks at uneven, unmemorable heights.
            GRID_RATES.forEach { rate ->
                val y = speedToY(rate, h)
                val isOneX = rate == 1f
                drawLine(
                    color = if (isOneX) oneXColor.copy(alpha = 0.55f) else gridColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = if (isOneX) 1.6f else 1f,
                    pathEffect = if (isOneX) {
                        PathEffect.dashPathEffect(floatArrayOf(9f, 7f))
                    } else {
                        null
                    }
                )
            }

            // ---- vertical guides at 25/50/75% ----------------------
            listOf(0.25f, 0.5f, 0.75f).forEach { t ->
                val x = t * w
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
            }

            // ---- the ramped region, filled under the curve ---------
            // Tinted by whether the curve speeds the clip up (red-ish,
            // shorter) or slows it down (green-ish, longer), which
            // matches the timeline-length readout above. Drawn as a
            // band around the 1x line so the area *above* 1x and the
            // area *below* it can be different colours — a single fill
            // from the baseline would encode nothing about direction.
            drawRampedBand(
                points = displayed,
                width = w,
                height = h,
                upColor = iOSRed.copy(alpha = 0.16f),
                downColor = iOSGreen.copy(alpha = 0.16f),
                oneXY = speedToY(1f, h)
            )

            // ---- the smooth curve ---------------------------------
            // Sampled per pixel rather than drawn as straight segments
            // between knots, so the rendering reflects the *continuous*
            // ramp the user is describing. The baked curve is the
            // staircase Media3 consumes (see the interpolator's KDoc) —
            // drawing that staircase here would look like a bug.
            val path = Path()
            val samples = (w.toInt().coerceAtLeast(2))
            for (i in 0..samples) {
                val t = i.toFloat() / samples
                val v = speedAt(points, t)
                val x = t * w
                val y = speedToY(v, h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = curveColor,
                style = Stroke(width = 3f)
            )

            // ---- the knots ----------------------------------------
            displayed.forEachIndexed { index, p ->
                val cx = p.normalizedTime * w
                val cy = speedToY(p.speed, h)
                val isEdge = index == 0 || index == displayed.size - 1
                val isHeld = index == draggingIndex
                // Halo first, so the dot reads as grabbable.
                drawCircle(
                    color = if (isHeld) iOSPurple.copy(alpha = 0.45f)
                    else iOSBlue.copy(alpha = 0.20f),
                    radius = if (isHeld) 15f else 11f,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = if (isEdge) iOSOrange else Color.White,
                    radius = if (isHeld) 8f else 6.5f,
                    center = Offset(cx, cy)
                )
                drawCircle(
                    color = curveColor,
                    radius = if (isHeld) 5f else 3.5f,
                    center = Offset(cx, cy)
                )
            }
        }
    }
}

// ======================================================================
// Y-axis mapping
// ======================================================================

private const val GRAPH_MIN_SPEED = 0.125f
private const val GRAPH_MAX_SPEED = 8f

/**
 * Multiplicative, so the axis is symmetric about 1×: the bounds are
 * `2^-3` and `2^+3`, so doubling and halving mirror each other exactly
 * and the dashed `1×` reference line lands dead centre. An asymmetric
 * window (0.25 to 8 would be `-2..+3`) pushes `1×` to 60% of the height,
 * which reads as a graph biased toward slow motion and puts the
 * reference line where the eye does not expect it.
 *
 * `internal` rather than `private` because the drag gesture's
 * round-trip (`speedToY` then `yToSpeed`) is exactly the behaviour worth
 * testing, and a `private` function cannot be reached from the test
 * source set. See [SpeedCurvePanelMathTest].
 */
internal fun speedToY(speed: Float, height: Float): Float {
    val clamped = speed.coerceIn(GRAPH_MIN_SPEED, GRAPH_MAX_SPEED)
    val lo = log2(GRAPH_MIN_SPEED)
    val hi = log2(GRAPH_MAX_SPEED)
    val f = (log2(clamped) - lo) / (hi - lo)
    // Screen Y grows downward; faster must be higher.
    return height * (1f - f)
}

/** Inverse of [speedToY]. See its KDoc for why this is `internal`. */
internal fun yToSpeed(y: Float, height: Float): Float {
    val lo = log2(GRAPH_MIN_SPEED)
    val hi = log2(GRAPH_MAX_SPEED)
    val f = (1f - (y / height).coerceIn(0f, 1f))
    return exp2(lo + f * (hi - lo)).coerceIn(SpeedCurve.MIN_SPEED, SpeedCurve.MAX_SPEED)
}

private fun log2(x: Float): Float = (kotlin.math.ln(x.toDouble()) / kotlin.math.ln(2.0)).toFloat()
private fun exp2(x: Float): Float = 2.0.pow(x.toDouble()).toFloat()

private fun xToTime(x: Float, width: Float): Float =
    (x / width).coerceIn(0f, 1f)

/** The octave landmarks drawn as horizontal guides. */
private val GRID_RATES = listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)

/**
 * Draw the region between the curve and the 1× line, split by direction.
 *
 * Above 1× the clip is being sped up, so the fill is warm; below 1× it is
 * slowed down, so the fill is cool. The two are accumulated into one
 * path each and drawn in one call apiece, so a 400-pixel sweep is two
 * draw calls rather than four hundred.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRampedBand(
    points: List<SpeedPoint>,
    width: Float,
    height: Float,
    upColor: Color,
    downColor: Color,
    oneXY: Float
) {
    if (points.isEmpty()) return
    val samples = width.toInt().coerceAtLeast(2)
    var aboveStart = -1
    var belowStart = -1

    fun flushAbove(from: Int, to: Int) {
        if (from < 0 || to <= from) return
        val p = Path()
        p.moveTo(from.toFloat(), oneXY)
        for (i in from..to) {
            val t = i.toFloat() / samples
            p.lineTo(i.toFloat(), speedToY(speedAt(points, t), height))
        }
        p.lineTo(to.toFloat(), oneXY)
        p.close()
        drawPath(p, upColor)
    }

    fun flushBelow(from: Int, to: Int) {
        if (from < 0 || to <= from) return
        val p = Path()
        p.moveTo(from.toFloat(), oneXY)
        for (i in from..to) {
            val t = i.toFloat() / samples
            p.lineTo(i.toFloat(), speedToY(speedAt(points, t), height))
        }
        p.lineTo(to.toFloat(), oneXY)
        p.close()
        drawPath(p, downColor)
    }

    for (i in 0..samples) {
        val v = speedAt(points, i.toFloat() / samples)
        if (v > 1.001f) {
            if (aboveStart < 0) aboveStart = i
            flushBelow(belowStart, i)
            belowStart = -1
        } else if (v < 0.999f) {
            if (belowStart < 0) belowStart = i
            flushAbove(aboveStart, i)
            aboveStart = -1
        } else {
            flushAbove(aboveStart, i)
            aboveStart = -1
            flushBelow(belowStart, i)
            belowStart = -1
        }
    }
    flushAbove(aboveStart, samples)
    flushBelow(belowStart, samples)
}

// ======================================================================
// Small helpers, all pure and unit-tested in SpeedCurvePanelMathTest
// ======================================================================

/** Knots are capped so the graph stays readable and the JSON blob stays small. */
internal const val MAX_KNOTS = 12

/**
 * Linear speed at source-normalised [t] over [points].
 *
 * **A deliberate duplicate of `SpeedCurve.speedAt`.** The panel needs the
 * value for a knot list it already sanitised, and rebuilding a
 * `SpeedCurve` per pixel would allocate a list and re-sort it 400 times
 * per frame. It is `internal` and covered by `SpeedCurvePanelMathTest`
 * so the two cannot drift silently.
 */
internal fun speedAt(points: List<SpeedPoint>, t: Float): Float {
    if (points.isEmpty()) return 1f
    if (points.size == 1) return points[0].speed
    val clamped = t.coerceIn(0f, 1f)
    if (clamped <= points.first().normalizedTime) return points.first().speed
    if (clamped >= points.last().normalizedTime) return points.last().speed
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        if (clamped >= a.normalizedTime && clamped <= b.normalizedTime) {
            val span = b.normalizedTime - a.normalizedTime
            if (span <= 0f) return b.speed
            return a.speed + (b.speed - a.speed) * ((clamped - a.normalizedTime) / span)
        }
    }
    return points.last().speed
}

/**
 * Index of the knot nearest to a tap/drag [offset], or `-1` when nothing
 * is within [HIT_RADIUS_PX].
 *
 * Returns `-1` rather than the closest knot unconditionally because the
 * tap handler uses this both to "grab" a knot and to decide whether a tap
 * is "on" one — and a tap in empty space must add a point, not start
 * dragging whichever knot happens to be nearest.
 */
internal fun nearestKnot(
    points: List<SpeedPoint>,
    offset: Offset,
    width: Float,
    height: Float,
    hitRadiusPx: Float = HIT_RADIUS_PX
): Int {
    if (width <= 0f || height <= 0f) return -1
    var best = -1
    var bestDist = hitRadiusPx * hitRadiusPx
    points.forEachIndexed { index, p ->
        val dx = p.normalizedTime * width - offset.x
        val dy = speedToY(p.speed, height) - offset.y
        val d = dx * dx + dy * dy
        if (d <= bestDist) {
            bestDist = d
            best = index
        }
    }
    return best
}

/** 24dp-ish at typical densities; the halo drawn around a knot matches it. */
internal const val HIT_RADIUS_PX = 34f

/**
 * Midpoint of the widest gap between adjacent knots — where the "+ point"
 * button inserts.
 *
 * The widest gap is chosen so repeated presses spread out evenly instead
 * of piling knots on top of each other.
 */
internal fun widestGapMidpoint(points: List<SpeedPoint>): Float {
    if (points.isEmpty()) return 0.5f
    if (points.size == 1) return if (points[0].normalizedTime < 0.5f) 1f else 0f
    var bestMid = 0.5f
    var bestSpan = -1f
    for (i in 0 until points.size - 1) {
        val span = points[i + 1].normalizedTime - points[i].normalizedTime
        if (span > bestSpan) {
            bestSpan = span
            bestMid = (points[i].normalizedTime + points[i + 1].normalizedTime) / 2f
        }
    }
    return bestMid.coerceIn(0f, 1f)
}

/** Mean of the knot rates — a sensible starting height for a new knot. */
internal fun averageSpeedAt(points: List<SpeedPoint>): Float {
    if (points.isEmpty()) return 1f
    return (points.sumOf { it.speed.toDouble() } / points.size)
        .toFloat()
        .coerceIn(SpeedCurve.MIN_SPEED, SpeedCurve.MAX_SPEED)
}

/**
 * The single constant rate the live preview can actually apply, given a
 * ramp: `source / output`. `1f` for an inactive curve.
 */
internal fun previewRateFor(
    sourceDurationMs: Long,
    outputDurationMs: Long,
    curve: SpeedCurve
): Float {
    if (!curve.isActive || outputDurationMs <= 0L || sourceDurationMs <= 0L) return 1f
    return (sourceDurationMs.toDouble() / outputDurationMs.toDouble()).toFloat()
}

/**
 * One line explaining the length change, or a neutral statement when
 * there isn't one. Kept here (rather than inline in the composable) so
 * the wording and the threshold are testable.
 */
internal fun durationSummary(sourceDurationMs: Long, outputDurationMs: Long): String {
    val delta = outputDurationMs - sourceDurationMs
    if (sourceDurationMs <= 0L) return "طول منبع نامشخص است."
    val pct = (delta.toDouble() / sourceDurationMs.toDouble() * 100.0).roundToInt()
    return when {
        pct == 0 -> "طول کلیپ تغییر نمی‌کند."
        pct > 0 -> "کلیپ $pct٪ طولانی‌تر می‌شود (حرکت آهسته غالب است)."
        else -> "کلیپ ${-pct}٪ کوتاه‌تر می‌شود (حرکت سریع غالب است)."
    }
}

/** `mm:ss.d` — short, because it sits in a dense header row. */
internal fun formatDuration(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val tenths = (safe % 1000L) / 100L
    return "%d:%02d.%d".format(minutes, seconds, tenths)
}

/** `0.25x` / `2x` — trims a trailing `.0` so the common cases read cleanly. */
internal fun formatRate(rate: Float): String {
    if (!rate.isFinite()) return "1"
    val rounded = (rate * 100f).roundToInt() / 100f
    return if (abs(rounded - rounded.roundToInt()) < 0.001f) {
        rounded.roundToInt().toString()
    } else {
        rounded.toString()
    }
}

// ======================================================================
// Presets + action chips
// ======================================================================

private val CURVE_PRESETS = listOf(
    SpeedCurvePreset.HERO,
    SpeedCurvePreset.BULLET,
    SpeedCurvePreset.MONTAGE,
    SpeedCurvePreset.FLASH_IN,
    SpeedCurvePreset.FLASH_OUT
)

private fun presetLabel(preset: SpeedCurvePreset): String = when (preset) {
    SpeedCurvePreset.HERO -> "Hero"
    SpeedCurvePreset.BULLET -> "Bullet"
    SpeedCurvePreset.MONTAGE -> "Montage"
    SpeedCurvePreset.FLASH_IN -> "Flash In"
    SpeedCurvePreset.FLASH_OUT -> "Flash Out"
    SpeedCurvePreset.CUSTOM -> "Custom"
}

/**
 * Two rows of preset chips. Two rows rather than a `LazyRow` because
 * five short labels fit on a phone at two per row and the whole set is
 * then visible without a scroll — a preset the user cannot see is a
 * preset they will not use.
 */
@Composable
private fun SpeedCurvePresetRows(
    current: SpeedCurvePreset,
    isModified: Boolean,
    onPresetSelected: (SpeedCurvePreset) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CURVE_PRESETS.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { preset ->
                    SpeedCurvePresetChip(
                        label = presetLabel(preset),
                        // A preset is only "selected" when the curve is
                        // still exactly that preset; once a knot moves
                        // the state becomes CUSTOM and no chip lights.
                        isSelected = !isModified && current == preset,
                        onClick = { onPresetSelected(preset) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // Pad the last row so its chips keep the same width as
                // the full rows above.
                repeat(3 - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SpeedCurvePresetChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) iOSBlue.copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(
                1.dp,
                if (isSelected) iOSBlue.copy(alpha = 0.55f) else HairlineSubtle,
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (isSelected) iOSBlue else LabelPrimary,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun SpeedCurveActionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.05f else 0.02f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) LabelPrimary else LabelSecondary
        )
    }
}
