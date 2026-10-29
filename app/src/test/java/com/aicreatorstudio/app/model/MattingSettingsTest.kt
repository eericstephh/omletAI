package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.ai.MaskPostProcess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MattingSettings] - the per-clip Background Remove
 * settings that flow through the render graph and into the export
 * pipeline. These cover the algorithm-tag round-trip (a project
 * saved with the v1 algorithm tag must load cleanly under the v2
 * MediaPipe engine), the identity defaults, and the threshold
 * range.
 */
class MattingSettingsTest {

    @Test
    fun `identity is disabled and has no background replacement`() {
        val id = MattingSettings.Identity
        assertFalse("identity must be disabled", id.enabled)
        assertNull("identity has no replacement background", id.replacementBackgroundUri)
    }

    @Test
    fun `default mirrors the previous chroma-key defaults`() {
        val def = MattingSettings.Default
        assertFalse("default is not enabled by default", def.enabled)
        // 0.20 was the v1 chroma-key default; carrying it here
        // means a v1 project round-trips without surprise.
        assertEquals(0.20f, def.threshold, 0.0001f)
        // 2026-10-25 — feather is the normalized composition-time
        // knob (0..1); the old pixel value was `featherPx = 2f`.
        assertEquals(MaskPostProcess.DEFAULT_FEATHER, def.feather, 0.0001f)
        assertEquals(0f, def.expansion, 0.0001f)
        assertFalse("reverse is off by default", def.reverse)
        assertNull(def.replacementBackgroundUri)
    }

    @Test
    fun `algorithm tag is the v1 chroma-key by default`() {
        assertEquals(
            "lab-distance-v1",
            MattingSettings.Default.algorithm
        )
    }

    @Test
    fun `mediapipe tag is exposed as a stable string constant`() {
        assertEquals(
            "mediapipe-selfie-multiclass-v1",
            MattingSettings.ALGORITHM_MEDIAPIPE_SELFIE
        )
    }

    // ---- 2026-10-27: the run commits "subject stays, background gone" ----

    @Test
    fun `a fresh run flips the untouched original mode to transparent`() {
        assertEquals(
            "pressing Remove Background with no explicit choice must mean " +
                "'subject stays, background removed'",
            MattingBackgroundMode.TRANSPARENT,
            MattingSettings.runDefaultBackgroundMode(MattingBackgroundMode.ORIGINAL)
        )
    }

    @Test
    fun `a deliberately chosen mode survives the run`() {
        for (chosen in MattingBackgroundMode.entries) {
            if (chosen == MattingBackgroundMode.ORIGINAL) continue
            assertEquals(
                "user choice must be preserved: $chosen",
                chosen,
                MattingSettings.runDefaultBackgroundMode(chosen)
            )
        }
    }

    @Test
    fun `transparent resolves to the canvas colour the letterbox bars use`() {
        // TRANSPARENT export composites over `CanvasLetterbox
        // .fadeColorArgb` — assert the resolver's contract the export
        // depends on: default black, opaque alpha, non-active swatch
        // falls back to black, blurred canvas is black (un-baked).
        assertEquals(0xFF000000.toInt(), CanvasLetterbox.fadeColorArgb(CanvasBackground.Black))
        assertEquals(
            0xFF123456.toInt(),
            CanvasLetterbox.fadeColorArgb(CanvasBackground.SolidColor(0xFF123456L))
        )
        // A swatch whose own alpha is zero is not a background.
        assertEquals(0xFF000000.toInt(), CanvasLetterbox.fadeColorArgb(CanvasBackground.SolidColor(0x00123456L)))
        assertEquals(0xFF000000.toInt(), CanvasLetterbox.fadeColorArgb(CanvasBackground.Blur))
    }

    @Test
    fun `custom settings round-trip the algorithm tag verbatim`() {
        val settings = MattingSettings(
            enabled = true,
            threshold = 0.5f,
            feather = 0.3f,
            expansion = 0.4f,
            reverse = true,
            replacementBackgroundUri = "content://foo",
            algorithm = MattingSettings.ALGORITHM_MEDIAPIPE_SELFIE
        )
        assertEquals(
            MattingSettings.ALGORITHM_MEDIAPIPE_SELFIE,
            settings.algorithm
        )
        // data class equality: the algorithm tag must survive
        // copy() unchanged.
        val copy = settings.copy(expansion = -0.5f, reverse = false)
        assertEquals(MattingSettings.ALGORITHM_MEDIAPIPE_SELFIE, copy.algorithm)
        assertEquals(-0.5f, copy.expansion, 0.0001f)
        assertFalse(copy.reverse)
        assertEquals(0.3f, copy.feather, 0.0001f)
    }

    @Test
    fun `unknown algorithm tags are preserved verbatim for forward compat`() {
        // A future v3 engine can be saved as e.g. "rvm-mobile-v1"
        // and a v2 build will read it back unchanged, even if it
        // can't act on it. The data class is intentionally
        // permissive about the algorithm string.
        val settings = MattingSettings(algorithm = "rvm-mobile-v1")
        assertEquals("rvm-mobile-v1", settings.algorithm)
    }

    @Test
    fun `lab distance tag constant is stable`() {
        // Hard-coded to keep the v1 chroma-key JSON field
        // forward-compatible: existing projects on disk still load
        // after the algorithm swap.
        assertEquals(
            "lab-distance-v1",
            MattingSettings.ALGORITHM_LAB_DISTANCE_V1
        )
    }

    @Test
    fun `replacement background uri is preserved`() {
        val uri = "content://media/external/images/media/42"
        val s = MattingSettings(replacementBackgroundUri = uri)
        assertEquals(uri, s.replacementBackgroundUri)
        assertNotNull(s.replacementBackgroundUri)
    }
}
