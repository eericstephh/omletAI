package com.aicreatorstudio.app.model

/**
 * One control point on a speed ramp.
 *
 * ## The time axis is the **source** clip, not the output
 *
 * [normalizedTime] is a fraction of the clip's **source** span (the range
 * `trimStartMs..trimEndMs`), *before* any retiming. That choice is
 * forced by the framework: Media3's `SpeedProvider` is sampled on the
 * **input** timeline — `SpeedChangeShaderProgram` asks for
 * `getSpeed(presentationTimeUs)` where `presentationTimeUs` is the
 * decoded frame's own timestamp — and
 * `SpeedProviderUtil.getDurationAfterSpeedProviderApplied` likewise
 * integrates `dt / speed(t)` over the input duration. A curve keyed on
 * output time would therefore have to be inverted before it could be
 * handed to Media3, and that inversion is not exact (the mapping is
 * only monotonic, not affine). Source time keeps the hardware path
 * exact and the UI honest — see [SpeedCurveInterpolator] for the two
 * directions and why the reverse one needs a lookup table.
 *
 * ## Why 0..1 and not milliseconds
 *
 * A normalised knot survives a trim: the user drags the trim handle and
 * the ramp keeps its shape relative to the footage, exactly like
 * `CropKeyframe` does for a tracked box. It also makes two ramps
 * comparable in tests without a clip.
 *
 * @param normalizedTime position in the source clip, `0f` = first frame,
 *   `1f` = last frame. Values outside `0..1` are clamped by
 *   [SpeedCurve.sanitized], because a knot past the end of the source
 *   has no frame to act on.
 * @param speed playback rate at that instant. `1f` is normal,
 *   `0.5f` is half speed (twice as long), `2f` is double speed. Must be
 *   finite and strictly positive — `ExoPlayer.setPlaybackParameters`
 *   and Media3's `SpeedChangeEffect` both reject `<= 0`.
 */
data class SpeedPoint(
    val normalizedTime: Float,
    val speed: Float
)

/**
 * Named speed-ramp shapes.
 *
 * [CUSTOM] means "the user has edited the knots" — it is the fallback
 * for every curve that does not exactly match a preset, and applying
 * [CUSTOM] is a no-op. The other five mirror the shapes an editor
 * reaches for most often; each is defined by [SpeedCurvePreset.points]
 * so the UI and the engine can never disagree about what "BULLET" means.
 */
enum class SpeedCurvePreset {
    /** User-edited knots. Selecting this preset changes nothing. */
    CUSTOM,

    /**
     * Slow build → explosive speed → super-slow landing → back to
     * normal. The "hero shot" ramp.
     */
    HERO,

    /**
     * Normal → super-slow → normal. The slow-motion beat.
     */
    BULLET,

    /**
     * Gentle speed-up through the middle. For condensing B-roll without
     * an obvious jump.
     */
    MONTAGE,

    /** Instant fast entry that settles to normal. */
    FLASH_IN,

    /** Normal that accelerates away at the tail. */
    FLASH_OUT;

