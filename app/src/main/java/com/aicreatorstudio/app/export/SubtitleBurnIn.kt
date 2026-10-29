package com.aicreatorstudio.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlaySettings
import androidx.compose.ui.text.font.FontWeight
import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.TextOverlayAnimation

/**
 * 2026-09-07 BURN-IN FIX — bakes subtitle overlays into the exported
 * MP4. Until now every subtitle was preview-only (the old engine doc
 * openly said "Text / subtitles are not burned in"), so the whole
 * Subs pipeline silently lost its output at export time.
 *
 * ## How it works
 *
 * Media3's `OverlayEffect` composites [TextureOverlay] bitmaps over
 * every output frame on its GL thread. ONE [DynamicSubtitleOverlay]
 * carries the whole subtitle track: `getBitmap(us)` finds the
 * caption(s) whose window contains the playhead and returns their
 * rendered bitmap — transparent outside every window.
 *
 * (2026-09-19: previously one overlay per caption; that breaks
 * Media3's 15-overlays-per-OverlayEffect ceiling on any video with
 * more than 15 captions. The per-caption renderer survives as
 * [SubtitleWindowOverlay]; it is simply no longer handed to the
 * effect directly.)
 *
 * ## Coordinate mapping
 *
 * Overlay windows are stored in TIMELINE ms. The exported segment
 * starts at the clip's `timelineStartMs` on the timeline and at
 * `sourceStartMs` in the source; with the segment's speed factor the
 * timeline→output mapping is exactly
 * `outputMs = overlayTimelineMs - clipTimelineStartMs`
 * (the trim offset cancels against the clip-in point because the
 * Transformer's ClippingConfiguration already starts there).
 *
 * ## Rendering fidelity
 *
 * All pixels come from the shared [CaptionRenderer] — the exact
 * same code (metrics, fonts, spans, pill, glow, placement,
 * entrance/exit) the canvas preview and the studio panel use, only
 * at full output resolution. Preview and export cannot drift apart
 * by construction.
 */
@UnstableApi
internal object SubtitleBurnInFactory {

    /**
     * 2026-09-19 SINGLE-DYNAMIC-OVERLAY — build AT MOST ONE
     * [TextureOverlay] for the whole subtitle track, no matter how
     * many captions it holds (1 overlay slot instead of N — the 15
     * overlay ceiling can never be hit by subtitles again).
     *
     * Every renderer is REBASED into output time
     * (`timelineMs - clipTimelineStartMs` applied to the segment
     * window AND to each word): the shared renderer and
     * [com.aicreatorstudio.app.ai.WordMotionEngine] then see one
     * consistent time base however many clips the project holds.
     * Single-clip projects at timeline 0 are bit-identical to the
     * old mapping.
     *
     * @param context application context (font loading)
     * @param textOverlays the project's overlays (timeline ms)
     * @param clipTimelineStartMs timeline position of the exported clip
     * @param outputWidthPx output frame width
     * @param outputHeightPx output frame height
     * @param outputDurationMs output duration of the segment (post-speed)
     */
    fun createOverlays(
        context: Context,
        textOverlays: List<TextOverlay>,
        clipTimelineStartMs: Long,
        outputWidthPx: Int,
        outputHeightPx: Int,
        outputDurationMs: Long
    ): List<androidx.media3.effect.BitmapOverlay> {
        // PHASE A (Persistence Integrity) — `subtitleSource != null`
        // used to be the gate, so every ordinary text / paragraph /
        // watermark overlay was silently dropped from the export while
        // still showing on screen. The shared [CaptionRenderer] already
        // renders any [TextOverlay] (colour, size, font, position,
        // stroke/shadow/background, animation), and the whole track is
        // still collapsed into ONE overlay slot by
        // [DynamicSubtitleOverlay], so including decorative text costs
        // no extra slots and cannot hit the 15-overlay ceiling.
        //
        // Blank-text overlays are still skipped: they render nothing.
        val captions = textOverlays.filter { it.text.isNotBlank() }
        if (captions.isEmpty() || outputWidthPx <= 0 || outputHeightPx <= 0) {
            return emptyList()
        }
        val typefaceCache = HashMap<String, Typeface?>()
        val renderers = captions.mapNotNull { overlay ->
            val startUs = (overlay.startTimeMs - clipTimelineStartMs) * 1000L
            val endUs = (overlay.endTimeMs - clipTimelineStartMs) * 1000L
            // Outside the exported segment's output window → skip.
            if (endUs <= 0L || startUs >= outputDurationMs * 1000L || endUs <= startUs) {
                return@mapNotNull null
            }
            // Rebase window + words into output time (see KDoc).
            val outOverlay = overlay.copy(
                startTimeMs = (startUs.coerceAtLeast(0L) / 1000L),
                endTimeMs = (endUs / 1000L),
                words = overlay.words.map { w ->
                    w.copy(
                        startTimeMs = w.startTimeMs - clipTimelineStartMs,
                        endTimeMs = w.endTimeMs - clipTimelineStartMs
                    )
                }
            )
            SubtitleWindowOverlay(
                context = context,
                overlay = outOverlay,
                startUs = startUs.coerceAtLeast(0L),
                endUs = endUs,
                outputWidthPx = outputWidthPx,
                outputHeightPx = outputHeightPx,
                typefaceCache = typefaceCache
            )
        }
        if (renderers.isEmpty()) return emptyList()
        // Exactly one overlay no matter the caption count.
        return listOf(
            DynamicSubtitleOverlay(
                renderers = renderers,
                outputWidthPx = outputWidthPx,
                outputHeightPx = outputHeightPx
            )
        )
    }
}

