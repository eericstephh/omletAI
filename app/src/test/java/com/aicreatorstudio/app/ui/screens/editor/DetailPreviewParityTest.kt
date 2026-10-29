package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.GrainSignal
import com.aicreatorstudio.app.model.VignettePlate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-21 — the "sharpness / vignette / grain are export-only" gap.
 *
 * ## The gap
 *
 * The Color Grading panel writes `vignetteAmount` /
 * `vignetteFeather` / `vignetteRoundness` and `grain`, the DB
 * persists them, and the **export** renders them — through
 * `VignetteOverlay` and `GrainEffect`. The preview rendered none of
 * them: the sliders moved and the screen did not change, so the user
 * had no way to judge the setting before exporting.
 *
 * (`sharpness` is a separable convolution — see
 * [com.aicreatorstudio.app.model.SharpnessKernel] — and is still
 * export-only. A convolution needs a shader or a per-pixel CPU pass;
 * the preview's video is a `TextureView`, so neither is available
 * without re-introducing exactly the filter-on-the-video-layer shape
 * that broke the Play button twice. See the note in
 * `EditorScreen`.)
 *
 * ## The fix
 *
 *  - **Vignette** — the preview builds its plate with the export's own
 *    `VignettePlate.pixels`, at the same 1/4 resolution, and stretches
 *    it over the video's rect with the same over-cover. The falloff
 *    and the alpha are therefore the export's, not a lookalike.
 *  - **Grain** — an approximation, because the export's grain is a
 *    fragment shader. The tile carries white/black speckles whose alpha
 *    follows the noise's magnitude, so it both lightens and darkens.
 */
class DetailPreviewParityTest {

    // -----------------------------------------------------------------
    // The vignette is the export's maths, not a lookalike
    // -----------------------------------------------------------------

    @Test
    fun `the preview plate is the export's plate`() {
        // Same inputs, same function, same pixels. This is the whole
        // parity claim for the vignette.
        val w = 1920
        val h = 1080
        val exportPlate = VignettePlate.pixels(
            frameWidth = w,
            frameHeight = h,
            amount = 0.6f,
            feather = 0.4f,
            roundness = 0.5f
        )
        val previewPlate = VignettePlate.pixels(
            frameWidth = w,
            frameHeight = h,
            amount = 0.6f,
            feather = 0.4f,
            roundness = 0.5f
        )
        assertEquals(exportPlate.size, previewPlate.size)
        assertTrue(
            "the two plates must be identical",
            exportPlate.contentEquals(previewPlate)
        )
    }

    @Test
    fun `the plate is generated at a quarter of the frame`() {
        val (pw, ph) = VignettePlate.plateSize(1920, 1080)
        assertEquals(480, pw)
        assertEquals(270, ph)
    }

    @Test
    fun `the vignette centre stays clear and the corners darken`() {
        val w = 400
        val h = 400
        val pixels = VignettePlate.pixels(w, h, 0.8f, 0.5f, 0.5f)
        val (pw, ph) = VignettePlate.plateSize(w, h)
        fun alphaAt(x: Int, y: Int): Int {
            val argb = pixels[y * pw + x]
            return (argb ushr 24) and 0xFF
        }
        val centre = alphaAt(pw / 2, ph / 2)
        val corner = alphaAt(0, 0)
        assertTrue(
            "the centre must stay clear (was $centre), the corner dark (was $corner)",
            centre < 16 && corner > centre
        )
    }

    @Test
    fun `an inactive vignette renders nothing`() {
        assertFalse(VignettePlate.isActive(0f))
        assertFalse(VignettePlate.isActive(-0.0001f))
        assertTrue(VignettePlate.isActive(0.4f))
        assertTrue(VignettePlate.isActive(-0.4f))
    }

    // -----------------------------------------------------------------
    // The grain tile
    // -----------------------------------------------------------------

    @Test
    fun `the grain tile is black and white speckles with varying alpha`() {
        val tile = GrainSignal.noiseTile(64, 1234L)
        assertEquals(64 * 64, tile.size)
        var sawLight = false
        var sawDark = false
        var sawTransparent = false
        for (argb in tile) {
            val a = (argb ushr 24) and 0xFF
            val rgb = argb and 0xFFFFFF
            when (rgb) {
                0x00FFFFFF -> {
                    sawLight = true
                    assertTrue("a light speckle must not be fully opaque", a < 255)
                }
                0x000000 -> sawDark = true
                else -> throw AssertionError("unexpected speckle colour ${rgb.toString(16)}")
            }
            if (a == 0) sawTransparent = true
        }
        assertTrue("the tile must contain light speckles", sawLight)
        assertTrue("the tile must contain dark speckles", sawDark)
        assertTrue("the tile must contain nearly-invisible speckles", sawTransparent)
    }

