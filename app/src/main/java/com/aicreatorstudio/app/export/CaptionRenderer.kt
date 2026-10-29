package com.aicreatorstudio.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.aicreatorstudio.app.ai.WordMotionEngine
import com.aicreatorstudio.app.ai.subtitle.PersianNormalizer
import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.KaraokeRanges
import com.aicreatorstudio.app.model.template.FontFallback
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.ProgressBarShape
import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.WordMotionKind

/**
 * 2026-09-19 — THE shared subtitle caption renderer (the
 * preview/export parity fix).
 *
 * ONE code path draws every Persian caption everywhere:
 *  - export burn-in (full output resolution, GL thread),
 *  - canvas preview (preview-canvas pixels, Compose `Image`),
 *  - studio panel previews (card pixels, static or animated).
 *
 * ## Why this kills the mismatch class
 *
 * Preview and export used to be two implementations (Compose
 * per-word `Text`s vs `StaticLayout` spans) plus a third simplified
 * mirror in the studio panel — each with its own padding, spacing,
 * glow/underline/pill handling and position math. Any template
 * tweak drifted three ways. Now there is exactly one definition
 * for every visual decision below, and the ONLY thing that differs
 * between callers is the canvas size ([canvasWidthPx] /
 * [canvasHeightPx]).
 *
 * ## Normalized coordinates (no screen pixels)
 *
 * Every metric derives from the caller-supplied canvas size, so a
 * 200 px panel card and a 1920 px export frame render the SAME
 * caption proportionally:
 *
 *  - font: `fontSizeSp / 720 * canvasHeight * 2` (the 720-unit
 *    canvas convention);
 *  - `dp`-named style fields (shadow offset, glow radius, corner
 *    radii): scaled by `canvasHeight / 720`;
 *  - width caps: fractions of the canvas width;
 *  - caption box padding + active-word pill insets: fractions of
 *    the resolved font size (single definition, both renderers);
 *  - position: style anchors + the overlay drag delta, in canvas
 *    fractions, clamped into the frame.
 *
 * ## Time-base contract
 *
 * [frameTimeMs] MUST share the time base of [TextOverlay.startTimeMs]
 * and its `words` (the mapper guarantees overlay/words agree).
 * Preview passes timeline ms; export passes output-mapped overlays
 * (rebased by the burn-in factory) with composition ms.
 * Word timings are always the real Whisper timings — never
 * synthesized here.
 *
 * 100% offline / native (`android.graphics` + `StaticLayout`;
 * bidi + Arabic shaping + ZWNJ preserved verbatim; no string
 * reversal anywhere). No Media3, no Compose — both callers link it.
 */
object CaptionRenderer {

    /** Canvas height the sp/dp conventions are authored against. */
    const val CANVAS_UNIT_PX: Float = 720f

    /** Caption box padding as a fraction of the resolved font size. */
    const val BOX_PAD_H_FRACTION: Float = 0.35f
    const val BOX_PAD_V_FRACTION: Float = 0.18f

    /** Active-word pill insets / corner radius (× font size). */
    const val PILL_PAD_H_FRACTION: Float = 0.30f
    const val PILL_PAD_V_FRACTION: Float = 0.12f
    const val PILL_RADIUS_FRACTION: Float = 0.30f

    /** Glow halo under the active word (accent, mostly transparent). */
    const val GLOW_ALPHA: Int = 140

    /** Gap between the last line's descenders and the progress bar (× font size). */
    const val PROGRESS_GAP_FRACTION: Float = 0.10f

    /** Cinematic scope the letterbox bars letterbox to (2.35:1). */
    const val LETTERBOX_SCOPE: Float = 2.35f

    /**
     * Gradient vector for a box, CSS angle convention (0° = to top,
     * 90° = to right), measured from the box CENTER along the
     * half-diagonal. Because only the DIRECTION comes from the
     * angle (the extent always reaches past the corners), the angle
     * never deforms on stretched caption boxes: 45° points
     * top-right on 16:9, 9:16 and 1:1 alike.
     *
     * Returns [x0, y0, x1, y1] in box px. Pure — unit-tested.
     */
    internal fun gradientEndpoints(
        boxWidthPx: Float,
        boxHeightPx: Float,
        angleDeg: Float
    ): FloatArray {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = kotlin.math.sin(rad).toFloat()
        val dy = -kotlin.math.cos(rad).toFloat()
        val half = kotlin.math.hypot(boxWidthPx / 2f, boxHeightPx / 2f)
        val cx = boxWidthPx / 2f
        val cy = boxHeightPx / 2f
        return floatArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
    }

