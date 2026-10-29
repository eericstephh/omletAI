package com.aicreatorstudio.app.ai

import kotlin.math.abs

/**
 * Temporal stabiliser for the matting mask.
 *
 * The segmenter runs once per sampled frame and has no memory, so the
 * raw mask flickers: a pixel that belongs to the subject in frame N can
 * be background in frame N+1 and back again. On a 10 fps sidecar that
 * reads as a shimmering outline and, worse, as short-lived ghost blobs
 * where the model briefly hallucinates a limb or a hand.
 *
 * ### Why the previous EMA was replaced rather than retuned
 *
 * The old filter was one line — `fg = 0.65 * current + 0.35 * previous` —
 * and it could not be fixed by moving 0.65, because the two things it had
 * to do pull in opposite directions:
 *
 * * slow enough to damp noise → a one-frame false positive became a
 *   **0.65-alpha ghost** that then decayed 0.39, 0.23, 0.13… i.e. a blob
 *   that existed for 100 ms stayed visible for roughly half a second;
 * * fast enough to follow motion → it trailed the subject's edge.
 *
 * Raising the weight fixed the trail and made the ghost worse; lowering
 * it did the reverse. The mistake was using an average at all: an average
 * can only *spread* a bad measurement, never *discard* it.
 *
 * ### What this does instead
 *
 * Two stages, and deliberately no third:
 *
 * 1. **Isolated-change rejection.** Each pixel's value is the median of
 *    `current`, `previous` and `two-frames-ago`. A value that appears for
 *    exactly one frame is a minority of three and is dropped **entirely**
 *    — that is what kills the ghost blobs, rather than dimming them. A
 *    value that persists becomes a majority on the second frame, so the
 *    only cost is a **one-frame delay on genuinely new content**.
 * 2. **Scene-cut reset.** When the frame differs from the previous one by
 *    [sceneCutDiff] or more, the subject has changed (a cut, a hard pan, a
 *    different person). Blending across that boundary is what drags the
 *    old silhouette along for several frames, so the history is dropped
 *    and the frame is returned as measured.
 *
 * **There is no exponential average on top, on purpose.** A median already
 * guarantees that nothing is emitted that was not a majority of the last
 * three measurements; blending that result with previous *output* only
 * reintroduces the decay the median just removed — a moving edge that the
 * median correctly holds back for one frame would then fade out over the
 * following four. That combination was written first and the test
 * `real motion is trusted immediately instead of trailing` is what caught
 * it.
 *
 * ### Consequences worth knowing
 *
 * * The output is **binary** for the current segmenter (the model's mask
 *   is a category mask, so every input value is 0 or 1). Soft edges are
 *   the job of the feather pass at composition time
 *   ([MaskPostProcess.gaussianFeather]), not of this class. An earlier
 *   KDoc in the engine claimed a "soft knee" produced partial alpha here;
 *   there never was one.
 * * The filter accepts real probabilities, so it keeps working unchanged
 *   if the segmenter is later switched to confidence masks.
 * * Everything is O(pixels) with one output allocation per frame, because
 *   this runs inside the sampling loop next to inference.
 *
 * ### Not thread-safe, by design
 *
 * One instance belongs to exactly one run. The state is per-run (not a
 * field on the singleton engine) so two overlapping clips cannot smooth
 * their masks against each other — the 2026-10-20 regression.
 *
 * ### What this is not
 *
 * This is not a temporal *model*. CapCut runs a GRU over the mask at
 * `frashevery 15` (`docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-10-25.md`
 * §10, item 5); a recurrence that learns what "the same subject" looks
 * like cannot be reproduced by per-pixel statistics. This class closes the
 * part of that gap that needs no new weights — flicker, ghost blobs and
 * cut ghosting — and it removes the noise a future model would otherwise
 * have to learn around.
 */
class MattingTemporalFilter(
    private val sceneCutDiff: Float = DEFAULT_SCENE_CUT_DIFF
) {
    init {
        require(sceneCutDiff > 0f && sceneCutDiff <= 1f) {
            "sceneCutDiff must be in (0, 1], was $sceneCutDiff"
        }
    }

    /** Two frames back, as measured. One of the three median samples. */
    private var prev2: FloatArray? = null

    /** One frame back, as measured. One of the three median samples. */
    private var prev1: FloatArray? = null

    private var width: Int = 0
    private var height: Int = 0

    /**
     * Mean absolute difference between the last frame and the one before
     * it, in 0..1. For a binary mask this is exactly the fraction of
     * pixels that changed class. Exposed for diagnostics; reading it never
     * changes behaviour.
     */
    var lastChangeFraction: Float = 0f
        private set

    /** True when the last [apply] call treated its input as a scene cut. */
    var lastWasSceneCut: Boolean = false
        private set

    /** Drop all history. The next [apply] returns its input unchanged. */
    fun reset() {
        prev2 = null
        prev1 = null
        width = 0
        height = 0
        lastChangeFraction = 0f
        lastWasSceneCut = false
    }

    /**
     * Filter one frame of foreground probability (values in 0..1; the
     * current segmenter emits 0/1, a confidence mask would emit the real
     * probability — both are handled).
     *
     * Returns a **new** array; [current] is never mutated.
     *
     * A size change (different clip, different analysis bound) resets the
     * history rather than blending incompatible grids.
     */
    fun apply(current: FloatArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0) { "size must be positive, was ${width}x$height" }
        require(current.size == width * height) {
            "buffer holds ${current.size} values but ${width}x$height needs ${width * height}"
        }
        val n = current.size
        val p1 = prev1
        val p2 = prev2

        // No usable history: this frame defines it.
        if (p1 == null || p2 == null || p1.size != n ||
            this.width != width || this.height != height
        ) {
            val out = current.copyOf()
            prev2 = out.copyOf()
            prev1 = out.copyOf()
            this.width = width
            this.height = height
            lastChangeFraction = 0f
            lastWasSceneCut = false
            return out
        }

        // Mean absolute difference from the previous measurement.
        var diffSum = 0f
        for (i in 0 until n) {
            diffSum += abs(current[i] - p1[i])
        }
        val change = diffSum / n
        lastChangeFraction = change

        // Scene cut: blending across it would drag the old silhouette
        // along, so restart from the measurement and collapse the history
        // to it (the next frame must be judged against the new subject).
        if (change >= sceneCutDiff) {
            val out = current.copyOf()
            prev2 = out.copyOf()
            prev1 = out.copyOf()
            lastWasSceneCut = true
            return out
        }
        lastWasSceneCut = false

        // Per-pixel median of three. Branchless: max(lo, min(hi, p2)).
        val out = FloatArray(n)
        for (i in 0 until n) {
            val c = current[i]
            val a = p1[i]
            val b = p2[i]
            val lo = if (c < a) c else a
            val hi = if (c < a) a else c
            out[i] = if (hi < b) hi else if (lo > b) lo else b
        }

        // Shift the window. `current` is owned by the caller, so copy it —
        // the sampler recycles the bitmap it came from and a future caller
        // could reuse the buffer.
        prev2 = p1.copyOf()
        prev1 = current.copyOf()
        return out
    }

    companion object {
        /**
         * Mean absolute mask change that counts as a scene cut. 0.35 is
         * far above anything ordinary motion produces between two samples
         * 100 ms apart (a fast pan changes a few percent) and well below a
         * cut to a different subject. Deliberately conservative: a false
         * cut costs one un-stabilised frame, a missed cut costs several
         * frames of ghost.
         */
        const val DEFAULT_SCENE_CUT_DIFF: Float = 0.35f
    }
}
