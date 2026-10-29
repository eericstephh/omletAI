package com.aicreatorstudio.app.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Categories used to group transitions in the library UI. */
enum class TransitionCategory(val displayName: String) {
    FADE("Fade"),
    ZOOM("Zoom"),
    PUSH_SLIDE("Push & Slide"),
    SWIPE_WHIP("Swipe & Whip"),
    ROTATE_SPIN("Rotate & Spin"),
    DIMENSIONAL("3D"),
    GLITCH_RGB("Glitch & RGB"),
    FLASH("Flash"),
    SHAKE_BOUNCE("Shake & Bounce"),
    BLUR("Blur"),
    LIGHT_FILM("Light & Film"),
    WARP_DISTORT("Warp & Distort")
}

/** Every built-in transition. Add new entries here to extend the library. */
enum class TransitionType(val displayName: String, val category: TransitionCategory) {
    NONE("None", TransitionCategory.FADE),

    // ── 2026-09-18: standard blend transitions ────────────────
    CROSS_DISSOLVE("Cross Dissolve", TransitionCategory.FADE),
    FADE_TO_BLACK("Fade to Black", TransitionCategory.FADE),
    FADE_TO_WHITE("Fade to White", TransitionCategory.FADE),
    FADE_IN("Fade In", TransitionCategory.FADE),
    FADE_OUT("Fade Out", TransitionCategory.FADE),

    ZOOM_IN("Zoom In", TransitionCategory.ZOOM),
    ZOOM_OUT("Zoom Out", TransitionCategory.ZOOM),
    SMOOTH_ZOOM("Smooth Zoom", TransitionCategory.ZOOM),
    LENS_ZOOM("Lens Zoom", TransitionCategory.ZOOM),
    DIGITAL_ZOOM("Digital Zoom", TransitionCategory.ZOOM),
    ELASTIC_ZOOM("Elastic Zoom", TransitionCategory.ZOOM),

    PUSH_LEFT("Push Left", TransitionCategory.PUSH_SLIDE),
    PUSH_RIGHT("Push Right", TransitionCategory.PUSH_SLIDE),
    PUSH_UP("Push Up", TransitionCategory.PUSH_SLIDE),
    PUSH_DOWN("Push Down", TransitionCategory.PUSH_SLIDE),
    SLIDE_LEFT("Slide Left", TransitionCategory.PUSH_SLIDE),
    SLIDE_RIGHT("Slide Right", TransitionCategory.PUSH_SLIDE),
    SLIDE_UP("Slide Up", TransitionCategory.PUSH_SLIDE),
    SLIDE_DOWN("Slide Down", TransitionCategory.PUSH_SLIDE),

    // ── 2026-09-18: wipe transitions ─────────────────────────
    WIPE_LEFT("Wipe Left", TransitionCategory.WARP_DISTORT),
    WIPE_RIGHT("Wipe Right", TransitionCategory.WARP_DISTORT),
    WIPE_UP("Wipe Up", TransitionCategory.WARP_DISTORT),
    WIPE_DOWN("Wipe Down", TransitionCategory.WARP_DISTORT),

    SWIPE("Swipe", TransitionCategory.SWIPE_WHIP),
    WHIP_PAN("Whip Pan", TransitionCategory.SWIPE_WHIP),

    ROTATE("Rotate", TransitionCategory.ROTATE_SPIN),
    SPIN("Spin", TransitionCategory.ROTATE_SPIN),

    FLIP_3D("3D Flip", TransitionCategory.DIMENSIONAL),
    CUBE("Cube", TransitionCategory.DIMENSIONAL),

    GLITCH("Glitch", TransitionCategory.GLITCH_RGB),
    RGB_SPLIT("RGB Split", TransitionCategory.GLITCH_RGB),
    PIXEL_GLITCH("Pixel Glitch", TransitionCategory.GLITCH_RGB),
    VHS_TRANSITION("VHS", TransitionCategory.GLITCH_RGB),

    FLASH_WHITE("White Flash", TransitionCategory.FLASH),
    FLASH_BLACK("Black Flash", TransitionCategory.FLASH),

    SHAKE("Shake", TransitionCategory.SHAKE_BOUNCE),
    CAMERA_SHAKE("Camera Shake", TransitionCategory.SHAKE_BOUNCE),
    BOUNCE("Bounce", TransitionCategory.SHAKE_BOUNCE),
    ELASTIC("Elastic", TransitionCategory.SHAKE_BOUNCE),
    STRETCH("Stretch", TransitionCategory.SHAKE_BOUNCE),

