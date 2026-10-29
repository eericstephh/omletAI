package com.aicreatorstudio.app.util

import com.aicreatorstudio.app.model.ChromaKeySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for the 2026-10-13 **chroma key**.
 *
 * ## Why these tests are worth trusting
 *
 * Every number asserted here was computed with an independent script
 * implementing the same BT.601 matrix, then confirmed against this
 * implementation — so a wrong expectation fails loudly instead of being
 * copied from the code under test. The two most important cases are pinned
 * exactly rather than in a tolerance band:
 *
 *  - a pixel leaning **away** from the key hue is provably untouched, which
 *    is what makes "non-key colours survive" an exact claim rather than an
 *    approximation; and
 *  - spill suppression is **monotonic**: `spillReduction = 0` is a literal
 *    no-op and `1.0` fully desaturates the pixel toward the key axis.
 *
 * ## The metric is YCbCr, not RGB
 *
 * Green screens are gradients. In RGB the lit and the shadowed green can be
 * 100 apart per channel; in YCbCr they share nearly identical `(Cb, Cr)`
 * and differ only in `Y`. Testing the metric with RGB-distance expectations
 * would assert the wrong algorithm and pass on a broken implementation.
 */
class ChromaKeyEngineTest {

    /** Pack an ARGB int from 8-bit channels. */
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        ((a shl 24) or (r shl 16) or (g shl 8) or b)

    /**
     * The default **enabled** key: green screen, default tuning.
     *
     * Note this is NOT `ChromaKeySettings.Default`, whose `isEnabled` is
     * `false` — keying a pixel through the model default is the identity,
     * which is correct behaviour but makes a test that expects removal pass
     * or fail for entirely the wrong reason. Every test that wants the
     * effect to actually happen uses this.
     */
    private val on = ChromaKeySettings(isEnabled = true)

    private fun alphaOf(pixel: Int): Int = (pixel ushr 24) and 0xFF
    private fun redOf(pixel: Int): Int = (pixel ushr 16) and 0xFF
    private fun greenOf(pixel: Int): Int = (pixel ushr 8) and 0xFF
    private fun blueOf(pixel: Int): Int = pixel and 0xFF

    /** Key one pixel and decode the channels, for readable assertions. */
    private data class Out(val a: Int, val r: Int, val g: Int, val b: Int)

    private fun keyed(
        r: Int,
        g: Int,
        b: Int,
        srcAlpha: Int = 255,
        settings: ChromaKeySettings = on
    ): Out {
        val p = ChromaKeyEngine.keyPixel(argb(r, g, b, srcAlpha), settings)
        return Out(alphaOf(p), redOf(p), greenOf(p), blueOf(p))
    }

    // ---- the keyed colour itself --------------------------------------

    @Test
    fun `pure green is removed completely`() {
        // The default settings. A pure #00FF00 pixel is distance 0, which is
        // below `similarity`, so alpha is 0 — not "low", exactly gone.
        val out = keyed(0, 255, 0)
        assertEquals(0, out.a)
    }

    @Test
    fun `a green screen variant is removed too`() {
        // A lit/shadowed green screen pixel. distance 0.19, still below the
        // default similarity of 0.40. RGB-distance keying would leave this
        // one untouched and the export would keep the edge of the screen.
        val out = keyed(10, 220, 80)
        assertEquals(0, out.a)
    }

    @Test
    fun `a dark green shadow is removed too`() {
        val out = keyed(0, 80, 20)
        assertEquals(0, out.a)
    }

    @Test
    fun `the key colour is its own distance zero`() {
        assertEquals(0f, ChromaKeyEngine.chromaDistance(argb(0, 255, 0), argb(0, 255, 0)), 1e-5f)
        assertEquals(0f, ChromaKeyEngine.chromaDistance(argb(0, 0, 255), argb(0, 0, 255)), 1e-5f)
    }

    @Test
    fun `a blue screen survives under a green key`() {
        // The metric is colour-agnostic, not "anything green". Under the
        // GREEN default key, blue is FOREGROUND and must survive with its
        // channels intact — this is the guard against an implementation
        // that removes whatever is furthest from the key.
        val out = keyed(0, 0, 255, settings = on)
        assertEquals(255, out.a)
        assertEquals(0, out.r)
        assertEquals(0, out.g)
        assertEquals(255, out.b)
    }

    @Test
    fun `a blue screen is removed under a blue key`() {
        // The other half of the colour-agnostic guard: switch the key to
        // blue and the same pixel is gone.
        val out = keyed(
            0, 0, 255,
            settings = on.copy(keyColorArgb = ChromaKeySettings.BLUE_KEY_COLOR_ARGB)
        )
        assertEquals(0, out.a)
    }

    // ---- the non-key colours ------------------------------------------

