package com.aicreatorstudio.app.model

/**
 * Which vertical edge of the canvas the video progress bar hugs.
 *
 * Two values only, by design: the bar is a *story timer* — a single
 * full-width strip that reads as "how much of this clip is left". A
 * free-floating variant would need its own drag/scale chrome and stop
 * being glanceable.
 */
enum class ProgressBarPosition {
    TOP,
    BOTTOM
}

/**
 * 2026-10-13 — **Video progress bar** (timeline indicator / story timer).
 *
 * A thin, full-width bar drawn **inside the canvas** that fills from 0%
 * to 100% as the playhead advances through the project. It is a project
 * level (not per-clip) setting: one bar for the exported file, exactly
 * like a story timer in a short-video app.
 *
 * ## Not to be confused with `ProgressBarSpec`
 *
 * `model/SubtitleTemplate.kt` already defines [ProgressBarSpec] /
 * [ProgressBarShape] — that is a **per-word highlight** drawn *inside a
 * caption box* (an underline that travels under the active word). This
 * type is unrelated: it is a canvas-wide timeline indicator owned by the
 * project, not by a subtitle template. The two deliberately share no
 * names beyond the "progress bar" phrase.
 *
 * ## Why a separate type instead of fields on `VideoProject`
 *
 * The bar has six coupled knobs that must travel together (position,
 * height, two colours, corner radius, margin). A `data class` gives them
 * one identity, one default, one `copy(...)` and one JSON codec, so a
 * persistence change cannot half-apply.
 *
 * ## Units
 *
 * The dp-named fields are **authoring units**, not screen dp. Both the
 * preview and the export convert them with the *same* canvas-height
 * convention that [com.aicreatorstudio.app.export.CaptionRenderer]
 * established for subtitles (`CANVAS_UNIT_PX = 720f`), via
 * [ProgressBarGeometry.dpToPx]. That is what makes a 4dp bar the same
 * fraction of the frame on a 1080p export and on the on-screen preview,
 * and it is why the preview must **not** use Compose's own
 * `Dp.toPx()` here (screen density has nothing to do with frame size).
 *
 * @param isEnabled off by default — an existing project exports
 *   byte-identically until the user turns the bar on.
 * @param position which edge the bar is pinned to.
 * @param heightDp bar thickness, clamped to
 *   `[MIN_HEIGHT_DP, MAX_HEIGHT_DP]` (2dp–12dp).
 * @param colorArgb the **fill** colour (the travelled part).
 * @param backgroundColorArgb the **track** colour (the remaining part).
 *   Semi-transparent white by default so it reads on any footage.
 * @param cornerRadiusDp `0` = square ends, `>= height/2` = a pill. The
 *   renderer clamps it to `height / 2` so a rounded bar can never become
 *   a lens shape.
 * @param marginDp inset from the canvas edge, applied to the pinned edge
 *   *and* symmetrically to the two sides, so the bar is inset all round.
 */
data class ProgressBarSettings(
    val isEnabled: Boolean = false,
    val position: ProgressBarPosition = ProgressBarPosition.BOTTOM,
    val heightDp: Float = 4f,
    val colorArgb: Int = 0xFFFF0055.toInt(),
    val backgroundColorArgb: Int = 0x33FFFFFF,
    val cornerRadiusDp: Float = 0f,
    val marginDp: Float = 0f
) {

    /**
     * The same settings with every numeric knob pulled into its legal
     * band and every non-finite value replaced by its default.
     *
     * Applied on **decode** as well as from the UI, so a hand-edited
     * JSON blob (or a blob written by a future build with a wider range)
     * can never produce a bar taller than the frame or a `NaN` geometry
     * that would throw inside `Canvas.drawRoundRect`.
     *
     * No value the panel can produce is ever altered by this: the panel's
     * sliders use exactly these bounds.
     */
    fun clamped(): ProgressBarSettings = ProgressBarSettings(
        isEnabled = isEnabled,
        position = position,
        heightDp = ProgressBarGeometry.clampOr(
            heightDp,
            ProgressBarGeometry.MIN_HEIGHT_DP,
            ProgressBarGeometry.MAX_HEIGHT_DP,
            Disabled.heightDp
        ),
        colorArgb = colorArgb,
        backgroundColorArgb = backgroundColorArgb,
        cornerRadiusDp = ProgressBarGeometry.clampOr(
            cornerRadiusDp,
            0f,
            ProgressBarGeometry.MAX_CORNER_RADIUS_DP,
            Disabled.cornerRadiusDp
        ),
        marginDp = ProgressBarGeometry.clampOr(
            marginDp,
            0f,
            ProgressBarGeometry.MAX_MARGIN_DP,
            Disabled.marginDp
        )
    )

    companion object {
        /**
         * The disabled default. Doubles as the fallback every legacy
         * project row decodes to, and as the "nothing to do" sentinel the
         * export checks before building an overlay.
         */
        val Disabled = ProgressBarSettings()
    }
}