    /**
     * Letterbox bar height for a frame (px, ≥0): full-width black
     * bars top + bottom that letterbox the frame to [LETTERBOX_SCOPE].
     * 16:9 → thin bars; 9:16 → tall scope bars (the caption, placed
     * in the lower safe area, lands on the bottom bar and stays
     * readable). Pure — unit-tested.
     */
    internal fun letterboxBarHeight(canvasWidthPx: Int, canvasHeightPx: Int): Float {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) return 0f
        val contentH = canvasWidthPx / LETTERBOX_SCOPE
        return ((canvasHeightPx - contentH) / 2f).coerceAtLeast(0f)
    }

    /** Cache key: pixel-snapped dims + colors + whole degrees. */
    internal fun gradientCacheKey(
        wPx: Int, hPx: Int, color0: Int, color1: Int, angleDeg: Float
    ): String = "$wPx\u00d7$hPx|$color0|$color1|${angleDeg.roundToInt()}"

    /**
     * Cached LinearGradient (PHASE 7 perf): shaders are rebuilt
     * ONLY when size/colors/angle change — never per frame — so
     * animated previews hold 60fps without GC churn. Bounded LRU
     * (thread-safe) shared by preview + export.
     */
    private val gradientShaders =
        object : android.util.LruCache<String, android.graphics.Shader>(8) {
            override fun sizeOf(key: String, value: android.graphics.Shader): Int = 1
        }

    internal fun gradientShader(
        boxWidthPx: Float,
        boxHeightPx: Float,
        color0: Int,
        color1: Int,
        angleDeg: Float
    ): android.graphics.Shader {
        val key = gradientCacheKey(
            boxWidthPx.roundToInt(), boxHeightPx.roundToInt(),
            color0, color1, angleDeg
        )
        return gradientShaders.get(key) ?: run {
            val e = gradientEndpoints(boxWidthPx, boxHeightPx, angleDeg)
            android.graphics.LinearGradient(
                e[0], e[1], e[2], e[3], color0, color1,
                android.graphics.Shader.TileMode.CLAMP
            ).also { gradientShaders.put(key, it) }
        }
    }

    /**
     * Continuous fill fraction across a word-timing range
     * ([firstStartMs, lastEndMs]) at [frameTimeMs]. Degenerate
     * ranges snap to 0/1 instead of dividing by zero.
     */
    internal fun progressFraction(
        firstStartMs: Long,
        lastEndMs: Long,
        frameTimeMs: Long
    ): Float {
        if (lastEndMs <= firstStartMs) {
            return if (frameTimeMs >= firstStartMs) 1f else 0f
        }
        return ((frameTimeMs - firstStartMs).toFloat() / (lastEndMs - firstStartMs))
            .coerceIn(0f, 1f)
    }

    /** True when the bar must grow from the reading edge (RTL). */
    internal fun progressFillRtl(isRtlCaption: Boolean, directionAware: Boolean): Boolean =
        directionAware && isRtlCaption

    // ---- 2026-09-24 WAVE accent (pure, JVM-testable) ----

    /** One full travel cycle of the wave accent. */
    const val WAVE_CYCLE_MS: Long = 3600L

    /** Wavelength as a fraction of the caption box width. */
    const val WAVE_WAVELENGTH_FRACTION: Float = 0.32f

    /** Wave amplitude as a fraction of the bar height. */
    const val WAVE_AMPLITUDE_FRACTION: Float = 0.85f

    /** Polyline segments per wavelength — enough to read as a curve. */
    const val WAVE_STEPS_PER_WAVELENGTH: Int = 10

    /**
     * Vertical offset of the travelling wave at canvas [x].
     *
     * The phase is expressed in ABSOLUTE x, so where the fill and the
     * track overlap they sit on the SAME wave — the fill reads as
     * growing along one wave instead of a second wave sliding over
     * the first.
     *
     * Pure (no Android types) so the wave math is unit-testable; the
     * renderer only walks the resulting Path.
     *
     * @param phaseRadians time-driven offset; advancing it travels
     *   the wave. 0 ⇒ a zero crossing at x = 0.
     */
    internal fun waveOffsetY(
        x: Float,
        amplitude: Float,
        wavelength: Float,
        phaseRadians: Float
    ): Float {
        if (wavelength <= 0f || amplitude == 0f) return 0f
        return amplitude * sin(2.0 * PI * (x / wavelength) + phaseRadians).toFloat()
    }

    /**
     * Travel phase at [frameTimeMs]: one full cycle every
     * [WAVE_CYCLE_MS] ms — slow enough to read as a gentle drift
     * rather than a flicker, and cheap (no state, no allocation).
     */
    internal fun wavePhaseAt(frameTimeMs: Long): Float {
        val t = (frameTimeMs % WAVE_CYCLE_MS).toFloat() / WAVE_CYCLE_MS
        return t * 2f * PI.toFloat()
    }

    // ---- single-definition normalized metrics (unit-tested) ----

    /** Resolved font size in px for a canvas of [canvasHeightPx]. */
    fun fontSizePx(fontSizeSp: Float, canvasHeightPx: Int): Float =
        (fontSizeSp / CANVAS_UNIT_PX) * canvasHeightPx * 2f

    /** Max text width in px for a canvas of [canvasWidthPx]. */
    fun maxTextWidthPx(fraction: Float, canvasWidthPx: Int): Int =
        (canvasWidthPx * fraction).toInt()

    /** `dp`-named style fields (shadow offset, glow, corners) → px. */
    fun dpToPx(dp: Float, canvasHeightPx: Int): Float =
        dp * (canvasHeightPx / CANVAS_UNIT_PX)

    /** Cheap RTL probe — one Persian/Arabic char ⇒ RTL caption. */
    fun isRtl(text: String): Boolean = PersianNormalizer.isPersianScript(text)

    /**
     * Style anchor as canvas fractions. Mirrors the burn-in's
     * exported fractions, which are the authoritative placement.
     */
    fun anchorFractions(style: SubtitleStyle): Pair<Float, Float> {
        val x = when (style.horizontalAlignment) {
            SubtitleAlignmentH.LEFT -> 0.12f
            SubtitleAlignmentH.CENTER -> 0.5f
            SubtitleAlignmentH.RIGHT -> 0.88f
        }
        val y = when (style.verticalAlignment) {
            SubtitleAlignmentV.TOP -> 0.12f
            SubtitleAlignmentV.CENTER -> 0.5f
            SubtitleAlignmentV.BOTTOM -> 0.88f
        }
        return x to y
    }

    /**
     * Box top-left in px from the anchor, the overlay drag delta
     * (`position`, 0.5 = no offset) and the measured box size.
     * Oversized boxes pin to the frame instead of throwing in
     * `coerceIn` (a box wider than the canvas is legal input).
     */
    fun placeBox(
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        anchorX: Float,
        anchorY: Float,
        dragX: Float,
        dragY: Float,
        boxWidthPx: Float,
        boxHeightPx: Float
    ): Pair<Float, Float> {
        val w = canvasWidthPx.toFloat()
        val h = canvasHeightPx.toFloat()
        val centerX = if (boxWidthPx >= w) {
            w / 2f
        } else {
            (anchorX * w + (dragX - 0.5f) * w)
                .coerceIn(boxWidthPx / 2f, w - boxWidthPx / 2f)
        }
        val left = (centerX - boxWidthPx / 2f).coerceIn(0f, (w - boxWidthPx).coerceAtLeast(0f))
        val top = (anchorY * h - boxHeightPx / 2f + (dragY - 0.5f) * h)
            .coerceIn(0f, (h - boxHeightPx).coerceAtLeast(0f))
        return left to top
    }

    // ---- full-caption render ----

    /**
     * Render [overlay] at [frameTimeMs] onto a full-canvas bitmap.
     * Outside every word window the caption still draws (static
     * captions show for their whole window); callers decide
     * visibility. Returns a fresh bitmap every call — callers own
     * caching/recycling.
     *
     * @param fontScale thumbnail multiplier for small preview cards
     *   (1 = exact canvas/export metrics). Values > 1 keep the same
     *   shaping/layout code with a larger font so picker cards stay
     *   legible; the animated card, canvas and export all use 1.
     * @param resolveCustomFontPath maps a `custom:<id>` font reference
     *   to an absolute file path (null when unknown/gone). Defaults to
     *   the process-wide [com.aicreatorstudio.app.data.font.CustomFontLocator],
     *   which is what makes the **preview and the export resolve the
     *   same font with no per-surface wiring** — the parity requirement
     *   holds even if a future caller forgets to pass one.
     */
    fun render(
        context: Context,
        overlay: TextOverlay,
        style: SubtitleStyle,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        frameTimeMs: Long,
        typefaceCache: MutableMap<String, Typeface?>,
        fontScale: Float = 1f,
        resolveCustomFontPath: ((String) -> String?)? = null
    ): Bitmap {
        if (canvasWidthPx <= 0 || canvasHeightPx <= 0) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        val bitmap = Bitmap.createBitmap(canvasWidthPx, canvasHeightPx, Bitmap.Config.ARGB_8888)
        val text = overlay.text.trim()
        if (text.isEmpty()) return bitmap

        // Whole-caption entrance/exit (single definition): the box
        // composite reuses it below; letterbox bars reuse its alpha.
        val transform = WordMotionEngine.captionTransformAt(overlay, frameTimeMs)

        // Frame-level cinematic scope bars (full video width on any
        // aspect): painted first so the caption lands on top.
        if (overlay.backgroundKind == CaptionBackgroundKind.LETTERBOX_BARS) {
            drawLetterboxBars(
                Canvas(bitmap), canvasWidthPx, canvasHeightPx,
                style.backgroundColorArgb, transform.alpha
            )
        }

        val fontPx = (fontSizePx(style.fontSizeSp, canvasHeightPx) * fontScale.coerceAtLeast(0.5f))
            .coerceAtLeast(12f)
        val strokePx = fontSizePx(style.strokeWidthSp, canvasHeightPx) * fontScale.coerceAtLeast(0.5f)
        val rtl = isRtl(text)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontPx
            color = style.textColorArgb
            isFakeBoldText = style.bold
            typeface = resolveTypeface(
                context, typefaceCache, style.fontFamily, rtl,
                style.fontWeightValue,
                resolveCustomFontPath
                    ?: com.aicreatorstudio.app.data.font.CustomFontLocator::pathFor
            )
            // 2026-09-20 FIX: LEFT is the correct alignment for
            // StaticLayout (it manages its own centering via
            // ALIGN_CENTER). CENTER on the TextPaint causes
            // FIRSTSTRONG_RTL text to render LTR on some Android
            // versions because drawText() repositions the baseline
            // relative to the center anchor, breaking bidi layout.
            textAlign = Paint.Align.LEFT
        }

        val viral = overlay.motionKind != WordMotionKind.NONE && overlay.words.isNotEmpty()
        val maxW = maxTextWidthPx(style.maxTextWidthFraction, canvasWidthPx).coerceAtLeast(64)
        // Pass 1 at full width → measure the REAL content width.
        // `layout.width` is the constructor width (useless for
        // sizing); the widest LAID-OUT line is the truth.
        val firstViral = if (viral) {
            buildViralLayout(textPaint, overlay, style, fontPx, frameTimeMs, rtl, maxW)
        } else null
        if (viral && firstViral == null) return bitmap
        val firstLayout: StaticLayout = firstViral?.layout ?: buildLegacyLayout(
            karaokeSpan(text, overlay, frameTimeMs) ?: text, textPaint, rtl, maxW
        )
        val measuredW = measureContentWidth(firstLayout)
        // Pass 2 at the TIGHT width: the capsule hugs the text
        // (never full-width) and every line centers by
        // construction — this fixes both the giant bar and the
        // off-center text. Reused as-is when already exact.
        val (layout, viralFinal) = if (measuredW >= maxW) {
            firstLayout to firstViral
        } else if (viral) {
            val rebuilt =
                buildViralLayout(textPaint, overlay, style, fontPx, frameTimeMs, rtl, measuredW)
                    ?: return bitmap
            rebuilt.layout to rebuilt
        } else {
            buildLegacyLayout(
                karaokeSpan(text, overlay, frameTimeMs) ?: text, textPaint, rtl, measuredW
            ) to null
        }

        // Caption box: tight content width + the single padding
        // definition (shared by preview and export). The progress
        // bar (if any) extends the box below the last line so it is
        // never clipped.
        val padH = (fontPx * BOX_PAD_H_FRACTION).toInt()
        val padV = (fontPx * BOX_PAD_V_FRACTION).toInt()
        val progressSpec = overlay.progressBar
            .takeIf { it != null && overlay.words.isNotEmpty() }
        val barH = if (progressSpec != null) {
            (fontPx * progressSpec.heightRatio).coerceAtLeast(2f)
        } else 0f
        val barGap = fontPx * PROGRESS_GAP_FRACTION
        val boxW = (layout.width + padH * 2).coerceAtLeast(1)
        val boxH = (layout.height + padV * 2 + (if (progressSpec != null) barH + barGap else 0f))
            .toInt().coerceAtLeast(1)
        val box = Bitmap.createBitmap(boxW, boxH, Bitmap.Config.ARGB_8888)
        val bc = Canvas(box)

        drawBackground(bc, overlay, style, boxW.toFloat(), boxH.toFloat(), canvasHeightPx)

        // Direction-aware progress bar under the text (after the
        // background so it sits on it, before glyphs — no overlap
        // either way since it lives below the last line).
        if (progressSpec != null) {
            drawProgressBar(
                bc, layout, progressSpec, overlay, fontPx,
                frameTimeMs, rtl, padH, padV, barH, barGap
            )
        }

        // Shadow: crisp offset pass (readable + cheap on every caller).
        if (style.shadowEnabled) {
            val shadowPaint = TextPaint(textPaint)
            shadowPaint.style = Paint.Style.FILL
            shadowPaint.color = style.shadowColorArgb
            shadowPaint.alpha = (style.shadowOpacity * 255f).toInt().coerceIn(0, 255)
            val dy = dpToPx(style.shadowOffsetYDp, canvasHeightPx)
            bc.save()
            bc.translate(padH.toFloat(), padV + dy)
            layout.draw(bc)
            bc.restore()
        }
        // Stroke: same-size pass ⇒ aligned halo for cursive Persian.
        if (style.strokeEnabled && strokePx > 0.5f) {
            val strokePaint = TextPaint(textPaint)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = strokePx
            strokePaint.color = style.strokeColorArgb
            strokePaint.strokeJoin = Paint.Join.ROUND
            bc.save()
            bc.translate(padH.toFloat(), padV.toFloat())
            layout.draw(bc)
            bc.restore()
        }
        // Active-word glow + pill ride the word's own measured bounds:
        // bidi-safe, no X math to mirror for RTL.
        viralFinal?.let { vf ->
            drawWordDecorations(
                bc, layout, vf.ranges, vf.states, overlay, style, fontPx,
                canvasHeightPx, frameTimeMs, padH, padV
            )
        }
        bc.save()
        bc.translate(padH.toFloat(), padV.toFloat())
        layout.draw(bc)
        bc.restore()

        // Placement + whole-caption entrance/exit (single definition).
        // [transform] was computed up front (letterbox bars reuse it).
        val (anchorX, anchorY) = anchorFractions(style)
        val (left, top) = placeBox(
            canvasWidthPx, canvasHeightPx, anchorX, anchorY,
            overlay.position.x, overlay.position.y,
            boxW.toFloat(), boxH.toFloat()
        )
        val out = Canvas(bitmap)
        out.save()
        val cx = left + boxW / 2f
        val cy = top + boxH / 2f
        out.translate(cx + transform.offsetXFraction * boxW, cy + transform.offsetYFraction * boxH)
        out.scale(transform.scale, transform.scale)
        out.translate(-cx, -cy)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (transform.alpha.coerceIn(0f, 1f) * 255f).toInt()
        }
        out.drawBitmap(box, left, top, paint)
        out.restore()
        box.recycle()
        return bitmap
    }

    // ---- viral word layer ----

    /** Fallback active-word tint (legacy karaoke look) when no template color is set. */
    val DEFAULT_KARAOKE_TINT: Int = 0xFF0A84FF.toInt()

    /**
     * Sentence-level active-word highlight for captions that carry
     * real word timings but no motion engine (fresh AI subtitles,
     * HIGHLIGHT templates with `motionKind == NONE`). Colors the
     * spoken word exactly like the legacy preview did; returns null
     * when there is no active word or the range cannot be located
     * safely (a missing highlight beats a wrong one).
     */
    fun karaokeSpan(
        text: String,
        overlay: TextOverlay,
        frameTimeMs: Long
    ): android.text.SpannableStringBuilder? {
        if (!overlay.karaokeEnabled || overlay.words.isEmpty()) return null
        val active = overlay.activeWordAt(frameTimeMs) ?: return null
        val words = overlay.words
        val idx = words.indexOfFirst { it === active }.takeIf { it >= 0 }
            ?: words.indexOfFirst {
                it.startTimeMs == active.startTimeMs && it.text == active.text
            }
        val range = KaraokeRanges.activeRange(
            text = text,
            wordTexts = words.map { it.text },
            wordIndex = idx
        ) ?: return null
        if (range.isEmpty()) return null
        return android.text.SpannableStringBuilder(text).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(
                    overlay.activeWordColorArgb ?: DEFAULT_KARAOKE_TINT
                ),
                range.first, range.last + 1, 0
            )
            setSpan(
                android.text.style.StyleSpan(Typeface.BOLD),
                range.first, range.last + 1, 0
            )
        }
    }

    /** Plain (sentence-level) layout at an explicit width. */
    private fun buildLegacyLayout(
        content: CharSequence,
        textPaint: TextPaint,
        rtl: Boolean,
        widthPx: Int
    ): StaticLayout = StaticLayout.Builder
        .obtain(content, 0, content.length, textPaint, widthPx.coerceAtLeast(64))
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setTextDirection(
            if (rtl) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL
            else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR
        )
        .setLineSpacing(0f, 1f)
        .setIncludePad(true)
        .build()

    /**
     * Widest laid-out line in px (≥1). `StaticLayout.width` is the
     * CONSTRUCTOR width and must never size the capsule — a short
     * caption would inherit a full-width background. Line breaking
     * itself stays word-based (breaks mid-word only when a single
     * word exceeds the width and the renderer is truly forced).
     */
    private fun measureContentWidth(layout: StaticLayout): Int {
        if (layout.lineCount <= 0) return 1
        var w = 0f
        for (i in 0 until layout.lineCount) {
            w = maxOf(w, layout.getLineWidth(i))
        }
        return kotlin.math.ceil(w).toInt().coerceAtLeast(1)
    }

    /** A viral layout plus its per-word builder ranges and motion states. */
    private data class ViralContent(
        val layout: StaticLayout,
        val ranges: List<Pair<Int, Int>>,
        val states: List<WordMotionEngine.WordState>
    )

    /**
     * One StaticLayout holding ALL words with per-frame states from
     * the shared [WordMotionEngine] (bounce scale, typewriter
     * prefix, karaoke color lerp, bold, underline). The active-word
     * pill + glow are drawn separately in [drawWordDecorations]
     * from measured bounds (padded + rounded, unlike a bare
     * BackgroundColorSpan).
     */
    private fun buildViralLayout(
        basePaint: TextPaint,
        overlay: TextOverlay,
        style: SubtitleStyle,
        fontPx: Float,
        frameTimeMs: Long,
        rtl: Boolean,
        canvasWidthPx: Int
    ): ViralContent? {
        val words = overlay.words
        val activeColor = overlay.activeWordColorArgb ?: style.textColorArgb
        val spanBuilder = android.text.SpannableStringBuilder()
        // Word ranges + motion states for the decoration pass.
        val ranges = ArrayList<Pair<Int, Int>>(words.size)
        val states = ArrayList<WordMotionEngine.WordState>(words.size)
        words.forEachIndexed { index, word ->
            val state = WordMotionEngine.wordStateAt(overlay, words, index, frameTimeMs)
            states.add(state)
            val isActive = frameTimeMs >= word.startTimeMs && frameTimeMs < word.endTimeMs
            val displayText = if (overlay.motionKind == WordMotionKind.TYPEWRITER) {
                word.text.take(state.visibleCharacters.coerceAtLeast(0))
            } else word.text
            if (displayText.isEmpty() && state.alpha <= 0.01f) {
                // Invisible pre-reveal word — keep its space so the
                // layout does not reflow.
                if (index < words.size - 1) spanBuilder.append(" ")
                ranges.add(-1 to -1)
                return@forEachIndexed
            }
            val spanStart = spanBuilder.length
            spanBuilder.append(displayText)
            val spanEnd = spanBuilder.length
            ranges.add(spanStart to spanEnd)
            val size = fontPx * state.scale.coerceIn(0.5f, 2.5f)
            spanBuilder.setSpan(
                android.text.style.AbsoluteSizeSpan(size.toInt()),
                spanStart, spanEnd, 0
            )
            val color = when (overlay.motionKind) {
                WordMotionKind.KARAOKE_FILL ->
                    lerpArgb(basePaint.color, activeColor, state.fillFraction)
                else -> if (isActive) activeColor else basePaint.color
            }
            val alphaColor = android.graphics.Color.argb(
                (state.alpha.coerceIn(0f, 1f) * 255f).toInt(),
                android.graphics.Color.red(color),
                android.graphics.Color.green(color),
                android.graphics.Color.blue(color)
            )
            spanBuilder.setSpan(
                android.text.style.ForegroundColorSpan(alphaColor),
                spanStart, spanEnd, 0
            )
            if (isActive && overlay.activeWordBold) {
                spanBuilder.setSpan(
                    android.text.style.StyleSpan(Typeface.BOLD),
                    spanStart, spanEnd, 0
                )
            }
            if (isActive && overlay.activeWordUnderline) {
                spanBuilder.setSpan(
                    android.text.style.UnderlineSpan(),
                    spanStart, spanEnd, 0
                )
            }
            if (index < words.size - 1) spanBuilder.append(" ")
        }
        if (spanBuilder.isBlank()) return null
        val layout = StaticLayout.Builder
            .obtain(
                spanBuilder, 0, spanBuilder.length,
                TextPaint(basePaint),
                maxTextWidthPx(style.maxTextWidthFraction, canvasWidthPx).coerceAtLeast(64)
            )
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setTextDirection(
                if (rtl) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL
                else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR
            )
            .setLineSpacing(0f, 1f)
            .setIncludePad(true)
            .build()
        return ViralContent(layout, ranges, states)
    }

    /**
     * Active-word glow (soft accent halo) + pill (padded rounded
     * bar) drawn from the layout's measured word bounds. Because
     * bounds come from bidi layout itself, RTL needs no mirroring:
     * decorations can never land on the wrong side.
     *
     * Words are NEVER drawn with standalone `drawText`: an isolated
     * draw re-shapes cursive Persian and misplaces scaled words
     * (the "smashed letters" bug). The glow is its own single-word
     * StaticLayout — a space-isolated word shapes identically to
     * its in-sentence form — positioned by measured bounds; the
     * pill is a plain round rect. The main layout then draws the
     * final glyphs on top via spans.
     */
    private fun drawWordDecorations(
        canvas: Canvas,
        layout: StaticLayout,
        ranges: List<Pair<Int, Int>>,
        states: List<WordMotionEngine.WordState>,
        overlay: TextOverlay,
        style: SubtitleStyle,
        fontPx: Float,
        canvasHeightPx: Int,
        frameTimeMs: Long,
        padH: Int,
        padV: Int
    ) {
        val words = overlay.words
        val pillArgb = overlay.activeWordBackgroundArgb
        val glowDp = overlay.activeWordGlowRadiusDp
        if (pillArgb == null && glowDp <= 0f) return
        val pillPadH = fontPx * PILL_PAD_H_FRACTION
        val pillPadV = fontPx * PILL_PAD_V_FRACTION
        val pillRadius = fontPx * PILL_RADIUS_FRACTION
        val glowPx = dpToPx(glowDp, canvasHeightPx)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pillArgb ?: 0 }
        words.forEachIndexed { index, word ->
            if (frameTimeMs < word.startTimeMs || frameTimeMs >= word.endTimeMs) return@forEachIndexed
            val (s, e) = ranges.getOrNull(index) ?: return@forEachIndexed
            if (s < 0 || e <= s) return@forEachIndexed
            val line = layout.getLineForOffset(s)
            val left = minOf(layout.getPrimaryHorizontal(s), layout.getPrimaryHorizontal(e.coerceAtMost(layout.text.length)))
            val right = maxOf(layout.getPrimaryHorizontal(s), layout.getPrimaryHorizontal(e.coerceAtMost(layout.text.length)))
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(line).toFloat()
            // Glow halo under the word: a single-word StaticLayout
            // with a blurred accent paint, overlaid EXACTLY on the
            // word's measured span (same size incl. bounce scale, same
            // typeface/weight). No drawText, no manual X math.
            if (glowDp > 0f) {
                val rtlWord = isRtl(word.text)
                val scaledSize =
                    fontPx * (states.getOrNull(index)?.scale?.coerceIn(0.5f, 2.5f) ?: 1f)
                val spanW = (right - left).coerceAtLeast(1f)
                val glowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = overlay.accentColorArgb
                    alpha = GLOW_ALPHA
                    textSize = scaledSize
                    typeface = layout.getPaint().typeface
                    isFakeBoldText = style.bold || overlay.activeWordBold
                    textAlign = Paint.Align.LEFT
                    setShadowLayer(glowPx * 2f, 0f, 0f, overlay.accentColorArgb)
                }
                val wordLayout = StaticLayout.Builder
                    .obtain(
                        word.text, 0, word.text.length, glowPaint,
                        kotlin.math.ceil(spanW).toInt().coerceAtLeast(2)
                    )
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(
                        if (rtlWord) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL
                        else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR
                    )
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(true)
                    .build()
                val lineH = (bottom - top).coerceAtLeast(1f)
                canvas.save()
                canvas.translate(
                    padH + left,
                    padV + top + ((lineH - wordLayout.height).coerceAtLeast(0f) / 2f)
                )
                wordLayout.draw(canvas)
                canvas.restore()
            }
            // Pill bar behind the word.
            if (pillArgb != null) {
                canvas.drawRoundRect(
                    RectF(
                        padH + left - pillPadH,
                        padV + top - pillPadV,
                        padH + right + pillPadH,
                        padV + bottom + pillPadV
                    ),
                    pillRadius, pillRadius, pillPaint
                )
            }
        }
    }

    // ---- backgrounds ----

    /**
     * Direction-aware progress bar under the whole caption.
     *
     * Geometry (single definition, preview == export):
     *  - spans the laid-out content width ([padH, padH + width]);
     *  - sits BELOW `getLineBottom(lastLine)` plus a gap, so
     *    descenders (ی، ر، p، g) can never collide with it —
     *    `getLineBottom` already includes the descent extent;
     *  - height = `heightRatio × fontPx`;
     *  - fill grows continuously with the REAL word timings
     *    (first word start → last word end), from the reading edge
     *    (right for RTL when [directionAware], else left).
     */
    private fun drawProgressBar(
        canvas: Canvas,
        layout: StaticLayout,
        spec: com.aicreatorstudio.app.model.ProgressBarSpec,
        overlay: TextOverlay,
        fontPx: Float,
        frameTimeMs: Long,
        rtlCaption: Boolean,
        padH: Int,
        padV: Int,
        barH: Float,
        barGap: Float
    ) {
        val words = overlay.words
        if (words.isEmpty() || layout.lineCount <= 0) return
        val firstStart = words.minOf { it.startTimeMs }
        val lastEnd = words.maxOf { it.endTimeMs }
        val fraction = progressFraction(firstStart, lastEnd, frameTimeMs)
        val left = padH.toFloat()
        val right = left + layout.width
        if (right <= left) return
        val barTop = padV + layout.getLineBottom(layout.lineCount - 1) + barGap
        // 2026-09-24 — WAVE reuses the whole bar contract (timing
        // sync, RTL direction, placement, heightRatio); only the
        // stroked path differs.
        if (spec.shape == ProgressBarShape.WAVE) {
            drawWaveAccent(
                canvas, spec, left, right, barTop, barH, fraction, rtlCaption, frameTimeMs
            )
            return
        }
        val radius = barH / 2f
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.trackArgb }
        canvas.drawRoundRect(RectF(left, barTop, right, barTop + barH), radius, radius, trackPaint)
        val fillW = (right - left) * fraction
        if (fillW > 0.5f) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = spec.colorArgb }
            val fillRect = if (progressFillRtl(rtlCaption, spec.directionAware)) {
                RectF(right - fillW, barTop, right, barTop + barH)
            } else {
                RectF(left, barTop, left + fillW, barTop + barH)
            }
            canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        }
    }

    /**
     * 2026-09-24 — the WAVE variant of the accent line. Identical
     * geometry, timing and RTL rules; only the path differs. The
     * track is stroked across the full width and the fill along the
     * reading-edge segment on the SAME phase, so the fill reads as
     * growing along one wave.
     */
    private fun drawWaveAccent(
        canvas: Canvas,
        spec: com.aicreatorstudio.app.model.ProgressBarSpec,
        left: Float,
        right: Float,
        barTop: Float,
        barH: Float,
        fraction: Float,
        rtlCaption: Boolean,
        frameTimeMs: Long
    ) {
        val width = right - left
        if (width <= 1f) return
        val centerY = barTop + barH / 2f
        val wavelength = (width * WAVE_WAVELENGTH_FRACTION).coerceAtLeast(1f)
        val amplitude = (barH / 2f) * WAVE_AMPLITUDE_FRACTION
        val strokeW = (barH * 0.55f).coerceAtLeast(1.5f)
        val phase = wavePhaseAt(frameTimeMs)
        val steps = ((width / wavelength) * WAVE_STEPS_PER_WAVELENGTH)
            .roundToInt().coerceIn(2, 240)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = spec.trackArgb
            style = Paint.Style.STROKE
            strokeWidth = strokeW
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(
            wavePath(left, right, centerY, amplitude, wavelength, phase, steps), trackPaint
        )

        val fillW = width * fraction
        if (fillW > 0.5f) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = spec.colorArgb
                style = Paint.Style.STROKE
                strokeWidth = strokeW
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val from = if (progressFillRtl(rtlCaption, spec.directionAware)) {
                right - fillW
            } else {
                left
            }
            canvas.drawPath(
                wavePath(from, from + fillW, centerY, amplitude, wavelength, phase, steps),
                fillPaint
            )
        }
    }

    /** Walk one wave from [x0] to [x1] on a single shared phase. */
    private fun wavePath(
        x0: Float,
        x1: Float,
        centerY: Float,
        amplitude: Float,
        wavelength: Float,
        phase: Float,
        steps: Int
    ): Path {
        val path = Path()
        val span = x1 - x0
        if (span <= 0f) return path
        val stepCount = steps.coerceAtLeast(2)
        val dx = span / stepCount
        path.moveTo(x0, centerY + waveOffsetY(x0, amplitude, wavelength, phase))
        for (i in 1..stepCount) {
            val x = x0 + dx * i
            path.lineTo(x, centerY + waveOffsetY(x, amplitude, wavelength, phase))
        }
        return path
    }

    /**
     * Frame-level cinematic scope bars (full video width): top +
     * bottom rects letterboxing the frame to [LETTERBOX_SCOPE].
     * Alpha follows the caption entrance so fade-style templates
     * animate the bars with the text.
     */
    private fun drawLetterboxBars(
        canvas: Canvas,
        canvasWidthPx: Int,
        canvasHeightPx: Int,
        colorArgb: Int,
        alpha: Float
    ) {
        val barH = letterboxBarHeight(canvasWidthPx, canvasHeightPx)
        if (barH <= 0.5f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorArgb
            this.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        }
        val w = canvasWidthPx.toFloat()
        val h = canvasHeightPx.toFloat()
        canvas.drawRect(0f, 0f, w, barH, paint)
        canvas.drawRect(0f, h - barH, w, h, paint)
    }

    private fun drawBackground(
        canvas: Canvas,
        overlay: TextOverlay,
        style: SubtitleStyle,
        boxWidth: Float,
        boxHeight: Float,
        canvasHeightPx: Int
    ) {
        val bgPaint = { opacity: Float ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                alpha = (opacity * 255f).toInt().coerceIn(0, 255)
            }
        }
        val radius = dpToPx(style.backgroundCornerRadiusDp, canvasHeightPx)
        val rect = RectF(0f, 0f, boxWidth, boxHeight)
        when (overlay.backgroundKind) {
            CaptionBackgroundKind.NONE ->
                if (style.backgroundEnabled) {
                    val p = bgPaint(style.backgroundOpacity)
                    p.color = style.backgroundColorArgb
                    canvas.drawRoundRect(rect, radius, radius, p)
                }
            CaptionBackgroundKind.SOLID_ROUNDED -> {
                val p = bgPaint(style.backgroundOpacity)
                p.color = style.backgroundColorArgb
                canvas.drawRoundRect(rect, radius, radius, p)
            }
            CaptionBackgroundKind.GRADIENT -> {
                // Angle-aware cached shader (single definition).
                val p = bgPaint(style.backgroundOpacity)
                p.shader = gradientShader(
                    boxWidth, boxHeight,
                    style.backgroundColorArgb, overlay.accentColorArgb,
                    overlay.gradientAngleDeg ?: 90f
                )
                canvas.drawRoundRect(rect, radius, radius, p)
            }
            CaptionBackgroundKind.BLUR -> {
                // Lightweight frosted look (no real blur pass — same
                // definition in preview and export): translucent plate
                // + hairline border.
                val p = bgPaint(style.backgroundOpacity)
                p.color = style.backgroundColorArgb
                canvas.drawRoundRect(rect, radius, radius, p)
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    // Qualified: the style *parameter* (SubtitleStyle)
                    // shadows Paint.style inside apply blocks.
                    this.style = Paint.Style.STROKE
                    strokeWidth = (canvasHeightPx / CANVAS_UNIT_PX).coerceAtLeast(1f)
                    color = android.graphics.Color.WHITE
                    alpha = (0.18f * 255f).toInt()
                }
                canvas.drawRoundRect(rect, radius, radius, border)
            }
            CaptionBackgroundKind.PER_LINE_HIGHLIGHT -> {
                val p = bgPaint(0.45f)
                p.color = overlay.accentColorArgb
                val r = dpToPx(6f, canvasHeightPx)
                canvas.drawRoundRect(rect, r, r, p)
            }
            CaptionBackgroundKind.LETTERBOX_BARS -> {
                // No-op here: scope bars are FRAME-level (full video
                // width) and painted in render(), not on the box.
            }
            CaptionBackgroundKind.FILMSTRIP -> {
                // 2026-09-24 — filmstrip: a dark plate carrying two
                // rows of perforations. Plain round-rects only (no
                // blur, no shader) with a hard cap on the hole count,
                // so the per-frame cost stays flat at any canvas size
                // and in both renderers.
                val plate = bgPaint(style.backgroundOpacity.coerceAtLeast(0.62f))
                plate.color = style.backgroundColorArgb
                canvas.drawRoundRect(rect, radius, radius, plate)

                val holeH = (boxHeight * 0.11f).coerceIn(2f, boxHeight * 0.22f)
                val holeW = holeH * 1.6f
                val gap = holeW * 0.9f
                val inset = (gap * 0.7f).coerceAtLeast(1f)
                val usable = boxWidth - inset * 2f
                if (usable > holeW) {
                    val count = (usable / (holeW + gap)).toInt().coerceIn(1, 20)
                    val used = count * holeW + (count - 1) * gap
                    val startX = inset + (usable - used) / 2f
                    val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = overlay.accentColorArgb
                        alpha = (0.9f * 255f).toInt()
                    }
                    val r = holeH * 0.3f
                    val topY = inset
                    val botY = boxHeight - inset - holeH
                    for (i in 0 until count) {
                        val x = startX + i * (holeW + gap)
                        canvas.drawRoundRect(
                            RectF(x, topY, x + holeW, topY + holeH), r, r, holePaint
                        )
                        canvas.drawRoundRect(
                            RectF(x, botY, x + holeW, botY + holeH), r, r, holePaint
                        )
                    }
                }
            }
        }
    }

    // ---- helpers ----

    /**
     * ARGB lerp (karaoke fill). Channel math is plain bit ops (no
     * `android.graphics.Color`) so it stays JVM-testable; alpha is
     * forced opaque exactly like the old export path.
     */
    internal fun lerpArgb(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun ch(a: Int, b: Int) = (a + (b - a) * f).toInt().coerceIn(0, 255)
        return (255 shl 24) or
            (ch((from shr 16) and 0xFF, (to shr 16) and 0xFF) shl 16) or
            (ch((from shr 8) and 0xFF, (to shr 8) and 0xFF) shl 8) or
            ch(from and 0xFF, to and 0xFF)
    }

    /**
     * Resolve a font reference to a [Typeface], loading bundled
     * families from res/font and **user-imported** fonts from device
     * storage.
     *
     * 2026-09-19 PHASE 5 — [weight] (CSS 100..1000) resolves the
     * closest REAL bundled TTF via [FontFallback.weightResName]
     * (minSdk-safe file lookup — no API-28 `Typeface.create`, never
     * synthetic-only for multi-file families). Missing files fall
     * back to the family base, then:
     *  - Persian captions NEVER use the system font (broken joining
     *    on some devices): final fallback is bundled Vazirmatn;
     *  - Latin captions fall back to bundled Roboto (deterministic
     *    across OEM system fonts).
     * Size never changes on fallback (it lives in the style), so no
     * jump or clipping can occur.
     *
     * 2026-10-12 CUSTOM FONTS — a [familyName] of the form
     * `custom:<id>` (see [com.aicreatorstudio.app.model.CustomFont])
     * resolves to the imported file via `Typeface.createFromFile`
     * instead. This is the hook that gives preview and export **full
     * parity**, because this single function is what both surfaces
     * call: the Compose preview builds its `FontFamily` from the same
     * path, and the canvas bitmap renderer builds its `Typeface` here.
     *
     * Imported-font failure is a *soft* failure: an entry whose file
     * was deleted, truncated, or never copied resolves to the bundled
     * default for the script rather than returning null, so a saved
     * project that references a removed font still exports — it just
     * renders in Vazirmatn. That is the same policy the bundled path
     * already uses for a missing resource.
     *
     * @param resolveCustomFontPath maps a `custom:<id>` reference to an
     *   absolute file path, or null when the font is unknown or its
     *   file is gone. Injected rather than looked up here because this
     *   renderer is deliberately free of DI: the export pipeline passes
     *   a lambda closed over the repository, and a preview passes one
     *   closed over the ViewModel's list. Absent (the default), custom
     *   references fall through to the bundled fallback.
     */
    fun resolveTypeface(
        context: Context,
        typefaceCache: MutableMap<String, Typeface?>,
        familyName: String,
        persianFallback: Boolean,
        weight: Int = 400,
        resolveCustomFontPath: ((String) -> String?)? = null
    ): Typeface? {
        // ---- 1. User-imported font (custom:<id>) ----
        if (com.aicreatorstudio.app.model.CustomFont.isCustomFontId(familyName)) {
            val path = resolveCustomFontPath?.invoke(familyName)
            if (!path.isNullOrBlank()) {
                // Cache key is prefixed so a custom id can never collide
                // with a res/font name that happens to match it.
                val tf = typefaceCache.getOrPut(CUSTOM_CACHE_PREFIX + familyName) {
                    runCatching { Typeface.createFromFile(path) }.getOrNull()
                }
                if (tf != null) return tf
            }
            // Unknown/deleted/corrupt import: fall through to the
            // bundled default rather than returning null. `requested`
            // below becomes DEFAULT, which the existing logic maps to
            // Vazirmatn (Persian) or Roboto (Latin).
            return resolveBundledTypeface(
                context, typefaceCache, PersianFont.DEFAULT, persianFallback, weight
            )
        }

        // ---- 2. Bundled family ----
        val requested = PersianFont.from(familyName)
        return resolveBundledTypeface(context, typefaceCache, requested, persianFallback, weight)
    }

    /**
     * The bundled-family resolution, unchanged in behaviour — extracted
     * so the custom-font branch above can reuse it verbatim for its
     * fallback instead of duplicating the candidate walk.
     */
    private fun resolveBundledTypeface(
        context: Context,
        typefaceCache: MutableMap<String, Typeface?>,
        requested: PersianFont,
        persianFallback: Boolean,
        weight: Int
    ): Typeface? {
        val base: PersianFont = if (requested == PersianFont.DEFAULT) {
            if (persianFallback) PersianFont.VAZIR else PersianFont.ROBOTO
        } else requested
        // Weight-specific file first, family base second.
        val candidates = listOf(
            FontFallback.weightResName(base, weight),
            base.resName
        ).distinct()
        for (resName in candidates) {
            val tf = typefaceCache.getOrPut(resName) {
                runCatching {
                    val resId = context.resources.getIdentifier(
                        resName, "font", context.packageName
                    )
                    if (resId != 0) ResourcesCompat.getFont(context, resId) else null
                }.getOrNull()
            }
            if (tf != null) return tf
        }
        return null
    }

    /**
     * Cache-key namespace for imported fonts. Chosen so it can never
     * equal a `res/font` entry name (those are lowercase identifiers
     * with no colon).
     */
    private const val CUSTOM_CACHE_PREFIX = "custom-file:"
}
