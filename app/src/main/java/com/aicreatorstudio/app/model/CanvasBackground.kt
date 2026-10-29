package com.aicreatorstudio.app.model

/**
 * How the editor fills the *letterbox* area — the region inside the
 * canvas box that the source video doesn't cover, either because the
 * source ratio differs from the canvas ratio (e.g. 9:16 source on a 1:1
 * canvas) or because the user has manually zoomed the clip smaller than
 * the canvas.
 *
 * **2026-09-16 — a solid colour now reaches the exported file.**
 * `CanvasBackground` used to be purely a preview concept: the export
 * transcoded the source at the canvas's pixel dimensions and left every
 * uncovered area black, so the BG panel's swatches changed the screen and
 * nothing else. [SolidColor] is now baked by
 * [com.aicreatorstudio.app.export.CanvasBackgroundOverlay], which paints the
 * bars over the frame once the `Presentation` has created them — see
 * [CanvasLetterbox] for the geometry and for why painting *over* the bars is
 * equivalent to painting *behind* the video.
 *
 * The **fade** family washes toward the same colour too, so a clip that fades
 * in or out on a coloured canvas dims toward the bars rather than toward black.
 * That is a second, independent consumer of the colour: the preview fades a clip
 * by compositing it over the letterbox `Box`, so the export's fade wash must use
 * the canvas colour as well. See
 * [CanvasLetterbox.fadeColorArgb] — the one place that decides it — and
 * [com.aicreatorstudio.app.export.ClipMotionEffects] for the pass.
 *
 * One gap remains, deliberate and reported rather than hidden:
 *
 *  - [Blur] is still preview-only. It needs a *second video layer*, which is a
 *    `Composition` carrying two sequences and a `VideoCompositor` — a change to
 *    the timeline model, not a field.
 */
sealed class CanvasBackground {

    /** Solid black. The default. */
    data object Black : CanvasBackground()

    /**
     * A user-selected solid color. [colorValue] is `0xAARRGGBB` as a
     * [Long] so the field stays a primitive (avoids Compose-UI types
     * leaking into the data layer). The UI layer maps it to
     * `androidx.compose.ui.graphics.Color` for rendering.
     */
    data class SolidColor(val colorValue: Long) : CanvasBackground()

    /**
     * The letterbox area is filled with a heavily-blurred, scaled-up
     * copy of the underlying video. The implementation in
     * `EditorVideoPreviewSection` renders a second `PlayerView` with
     * `resizeMode = ZOOM` and `Modifier.blur(36.dp)` behind the
     * foreground player.
     */
    data object Blur : CanvasBackground()
}