/**
 * PHASE A (Persistence Integrity) — the style a [TextOverlay] burns
 * with, resolving the two storage conventions into one.
 *
 * An overlay carries its look in two places:
 *  - `subtitleStyle` — the typed [SubtitleStyle] the subtitle / studio
 *    panels write. Decorative (Add Text / Paragraph) overlays usually
 *    leave it `null` and keep their look on the overlay itself
 *    (`color`, `fontSizeSp`, `fontFamily`, `fontWeight`, `position`,
 *    `backgroundKind`).
 *
 * 2026-10-16 QA PARITY FIX — a real `subtitleStyle` always wins, and
 * when it is `null` the two overlay KINDS resolve differently, so the
 * burn has to follow the preview exactly:
 *
 *  - a SUBTITLE (`subtitleSource != null`: AI transcription or SRT
 *    import) is drawn on the canvas with `subtitleStyle
 *    ?: SubtitleStyle.Default` (`EditorScreen`'s caption branch), so the
 *    burn uses [SubtitleStyle.Default] too. Before this fix a freshly
 *    transcribed caption previewed as bold, stroked text in a black
 *    pill anchored at the bottom and exported as plain non-bold text,
 *    no stroke, no pill, centred in the frame.
 *  - a DECORATIVE Add-Text / Paragraph overlay keeps its look on the
 *    overlay itself ([decorativeExportStyle]). Falling back to
 *    [SubtitleStyle.Default] there would burn white 24sp bottom-centred
 *    text — not what the user typed.
 */
internal fun TextOverlay.exportStyle(): SubtitleStyle = subtitleStyle ?: when {
    subtitleSource != null -> SubtitleStyle.Default
    else -> decorativeExportStyle()
}

/**
 * Style derived from a DECORATIVE overlay's own fields — the
 * [exportStyle] fallback for Add-Text / Paragraph overlays, which store
 * their look on the overlay rather than in a `subtitleStyle`.
 *
 * Kept framework-free so the bridge is verifiable on the JVM.
 */
