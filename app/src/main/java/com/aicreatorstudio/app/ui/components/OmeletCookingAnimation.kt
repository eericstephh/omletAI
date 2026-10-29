package com.aicreatorstudio.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Illustration-only palette for the cooking animation.
 *
 * Deliberately NOT the app's UI tokens: this is a flat 2D cartoon of a pan
 * cooking a film-strip omelette, so the greys/golds/oranges are art
 * direction, not semantics. They are collected here purely so the artwork
 * has one place to change and no stray literals sit in the draw code.
 */
private object OmeletIllustration {
    val Outline = Color(0xFF2B2B33)
    val Pan = Color(0xFF45454F)
    val PanInner = Color(0xFF2E2E38)
    val FilmBase = Color(0xFFF0C64F)
    val FilmDark = Color(0xFF3A3A45)
    val Reel = Color(0xFF4A4A57)
    val Accent = Color(0xFFFF8A5C)
    val Steam = Color(0xFFC9C9D6)
    val FlameOuter = Color(0xFFFFB03A)
    val FlameInner = Color(0xFFFFE08A)
    val ReelHub = Color(0xFFE8E8F0)
}

/**
 * 2026-09-06 — "OMELET MADE OF MOVIES" export animation (v2, premium).
 *
 * A cute 2D-cartoon frying pan cooking an omelette made entirely from
 * vintage filmmaking materials — 35 mm film strips, spinning film
 * reels and little bouncing film frames. The whole illustration is
 * drawn as flat vector shapes with bold dark outlines (transparent-PNG
 * sticker look), loops seamlessly over 3.6 s (first frame == last
 * frame), and the camera never moves.
 *
 * Draw order (back → front):
 *   heat shimmer → pan body → film omelette → spinning reels →
 *   bouncing film frames → sizzle sparks → handle → steam.
 *
 * Everything is phase-driven off a single `loop` value (0f..1f) with
 * sin/cos of 2π·loop, which guarantees a perfect infinite loop.
 */
@Composable
fun OmeletCookingAnimation(
    modifier: Modifier = Modifier,
    outlineColor: Color = OmeletIllustration.Outline,
    panColor: Color = OmeletIllustration.Pan,
    panInnerColor: Color = OmeletIllustration.PanInner,
    filmBaseColor: Color = OmeletIllustration.FilmBase,
    filmDarkColor: Color = OmeletIllustration.FilmDark,
    reelColor: Color = OmeletIllustration.Reel,
    accentColor: Color = OmeletIllustration.Accent,
    steamColor: Color = OmeletIllustration.Steam
) {
    val loop = rememberInfiniteTransition(label = "omelet-loop")
        .animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = LinearEasing)
            ), label = "t"
        ).value
    // Independent 0..1 oscillation for back-and-forth motions.
    val sway = rememberInfiniteTransition(label = "omelet-sway")
        .animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ), label = "sway"
        ).value

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val tau = (2.0 * PI).toFloat()

        // ---- global gentle shake of the whole scene (the pan "sizzles") ----
        val shakeX = sin(loop * tau * 3f) * w * 0.008f
        val shakeY = sin(loop * tau * 6f) * h * 0.004f
        translate(shakeX, shakeY) {
            drawScene(w, h, loop, sway, outlineColor, panColor, panInnerColor,
                filmBaseColor, filmDarkColor, reelColor, accentColor, steamColor)
        }
    }
}