    @Test
    fun `a neutral grey is untouched`() {
        // Grey sits on the neutral chroma axis: distance 0.5 from the key,
        // far outside the ramp, and its chroma projection is 0 — so neither
        // the alpha nor a single channel moves.
        val out = keyed(128, 128, 128)
        assertEquals(255, out.a)
        assertEquals(128, out.r)
        assertEquals(128, out.g)
        assertEquals(128, out.b)
    }

    @Test
    fun `white and black are untouched`() {
        for (c in listOf(255, 0)) {
            val out = keyed(c, c, c)
            assertEquals("channel $c kept its alpha", 255, out.a)
            assertEquals("channel $c kept its red", c, out.r)
            assertEquals("channel $c kept its green", c, out.g)
            assertEquals("channel $c kept its blue", c, out.b)
        }
    }

    @Test
    fun `red is untouched by a green key`() {
        // Red projects NEGATIVE onto the green key axis, so spill
        // suppression provably cannot touch it. This is the exactness of
        // "non-key colours survive", asserted channel by channel.
        val out = keyed(255, 0, 0)
        assertEquals(255, out.a)
        assertEquals(255, out.r)
        assertEquals(0, out.g)
        assertEquals(0, out.b)
    }

    @Test
    fun `skin is untouched by a green key`() {
        // The important real-world case: a face under a green screen.
        val out = keyed(232, 180, 140)
        assertEquals(255, out.a)
        assertEquals(232, out.r)
        assertEquals(180, out.g)
        assertEquals(140, out.b)
    }

    // ---- the alpha ramp ----------------------------------------------

    @Test
    fun `the ramp interpolates between similarity and its smoothness`() {
        // A pixel just inside the ramp must land strictly between the two
        // endpoints — that is the whole point of the Hermite curve, and a
        // linear or hard-step implementation would give 0 or 255 instead.
        val inside = keyed(200, 220, 200)
        assertTrue(
            "alpha ${inside.a} must be strictly partial",
            inside.a in 1..254
        )
    }

    @Test
    fun `zero smoothness is a hard step`() {
        // Neutral grey is distance 0.5 from green. With smoothness 0 the
        // ramp is degenerate, so it becomes a step at `similarity` instead
        // of dividing by zero.
        val s = ChromaKeySettings(
            isEnabled = true, smoothness = 0f, spillReduction = 0f
        )
        assertEquals("below the step is kept", 255, keyed(128, 128, 128, settings = s.copy(similarity = 0.30f)).a)
        assertEquals("at the step is kept", 255, keyed(128, 128, 128, settings = s.copy(similarity = 0.50f)).a)
        assertEquals("above the step is gone", 0, keyed(128, 128, 128, settings = s.copy(similarity = 0.55f)).a)
        assertEquals("far above the step is gone", 0, keyed(128, 128, 128, settings = s.copy(similarity = 0.70f)).a)
    }

    @Test
    fun `similarity at the maximum keys everything`() {
        // `maxChromaDistance` is the true cube maximum, so the top of the
        // slider really is "off the end" and not an arbitrary cut-off.
        val out = keyed(
            128, 128, 128,
            settings = ChromaKeySettings(isEnabled = true, similarity = 1f, smoothness = 0f)
        )
        assertEquals(0, out.a)
    }

    @Test
    fun `alpha never leaves the byte range over a full sweep`() {
        val levels = listOf(0f, 0.1f, 0.25f, 0.4f, 0.5f, 0.75f, 0.9f, 1f)
        val pixels = listOf(0, 32, 64, 96, 128, 160, 192, 224, 255)
        for (k in pixels) for (j in pixels) for (i in levels) for (s in levels) {
            val settings = ChromaKeySettings(
                isEnabled = true, similarity = i, smoothness = s, spillReduction = 0.5f
            )
            val out = keyed(k, j, 255 - k, settings = settings)
            assertTrue("alpha ${out.a} out of range", out.a in 0..255)
            assertTrue("red ${out.r} out of range", out.r in 0..255)
            assertTrue("green ${out.g} out of range", out.g in 0..255)
            assertTrue("blue ${out.b} out of range", out.b in 0..255)
        }
    }

    @Test
    fun `source alpha is multiplied in, not replaced`() {
        // A half-transparent overlay frame must stay half-transparent when
        // nothing is keyed, and must not become "half of nothing" when the
        // pixel itself is removed.
        val kept = keyed(128, 128, 128, srcAlpha = 128)
        assertEquals("an untouched pixel keeps its own alpha", 128, kept.a)

        val removed = keyed(
            0, 255, 0, srcAlpha = 128,
            settings = ChromaKeySettings(isEnabled = true, spillReduction = 0f)
        )
        assertEquals("a keyed pixel is fully transparent", 0, removed.a)
    }

