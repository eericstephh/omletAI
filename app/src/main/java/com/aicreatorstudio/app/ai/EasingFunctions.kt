package com.aicreatorstudio.app.ai

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PHASE 8 — pure, deterministic easing functions.
 *
 * Every function maps t ∈ [0,1] → value ∈ [0,1] (with overshoot
 * exceptions for back/elastic). No Android, no Compose — fully
 * JVM unit-testable.
 *
 * All functions are pure: output depends ONLY on t, so preview
 * and export produce byte-identical results for the same frame.
 */
object EasingFunctions {

    /** Identifier for JSON serialization and the panel picker. */
    enum class Easing(val displayName: String) {
        LINEAR("خطی"),
        EASE_IN_QUAD("شروع آرام"),
        EASE_OUT_QUAD("پایان آرام"),
        EASE_IN_OUT_QUAD("آرام–تند–آرام"),
        EASE_IN_CUBIC("شروع آرام (مکعبی)"),
        EASE_OUT_CUBIC("پایان آرام (مکعبی)"),
        EASE_IN_OUT_CUBIC("آرام–تند (مکعبی)"),
        EASE_OUT_BACK("برگشت (overshoot)"),
        EASE_OUT_ELASTIC("لاستیکی"),
        EASE_OUT_BOUNCE("پرشی")
    }

    /** Resolve a string identifier to an [Easing]; unknown → LINEAR. */
    fun fromString(value: String?): Easing = when {
        value == null -> Easing.LINEAR
        else -> Easing.entries.firstOrNull {
            it.name.equals(value.trim(), ignoreCase = true)
        } ?: Easing.LINEAR
    }

    /** Dispatch: easing identifier → pure function (t → value). */
    fun resolve(easing: Easing): (Float) -> Float = when (easing) {
        Easing.LINEAR -> ::linear
        Easing.EASE_IN_QUAD -> ::easeInQuad
        Easing.EASE_OUT_QUAD -> ::easeOutQuad
        Easing.EASE_IN_OUT_QUAD -> ::easeInOutQuad
        Easing.EASE_IN_CUBIC -> ::easeInCubic
        Easing.EASE_OUT_CUBIC -> ::easeOutCubic
        Easing.EASE_IN_OUT_CUBIC -> ::easeInOutCubic
        Easing.EASE_OUT_BACK -> ::easeOutBack
        Easing.EASE_OUT_ELASTIC -> ::easeOutElastic
        Easing.EASE_OUT_BOUNCE -> ::easeOutBounce
    }

    // ---- individual functions ----

    fun linear(t: Float): Float = t.coerceIn(0f, 1f)

    fun easeInQuad(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x
    }

    fun easeOutQuad(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * (2f - x)
    }

    fun easeInOutQuad(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 2f * x * x else -1f + (4f - 2f * x) * x
    }

    fun easeInCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x
    }

    fun easeOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val dec = 1f - x
        return 1f - dec * dec * dec
    }

    fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) {
            4f * x * x * x
        } else {
            val dec = 1f - x
            1f - 4f * dec * dec * dec
        }
    }

    /**
     * Overshoot back: exceeds 1.0 then settles.
     * s = 1.70158 (standard "back" constant), halved for caption
     * safety: peak ≈ 1.15 — well within the 1.4x margin.
     */
    fun easeOutBack(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        val s = 1.70158f * 0.5f
        val dec = 1f - x
        return 1f - (s + 1f) * dec * dec * dec + s * dec * dec
    }

    /**
     * Elastic ease-out: bouncy spring that settles.
     * p = 0.3 (period), a = 1, s = p/4.
     * Peak value ≈ 1.05 — safe for captions.
     */
    fun easeOutElastic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        if (x == 0f || x == 1f) return x
        val p = 0.3f
        val a = 1f
        val s = p / 4f
        return (a * 2f.pow(-10f * x) * sin((x - s) * (2f * Math.PI.toFloat()) / p) + 1f)
    }

    /**
     * Bounce ease-out: series of diminishing bounces.
     * Peak value = 1.0 (no overshoot beyond 1).
     */
    fun easeOutBounce(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when {
            x < 1f / 2.75f -> 7.5625f * x * x
            x < 2f / 2.75f -> {
                val x2 = x - 1.5f / 2.75f
                7.5625f * x2 * x2 + 0.75f
            }
            x < 2.5f / 2.75f -> {
                val x2 = x - 2.25f / 2.75f
                7.5625f * x2 * x2 + 0.9375f
            }
            else -> {
                val x2 = x - 2.625f / 2.75f
                7.5625f * x2 * x2 + 0.984375f
            }
        }
    }

    // ---- deterministic pseudo-random for shake/glitch ----

    /**
     * Deterministic pseudo-random in [0, 1) seeded by [wordId] +
     * [frameMs]. Uses a simple hash so the same word at the same
     * frame always yields the same value — preview and export
     * produce byte-identical output.
     */
    fun deterministicRandom(wordId: String, frameMs: Long): Float {
        // FNV-1a hash of the combined seed.
        var hash = 0x811c9dc5L
        for (c in wordId) {
            hash = hash xor c.code.toLong()
            hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        }
        hash = hash xor (frameMs and 0xFFFFFFFFL)
        hash = (hash * 0x01000193L) and 0xFFFFFFFFL
        // Map to [0, 1).
        return (hash and 0x00FFFFFFL).toFloat() / 0x01000000L.toFloat()
    }

    /**
     * Deterministic pseudo-random in [-1, 1) seeded by [wordId] +
     * [frameMs]. Centered around zero — ideal for shake offsets.
     */
    fun deterministicShake(wordId: String, frameMs: Long): Float =
        2f * deterministicRandom(wordId, frameMs) - 1f
}
