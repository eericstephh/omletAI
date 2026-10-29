package com.aicreatorstudio.app.model

/**
 * Pure gain-curve math for per-clip audio fades.
 *
 * Extracted into its own object so the export's audio processor, the live
 * preview's volume driver and the unit tests all share **one** definition of
 * "what gain is this clip at right now". Duplicating the ramp is how the two
 * renderers would drift apart.
 *
 * A fade is linear in **amplitude**, matching the `AudioClip` KDoc ("linear
 * fade-in duration"), so the gain is a straight ramp rather than a
 * perceptual/log curve.
 *
 * Nothing here touches Android, so it is testable on the JVM — this project
 * has no Robolectric.
 */
object AudioFade {

    /** `true` when neither end has a fade, i.e. the clip plays at unity gain. */
    fun isIdentity(fadeInMs: Long, fadeOutMs: Long): Boolean =
        fadeInMs <= 0L && fadeOutMs <= 0L

    /**
     * Gain at [clipLocalMs] into a clip whose output length is
     * [clipDurationMs].
     *
     * Both windows are clamped to the clip, so a fade longer than the clip
     * simply spans it instead of stalling part-way through. When the In and
     * Out windows overlap, the **smaller** gain wins: the clip dips in the
     * middle rather than one fade silently cancelling the other.
     *
     * @param clipLocalMs position inside the clip, in **output** time (i.e.
     *   after any speed change), `<= 0` before the clip and `>= duration`
     *   after it
     * @param clipDurationMs the clip's own output length; `<= 0` yields unity
     *   rather than dividing by zero
     * @return the gain multiplier in `0f..1f`
     */
    fun gainAt(
        clipLocalMs: Double,
        clipDurationMs: Long,
        fadeInMs: Long,
        fadeOutMs: Long
    ): Float {
        if (isIdentity(fadeInMs, fadeOutMs)) return 1f
        if (clipDurationMs <= 0L) return 1f
        if (!clipLocalMs.isFinite()) return 1f

        val duration = clipDurationMs.toDouble()
        val local = clipLocalMs.coerceIn(0.0, duration)

        // A window can never be longer than the clip it plays over.
        val inWindow = fadeInMs.coerceIn(0L, clipDurationMs).toDouble()
        val outWindow = fadeOutMs.coerceIn(0L, clipDurationMs).toDouble()

        var gain = 1.0
        if (inWindow > 0.0 && local < inWindow) {
            gain = local / inWindow
        }
        if (outWindow > 0.0) {
            val remaining = duration - local
            if (remaining < outWindow) {
                gain = minOf(gain, remaining / outWindow)
            }
        }
        return gain.coerceIn(0.0, 1.0).toFloat()
    }
}