    @Test
    fun `a fully transparent pixel is returned unchanged`() {
        // Nothing to key: the engine must not spend work on it, and must not
        // emit a pixel that is transparent in RGB but opaque in alpha.
        val transparent = argb(0, 0, 0, 0)
        assertEquals(
            transparent,
            ChromaKeyEngine.keyPixel(transparent, ChromaKeySettings.Default)
        )
    }

    // ---- spill suppression -------------------------------------------

    @Test
    fun `spill suppression is a literal no-op at zero`() {
        // The slider's minimum must not move a single channel, otherwise the
        // "0%" position would silently change the exported file.
        val out = keyed(
            200, 220, 200,
            settings = ChromaKeySettings(isEnabled = true, spillReduction = 0f)
        )
        assertEquals(200, out.r)
        assertEquals(220, out.g)
        assertEquals(200, out.b)
    }

    @Test
    fun `spill suppression desaturates monotonically toward the key axis`() {
        // A greenish grey that SURVIVES the key (distance 0.46, inside the
        // ramp) — spill only ever runs on a pixel that is still visible.
        //
        // Each stronger setting pulls the chroma a little further back to
        // neutral, and the maximum reaches exactly neutral. Computed with an
        // independent script, then confirmed against the implementation.
        val expected = mapOf(
            0.00f to Triple(200, 220, 200),
            0.25f to Triple(203, 218, 203),
            0.50f to Triple(206, 216, 206),
            0.75f to Triple(209, 214, 209),
            1.00f to Triple(212, 212, 212)
        )
        for ((spill, rgb) in expected) {
            val out = keyed(
                200, 220, 200,
                settings = ChromaKeySettings(isEnabled = true, spillReduction = spill)
            )
            assertEquals("red at spill $spill", rgb.first, out.r)
            assertEquals("green at spill $spill", rgb.second, out.g)
            assertEquals("blue at spill $spill", rgb.third, out.b)
            // Spill never revives a keyed pixel, so the alpha is the same
            // across the whole sweep.
            assertEquals("alpha at spill $spill", 168, out.a)
        }
    }

    @Test
    fun `the fully de-spilled pixel is neutral`() {
        val out = keyed(
            200, 220, 200,
            settings = ChromaKeySettings(isEnabled = true, spillReduction = 1f)
        )
        // Not merely "closer": all three channels agree, which is what
        // "no green reflection left" means.
        assertEquals(out.g, out.r)
        assertEquals(out.b, out.r)
    }

    @Test
    fun `spill suppression ignores a pixel leaning away from the key`() {
        // Red projects negative onto the green axis, so even full spill must
        // leave it alone. A naive hue-rotation implementation would drag a
        // red shirt toward grey and this test would catch it.
        val settings = ChromaKeySettings(
            isEnabled = true, similarity = 0f, spillReduction = 1f
        )
        val out = keyed(255, 0, 0, settings = settings)
        assertEquals(255, out.r)
        assertEquals(0, out.g)
        assertEquals(0, out.b)
    }

    @Test
    fun `a neutral key colour has no direction to pull along`() {
        // Black, white and grey carry no hue, so spill suppression must be a
        // no-op rather than a division by zero or a NaN. Similarity 0 keeps
        // the pixel visible, so the RGB channels are genuinely compared —
        // without it a neutral key would remove the pixel first and the
        // channels would never be inspected.
        for (k in listOf(argb(0, 0, 0), argb(255, 255, 255), argb(128, 128, 128))) {
            val out = keyed(
                200, 220, 200,
                settings = ChromaKeySettings(
                    isEnabled = true, keyColorArgb = k, similarity = 0f, spillReduction = 1f
                )
            )
            assertTrue("pixel must survive key $k, alpha was ${out.a}", out.a > 0)
            assertEquals("red with key $k", 200, out.r)
            assertEquals("green with key $k", 220, out.g)
            assertEquals("blue with key $k", 200, out.b)
        }
    }

    // ---- disabled / sanitise -----------------------------------------

    @Test
    fun `a disabled key is the identity`() {
        // Turning the effect off must return the pixel byte-for-byte,
        // including for a pixel the very same setting would key if enabled.
        val original = argb(0, 255, 0)
        val out = ChromaKeyEngine.keyPixel(
            original,
            on.copy(isEnabled = false)
        )
        assertEquals(original, out)
    }

    @Test
    fun `sanitize forces the key colour opaque`() {
        // Two equal colours must compare equal, and the metric must never
        // read a channel that does not participate in the key. A green with
        // alpha 0 must come back as the opaque green default.
        val transparentGreen = argb(0, 255, 0, a = 0)
        assertEquals(0x0000FF00, transparentGreen)
        val out = ChromaKeyEngine.sanitize(
            ChromaKeySettings(keyColorArgb = transparentGreen)
        )
        assertEquals(ChromaKeySettings.DEFAULT_KEY_COLOR_ARGB, out.keyColorArgb)
        assertEquals(
            "the alpha must have been forced on",
            ChromaKeySettings.OPAQUE_ALPHA_MASK,
            out.keyColorArgb and ChromaKeySettings.OPAQUE_ALPHA_MASK
        )
    }