    /**
     * The knots that define this preset, or `null` for [CUSTOM].
     *
     * Returned as a fresh list every call so a caller can hand it
     * straight to the user's editing state without aliasing a shared
     * constant.
     */
    val points: List<SpeedPoint>?
        get() = when (this) {
            CUSTOM -> null
            // 0% normal → 25% half → 40% 4x punch → 62% quarter-speed
            // landing → 100% normal. The punch is deliberately short:
            // a hero ramp reads as "fast" because of how abruptly it
            // arrives, not because the fast section is long.
            HERO -> listOf(
                SpeedPoint(0f, 1f),
                SpeedPoint(0.25f, 0.5f),
                SpeedPoint(0.40f, 4f),
                SpeedPoint(0.62f, 0.25f),
                SpeedPoint(1f, 1f)
            )
            // Normal, then a sustained super-slow beat across the
            // middle, then normal. The slow section is wide because
            // the whole point of a bullet-time beat is dwelling on it.
            BULLET -> listOf(
                SpeedPoint(0f, 1f),
                SpeedPoint(0.30f, 1f),
                SpeedPoint(0.38f, 0.15f),
                SpeedPoint(0.62f, 0.15f),
                SpeedPoint(0.70f, 1f),
                SpeedPoint(1f, 1f)
            )
            // A shallow, symmetric speed-up: 1x → 2x → 1x. Boring on
            // purpose — a montage pass should not draw attention.
            MONTAGE -> listOf(
                SpeedPoint(0f, 1f),
                SpeedPoint(0.5f, 2f),
                SpeedPoint(1f, 1f)
            )
            // Starts already fast and eases down to normal over the
            // first quarter. Ramps are evaluated from the *first*
            // knot, so a fast knot at 0 is what makes this an "in".
            FLASH_IN -> listOf(
                SpeedPoint(0f, 6f),
                SpeedPoint(0.25f, 1f),
                SpeedPoint(1f, 1f)
            )
            FLASH_OUT -> listOf(
                SpeedPoint(0f, 1f),
                SpeedPoint(0.75f, 1f),
                SpeedPoint(1f, 6f)
            )
        }
}

/**
 * A time-varying playback speed applied to one clip, replacing the
 * constant [VideoClip.speed] when non-null and usable.
 *
 * ## Relationship to [VideoClip.speed]
 *
 * `VideoClip.speed` is **not** superseded and must keep working: it is
 * the constant-speed control that has shipped since v1, it is a Room
 * column, and the Speed panel's preset chips write it. When
 * [isActive] is `true` this curve wins and `speed` is ignored; when it
 * is `false` nothing changes at all and the export takes the original
 * constant-speed path byte-for-byte. That is the whole
 * backward-compatibility story — see [isActive].
 *
 * ## Pitch
 *
 * [preservePitch] is always honoured by Media3: the audio path is
 * `SpeedChangingAudioProcessor`, which stretches through
 * `SonicAudioProcessor` and therefore never resamples. There is no
 * "chipmunk" mode to switch to, so the flag is carried for the record
 * (and for a future non-Sonic path) rather than toggling a branch
 * today. It defaults to `true`, which is also Media3's behaviour.
 *
 * @param points the knots, in any order; [sanitized] sorts, dedupes and
 *   clamps them. Fewer than two usable knots after sanitising means the
 *   ramp cannot express a change and [isActive] reports `false`.
 * @param preset the named shape these knots came from, or [SpeedCurvePreset.CUSTOM]
 *   once the user has moved a point. Purely descriptive — the engine
 *   reads [points], never the preset.
 * @param preservePitch keep the pitch while retiming. See above.
 */
