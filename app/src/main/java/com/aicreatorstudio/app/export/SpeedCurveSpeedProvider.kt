package com.aicreatorstudio.app.export

import androidx.media3.common.C
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import com.aicreatorstudio.app.model.util.SpeedCurveInterpolator

/**
 * Adapts a [SpeedCurveInterpolator] to Media3's [SpeedProvider] so a
 * speed ramp can drive **both** halves of the export.
 *
 * ## Why one class serves the video and the audio
 *
 * `SpeedChangeEffect` (video, via `SpeedChangeShaderProgram`) and
 * `SpeedChangingAudioProcessor` (audio, via `SonicAudioProcessor`) both
 * take a [SpeedProvider] and both sample it on the **input** timeline.
 * Handing them the same instance is what keeps picture and sound locked:
 * there is one ramp, evaluated once, and neither side can drift.
 *
 * ## The time axis
 *
 * The provider is asked for a speed at a *source* timestamp — the
 * decoded frame's own PTS for video, and the sample's input timestamp
 * for audio. [SpeedCurveInterpolator] is keyed the same way, so this is
 * a straight delegation. Media3 additionally calls
 * `getNextSpeedChangeTimeUs` to learn where the current rate stops
 * applying, and the interpolator's staircase grid is exactly that
 * boundary list.
 *
 * **These two calls must agree**, because `SpeedChangeShaderProgram`
 * walks the boundaries in a loop and divides by the rate in force. If
 * `getSpeed(t)` changed at a time other than a boundary,
 * `SpeedProviderUtil`'s duration and the shader's accumulated output
 * PTS would disagree, and the result is the one thing worse than a
 * wrong length: a file whose audio and video drift apart. So
 * [getSpeed] is implemented by locating the slice that contains `t` and
 * returning *that slice's* rate — never a freshly sampled curve value.
 *
 * ## Constant `speed` multiplies the ramp
 *
 * The curve's rates are pre-multiplied by the clip's constant
 * [androidx.media3.common.audio.SpeedProvider] equivalent before this
 * provider is built (see [SpeedCurveInterpolator] and
 * [RenderSegment.speed]). That is why there is no `speed` parameter
 * here: by the time an instance exists, the multiplication has already
 * happened, so the video effect, the audio processor and
 * [RenderSegment.outputDurationMs] all read the same numbers.
 *
 * @param interpolator the ramp, already scaled by any constant speed.
 *   Its source duration must match the segment's range — this provider
 *   knows nothing about clipping.
 */
@UnstableApi
internal class SpeedCurveSpeedProvider(
    private val interpolator: SpeedCurveInterpolator
) : SpeedProvider {

    /**
     * Slice boundaries in absolute source microseconds, ascending,
     * starting at 0. `boundaries[i]` is where slice `i` begins; the
     * implicit end of the last slice is the source duration.
     */
    private val boundaries: LongArray = interpolator.speedChangeGridUs().toLongArray()

    /**
     * The rate for the slice containing [timeUs].
     *
     * Clamped at both ends rather than extrapolated: Media3 can ask
     * about a timestamp a hair past the last frame, and returning the
     * final rate there is correct, whereas evaluating the curve beyond
     * `1f` would depend on `SpeedCurve.speedAt`'s hold-last behaviour in
     * a way that is easy to break.
     */
    override fun getSpeed(timeUs: Long): Float {
        if (timeUs <= 0L) return interpolator.speedForSlice(0)
        val index = sliceIndexFor(timeUs)
        return interpolator.speedForSlice(index)
    }

    /**
     * The start of the next slice after [timeUs], or
     * [C.TIME_UNSET] when none remains.
     *
     * Media3's contract is explicit: "If `timeUs` corresponds to a
     * speed change, the returned value corresponds to the **following**
     * speed change." That is why this searches strictly greater-than
     * rather than greater-or-equal — returning the caller's own
     * timestamp would make the shader's walk loop forever on one
     * boundary.
     */
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
        if (boundaries.isEmpty()) return C.TIME_UNSET
        // bisectLeft: first index whose boundary is strictly > timeUs.
        var lo = 0
        var hi = boundaries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (boundaries[mid] > timeUs) hi = mid else lo = mid + 1
        }
        if (lo >= boundaries.size) return C.TIME_UNSET
        return boundaries[lo]
    }

    /**
     * Index of the slice that contains [timeUs] — the last boundary at
     * or before it.
     */
    private fun sliceIndexFor(timeUs: Long): Int {
        if (boundaries.isEmpty()) return 0
        var lo = 0
        var hi = boundaries.size
        // bisectRight: first index whose boundary is strictly > timeUs;
        // the slice is the one before it.
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (boundaries[mid] > timeUs) hi = mid else lo = mid + 1
        }
        return (lo - 1).coerceIn(0, interpolator.sliceCount - 1)
    }

    override fun toString(): String = "SpeedCurveSpeedProvider($interpolator)"
}