    MOTION_BLUR("Motion Blur", TransitionCategory.BLUR),
    BLUR_IN("Blur In", TransitionCategory.BLUR),
    BLUR_OUT("Blur Out", TransitionCategory.BLUR),
    DIRECTIONAL_BLUR("Dir. Blur", TransitionCategory.BLUR),

    LIGHT_LEAK("Light Leak", TransitionCategory.LIGHT_FILM),
    FILM_BURN("Film Burn", TransitionCategory.LIGHT_FILM),

    RIPPLE("Ripple", TransitionCategory.WARP_DISTORT),
    WARP("Warp", TransitionCategory.WARP_DISTORT),
    DISTORTION("Distortion", TransitionCategory.WARP_DISTORT)
}

/** User-facing placement: entering, leaving, or both ends of the clip. */
enum class TransitionScope(val displayName: String) {
    IN("In"),
    OUT("Out"),
    BOTH("In + Out")
}

/** Playback direction passed into the engine evaluator. */
enum class TransitionDir { IN, OUT }

enum class TransitionEasing(val displayName: String) {
    LINEAR("Linear"),
    EASE_IN("Ease In"),
    EASE_OUT("Ease Out"),
    EASE_IN_OUT("In-Out"),
    BOUNCE("Bounce"),
    ELASTIC("Elastic")
}

/** Full configuration of one transition slot on a clip. */
data class TransitionConfig(
    val type: TransitionType = TransitionType.ZOOM_IN,
    val durationMs: Int = 500,
    val intensity: Float = 1f,
    val easing: TransitionEasing = TransitionEasing.EASE_IN_OUT
) {
    /** Compact serializable form stored in the database: TYPE:duration:intensity:EASING */
    fun encode(): String = "${type.name}:$durationMs:$intensity:${easing.name}"

    companion object {
        fun decode(raw: String?): TransitionConfig? = runCatching {
            val parts = raw?.split(":") ?: return null
            TransitionConfig(
                type = TransitionType.valueOf(parts[0]),
                durationMs = parts[1].toInt(),
                intensity = parts[2].toFloat(),
                easing = TransitionEasing.valueOf(parts[3])
            )
        }.getOrNull()
    }
}

/** Ready-to-use viral presets for short-form content. */
data class TransitionPreset(val displayName: String, val config: TransitionConfig)

object TransitionPresets {
    val viral: List<TransitionPreset> = listOf(
        TransitionPreset("Viral Zoom", TransitionConfig(TransitionType.ZOOM_IN, 350, 1.4f, TransitionEasing.EASE_OUT)),
        TransitionPreset("Smooth TikTok", TransitionConfig(TransitionType.SMOOTH_ZOOM, 600, 1.0f, TransitionEasing.EASE_IN_OUT)),
        TransitionPreset("Fast Swipe", TransitionConfig(TransitionType.SWIPE, 250, 1.3f, TransitionEasing.EASE_OUT)),
        TransitionPreset("Whip Transition", TransitionConfig(TransitionType.WHIP_PAN, 220, 1.5f, TransitionEasing.EASE_IN_OUT)),
        TransitionPreset("Beat Zoom", TransitionConfig(TransitionType.ZOOM_IN, 180, 1.8f, TransitionEasing.LINEAR)),
        TransitionPreset("Flash Impact", TransitionConfig(TransitionType.FLASH_WHITE, 160, 1.6f, TransitionEasing.EASE_OUT)),
        TransitionPreset("Glitch Impact", TransitionConfig(TransitionType.GLITCH, 260, 1.5f, TransitionEasing.LINEAR)),
        TransitionPreset("Cinematic", TransitionConfig(TransitionType.FADE_IN, 900, 1.0f, TransitionEasing.EASE_IN_OUT)),
        TransitionPreset("Dynamic Motion", TransitionConfig(TransitionType.CAMERA_SHAKE, 400, 1.3f, TransitionEasing.EASE_OUT)),
        TransitionPreset("Camera Shake", TransitionConfig(TransitionType.SHAKE, 350, 1.4f, TransitionEasing.EASE_IN_OUT)),
        TransitionPreset("Speed Ramp", TransitionConfig(TransitionType.MOTION_BLUR, 300, 1.5f, TransitionEasing.EASE_IN))
    )
}

/** Everything the GPU layer needs to render one moment of a transition. */
data class TransitionPose(
    val alpha: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val translationXDp: Float = 0f,
    val translationYDp: Float = 0f,
    val rotationZDeg: Float = 0f,
    val rotationXDeg: Float = 0f,
    val blurDp: Float = 0f,
    val flashWhite: Float = 0f,
    val flashBlack: Float = 0f
)

