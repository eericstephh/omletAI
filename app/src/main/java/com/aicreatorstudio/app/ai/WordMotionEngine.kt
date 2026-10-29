package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.TextOverlay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * 2026-09-14 VIRAL MOTION ENGINE — pure, deterministic math for
 * every [com.aicreatorstudio.app.model.WordMotionKind]. Given the
 * overlay's word timings and the current playhead, it computes the
 * per-word visual state (alpha / scale / offsetX / offsetY /
 * fillFraction / visibleCharacters). Both the Compose preview and
 * the export renderer call the SAME functions so preview and
 * burned-in output never drift apart.
 *
 * No Compose, no Android types — fully JVM unit-testable.
 */
object WordMotionEngine {

    /** Visual state of one word at a point in time. */
    data class WordState(
        /** 0..1 visibility (TYPEWRITER/FADE_SCALE use partial). */
        val alpha: Float,
        /** Scale multiplier (1 = rest). */
        val scale: Float,
        /** Horizontal offset fraction of text height (SHAKE). */
        val offsetXFraction: Float,
        /** Vertical offset fraction of text height (SLIDE/RISE). */
        val offsetYFraction: Float,
        /**
         * TYPEWRITER: how many leading characters are revealed
         * (0..word.length). Other kinds: full length.
         */
        val visibleCharacters: Int,
        /**
         * KARAOKE_FILL: 0..1 fill of the word's color from the
         * reading edge (right for RTL). Other kinds: 0.
         */
        val fillFraction: Float
    ) {
        companion object {
            val Rest = WordState(1f, 1f, 0f, 0f, Int.MAX_VALUE, 0f)
        }
    }

    /** Cheap RTL probe — one Persian/Arabic char ⇒ RTL word. */
    fun isRtlWord(word: String): Boolean =
        word.any { c -> c.code in 0x0600..0x06FF || c.code in 0xFB50..0xFDFF }

