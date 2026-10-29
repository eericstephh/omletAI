package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * PHASE 7 — pure tests for background geometry, cache key, and
 * engineTypeString reverse mapping. No Android deps; JVM-safe.
 */
class CaptionBackgroundTest {

    @Test
    fun gradientEndpoints_0deg_pointsToTop() {
        val e = CaptionRenderer.gradientEndpoints(100f, 200f, 0f)
        val cx = 50f
        val cy = 100f
        val halfDiag = hypot(50f, 100f)
        assertEquals(cx, e[0], 0.01f)
        assertEquals(cy + halfDiag, e[1], 0.5f)
        assertEquals(cx, e[2], 0.01f)
        assertEquals(cy - halfDiag, e[3], 0.5f)
    }

    @Test
    fun gradientEndpoints_90deg_pointsToRight() {
        val e = CaptionRenderer.gradientEndpoints(200f, 100f, 90f)
        val cx = 100f
        val cy = 50f
        val halfDiag = hypot(100f, 50f)
        assertEquals(cx - halfDiag, e[0], 0.5f)
        assertEquals(cy, e[1], 0.01f)
        assertEquals(cx + halfDiag, e[2], 0.5f)
        assertEquals(cy, e[3], 0.01f)
    }

    @Test
    fun gradientEndpoints_45deg_pointsToTopRight() {
        val e = CaptionRenderer.gradientEndpoints(100f, 100f, 45f)
        val dx = e[2] - e[0]
        val dy = e[1] - e[3]
        assertTrue(abs(dx - dy) < 1f)
    }

    @Test
    fun gradientEndpoints_symmetric() {
        val w = 300f; val h = 150f; val angle = 37f
        val e = CaptionRenderer.gradientEndpoints(w, h, angle)
        val cx = w / 2f; val cy = h / 2f
        assertEquals(cx, (e[0] + e[2]) / 2f, 0.5f)
        assertEquals(cy, (e[1] + e[3]) / 2f, 0.5f)
    }

    @Test
    fun gradientEndpoints_360deg_sameAs0deg() {
        val e0 = CaptionRenderer.gradientEndpoints(100f, 200f, 0f)
        val e360 = CaptionRenderer.gradientEndpoints(100f, 200f, 360f)
        assertEquals(e0[0], e360[0], 0.01f)
        assertEquals(e0[1], e360[1], 0.01f)
        assertEquals(e0[2], e360[2], 0.01f)
        assertEquals(e0[3], e360[3], 0.01f)
    }

    @Test
    fun letterboxBarHeight_16x9_returnsExpected() {
        val barH = CaptionRenderer.letterboxBarHeight(1920, 1080)
        assertTrue(barH > 120f && barH < 145f)
    }

    @Test
    fun letterboxBarHeight_9x16_returnsLarge() {
        val barH = CaptionRenderer.letterboxBarHeight(1080, 1920)
        assertTrue(barH > 700f)
    }

    @Test
    fun letterboxBarHeight_square_usesScope() {
        val barH = CaptionRenderer.letterboxBarHeight(1000, 1000)
        assertTrue(barH > 270f && barH < 310f)
    }

    @Test
    fun letterboxBarHeight_scopeAspect_returns0() {
        val scopeW = 2350
        val scopeH = (scopeW / 2.35f).roundToInt()
        val barH = CaptionRenderer.letterboxBarHeight(scopeW, scopeH)
        assertEquals(0f, barH, 1f)
    }

    @Test
    fun letterboxBarHeight_widerThanScope_returns0() {
        val barH = CaptionRenderer.letterboxBarHeight(3000, 1000)
        assertEquals(0f, barH, 1f)
    }

    @Test
    fun letterboxBarHeight_zeroDimensions_returns0() {
        assertEquals(0f, CaptionRenderer.letterboxBarHeight(0, 0), 0.01f)
        assertEquals(0f, CaptionRenderer.letterboxBarHeight(0, 1080), 0.01f)
        assertEquals(0f, CaptionRenderer.letterboxBarHeight(1920, 0), 0.01f)
    }

    @Test
    fun gradientCacheKey_sameInputs_sameKey() {
        val k1 = CaptionRenderer.gradientCacheKey(100, 200, 0xFF0000, 0x00FF00, 45f)
        val k2 = CaptionRenderer.gradientCacheKey(100, 200, 0xFF0000, 0x00FF00, 45f)
        assertEquals(k1, k2)
    }

    @Test
    fun gradientCacheKey_differentAngle_differentKey() {
        val k1 = CaptionRenderer.gradientCacheKey(100, 200, 0xFF0000, 0x00FF00, 45f)
        val k2 = CaptionRenderer.gradientCacheKey(100, 200, 0xFF0000, 0x00FF00, 90f)
        assertNotEquals(k1, k2)
    }

    @Test
    fun gradientCacheKey_differentSize_differentKey() {
        val k1 = CaptionRenderer.gradientCacheKey(100, 200, 0xFF0000, 0x00FF00, 45f)
        val k2 = CaptionRenderer.gradientCacheKey(101, 200, 0xFF0000, 0x00FF00, 45f)
        assertNotEquals(k1, k2)
    }

    @Test
    fun gradientCacheKey_snapsAngleToInteger() {
        val k1 = CaptionRenderer.gradientCacheKey(100, 200, 0xFF0000, 0x00FF00, 45.2f)
        val k2 = CaptionRenderer.gradientCacheKey(100, 200, 0xFF0000, 0x00FF00, 45.4f)
        assertEquals(k1, k2)
    }

    @Test
    fun engineTypeString_roundTrips() {
        val kinds = listOf(
            CaptionBackgroundKind.NONE to "none",
            CaptionBackgroundKind.SOLID_ROUNDED to "solidbox",
            CaptionBackgroundKind.GRADIENT to "gradientbox",
            CaptionBackgroundKind.LETTERBOX_BARS to "letterboxbars",
            CaptionBackgroundKind.BLUR to "blurvideo",
            CaptionBackgroundKind.PER_LINE_HIGHLIGHT to "perlinehighlight"
        )
        kinds.forEach { (kind, expected) ->
            assertEquals(expected, kind.engineTypeString)
        }
    }

    @Test
    fun engineTypeString_allKindsCovered() {
        CaptionBackgroundKind.entries.forEach { kind ->
            assertTrue(kind.engineTypeString.isNotBlank())
        }
    }

    @Test
    fun letterboxBars_hasNoCaptionBox() {
        val boxKinds = setOf(
            CaptionBackgroundKind.SOLID_ROUNDED,
            CaptionBackgroundKind.GRADIENT,
            CaptionBackgroundKind.BLUR,
            CaptionBackgroundKind.PER_LINE_HIGHLIGHT
        )
        assertTrue(CaptionBackgroundKind.LETTERBOX_BARS !in boxKinds)
    }

    @Test
    fun gradientAngle_90_isHorizontal() {
        val e = CaptionRenderer.gradientEndpoints(200f, 100f, 90f)
        assertEquals(e[1], e[3], 1f)
    }

    @Test
    fun gradientAngle_0_isVertical() {
        val e = CaptionRenderer.gradientEndpoints(200f, 100f, 0f)
        assertEquals(e[0], e[2], 1f)
    }
}
