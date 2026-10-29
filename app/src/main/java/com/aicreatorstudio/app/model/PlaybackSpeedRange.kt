package com.aicreatorstudio.app.model

/**
 * Single source of truth for the playback-speed range the editor
 * exposes.
 *
 * Before this existed the `0.25f..4f` clamp was copy-pasted across the
 * ViewModel (`setClipSpeed`, `onPlaybackSpeedChanged`), the preview
 * effects in `EditorScreen` and the Effects-panel slider range. Any one
 * of those drifting would have produced a UI that offers a speed the
 * player then silently rejects, so the bounds now live here and every
 * caller clamps through [clamp].
 *
 * [MIN] / [MAX] describe the *supported* range. The core product range
 * is 0.25×–2×; 4× is kept as an extra quick preset on top of it.
 */
object PlaybackSpeedRange {

    /** Slowest supported rate (0.25×). */
    const val MIN: Float = 0.25f

    /** Fastest supported rate (4×). */
    const val MAX: Float = 4f

    /** Quick-pick presets shown by the Speed panel, ascending. */
    val PRESETS: List<Float> = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

    /**
     * Clamps [speed] into the supported range.
     *
     * `Float.NaN` is mapped to `1f` (neutral) rather than propagated:
     * Kotlin's `coerceIn` returns NaN unchanged because every comparison
     * against NaN is false, and `ExoPlayer.setPlaybackParameters` throws
     * when the speed is not strictly positive — so an unguarded NaN
     * would crash the preview. Infinities clamp normally to [MAX] / [MIN].
     */
    fun clamp(speed: Float): Float =
        if (speed.isNaN()) 1f else speed.coerceIn(MIN, MAX)
}