    /**
     * Compute the visual state of [word] (index [wordIndex] of
     * [allWords]) at [timeMs] under the overlay's motion kind.
     *
     * @param overlay the caption carrying the motion config
     *   (motionKind, animationDurationMs, activeWordScale…).
     * @param timeMs the CURRENT playhead (timeline ms).
     */
    fun wordStateAt(
        overlay: TextOverlay,
        allWords: List<TranscribedWord>,
        wordIndex: Int,
        timeMs: Long
    ): WordState {
        val word = allWords.getOrNull(wordIndex) ?: return WordState.Rest
        val kind = overlay.motionKind
        if (kind == com.aicreatorstudio.app.model.WordMotionKind.NONE) {
            return WordState.Rest
        }
        val wordStart = word.startTimeMs
        val wordEnd = word.endTimeMs
        val durMs = overlay.durationMs.coerceAtLeast(1L)
        val effectMs = overlay.animationDurationMs.coerceIn(60, 1500)

        // Word-level timing may be timeline-shifted (the mapper
        // offsets words by the clip anchor). The overlay window
        // spans overlay.startTimeMs..endTimeMs; normalize the word's
        // window against the overlay window so both share the
        // playhead's timeline space.
        val overlayStart = overlay.startTimeMs
        val overlayEnd = overlay.endTimeMs
        val span = (overlayEnd - overlayStart).coerceAtLeast(1L)
        val relStart = (wordStart - overlayStart).coerceIn(0L, span)
        val relEnd = (relStart + (wordEnd - wordStart).coerceAtLeast(1L))
            .coerceAtMost(span)
        val relNow = (timeMs - overlayStart).coerceIn(0L, span)

        return when (kind) {
            com.aicreatorstudio.app.model.WordMotionKind.HIGHLIGHT_COLOR ->
                if (relNow in relStart until relEnd) WordState.Rest else WordState.Rest

            com.aicreatorstudio.app.model.WordMotionKind.BOUNCE_POP -> {
                // Spring: quick overshoot then settle. Uses a
                // damped sine for a real bounce feel.
                if (relNow < relStart) hiddenWord(word)
                else {
                    val t = ((relNow - relStart).toFloat() / effectMs).coerceAtLeast(0f)
                    val active = relNow < relEnd
                    if (active) {
                        val bounce = if (t < 1f) {
                            abs(sin(t * (Math.PI * 2.5).toFloat())) * (1f - t) * 0.35f
                        } else 0f
                        WordState(
                            alpha = 1f,
                            scale = overlay.activeWordScale + bounce,
                            offsetXFraction = 0f,
                            offsetYFraction = -bounce * 0.4f,
                            visibleCharacters = word.text.length,
                            fillFraction = 0f
                        )
                    } else WordState.Rest
                }
            }

            com.aicreatorstudio.app.model.WordMotionKind.TYPEWRITER -> {
                // Characters appear across the word's own window;
                // the word stays once finished.
                if (relNow < relStart) hiddenWord(word)
                else {
                    val w = (relEnd - relStart).coerceAtLeast(1L)
                    val frac = ((relNow - relStart).toFloat() / w).coerceIn(0f, 1f)
                    val chars = (frac * word.text.length).toInt()
                    WordState(
                        alpha = 1f,
                        scale = 1f,
                        offsetXFraction = 0f,
                        offsetYFraction = 0f,
                        visibleCharacters = chars.coerceAtLeast(1),
                        fillFraction = 0f
                    )
                }
            }

            com.aicreatorstudio.app.model.WordMotionKind.SLIDE_UP_WORD -> {
                if (relNow < relStart) WordState.Rest.copy(alpha = 0f)
                else {
                    val t = ((relNow - relStart).toFloat() / effectMs).coerceIn(0f, 1f)
                    val ease = 1f - (1f - t) * (1f - t)   // ease-out quad
                    val active = relNow < relEnd
                    WordState(
                        alpha = ease,
                        scale = if (active) overlay.activeWordScale else 1f,
                        offsetXFraction = 0f,
                        offsetYFraction = (1f - ease) * 0.5f,
                        visibleCharacters = word.text.length,
                        fillFraction = 0f
                    )
                }
            }

            com.aicreatorstudio.app.model.WordMotionKind.FADE_SCALE -> {
                if (relNow < relStart) WordState.Rest.copy(alpha = 0f)
                else {
                    val t = ((relNow - relStart).toFloat() / effectMs).coerceIn(0f, 1f)
                    val active = relNow < relEnd
                    val target = if (active) overlay.activeWordScale else 1f
                    WordState(
                        alpha = t,
                        scale = 0.7f + 0.3f * t + (target - 1f) * t,
                        offsetXFraction = 0f,
                        offsetYFraction = 0f,
                        visibleCharacters = word.text.length,
                        fillFraction = 0f
                    )
                }
            }

            com.aicreatorstudio.app.model.WordMotionKind.SHAKE_WIGGLE -> {
                val active = relNow in relStart until relEnd
                if (!active) WordState.Rest
                else {
                    val t = (relNow - relStart).toFloat()
                    val shake = sin(t / 40f * (Math.PI * 2f).toFloat()) *
                        (1f - ((relNow - relStart).toFloat() /
                            (relEnd - relStart).coerceAtLeast(1L)).coerceIn(0f, 1f) * 0.5f)
                    WordState(
                        alpha = 1f,
                        scale = 1f + overlay.activeWordScale * 0.1f,
                        offsetXFraction = shake * 0.12f,
                        offsetYFraction = shake * 0.06f,
                        visibleCharacters = word.text.length,
                        fillFraction = 0f
                    )
                }
            }

            com.aicreatorstudio.app.model.WordMotionKind.NEON_GLOW ->
                WordState.Rest   // glow handled by the renderers

            com.aicreatorstudio.app.model.WordMotionKind.KARAOKE_FILL -> {
                if (relNow < relStart) WordState.Rest.copy(fillFraction = 0f)
                else {
                    val w = (relEnd - relStart).coerceAtLeast(1L)
                    val frac = ((relNow - relStart).toFloat() / w).coerceIn(0f, 1f)
                    WordState(
                        alpha = 1f,
                        scale = 1f,
                        offsetXFraction = 0f,
                        offsetYFraction = 0f,
                        visibleCharacters = word.text.length,
                        fillFraction = frac
                    )
                }
            }

            com.aicreatorstudio.app.model.WordMotionKind.RISE_BOUNCE -> {
                // Whole-caption motion — per word only the bounce
                // of the caption window matters; word itself rests
                // once the caption has risen.
                val t = ((relNow).toFloat() / effectMs).coerceAtLeast(0f)
                val eased = if (t >= 1f) 1f else {
                    // overshoot bounce curve
                    val b = 1f - abs(cos(t * (Math.PI * 1.2).toFloat())) * (1f - t)
                    b.coerceIn(0f, 1.2f)
                }
                if (relNow < relStart) WordState.Rest
                else WordState(
                    alpha = 1f,
                    scale = 1f,
                    offsetXFraction = 0f,
                    offsetYFraction = (1f - eased.coerceAtMost(1f)) * 1.5f,
                    visibleCharacters = word.text.length,
                    fillFraction = 0f
                )
            }

            com.aicreatorstudio.app.model.WordMotionKind.GLITCH -> {
                // PHASE 8 — deterministic digital distortion.
                // Random horizontal offset + brief flicker, seeded
                // by word ID + frame for byte-identical preview/export.
                val active = relNow in relStart until relEnd
                if (!active) WordState.Rest
                else {
                    val frameMs = relNow.toInt().toLong()
                    val dx = EasingFunctions.deterministicShake(word.id, frameMs)
                    val flicker = EasingFunctions.deterministicRandom(word.id + "f", frameMs)
                    WordState(
                        alpha = if (flicker > 0.15f) 1f else 0.3f,
                        scale = 1f + abs(dx) * 0.08f,
                        offsetXFraction = dx * 0.15f,
                        offsetYFraction = dx * 0.04f,
                        visibleCharacters = word.text.length,
                        fillFraction = 0f
                    )
                }
            }

            else -> WordState.Rest
        }
    }

