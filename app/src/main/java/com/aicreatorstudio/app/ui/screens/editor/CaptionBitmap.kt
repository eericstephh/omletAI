package com.aicreatorstudio.app.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.export.CaptionRenderer
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.withTemplate

/**
 * 2026-09-19 — preview bridge to the shared [CaptionRenderer].
 *
 * Every on-screen caption preview (canvas, studio cards) is a
 * bitmap drawn by the EXACT code that burns the export — same
 * metrics, fonts, spans, pill, glow, placement and motion. There
 * is no separate Compose text implementation to drift.
 *
 * Static captions render once per (overlay, size); animated ones
 * (viral motion or entry/exit) re-render per frame. Previous
 * bitmaps are recycled on key change / dispose, so per-frame viral
 * previews never leak.
 */
@Composable
fun rememberCaptionImageBitmap(
    overlay: TextOverlay,
    style: SubtitleStyle,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    frameTimeMs: Long,
    fontScale: Float = 1f
): ImageBitmap? {
    val context = LocalContext.current
    val typefaceCache = remember { HashMap<String, Typeface?>() }
    val viral = overlay.motionKind != WordMotionKind.NONE && overlay.words.isNotEmpty()
    val animated = viral ||
        overlay.textAnimation != TextOverlayAnimation.NONE ||
        overlay.textAnimationOut != TextOverlayAnimation.NONE
    val valid = canvasWidthPx > 0 && canvasHeightPx > 0 && overlay.text.isNotBlank()
    // The key always participates in remember (even when invalid)
    // so remember-order stays stable across size/text flips.
    val key: Any = if (animated) {
        CaptionFrameKey(overlay, style, canvasWidthPx, canvasHeightPx, frameTimeMs, fontScale, valid)
    } else {
        CaptionStaticKey(overlay, style, canvasWidthPx, canvasHeightPx, fontScale, valid)
    }
    val bitmap: Bitmap? = remember(key) {
        if (!valid) null
        else runCatching {
            CaptionRenderer.render(
                context = context,
                overlay = overlay,
                style = style,
                canvasWidthPx = canvasWidthPx,
                canvasHeightPx = canvasHeightPx,
                frameTimeMs = frameTimeMs,
                typefaceCache = typefaceCache,
                fontScale = fontScale
            )
        }.getOrNull()
    }
    DisposableEffect(key) {
        onDispose { runCatching { bitmap?.recycle() } }
    }
    return remember(bitmap) { bitmap?.asImageBitmap() }
}

private data class CaptionFrameKey(
    val overlay: TextOverlay,
    val style: SubtitleStyle,
    val w: Int,
    val h: Int,
    val t: Long,
    val fontScale: Float,
    val valid: Boolean
)

private data class CaptionStaticKey(
    val overlay: TextOverlay,
    val style: SubtitleStyle,
    val w: Int,
    val h: Int,
    val fontScale: Float,
    val valid: Boolean
)

/**
 * Template preview bridge: builds a synthetic overlay from the
 * template + sample tokens (even virtual timings — this is a
 * labeled demo, never a transcript) and renders it through the
 * shared engine, so studio cards show pixel-identical styling to
 * the canvas and the export.
 */
@Composable
fun rememberTemplateImageBitmap(
    template: SubtitleTemplate,
    tokens: List<String>,
    frameTimeMs: Long,
    wordDurationMs: Int,
    canvasWidthPx: Int,
    canvasHeightPx: Int,
    fontScale: Float = 1f
): ImageBitmap? {
    val overlay = remember(template, tokens) {
        val words = tokens.mapIndexed { i, tok ->
            TranscribedWord(
                text = tok,
                startTimeMs = (i * wordDurationMs).toLong(),
                endTimeMs = ((i + 1) * wordDurationMs).toLong()
            )
        }
        TextOverlay(
            text = tokens.joinToString(" "),
            startTimeMs = 0L,
            endTimeMs = (tokens.size * wordDurationMs).toLong(),
            words = words
        ).withTemplate(template)
    }
    // Clamp into the demo window so a frozen card still lands on a
    // real word state (animated callers pass an advancing time).
    val t = frameTimeMs.coerceIn(0L, (tokens.size * wordDurationMs).toLong())
    return rememberCaptionImageBitmap(
        overlay = overlay,
        style = overlay.subtitleStyle ?: SubtitleStyle.Default,
        canvasWidthPx = canvasWidthPx,
        canvasHeightPx = canvasHeightPx,
        frameTimeMs = t,
        fontScale = fontScale
    )
}