/**
 * Pure-Kotlin transition engine. Evaluates a [TransitionConfig] at any
 * phase progress (0f..1f) into a renderable [TransitionPose]. UI-agnostic
 * so it can drive previews, thumbnails, and future export rendering alike.
 */
object TransitionEngine {

    fun ease(easing: TransitionEasing, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (easing) {
            TransitionEasing.LINEAR -> x
            TransitionEasing.EASE_IN -> x * x
            TransitionEasing.EASE_OUT -> 1f - (1f - x) * (1f - x)
            TransitionEasing.EASE_IN_OUT -> x * x * (3f - 2f * x)
            TransitionEasing.BOUNCE -> bounce(x)
            TransitionEasing.ELASTIC -> elastic(x)
        }
    }

    private fun bounce(t: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        var x = t
        return when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> {
                x -= 1.5f / d1
                n1 * x * x + 0.75f
            }
            x < 2.5f / d1 -> {
                x -= 2.25f / d1
                n1 * x * x + 0.9375f
            }
            else -> {
                x -= 2.625f / d1
                n1 * x * x + 0.984375f
            }
        }
    }

    private fun elastic(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val c4 = (2f * PI.toFloat()) / 3f
        return (2f.pow(10f * (t - 1f)) * sin((t * 10f - 10.75f) * c4)) + 1f
    }

    /**
     * Evaluates the transition at [progress] (0f..1f inside the transition
     * window). For IN the pose starts extreme and settles to identity; for
     * OUT it starts at identity and grows to the extreme.
     */
    fun evaluate(config: TransitionConfig, dir: TransitionDir, progress: Float): TransitionPose {
        val eased = ease(config.easing, progress.coerceIn(0f, 1f))
        // Normalized "amount of effect": ramps 1->0 for IN, 0->1 for OUT.
        val a = (if (dir == TransitionDir.IN) 1f - eased else eased) *
            config.intensity.coerceIn(0.2f, 3f)
        val p = progress.coerceIn(0f, 1f)

        val pose = when (config.type) {
            TransitionType.NONE -> TransitionPose()

            // ── 2026-09-18: standard blend transitions ──────
            TransitionType.CROSS_DISSOLVE -> TransitionPose(
                alpha = 1f - a.coerceIn(0f, 1f),
                blurDp = 1.5f * a
            )
            TransitionType.FADE_TO_BLACK -> TransitionPose(
                alpha = 1f - a.coerceIn(0f, 1f),
                flashBlack = a.coerceIn(0f, 1f)
            )
            TransitionType.FADE_TO_WHITE -> TransitionPose(
                alpha = 1f - a.coerceIn(0f, 1f),
                flashWhite = a.coerceIn(0f, 1f)
            )
            TransitionType.FADE_IN, TransitionType.FADE_OUT ->
                TransitionPose(alpha = 1f - a.coerceIn(0f, 1f))

            // ── 2026-09-18: wipe transitions ────────────────
            TransitionType.WIPE_LEFT -> TransitionPose(
                translationXDp = (-430f + 430f * a).coerceIn(-430f, 0f),
                alpha = 1f
            )
            TransitionType.WIPE_RIGHT -> TransitionPose(
                translationXDp = (430f - 430f * a).coerceIn(0f, 430f),
                alpha = 1f
            )
            TransitionType.WIPE_UP -> TransitionPose(
                translationYDp = (-280f + 280f * a).coerceIn(-280f, 0f),
                alpha = 1f
            )
            TransitionType.WIPE_DOWN -> TransitionPose(
                translationYDp = (280f - 280f * a).coerceIn(0f, 280f),
                alpha = 1f
            )

            TransitionType.ZOOM_IN -> zoom(1f + 0.5f * a)
            TransitionType.ZOOM_OUT -> zoom((1f - 0.45f * a).coerceAtLeast(0.15f))
            TransitionType.SMOOTH_ZOOM -> zoom(1f + 0.28f * a)
            TransitionType.LENS_ZOOM -> TransitionPose(scaleX = 1f + 0.85f * a, scaleY = 1f + 0.85f * a, blurDp = 6f * a)
            TransitionType.DIGITAL_ZOOM -> {
                val step = (a * 4f).toInt() / 4f
                zoom(1f + 0.4f * step)
            }
            TransitionType.ELASTIC_ZOOM -> zoom(1f + 0.6f * a)

            TransitionType.PUSH_LEFT -> slide(tx = -430f * a)
            TransitionType.PUSH_RIGHT -> slide(tx = 430f * a)
            TransitionType.PUSH_UP -> slide(ty = -280f * a)
            TransitionType.PUSH_DOWN -> slide(ty = 280f * a)
            TransitionType.SLIDE_LEFT -> slide(tx = -340f * a, alpha = 1f - 0.3f * a)
            TransitionType.SLIDE_RIGHT -> slide(tx = 340f * a, alpha = 1f - 0.3f * a)
            TransitionType.SLIDE_UP -> slide(ty = -240f * a, alpha = 1f - 0.3f * a)
            TransitionType.SLIDE_DOWN -> slide(ty = 240f * a, alpha = 1f - 0.3f * a)

            TransitionType.SWIPE -> slide(tx = -320f * a, ty = -180f * a, alpha = 1f - 0.25f * a)
            TransitionType.WHIP_PAN -> TransitionPose(translationXDp = -520f * a, blurDp = 18f * a)

            TransitionType.ROTATE -> TransitionPose(rotationZDeg = 90f * a, scaleX = (1f - 0.2f * a), scaleY = (1f - 0.2f * a))
            TransitionType.SPIN -> TransitionPose(rotationZDeg = 360f * a, scaleX = 1f - 0.3f * a, scaleY = 1f - 0.3f * a)

            TransitionType.FLIP_3D -> TransitionPose(rotationXDeg = 70f * a, scaleY = 1f - 0.65f * a)
            TransitionType.CUBE -> TransitionPose(rotationXDeg = 55f * a, translationYDp = 120f * a, scaleX = 1f - 0.25f * a)

            TransitionType.GLITCH -> TransitionPose(
                translationXDp = sin(p * 97f) * 42f * a,
                translationYDp = cos(p * 83f) * 26f * a,
                alpha = 1f - 0.08f * a,
                flashBlack = 0.12f * a
            )
            TransitionType.RGB_SPLIT -> TransitionPose(
                translationXDp = sin(p * 80f) * 13f * a,
                blurDp = 2f * a,
                flashWhite = 0.06f * a
            )
            TransitionType.PIXEL_GLITCH -> TransitionPose(
                translationXDp = sin((p * 7f).toInt() * 31f) * 34f * a,
                scaleY = 1f + 0.05f * sin(p * 51f) * a,
                flashBlack = 0.2f * a
            )
            TransitionType.VHS_TRANSITION -> TransitionPose(
                translationXDp = sin(p * 43f) * 9f * a,
                alpha = 1f - 0.18f * abs(sin(p * 40f)),
                blurDp = 2f * a,
                flashWhite = 0.04f * a
            )

            TransitionType.FLASH_WHITE -> TransitionPose(flashWhite = a.coerceAtMost(1f))
            TransitionType.FLASH_BLACK -> TransitionPose(flashBlack = a.coerceAtMost(1f))

            TransitionType.SHAKE -> TransitionPose(
                translationXDp = sin(p * 55f) * 11f * a,
                translationYDp = cos(p * 47f) * 11f * a,
                rotationZDeg = sin(p * 61f) * 2.5f * a
            )
            TransitionType.CAMERA_SHAKE -> TransitionPose(
                translationXDp = sin(p * 33f) * 17f * a,
                translationYDp = cos(p * 29f) * 15f * a,
                rotationZDeg = sin(p * 37f) * 3.5f * a,
                scaleX = 1f + 0.03f * a
            )
            TransitionType.BOUNCE -> TransitionPose(scaleX = 1f - 0.18f * a, scaleY = 1f - 0.18f * a)
            TransitionType.ELASTIC -> TransitionPose(scaleX = 1f - 0.28f * a, scaleY = 1f - 0.28f * a)
            TransitionType.STRETCH -> TransitionPose(scaleX = 1f + 0.55f * a, scaleY = (1f - 0.3f * a).coerceAtLeast(0.3f))

            TransitionType.MOTION_BLUR -> TransitionPose(blurDp = 14f * a, translationXDp = 44f * a)
            TransitionType.BLUR_IN -> TransitionPose(blurDp = 16f * a)
            TransitionType.BLUR_OUT -> TransitionPose(blurDp = 16f * a)
            TransitionType.DIRECTIONAL_BLUR -> TransitionPose(blurDp = 12f * a, translationYDp = 22f * a)

            TransitionType.LIGHT_LEAK -> TransitionPose(flashWhite = 0.55f * a, blurDp = 3f * a)
            TransitionType.FILM_BURN -> TransitionPose(flashWhite = (a * a).coerceAtMost(1f), blurDp = 2.5f * a)

            TransitionType.RIPPLE -> TransitionPose(
                scaleX = 1f + 0.22f * a * sin(p * PI.toFloat() * 3f),
                scaleY = 1f + 0.22f * a * sin(p * PI.toFloat() * 3f)
            )
            TransitionType.WARP -> TransitionPose(
                scaleX = 1f + 0.3f * a * sin(p * PI.toFloat() * 2f),
                scaleY = 1f - 0.2f * a * sin(p * PI.toFloat() * 2f),
                rotationZDeg = sin(p * PI.toFloat() * 2f) * 6f * a
            )
            TransitionType.DISTORTION -> TransitionPose(
                scaleX = 1f + 0.25f * a * sin(p * 21f),
                scaleY = 1f + 0.25f * a * cos(p * 17f),
                blurDp = 2f * a
            )
        }
        return pose
    }

    private fun zoom(s: Float) = TransitionPose(scaleX = s, scaleY = s)
    private fun slide(tx: Float = 0f, ty: Float = 0f, alpha: Float = 1f) =
        TransitionPose(translationXDp = tx, translationYDp = ty, alpha = alpha)

    /**
     * Resolves the transition pose at a **clip-local** playhead position.
     *
     * This is the single source of truth for "which transition is running
     * right now, and how far through it are we". Both renderers call it:
     *
     * - the Compose preview passes `currentPositionMs - clipTimelineStartMs`
     *   and the clip's `effectiveDurationMs`;
     * - the export passes `outputTimeUs / 1000 * speed` (the Transformer's
     *   output clock runs faster than the source when speed > 1) and the
     *   segment's `sourceDurationMs`.
     *
     * Only one transition can be active at a time. When the In and Out
     * windows overlap — which happens whenever
     * `inDuration + outDuration > clipDuration` — the In window wins,
     * matching the original preview behaviour.
     *
     * @param clipLocalMs playhead position relative to the clip's own start
     * @param clipDurationMs the clip's own length; `<= 0` yields an identity
     *   pose rather than dividing by zero
     * @param transitionIn entering transition, or `null` for none
     * @param transitionOut leaving transition, or `null` for none
     */
    fun poseForClipLocal(
        clipLocalMs: Long,
        clipDurationMs: Long,
        transitionIn: TransitionConfig?,
        transitionOut: TransitionConfig?
    ): TransitionPose {
        if (clipDurationMs <= 0L) return TransitionPose()
        val localMs = clipLocalMs.coerceIn(0L, clipDurationMs)
        // A window can never be longer than the clip it plays over.
        val inWin = (transitionIn?.durationMs?.toLong() ?: 0L).coerceAtMost(clipDurationMs)
        val outWin = (transitionOut?.durationMs?.toLong() ?: 0L).coerceAtMost(clipDurationMs)

        if (transitionIn != null && inWin > 0L && localMs <= inWin) {
            return evaluate(transitionIn, TransitionDir.IN, localMs.toFloat() / inWin)
        }
        if (transitionOut != null && outWin > 0L && localMs >= clipDurationMs - outWin) {
            // Progress runs 0 -> 1 TOWARD THE CLIP'S END, which is what
            // [evaluate] documents for OUT ("starts at identity and grows
            // to the extreme") and what makes a leaving transition read
            // correctly: FADE_OUT reaches full black at the last frame,
            // ZOOM_OUT is widest at the last frame.
            //
            // The original preview code computed this as
            // `(clipDuration - localMs) / outWin`, i.e. 1 -> 0, which
            // INVERTED every leaving transition — FADE_OUT behaved exactly
            // like FADE_IN. That was invisible for as long as nothing
            // rendered transitions at all; it became visible the moment the
            // preview and the export started honouring them.
            val windowStartMs = clipDurationMs - outWin
            val p = (localMs - windowStartMs).toFloat() / outWin
            return evaluate(transitionOut, TransitionDir.OUT, p)
        }
        return TransitionPose()
    }

    /**
     * Computes the combined pose for the video preview at a playback
     * position, honoring whichever clip spans that position and its
     * In / Out configurations.
     */
    fun poseForCurrentPosition(clips: List<VideoClip>, positionMs: Long): TransitionPose {
        for (clip in clips) {
            val start = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
            val span = clip.effectiveDurationMs.coerceAtLeast(0L)
            if (span == 0L || positionMs < start || positionMs > start + span) continue

            return poseForClipLocal(
                clipLocalMs = positionMs - start,
                clipDurationMs = span,
                transitionIn = TransitionConfig.decode(clip.transitionIn),
                transitionOut = TransitionConfig.decode(clip.transitionOut)
            )
        }
        return TransitionPose()
    }
}