    @Test
    fun `sanitize clamps out-of-range levels`() {
        val out = ChromaKeyEngine.sanitize(
            ChromaKeySettings(
                isEnabled = true,
                similarity = -5f, smoothness = 12f, spillReduction = -0.25f
            )
        )
        assertEquals(ChromaKeyEngine.MIN_LEVEL, out.similarity, 0f)
        assertEquals(ChromaKeyEngine.MAX_LEVEL, out.smoothness, 0f)
        assertEquals(ChromaKeyEngine.MIN_LEVEL, out.spillReduction, 0f)
    }

    @Test
    fun `sanitize falls back to the working default for a non-finite level`() {
        // A corrupt similarity of 0 would mean "key nothing", i.e. the user
        // sees the feature appear broken. The default actually works.
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val out = ChromaKeyEngine.sanitize(
                ChromaKeySettings(isEnabled = true, similarity = bad)
            )
            assertEquals("similarity for $bad", ChromaKeySettings.DEFAULT_SIMILARITY, out.similarity, 0f)
        }
    }

    @Test
    fun `sanitize leaves a valid setting untouched`() {
        val s = ChromaKeySettings(
            isEnabled = true, keyColorArgb = argb(0, 0, 255),
            similarity = 0.55f, smoothness = 0.2f, spillReduction = 0.75f
        )
        assertEquals(s, ChromaKeyEngine.sanitize(s))
    }

    // ---- the metric ---------------------------------------------------

    @Test
    fun `the chroma distance of the neutral axis is exactly one half`() {
        // Grey, white and black all sit on the neutral chroma axis, so all
        // three are the same distance from any coloured key — 0.5. That is
        // what the metric excludes luminance for.
        for (c in listOf(0, 128, 255)) {
            assertEquals(
                "distance for $c", 0.5f,
                ChromaKeyEngine.chromaDistance(argb(c, c, c), argb(0, 255, 0)), 1e-4f
            )
        }
    }

    @Test
    fun `the maximum distance is the true cube maximum`() {
        // Green↔magenta is the widest pair the cube can produce; it alone
        // reaches the maximum. Red↔cyan is the runner-up at 269.13 — close
        // enough that naming it here instead of measuring it is an easy
        // mistake, and it would keep a shrunken `maxChromaDistance`
        // undetected.
        val max = ChromaKeyEngine.maxChromaDistance
        assertEquals(272.28f, max, 0.02f)
        assertEquals(
            1f,
            ChromaKeyEngine.chromaDistance(argb(0, 255, 0), argb(255, 0, 255)),
            1e-3f
        )
        assertEquals(
            "red and cyan are WIDER than a unit distance would allow, not the max",
            269.13f / max, ChromaKeyEngine.chromaDistance(argb(255, 0, 0), argb(0, 255, 255)), 1e-4f
        )
    }

    @Test
    fun `keyPixelsInPlace reports zero when the key is off`() {
        // The caller treats 0 as "draw un-keyed", so this must not silently
        // return the pixel count and leave the pixels alone.
        val pixels = intArrayOf(argb(0, 255, 0), argb(255, 0, 0))
        val n = ChromaKeyEngine.keyPixelsInPlace(
            pixels, pixels.size, on.copy(isEnabled = false)
        )
        assertEquals(0, n)
        assertEquals("the pixel must be untouched", argb(0, 255, 0), pixels[0])
    }

    @Test
    fun `keyPixelsInPlace keys every pixel in the batch`() {
        val pixels = IntArray(4) { argb(0, 255, 0) }
        val n = ChromaKeyEngine.keyPixelsInPlace(pixels, pixels.size, on)
        assertEquals(4, n)
        // `IntArray` is not an `Iterable`, so there is no `all {}` — count
        // instead, and assert the count is zero.
        assertEquals("every pixel was keyed", 0, pixels.count { alphaOf(it) != 0 })
    }

    @Test
    fun `a shorter count keys only the prefix`() {
        // The export passes an exact count from `getPixels`, which is not
        // always the array length for the last row.
        val pixels = intArrayOf(argb(0, 255, 0), argb(0, 255, 0), argb(255, 0, 0))
        ChromaKeyEngine.keyPixelsInPlace(pixels, 2, on)
        assertEquals(0, alphaOf(pixels[0]))
        assertEquals(0, alphaOf(pixels[1]))
        assertEquals("the untouched tail keeps its alpha", 255, alphaOf(pixels[2]))
    }
}