data class SpeedCurve(
    val points: List<SpeedPoint> = EMPTY_POINTS,
    val preset: SpeedCurvePreset = SpeedCurvePreset.CUSTOM,
    val preservePitch: Boolean = true
) {
    /**
     * Whether this curve should drive playback.
     *
     * Three ways to be inactive, and all of them are silent rather than
     * an error: no curve at all (`null` on the clip), too few knots to
     * describe a ramp, or knots that all sit at exactly `1f`. The last
     * one matters most — a user who opens the curve editor and closes
     * it again produces five points at `1f`, and that must not put the
     * export on a different code path from an untouched project.
     */
    val isActive: Boolean
        get() {
            val clean = sanitized().points
            if (clean.size < 2) return false
            return clean.any { kotlin.math.abs(it.speed - 1f) > SPEED_EPSILON }
        }

    /**
     * The same curve with its knots sorted by time, clamped into
     * `0..1`, made strictly monotonic in time, and carrying finite
     * speeds inside [MIN_SPEED]..[MAX_SPEED].
     *
     * Called by every consumer instead of trusting the stored list,
     * because the points come from a UI that lets the user drag them
     * past each other and from a JSON blob that might predate this
     * feature. A duplicated timestamp is dropped rather than nudged:
     * two knots at the same instant would make the interpolator's
     * `t1 - t0` zero and divide by it.
     */
    fun sanitized(): SpeedCurve {
        if (points.isEmpty()) return this
        val scaled = points.map {
            SpeedPoint(
                normalizedTime = it.normalizedTime.coerceIn(0f, 1f),
                speed = if (it.speed.isFinite()) {
                    it.speed.coerceIn(MIN_SPEED, MAX_SPEED)
                } else {
                    1f
                }
            )
        }.sortedBy { it.normalizedTime }

        // Enforce strictly increasing time. A tie is resolved in favour
        // of the *later* knot, matching "the user's most recent drag
        // wins"; a duplicate must never survive, because the
        // interpolator divides by the gap between neighbours.
        val clean = ArrayList<SpeedPoint>(scaled.size)
        for (p in scaled) {
            val last = clean.lastOrNull()
            if (last == null || p.normalizedTime > last.normalizedTime) {
                clean.add(p)
            } else {
                clean[clean.size - 1] = p
            }
        }
        return copy(points = clean)
    }

    /**
     * The speed at source-normalised time [normalizedTime], linearly
     * interpolated between the surrounding knots and held constant
     * outside them.
     *
     * **Linear, deliberately, and this is a real design decision.** The
     * engine consumes this through Media3's `SpeedProvider`, which
     * treats the value as *piecewise constant* — see
     * [SpeedCurveInterpolator.toSpeedProvider]. Sampling a spline here
     * would therefore buy nothing at the boundary while making
     * [SpeedCurveInterpolator.totalOutputDurationMs] disagree with what
     * Media3 actually writes (the two would integrate different
     * functions). Linear keeps the on-screen curve, the predicted
     * duration and the exported file on the same numbers, which
     * matters more than a marginally prettier ramp. The *drawn* curve
     * is smoothed for looks; the *baked* curve is this one.
     */
    fun speedAt(normalizedTime: Float): Float {
        val clean = sanitized().points
        if (clean.isEmpty()) return 1f
        if (clean.size == 1) return clean[0].speed
        val t = normalizedTime.coerceIn(0f, 1f)
        val first = clean.first()
        if (t <= first.normalizedTime) return first.speed
        val last = clean.last()
        if (t >= last.normalizedTime) return last.speed
        for (i in 0 until clean.size - 1) {
            val a = clean[i]
            val b = clean[i + 1]
            if (t >= a.normalizedTime && t <= b.normalizedTime) {
                val span = b.normalizedTime - a.normalizedTime
                if (span <= 0f) return b.speed
                val f = (t - a.normalizedTime) / span
                return a.speed + (b.speed - a.speed) * f
            }
        }
        return last.speed
    }

    companion object {
        /** Lowest rate a knot may carry. */
        const val MIN_SPEED: Float = 0.1f

        /** Highest rate a knot may carry. */
        const val MAX_SPEED: Float = 10f

        /**
         * Below this, two speeds count as equal — used only to decide
         * whether a curve is worth activating, so it wants to be loose
         * enough that float noise never trips it.
         */
        const val SPEED_EPSILON: Float = 1e-3f

        private val EMPTY_POINTS: List<SpeedPoint> = emptyList()

        /** A flat `1f` ramp — the neutral curve the editor opens with. */
        val Flat: SpeedCurve = SpeedCurve(
            points = listOf(SpeedPoint(0f, 1f), SpeedPoint(1f, 1f)),
            preset = SpeedCurvePreset.CUSTOM
        )

        /** The knots for [preset], or [Flat]'s for [SpeedCurvePreset.CUSTOM]. */
        fun ofPreset(preset: SpeedCurvePreset): SpeedCurve {
            val pts = preset.points
            return if (pts == null) {
                Flat
            } else {
                SpeedCurve(points = pts, preset = preset)
            }
        }
    }
}