    /** A word not yet revealed (TYPEWRITER/BOUNCE pre-state). */
    private fun hiddenWord(word: TranscribedWord): WordState = WordState(
        alpha = 0f,
        scale = 1f,
        offsetXFraction = 0f,
        offsetYFraction = 0f,
        visibleCharacters = 0,
        fillFraction = 0f
    )

    /** Whole-caption entrance/exit transform (RISE_BOUNCE uses it). */
    fun captionTransformAt(overlay: TextOverlay, timeMs: Long): CaptionTransform {
        val inAnim = overlay.textAnimation
        val outAnim = overlay.textAnimationOut
        val effectMs = overlay.animationDurationMs.coerceIn(60, 1500).toFloat()
        // PHASE 10E — exit uses its own duration when set.
        val outEffectMs = (overlay.animationDurationMsOut ?: overlay.animationDurationMs)
            .coerceIn(60, 1500).toFloat()
        val sinceStart = (timeMs - overlay.startTimeMs).toFloat()
        val untilEnd = (overlay.endTimeMs - timeMs).toFloat()

        // PHASE 8 — resolve easing presets (pure functions of t).
        val inEasing = EasingFunctions.resolve(
            EasingFunctions.fromString(overlay.easingIn)
        )
        val outEasing = EasingFunctions.resolve(
            EasingFunctions.fromString(overlay.easingOut)
        )

        var alpha = 1f
        var scale = 1f
        var offsetY = 0f
        var offsetX = 0f
        when (inAnim) {
            com.aicreatorstudio.app.model.TextOverlayAnimation.FADE_IN,
            com.aicreatorstudio.app.model.TextOverlayAnimation.FADE_IN_OUT -> {
                if (sinceStart < effectMs) {
                    val raw = (sinceStart / effectMs).coerceIn(0f, 1f)
                    alpha *= inEasing(raw)
                }
            }
            com.aicreatorstudio.app.model.TextOverlayAnimation.POP -> {
                if (sinceStart < effectMs) {
                    val raw = (sinceStart / effectMs).coerceIn(0f, 1f)
                    alpha *= inEasing(raw)
                    // 0.6 -> 1.0 with overshoot-safe easing (max ~1.17).
                    scale *= 0.6f + 0.4f * inEasing(raw)
                }
            }
            com.aicreatorstudio.app.model.TextOverlayAnimation.SLIDE_UP -> {
                if (sinceStart < effectMs) {
                    val raw = (sinceStart / effectMs).coerceIn(0f, 1f)
                    alpha *= inEasing(raw)
                    offsetY += (1f - inEasing(raw)) * 0.08f
                }
            }
            com.aicreatorstudio.app.model.TextOverlayAnimation.GLITCH_IN -> {
                if (sinceStart < effectMs) {
                    val raw = (sinceStart / effectMs).coerceIn(0f, 1f)
                    alpha *= inEasing(raw)
                    val seed = overlay.id + "gi"
                    val jitter = EasingFunctions.deterministicShake(seed, sinceStart.roundToInt().toLong())
                    offsetX = jitter * 0.12f * (1f - raw)
                    scale *= 1f + (1f - raw) * 0.15f
                }
            }
            else -> {}
        }
        when (outAnim) {
            com.aicreatorstudio.app.model.TextOverlayAnimation.FADE_OUT,
            com.aicreatorstudio.app.model.TextOverlayAnimation.FADE_IN_OUT -> {
                if (untilEnd < outEffectMs) {
                    val raw = (untilEnd / outEffectMs).coerceIn(0f, 1f)
                    alpha *= outEasing(raw)
                }
            }
            com.aicreatorstudio.app.model.TextOverlayAnimation.GLITCH_OUT -> {
                if (untilEnd < outEffectMs) {
                    val raw = (untilEnd / outEffectMs).coerceIn(0f, 1f)
                    alpha *= outEasing(raw)
                    val seed = overlay.id + "go"
                    val jitter = EasingFunctions.deterministicShake(seed, untilEnd.roundToInt().toLong())
                    offsetX = jitter * 0.12f * (1f - raw)
                    scale *= 1f + (1f - raw) * 0.15f
                }
            }
            else -> {}
        }
        return CaptionTransform(
            alpha = alpha.coerceIn(0f, 1f),
            scale = scale,
            offsetYFraction = offsetY,
            offsetXFraction = offsetX
        )
    }

    /** Whole-caption transform (alpha/scale/offset) at a playhead. */
    data class CaptionTransform(
        val alpha: Float,
        val scale: Float,
        /** Vertical offset as a fraction of canvas height (down = +). */
        val offsetYFraction: Float,
        /** PHASE 8 — horizontal offset for glitch effects. */
        val offsetXFraction: Float = 0f
    )
}