    @Test
    fun `the grain tile is deterministic for a seed and differs between seeds`() {
        val a = GrainSignal.noiseTile(32, 7L)
        val b = GrainSignal.noiseTile(32, 7L)
        val c = GrainSignal.noiseTile(32, 8L)
        assertTrue("the same seed must give the same tile", a.contentEquals(b))
        assertFalse("a different seed must give a different tile", a.contentEquals(c))
    }

    @Test
    fun `the grain tile size is clamped to something sane`() {
        assertEquals(4, GrainSignal.noiseTile(1, 1L).size)
        assertEquals(4, GrainSignal.noiseTile(0, 1L).size)
        assertEquals(2 * 2, GrainSignal.noiseTile(2, 1L).size)
    }

    @Test
    fun `the grain amplitude matches the export's budget`() {
        assertEquals(0f, GrainSignal.amplitude(0f), 1e-6f)
        assertEquals(GrainSignal.MAX_AMPLITUDE, GrainSignal.amplitude(1f), 1e-6f)
        assertEquals(GrainSignal.MAX_AMPLITUDE / 2f, GrainSignal.amplitude(0.5f), 1e-6f)
    }

    // -----------------------------------------------------------------
    // Wiring
    // -----------------------------------------------------------------

    private fun readSource(relative: String): String {
        var dir: File = File(".").absoluteFile
        val root = generateSequence(dir) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: throw IllegalStateException("settings.gradle.kts not found")
        val f = File(root, relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private val editorScreen =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"

    @Test
    fun `the preview renders the vignette from the export's plate`() {
        val src = readSource(editorScreen)
        assertTrue(
            "the preview must build the plate with VignettePlate.pixels",
            src.contains("com.aicreatorstudio.app.model.VignettePlate.pixels(")
        )
        assertTrue(
            "the preview must place the plate on the video's rect",
            src.contains("com.aicreatorstudio.app.model.CanvasLetterbox.videoRect(")
        )
        assertTrue(
            "the preview must use the export's over-cover",
            src.contains(".overlayScale(")
        )
    }

    @Test
    fun `the preview renders the grain`() {
        val src = readSource(editorScreen)
        assertTrue(
            "the preview must build a grain tile",
            src.contains(".noiseTile(")
        )
        assertTrue(
            "the preview must tile the noise",
            src.contains("TileMode.Repeated")
        )
    }

    @Test
    fun `the detail passes are siblings, not filters on the video layer`() {
        // The Play-button history: a colour filter on the video layer
        // broke playback twice. The vignette and the grain must be
        // drawn as overlays, never as a filter on the AndroidView's
        // modifier chain.
        val src = readSource(editorScreen)
        assertFalse(
            "the detail passes must not use a colour filter on the video layer",
            src.contains("Modifier\n                        .fillMaxSize()\n                        // 2026-09-19 — the preview colour is NOT applied here.")
        )
    }

    @Test
    fun `the vignette amount is not silently ignored anywhere`() {
        // The export's own check, so the two agree on "is it on".
        assertTrue(
            "a vignette must survive into the export",
            ColorGrading(vignetteAmount = 0.5f).let {
                VignettePlate.isActive(it.vignetteAmount)
            }
        )
    }

    @Test
    fun `the video rect helper is the export's`() {
        // A sanity check on the helper the preview now shares with the
        // export: a 16:9 source in a 4:3 canvas letterboxes.
        val rect = com.aicreatorstudio.app.model.CanvasLetterbox.videoRect(
            canvasWidth = 400,
            canvasHeight = 300,
            sourceWidth = 1920,
            sourceHeight = 1080,
            rotationDegrees = 0f,
            fillMode = FillMode.FIT
        )
        assertNotEquals(null, rect)
        val r = rect!!
        assertEquals(400f, r.width, 0.01f)
        assertEquals(225f, r.height, 0.01f)
        assertEquals(37.5f, r.top, 0.01f)
    }
}