private fun DrawScope.drawScene(
    w: Float, h: Float, loop: Float, sway: Float,
    outline: Color, pan: Color, panIn: Color,
    film: Color, filmDark: Color, reel: Color,
    accent: Color, steam: Color
) {
    val oW = w * 0.016f // outline width
    val tau = (2.0 * PI).toFloat()

    // ---- layout ----
    val panCx = w * 0.48f
    val panCy = h * 0.66f
    val panRx = w * 0.34f          // pan ellipse radius-x (3/4 view)
    val panRy = panRx * 0.42f      // ellipse radius-y
    val floorY = panCy + panRy * 0.55f

    // ---- 1. flame under the pan (flicker) ----
    val flames = 5
    for (i in 0 until flames) {
        val fx = panCx - panRx * 0.72f + i * (panRx * 1.44f / (flames - 1))
        val phase = loop * tau + i * 1.3f
        val fh = h * (0.05f + 0.035f * (0.5f + 0.5f * sin(phase)))
        val fc = if (i % 2 == 0) accent else OmeletIllustration.FlameOuter
        drawLine(fc.copy(alpha = 0.95f), Offset(fx, floorY + h * 0.01f),
            Offset(fx, floorY - fh), w * 0.02f, StrokeCap.Round)
        drawLine(OmeletIllustration.FlameInner.copy(alpha = 0.9f), Offset(fx, floorY + h * 0.005f),
            Offset(fx, floorY - fh * 0.5f), w * 0.009f, StrokeCap.Round)
    }
    // stove base bar
    drawRoundRect(
        color = pan.copy(alpha = 0.5f),
        topLeft = Offset(panCx - panRx * 1.1f, floorY),
        size = Size(panRx * 2.2f, h * 0.035f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.02f)
    )

    // ---- 2. steam (behind the pan, rising curls) ----
    drawSteam(loop, panCx + panRx * 0.30f, panCy - panRy * 1.1f, w, h, steam, 1f)
    drawSteam((loop + 0.33f) % 1f, panCx + panRx * 0.55f, panCy - panRy * 1.0f, w, h, steam, 0.75f)
    drawSteam((loop + 0.66f) % 1f, panCx - panRx * 0.20f, panCy - panRy * 1.15f, w, h, steam, 0.55f)

    // ---- 3. pan body (side 3/4 view) ----
    // body: rounded slab below the rim ellipse
    drawRoundRect(
        color = pan,
        topLeft = Offset(panCx - panRx, panCy - panRy * 0.2f),
        size = Size(panRx * 2f, panRy * 1.35f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(panRx * 0.22f, panRy * 0.9f)
    )
    // body outline
    drawRoundRect(
        color = outline,
        topLeft = Offset(panCx - panRx, panCy - panRy * 0.2f),
        size = Size(panRx * 2f, panRy * 1.35f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(panRx * 0.22f, panRy * 0.9f),
        style = Stroke(oW)
    )
    // rim ellipse (opening)
    drawEllipse(panCx, panCy, panRx, panRy, panIn, outline, oW)
    // inner rim highlight
    drawEllipseStroke(panCx, panCy, panRx * 0.9f, panRy * 0.82f, Color.White.copy(alpha = 0.18f), oW * 0.6f)

    // ---- 4. the FILM OMELETTE inside the pan ----
    val omCx = panCx
    val omCy = panCy + panRy * 0.05f
    val omRx = panRx * 0.72f
    val omRy = panRy * 0.72f

    // golden film-strip base (wavy fried edge) — built from arcs
    drawWavyFilmBlob(omCx, omCy, omRx, omRy, loop, film, filmDark, outline, oW * 0.8f)

    // spinning film reels as "toppings"
    drawReel(omCx - omRx * 0.30f, omCy - omRy * 0.15f, omRx * 0.42f, loop * 360f, reel, outline, oW * 0.7f)
    drawReel(omCx + omRx * 0.28f, omCy + omRy * 0.10f, omRx * 0.30f, -loop * 300f, reel, outline, oW * 0.6f)

    // draped 35 mm film strip across the omelette (wiggle)
    drawFilmStrip(omCx - omRx * 0.45f, omCy - omRy * 0.55f, omRx * 1.05f, omRy * 0.42f,
        loop, film, filmDark, outline, oW * 0.7f)

    // small bouncing film-frame pieces
    drawFramePiece(omCx - omRx * 0.05f, omCy - omRy * 0.62f, omRx * 0.26f, loop, 0f, film, filmDark, outline, oW * 0.6f, h)
    drawFramePiece(omCx + omRx * 0.52f, omCy - omRy * 0.30f, omRx * 0.22f, loop, 2.1f, filmDark, film, outline, oW * 0.6f, h)
    drawFramePiece(omCx - omRx * 0.62f, omCy + omRy * 0.18f, omRx * 0.20f, loop, 4.2f, film, filmDark, outline, oW * 0.55f, h)

    // ---- 5. sizzle sparks popping around the omelette ----
    for (i in 0 until 6) {
        val phase = (loop + i * 0.166f) % 1f
        val ang = (i * 57f + sin(phase * tau) * 20f) * (PI / 180f).toFloat()
        val rr = omRx * (0.75f + phase * 0.35f)
        val sx = omCx + cos(ang) * rr
        val sy = omCy + sin(ang) * rr * 0.6f - phase * h * 0.05f
        drawCircle(accent.copy(alpha = (1f - phase) * 0.9f), w * 0.011f * (1f + phase), Offset(sx, sy))
    }

    // ---- 6. handle (right side) ----
    val hx0 = panCx + panRx * 0.96f
    val hy0 = panCy - panRy * 0.1f
    val hx1 = panCx + panRx * 1.62f
    val hy1 = hy0 - h * 0.015f + sin(sway * tau) * h * 0.012f
    val startOff = Offset(hx0, hy0)
    val endOff = Offset(hx1, hy1)
    val outlineA = Offset(hx0 + w * 0.005f, hy0 - w * 0.026f)
    val outlineB = Offset(hx1 - w * 0.004f, hy1 - w * 0.026f)
    val outlineC = Offset(hx0 + w * 0.005f, hy0 + w * 0.026f)
    val outlineD = Offset(hx1 - w * 0.004f, hy1 + w * 0.026f)
    drawLine(pan, startOff, endOff, w * 0.055f, StrokeCap.Round)
    drawLine(outline, outlineA, outlineB, oW * 0.6f, StrokeCap.Round)
    drawLine(outline, outlineC, outlineD, oW * 0.6f, StrokeCap.Round)
    // handle tip cap
    drawCircle(pan, w * 0.032f, endOff)
    drawCircle(outline, w * 0.032f, endOff, style = Stroke(oW * 0.8f))
}

/** Golden wavy film blob — the omelette body. */
private fun DrawScope.drawWavyFilmBlob(
    cx: Float, cy: Float, rx: Float, ry: Float,
    loop: Float, film: Color, filmDark: Color, outline: Color, oW: Float
) {
    val path = Path()
    val bumps = 10
    for (i in 0..bumps) {
        val t = i.toFloat() / bumps
        val ang = t * 2f * PI.toFloat()
        val wob = 1f + 0.06f * sin(t * 6.28f * 2f + loop * 6.28f)
        val px = cx + cos(ang) * rx * wob
        val py = cy + sin(ang) * ry * wob
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, film)
    drawPath(path, outline, style = Stroke(oW))
    // film strip shading: inner dark line following the blob
    drawCircle(filmDark.copy(alpha = 0.25f), radius = rx * 0.8f,
        center = Offset(cx, cy), style = Stroke(oW * 0.7f))
}

/** A film reel: dark disc, center hub, 6 sprocket holes. */
private fun DrawScope.drawReel(
    cx: Float, cy: Float, r: Float, rotationDeg: Float,
    reelColor: Color, outline: Color, oW: Float
) {
    rotate(degrees = rotationDeg, pivot = Offset(cx, cy)) {
        drawCircle(reelColor, r, Offset(cx, cy))
        drawCircle(outline, r, Offset(cx, cy), style = Stroke(oW))
        // sprocket holes
        for (i in 0 until 6) {
            val ang = (i * 60f) * (PI / 180f).toFloat()
            drawCircle(
                Color.Black.copy(alpha = 0.55f), r * 0.16f,
                Offset(cx + cos(ang) * r * 0.58f, cy + sin(ang) * r * 0.58f)
            )
        }
        // hub
        drawCircle(OmeletIllustration.ReelHub, r * 0.22f, Offset(cx, cy))
        drawCircle(outline, r * 0.22f, Offset(cx, cy), style = Stroke(oW * 0.7f))
        drawCircle(Color.Black.copy(alpha = 0.35f), r * 0.08f, Offset(cx, cy))
    }
}

/** A 35 mm film strip with sprocket holes, gently wiggling. */
private fun DrawScope.drawFilmStrip(
    x: Float, y: Float, w: Float, hStrip: Float,
    loop: Float, film: Color, filmDark: Color, outline: Color, oW: Float
) {
    val wig = sin(loop * tau2()) * hStrip * 0.18f
    rotate(degrees = -6f + wig * 8f, pivot = Offset(x + w / 2f, y + hStrip / 2f)) {
        // strip body
        drawRoundRect(
            film, topLeft = Offset(x, y),
            size = Size(w, hStrip),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(hStrip * 0.3f)
        )
        drawRoundRect(
            outline, topLeft = Offset(x, y),
            size = Size(w, hStrip),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(hStrip * 0.3f),
            style = Stroke(oW)
        )
        // frames on the strip
        val frames = 4
        for (i in 0 until frames) {
            val fw = w * 0.16f
            val fx = x + w * 0.07f + i * (w * 0.23f)
            drawRoundRect(
                filmDark.copy(alpha = 0.8f), topLeft = Offset(fx, y + hStrip * 0.25f),
                size = Size(fw, hStrip * 0.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(fw * 0.15f)
            )
        }
        // sprocket holes top & bottom
        val holes = 9
        for (i in 0 until holes) {
            val hx = x + w * 0.05f + i * (w * 0.9f / (holes - 1))
            drawCircle(Color.Black.copy(alpha = 0.5f), hStrip * 0.09f,
                Offset(hx, y + hStrip * 0.13f))
            drawCircle(Color.Black.copy(alpha = 0.5f), hStrip * 0.09f,
                Offset(hx, y + hStrip * 0.87f))
        }
    }
}

/** A little film-frame piece bouncing as it cooks. */
private fun DrawScope.drawFramePiece(
    cx: Float, cy: Float, r: Float, loop: Float, phase: Float,
    body: Color, inner: Color, outline: Color, oW: Float, h: Float
) {
    val bounce = sin((loop * tau2()) + phase).coerceAtLeast(0f)
    val rot = sin((loop * tau2()) + phase) * 14f
    translate(cx, cy - bounce * h * 0.02f) {
        rotate(degrees = rot) {
            drawRoundRect(
                body, topLeft = Offset(-r, -r * 0.7f),
                size = Size(r * 2f, r * 1.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.2f)
            )
            drawRoundRect(
                outline, topLeft = Offset(-r, -r * 0.7f),
                size = Size(r * 2f, r * 1.4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.2f),
                style = Stroke(oW)
            )
            drawRoundRect(
                inner.copy(alpha = 0.7f), topLeft = Offset(-r * 0.55f, -r * 0.38f),
                size = Size(r * 1.1f, r * 0.76f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.12f)
            )
        }
    }
}

/** A rising steam curl. */
private fun DrawScope.drawSteam(
    phase: Float, baseX: Float, baseY: Float, w: Float, h: Float, color: Color, strength: Float
) {
    val rise = phase * h * 0.28f
    val alpha = (1f - phase) * 0.6f * strength
    val sway = sin(phase * 6.28f) * w * 0.03f
    val path = Path().apply {
        moveTo(baseX, baseY)
        cubicTo(
            baseX + sway, baseY - rise * 0.4f,
            baseX - sway, baseY - rise * 0.7f,
            baseX + sway * 0.6f, baseY - rise
        )
    }
    drawPath(path, color.copy(alpha = alpha),
        style = Stroke(w * 0.022f, cap = StrokeCap.Round))
    // puff head
    drawCircle(color.copy(alpha = alpha * 0.8f), w * 0.018f * (0.6f + phase),
        Offset(baseX + sway * 0.6f, baseY - rise))
}

private fun DrawScope.drawEllipse(
    cx: Float, cy: Float, rx: Float, ry: Float,
    fill: Color, outline: Color, oW: Float
) {
    drawOval(fill, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f))
    drawOval(outline, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f), style = Stroke(oW))
}

private fun DrawScope.drawEllipseStroke(
    cx: Float, cy: Float, rx: Float, ry: Float, color: Color, width: Float
) {
    drawOval(color, Offset(cx - rx, cy - ry), Size(rx * 2f, ry * 2f), style = Stroke(width))
}

private fun DrawScope.drawOval(
    color: Color, topLeft: Offset, size: Size, style: Stroke = Stroke()
) {
    drawRoundRect(
        color = color, topLeft = topLeft, size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f, size.height / 2f),
        style = style
    )
}

private fun tau2(): Float = (2.0 * PI).toFloat()
