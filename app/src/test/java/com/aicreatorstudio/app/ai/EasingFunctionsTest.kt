package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * PHASE 8 — pure math tests for [EasingFunctions]: boundary values,
 * shape properties, deterministic pseudo-random, and safety margins.
 */
class EasingFunctionsTest {

    // ---- boundary conditions (every easing) ----

    @Test
    fun allEasings_startAtZero() {
        EasingFunctions.Easing.entries.forEach { e ->
            val fn = EasingFunctions.resolve(e)
            assertEquals("${e.name} must start at 0", 0f, fn(0f), 0.001f)
        }
    }

    @Test
    fun allEasings_endAtOne() {
        EasingFunctions.Easing.entries.forEach { e ->
            val fn = EasingFunctions.resolve(e)
            assertEquals("${e.name} must end at 1", 1f, fn(1f), 0.01f)
        }
    }

    // ---- monotonicity (non-overshoot easings) ----

    @Test
    fun easeInQuad_isMonotonic() {
        var prev = 0f
        for (i in 1..20) {
            val t = i / 20f
            val v = EasingFunctions.easeInQuad(t)
            assertTrue("easeInQuad($t)=$v must be >= previous=$prev", v >= prev)
            prev = v
        }
    }

    @Test
    fun easeOutQuad_isMonotonic() {
        var prev = 0f
        for (i in 1..20) {
            val t = i / 20f
            val v = EasingFunctions.easeOutQuad(t)
            assertTrue("easeOutQuad($t)=$v must be >= previous=$prev", v >= prev)
            prev = v
        }
    }

    @Test
    fun easeInOutQuad_isMonotonic() {
        var prev = 0f
        for (i in 1..20) {
            val t = i / 20f
            val v = EasingFunctions.easeInOutQuad(t)
            assertTrue("easeInOutQuad($t)=$v must be >= previous=$prev", v >= prev)
            prev = v
        }
    }

    @Test
    fun easeOutBounce_endsAtOne() {
        assertEquals(1f, EasingFunctions.easeOutBounce(1f), 0.001f)
    }

    @Test
    fun easeOutBounce_neverNegative() {
        for (i in 0..40) {
            val t = i / 40f
            val v = EasingFunctions.easeOutBounce(t)
            assertTrue("easeOutBounce($t)=$v must be >= 0", v >= 0f)
        }
    }

    // ---- overshoot safety (back/elastic must not exceed 1.4x) ----

    private val MAX_SAFE_SCALE = 1.4f

    @Test
    fun easeOutBack_neverExceedsMaxScale() {
        for (i in 0..100) {
            val t = i / 100f
            val v = EasingFunctions.easeOutBack(t)
            assertTrue("easeOutBack($t)=$v must be <= $MAX_SAFE_SCALE", v <= MAX_SAFE_SCALE)
        }
    }

    @Test
    fun easeOutElastic_neverExceedsMaxScale() {
        for (i in 0..100) {
            val t = i / 100f
            val v = EasingFunctions.easeOutElastic(t)
            assertTrue("easeOutElastic($t)=$v must be <= $MAX_SAFE_SCALE", v <= MAX_SAFE_SCALE)
        }
    }

    @Test
    fun easeOutBack_hasOvershoot() {
        // easeOutBack must exceed 1.0 at some point.
        val peak = (0..100).maxOf { EasingFunctions.easeOutBack(it / 100f) }
        assertTrue("easeOutBack peak=$peak must exceed 1.0", peak > 1.0f)
    }

    // ---- shape properties ----

    @Test
    fun easeInCubic_isSlowerThanQuad() {
        // easeInCubic(t) < easeInQuad(t) for t < 0.5 (cubic starts slower).
        for (i in 1..9) {
            val t = i / 10f
            assertTrue(
                "easeInCubic($t) should be < easeInQuad($t)",
                EasingFunctions.easeInCubic(t) < EasingFunctions.easeInQuad(t)
            )
        }
    }

    @Test
    fun easeInOutCubic_isSymmetric() {
        for (i in 1..49) {
            val t = i / 100f
            val fromStart = EasingFunctions.easeInOutCubic(t)
            val fromEnd = 1f - EasingFunctions.easeInOutCubic(1f - t)
            assertEquals("symmetry at t=$t", fromStart, fromEnd, 0.001f)
        }
    }

    // ---- linear ----

    @Test
    fun linear_returnsInput() {
        for (i in 0..10) {
            val t = i / 10f
            assertEquals(t, EasingFunctions.linear(t), 0.001f)
        }
    }

    // ---- fromString ----

    @Test
    fun fromString_resolvesKnown() {
        assertEquals(
            EasingFunctions.Easing.EASE_OUT_BACK,
            EasingFunctions.fromString("EASE_OUT_BACK")
        )
    }

    @Test
    fun fromString_nullReturnsLinear() {
        assertEquals(EasingFunctions.Easing.LINEAR, EasingFunctions.fromString(null))
    }

    @Test
    fun fromString_unknownReturnsLinear() {
        assertEquals(EasingFunctions.Easing.LINEAR, EasingFunctions.fromString("banana"))
    }

    // ---- deterministic random ----

    @Test
    fun deterministicRandom_sameInputs_sameOutput() {
        val v1 = EasingFunctions.deterministicRandom("word_42", 1500L)
        val v2 = EasingFunctions.deterministicRandom("word_42", 1500L)
        assertEquals(v1, v2, 0.0001f)
    }

    @Test
    fun deterministicRandom_differentInputs_differentOutput() {
        // Use widely different seeds to avoid hash collisions.
        val v1 = EasingFunctions.deterministicRandom("alpha", 100L)
        val v2 = EasingFunctions.deterministicRandom("omega", 999L)
        assertNotEquals(v1, v2, 0.0001f)
    }

    @Test
    fun deterministicRandom_differentWord_differentOutput() {
        val v1 = EasingFunctions.deterministicRandom("word_42", 1500L)
        val v2 = EasingFunctions.deterministicRandom("word_43", 1500L)
        assertNotEquals(v1, v2, 0.0001f)
    }

    @Test
    fun deterministicRandom_inUnitRange() {
        for (i in 0..99) {
            val v = EasingFunctions.deterministicRandom("test", i.toLong())
            assertTrue("deterministicRandom must be in [0, 1)", v >= 0f && v < 1f)
        }
    }

    @Test
    fun deterministicShake_inCenteredRange() {
        for (i in 0..99) {
            val v = EasingFunctions.deterministicShake("test", i.toLong())
            assertTrue("deterministicShake must be in [-1, 1)", v >= -1f && v < 1f)
        }
    }

    // ---- resolve dispatch ----

    @Test
    fun resolve_dispatchesCorrectly() {
        assertEquals(EasingFunctions::linear, EasingFunctions.resolve(EasingFunctions.Easing.LINEAR))
        assertEquals(EasingFunctions::easeInQuad, EasingFunctions.resolve(EasingFunctions.Easing.EASE_IN_QUAD))
        assertEquals(EasingFunctions::easeOutBack, EasingFunctions.resolve(EasingFunctions.Easing.EASE_OUT_BACK))
        assertEquals(EasingFunctions::easeOutBounce, EasingFunctions.resolve(EasingFunctions.Easing.EASE_OUT_BOUNCE))
    }
}