private fun TextOverlay.decorativeExportStyle(): SubtitleStyle = SubtitleStyle(
    fontFamily = fontFamily,
    fontSizeSp = fontSizeSp,
    textColorArgb = color.convertToArgb(),
    bold = fontWeight >= FontWeight.SemiBold,
    fontWeightValue = when (fontWeight) {
        FontWeight.Bold -> 700
        FontWeight.SemiBold -> 600
        FontWeight.Medium -> 500
        FontWeight.Light -> 300
        FontWeight.Thin -> 200
        else -> 400
    },
    // Decorative overlays place themselves by `position`, which the
    // renderer applies as the drag delta off this anchor — a centred
    // anchor keeps that drag 1:1 with the on-screen box.
    horizontalAlignment = SubtitleAlignmentH.CENTER,
    verticalAlignment = SubtitleAlignmentV.CENTER,
    positionX = position.x,
    positionY = position.y,
    // The legacy overlay fields express unit size, not a max width.
    maxTextWidthFraction = 1f,
    // No stroke/shadow/pill unless the overlay asked for one: the
    // preview's free-position path draws none of these by default.
    strokeEnabled = false,
    shadowEnabled = false,
    backgroundEnabled = backgroundKind != CaptionBackgroundKind.NONE,
    backgroundColorArgb = backgroundColor?.convertToArgb() ?: SubtitleStyle.Default.backgroundColorArgb,
    backgroundOpacity = SubtitleStyle.Default.backgroundOpacity,
    backgroundCornerRadiusDp = when (backgroundKind) {
        CaptionBackgroundKind.SOLID_ROUNDED,
        CaptionBackgroundKind.BLUR -> 12f
        else -> 0f
    }
)

/**
 * Compose `Color` → ARGB `Int`, framework-free on the JVM so
 * [exportStyle] is unit-testable. Mirrors
 * [android.graphics.Color.argb] on the compose side.
 */
private fun androidx.compose.ui.graphics.Color.convertToArgb(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Small per-overlay bitmap cache so repeated frames don't re-render. */
@UnstableApi
private class CaptionBitmapCache(
    private val renderer: () -> Bitmap?
) {
    private val cache = object : LruCache<String, Bitmap>(3) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun get(key: String): Bitmap? = cache.get(key) ?: renderer()?.also { cache.put(key, it) }
}

@UnstableApi
internal class SubtitleWindowOverlay(
    private val context: Context,
    private val overlay: TextOverlay,
    private val startUs: Long,
    private val endUs: Long,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int,
    private val typefaceCache: MutableMap<String, android.graphics.Typeface?>
) : androidx.media3.effect.BitmapOverlay() {

    private val style: SubtitleStyle = overlay.exportStyle()

    /**
     * Static captions render once: with no motion and no
     * entrance/exit their steady state never changes, so one bitmap
     * serves the whole window. Viral + entrance-animated captions
     * re-render per frame through the shared renderer.
     */
    private val cache = CaptionBitmapCache {
        runCatching {
            CaptionRenderer.render(
                context = context,
                overlay = overlay,
                style = style,
                canvasWidthPx = outputWidthPx,
                canvasHeightPx = outputHeightPx,
                frameTimeMs = startUs / 1000L,
                typefaceCache = typefaceCache
            )
        }.getOrNull()
    }

    /** Per-word states are recomputed per frame: never cached. */
    private val hasViralMotion: Boolean =
        overlay.motionKind != com.aicreatorstudio.app.model.WordMotionKind.NONE &&
            overlay.words.isNotEmpty()

    /** Entrance/exit varies with the playhead: never cached. */
    private val hasEntrance: Boolean =
        overlay.textAnimation != TextOverlayAnimation.NONE ||
            overlay.textAnimationOut != TextOverlayAnimation.NONE

    // Full-frame bitmaps: the default overlay settings (both anchors
    // centered, scale 1) map the bitmap 1:1 onto the output frame, so
    // caption placement happens INSIDE the rendered bitmap.
    private val defaultSettings: OverlaySettings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, 0f)
        .setOverlayFrameAnchor(0f, 0f)
        .build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap =
        renderAt(presentationTimeUs)

    /**
     * The frame render entry used by [DynamicSubtitleOverlay].
     * Renders this caption through the shared [CaptionRenderer]
     * when [presentationTimeUs] is inside its window, else a shared
     * transparent frame.
     */
    fun renderAt(presentationTimeUs: Long): Bitmap {
        if (!isActiveAt(presentationTimeUs)) {
            // Outside the window the engine still asks for a bitmap
            // once (it caches textures); hand it a fully transparent
            // frame-sized bitmap.
            return transparentFrame()
        }
        if (hasViralMotion || hasEntrance) {
            return runCatching {
                CaptionRenderer.render(
                    context = context,
                    overlay = overlay,
                    style = style,
                    canvasWidthPx = outputWidthPx,
                    canvasHeightPx = outputHeightPx,
                    frameTimeMs = presentationTimeUs / 1000L,
                    typefaceCache = typefaceCache
                )
            }.getOrNull() ?: transparentFrame()
        }
        return cache.get(overlay.id) ?: transparentFrame()
    }

    /** True while [presentationTimeUs] is inside this caption's window. */
    fun isActiveAt(presentationTimeUs: Long): Boolean =
        SubtitleOverlayScheduler.isActive(startUs, endUs, presentationTimeUs)

    /** This caption's output window, for the dynamic scheduler. */
    internal val windowUs: Pair<Long, Long> get() = startUs to endUs

    /** Lazily-allocated fully-transparent full-frame bitmap. */
    private fun transparentFrame(): Bitmap {
        return transparentBitmap ?: Bitmap.createBitmap(
            outputWidthPx, outputHeightPx, Bitmap.Config.ARGB_8888
        ).also { transparentBitmap = it }
    }

    private var transparentBitmap: Bitmap? = null

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        defaultSettings
}