/**
 * The pixel rectangle(s) the bar occupies in one frame.
 *
 * Plain floats and `Int`s on purpose. `unitTests.isReturnDefaultValues =
 * true` makes every `android.*` type inert in a JVM test (`RectF` returns
 * `0f` for every field), so a `RectF`-typed result would be asserted
 * against zeros and pass for the wrong reason. Both consumers take these
 * numbers as-is:
 *
 *  - the preview, via `DrawScope.drawRoundRect` (Compose draw space is
 *    already pixels);
 *  - the export, via `android.graphics.Canvas.drawRoundRect`.
 *
 * One geometry function feeding two renderers is what makes preview and
 * exported file agree **by construction** rather than by review.
 */
data class ProgressBarLayout(
    val trackLeft: Float,
    val trackTop: Float,
    val trackRight: Float,
    val trackBottom: Float,
    val fillLeft: Float,
    val fillTop: Float,
    val fillRight: Float,
    val fillBottom: Float,
    val cornerRadiusPx: Float,
    val trackColorArgb: Int,
    val fillColorArgb: Int
) {
    val trackWidth: Float get() = trackRight - trackLeft
    val trackHeight: Float get() = trackBottom - trackTop

    /** True when there is no bar to draw at all (degenerate canvas / height). */
    val isEmpty: Boolean get() = trackWidth <= 0f || trackHeight <= 0f

    /** True when the filled portion has any width. */
    val isFillVisible: Boolean get() = !isEmpty && fillRight > fillLeft
}

/**
 * Pure geometry + progress math for the video progress bar.
 *
 * Kept in the `model` layer — not in the Compose panel and not in the
 * export overlay — because that is the only place it can be unit-tested
 * without a device: `Media3TransformerExportEngine` needs a `Context` and
 * the panel needs a composition. Everything here is plain arithmetic over
 * `Float`/`Long`/`Int`.
 */
object ProgressBarGeometry {

    /**
     * Canvas height the dp conventions are authored against. **The same
     * constant `CaptionRenderer` uses for subtitles** — sharing it is what
     * makes "4dp tall" mean the same fraction of the frame in the preview
     * and in the exported file.
     */
    const val CANVAS_UNIT_PX: Float = 720f

    /** Thickness band the panel's slider offers. */
    const val MIN_HEIGHT_DP: Float = 2f
    const val MAX_HEIGHT_DP: Float = 12f

    /** Corner-radius band. The renderer additionally caps it at `height / 2`. */
    const val MAX_CORNER_RADIUS_DP: Float = 12f

    /** Inset band, per side. */
    const val MAX_MARGIN_DP: Float = 48f

    /**
     * Progress of [positionMs] through [durationMs], in `0f..1f`.
     *
     * Degenerate input returns `0f` rather than a `NaN`/`Infinity`: a
     * project whose duration is not known yet (a freshly-opened editor, a
     * zero-length trim) must render an **empty** bar, never a full one and
     * never a crash. A position past the end clamps to `1f`, which is the
     * honest reading — the timeline is over.
     */
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        if (positionMs <= 0L) return 0f
        if (positionMs >= durationMs) return 1f
        return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Converts an authoring dp into canvas pixels.
     *
     * `px = dp * canvasHeightPx / CANVAS_UNIT_PX`. A non-finite or
     * non-positive input returns `0f`, which the callers treat as "no
     * bar" — the safe direction, because a `NaN` reaching
     * `Canvas.drawRoundRect` throws and would abort the export pipeline.
     */
    fun dpToPx(dp: Float, canvasHeightPx: Float): Float {
        if (!dp.isFinite() || dp <= 0f) return 0f
        if (!canvasHeightPx.isFinite() || canvasHeightPx <= 0f) return 0f
        return dp * canvasHeightPx / CANVAS_UNIT_PX
    }

