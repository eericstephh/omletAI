package com.aicreatorstudio.app.ai.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PHASE B7 — JVM unit tests for the post-process settings.
 * Locks the contract:
 *
 *  - `Default` enables every step with safe values;
 *  - `Identity` disables every step and reports `isIdentity`;
 *  - Out-of-range numeric limits throw `IllegalArgumentException`
 *    from the `init` block.
 */
class SubtitlePostProcessSettingsTest {

    @Test
    fun `default enables every step`() {
        val s = SubtitlePostProcessSettings.Default
        assertTrue(s.normalizePersian)
        assertTrue(s.collapseRepetition)
        assertTrue(s.filterSilence)
        assertTrue(s.resegment)
        // 2026-09-11 — the conservative typo corrector joins Default.
        assertTrue(s.correctTypos)
    }

    @Test
    fun `default has safe numeric limits`() {
        val s = SubtitlePostProcessSettings.Default
        assertEquals(42, s.maxCharsPerLine)
        assertEquals(4_000L, s.maxSegmentDurationMs)
        assertEquals(800L, s.minSegmentDurationMs)
        assertEquals(200L, s.mergeGapMs)
        assertEquals(0.15f, s.minConfidenceForLongWord, 0.0001f)
        assertEquals(5_000L, s.longWordMsThreshold)
    }

    @Test
    fun `default is not identity`() {
        assertFalse(SubtitlePostProcessSettings.Default.isIdentity)
    }

    @Test
    fun `identity disables every step`() {
        val s = SubtitlePostProcessSettings.Identity
        assertFalse(s.normalizePersian)
        assertFalse(s.collapseRepetition)
        assertFalse(s.filterSilence)
        assertFalse(s.resegment)
        assertFalse(s.correctTypos)
        assertTrue(s.isIdentity)
    }

    @Test
    fun `isIdentity true when all five steps off`() {
        val s = SubtitlePostProcessSettings(
            normalizePersian = false,
            collapseRepetition = false,
            filterSilence = false,
            resegment = false,
            correctTypos = false
        )
        assertTrue(s.isIdentity)
    }

    @Test
    fun `corrector thresholds validated`() {
        // autoApplyThreshold out of range.
        try {
            SubtitlePostProcessSettings(autoApplyThreshold = 0.2f)
            fail("expected autoApplyThreshold validation")
        } catch (expected: IllegalArgumentException) {
        }
        // reviewThreshold above autoApply is contradictory.
        try {
            SubtitlePostProcessSettings(
                autoApplyThreshold = 0.8f,
                reviewThreshold = 0.9f
            )
            fail("expected reviewThreshold<=autoApply validation")
        } catch (expected: IllegalArgumentException) {
        }
        // The brief's suggested defaults.
        val s = SubtitlePostProcessSettings.Default
        assertEquals(0.90f, s.autoApplyThreshold, 0.0001f)
        assertEquals(0.70f, s.reviewThreshold, 0.0001f)
    }

    @Test
    fun `isIdentity true when all four steps off`() {
        val s = SubtitlePostProcessSettings(
            normalizePersian = false,
            collapseRepetition = false,
            filterSilence = false,
            resegment = false,
            correctTypos = false
        )
        assertTrue(s.isIdentity)
    }

    @Test
    fun `isIdentity false when any step is on`() {
        val s = SubtitlePostProcessSettings(normalizePersian = true)
        assertFalse(s.isIdentity)
    }

    // -------- range validation --------

    @Test
    fun `maxCharsPerLine below 20 throws`() {
        try {
            SubtitlePostProcessSettings(maxCharsPerLine = 19)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxCharsPerLine"))
        }
    }

    @Test
    fun `maxCharsPerLine above 200 throws`() {
        try {
            SubtitlePostProcessSettings(maxCharsPerLine = 201)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxCharsPerLine"))
        }
    }

    @Test
    fun `maxSegmentDurationMs below 1000 throws`() {
        try {
            SubtitlePostProcessSettings(maxSegmentDurationMs = 999L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxSegmentDurationMs"))
        }
    }

    @Test
    fun `mergeGapMs above 1000 throws`() {
        try {
            SubtitlePostProcessSettings(mergeGapMs = 1_001L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mergeGapMs"))
        }
    }

    @Test
    fun `minConfidenceForLongWord below zero throws`() {
        try {
            SubtitlePostProcessSettings(minConfidenceForLongWord = -0.1f)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minConfidenceForLongWord"))
        }
    }

    @Test
    fun `minConfidenceForLongWord above one throws`() {
        try {
            SubtitlePostProcessSettings(minConfidenceForLongWord = 1.1f)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("minConfidenceForLongWord"))
        }
    }

    @Test
    fun `longWordMsThreshold above 30_000 throws`() {
        try {
            SubtitlePostProcessSettings(longWordMsThreshold = 30_001L)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("longWordMsThreshold"))
        }
    }

    @Test
    fun `boundary values are accepted`() {
        // 20 and 200 are inclusive.
        SubtitlePostProcessSettings(maxCharsPerLine = 20)
        SubtitlePostProcessSettings(maxCharsPerLine = 200)
        // 0.0 and 1.0 are inclusive.
        SubtitlePostProcessSettings(minConfidenceForLongWord = 0.0f)
        SubtitlePostProcessSettings(minConfidenceForLongWord = 1.0f)
    }
}