/**
 * 2026-09-19 — the SINGLE dynamic subtitle overlay handed to
 * Media3's `OverlayEffect`, no matter how many captions the track
 * holds (1 overlay slot instead of N — the 15-overlay ceiling can
 * never be hit by subtitles again).
 *
 * Per frame, [SubtitleOverlayScheduler] selects the caption(s)
 * whose window contains `presentationTimeUs`; each selected
 * caption renders through its own [SubtitleWindowOverlay] (with
 * that renderer's own static cache / per-frame viral path), and
 * overlapping captions composite in speech order onto one
 * full-frame bitmap. Outside every window a shared transparent
 * frame is returned.
 *
 * Bitmaps are never mutated here (`Canvas.drawBitmap` only reads
 * its source), so returning the renderers' cached bitmaps directly
 * in the common single-caption case is safe — and allocation-free.
 */
@UnstableApi
internal class DynamicSubtitleOverlay(
    private val renderers: List<SubtitleWindowOverlay>,
    private val outputWidthPx: Int,
    private val outputHeightPx: Int
) : androidx.media3.effect.BitmapOverlay() {

    // Full-frame bitmaps: the default overlay settings (both anchors
    // centered, scale 1) map the bitmap 1:1 onto the output frame, so
    // caption placement happens INSIDE the rendered bitmaps.
    private val defaultSettings: OverlaySettings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, 0f)
        .setOverlayFrameAnchor(0f, 0f)
        .build()

    /** Shared transparent frame for instants with no active caption. */
    private var transparentBitmap: Bitmap? = null

    /** Reused composite buffer for overlapping captions (rare). */
    private var compositeBitmap: Bitmap? = null

    /** Windows in renderer order, for the scheduler. */
    private val windows: List<Pair<Long, Long>> by lazy(LazyThreadSafetyMode.NONE) {
        // Built lazily so construction stays allocation-free; the
        // renderer list is fixed for the life of the export.
        renderers.map { it.windowUs }
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val active = SubtitleOverlayScheduler.activeIndices(windows, presentationTimeUs)
        return when {
            active.isEmpty() -> transparentFrame()
            active.size == 1 -> renderers[active[0]].renderAt(presentationTimeUs)
            else -> {
                val composite = compositeBitmap ?: Bitmap.createBitmap(
                    outputWidthPx, outputHeightPx, Bitmap.Config.ARGB_8888
                ).also { compositeBitmap = it }
                composite.eraseColor(android.graphics.Color.TRANSPARENT)
                val canvas = Canvas(composite)
                for (i in active) {
                    canvas.drawBitmap(renderers[i].renderAt(presentationTimeUs), 0f, 0f, null)
                }
                composite
            }
        }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        defaultSettings

    /** Lazily-allocated fully-transparent full-frame bitmap. */
    private fun transparentFrame(): Bitmap {
        return transparentBitmap ?: Bitmap.createBitmap(
            outputWidthPx, outputHeightPx, Bitmap.Config.ARGB_8888
        ).also { transparentBitmap = it }
    }
}