    /** `value` clamped into `[min, max]`, or `fallback` when it is not a usable number. */
    fun clampOr(value: Float, min: Float, max: Float, fallback: Float): Float {
        if (!value.isFinite()) return fallback
        return value.coerceIn(min, max)
    }

    /**
     * Resolves the bar's pixel geometry inside a `canvasWidthPx` ×
     * `canvasHeightPx` frame.
     *
     * The bar always spans the full canvas width minus [ProgressBarSettings.marginDp]
     * on each side, and is pinned to the chosen edge with the same margin.
     * The filled portion is the track's left edge extended by
     * `progress × trackWidth`, so the fill and the track share a left edge
     * and an exact vertical extent — the only difference between them is
     * how far right they reach.
     *
     * @param progress a fraction, clamped into `0f..1f`. Non-finite input
     *   is read as `0f`.
     * @return an [ProgressBarLayout] whose [ProgressBarLayout.isEmpty] is
     *   true for any degenerate input, so both renderers can skip drawing
     *   instead of guarding separately.
     */
    fun layout(
        settings: ProgressBarSettings,
        canvasWidthPx: Float,
        canvasHeightPx: Float,
        progress: Float
    ): ProgressBarLayout {
        val blank = ProgressBarLayout(
            trackLeft = 0f, trackTop = 0f, trackRight = 0f, trackBottom = 0f,
            fillLeft = 0f, fillTop = 0f, fillRight = 0f, fillBottom = 0f,
            cornerRadiusPx = 0f,
            trackColorArgb = settings.backgroundColorArgb,
            fillColorArgb = settings.colorArgb
        )
        if (!canvasWidthPx.isFinite() || !canvasHeightPx.isFinite()) return blank
        if (canvasWidthPx <= 0f || canvasHeightPx <= 0f) return blank

        // A bar taller than the frame is meaningless and, left unclamped,
        // would push `top` negative and paint outside the buffer. The
        // settings are already clamped at every entry point, so this only
        // ever fires for a caller that bypassed them — but "never throws,
        // never paints outside the frame" is cheap to guarantee here.
        val barHeight = dpToPx(settings.heightDp, canvasHeightPx)
            .coerceAtMost(canvasHeightPx)
        if (barHeight <= 0f) return blank

        val margin = dpToPx(settings.marginDp, canvasHeightPx)

        // Horizontal inset. Keep at least one pixel of bar even when the
        // margin is absurd relative to the frame, so `right > left` always
        // holds for a usable canvas.
        val maxInset = ((canvasWidthPx - 1f) / 2f).coerceAtLeast(0f)
        val inset = margin.coerceAtMost(maxInset)
        val left = inset
        val right = canvasWidthPx - inset
        if (right <= left) return blank

        // Vertical placement against the pinned edge, then pulled inside
        // the canvas: a margin larger than the frame must not push the bar
        // off-screen (and a negative top would draw nothing at all).
        val top = when (settings.position) {
            ProgressBarPosition.TOP -> margin
            ProgressBarPosition.BOTTOM -> canvasHeightPx - margin - barHeight
        }.coerceIn(0f, (canvasHeightPx - barHeight).coerceAtLeast(0f))
        val bottom = top + barHeight

        // A pill is `height / 2`; anything more would bow the ends inward.
        val radius = dpToPx(settings.cornerRadiusDp, canvasHeightPx)
            .coerceIn(0f, barHeight / 2f)

        val fraction = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        val fillRight = left + (right - left) * fraction

        return ProgressBarLayout(
            trackLeft = left,
            trackTop = top,
            trackRight = right,
            trackBottom = bottom,
            fillLeft = left,
            fillTop = top,
            fillRight = fillRight,
            fillBottom = bottom,
            cornerRadiusPx = radius,
            trackColorArgb = settings.backgroundColorArgb,
            fillColorArgb = settings.colorArgb
        )
    }
}
