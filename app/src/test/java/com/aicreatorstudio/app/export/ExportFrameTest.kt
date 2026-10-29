package com.aicreatorstudio.app.export

import androidx.media3.effect.Presentation
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.FillMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [ExportFrame] — the pure decisions behind the export's output
 * frame: how big it is, when it has to be pinned, and how a clip is fitted
 * into it.
 *
 * Three separate defects lived in these answers, and each has a test here
 * because each was invisible without one:
 *
 *  1. **`fillMode` was silently dropped.** `buildPresentation` hard-coded
 *     `LAYOUT_SCALE_TO_FIT` in both branches, so a clip the user set to FILL
 *     exported letterboxed and the FIT/FILL/MANUAL control changed nothing
 *     outside the preview.
 *  2. **The output size never reached the wash passes.**
 *     `ClipMotionEffects.build` used to skip its full-frame washes when the
 *     output size was unknown — it had to, because an overlay is scaled by
 *     `overlaySize / backgroundSize` and a mis-sized wash covers the frame
 *     only partially. `buildEditedMediaItem` declared the two size
 *     parameters but its only caller never passed them, so they were always
 *     `0` and **every** fade, flash and `opacity` wash was skipped.
 *
 *     The washes no longer take a size at all: they observe it from Media3's
 *     `TextureOverlay.configure`, which is the only route that also works for
 *     a FREE canvas whose frame was grown by a rotation. The size this object
 *     resolves is still the single source for the two consumers that must know
 *     it up front — the `Presentation` and `CanvasBackgroundOverlay`'s
 *     build-time test — so this object remains the one owner of that value.
 *  3. **A FREE canvas must resolve from one shared reference.** Media3
 *     writes a single video track for a composition, so resolving per clip
 *     would give a mixed-resolution project a different frame per item —
 *     which `MuxerWrapper` rejects with `AppendTrackFormatException`.
 */
class ExportFrameTest {

    // --- resolve — the size of the exported frame ---

    @Test
    fun `a sized canvas preset wins over the source pixels`() {
        // The whole point of picking 9:16 is that the file is 9:16, whatever
        // the clip was shot at.
        val size = ExportFrame.resolve(
            canvasPreset = CanvasPreset.VERTICAL_9_16,
            reference = segment(widthPx = 1920, heightPx = 1080)
        )
        assertEquals(1080 to 1920, size)
    }

    @Test
    fun `every sized preset resolves to its own export size`() {
        // Exhaustive so a new preset cannot quietly fall through to the
        // source-pixels branch.
        val sized = CanvasPreset.entries.filter { it.exportWidth > 0 && it.exportHeight > 0 }
        assertTrue("expected some sized presets", sized.isNotEmpty())
        sized.forEach { preset ->
            assertEquals(
                "size for ${preset.displayName}",
                preset.exportWidth to preset.exportHeight,
                ExportFrame.resolve(preset, segment(widthPx = 640, heightPx = 480))
            )
        }
    }

    @Test
    fun `a FREE canvas falls back to the reference clip's pixels`() {
        val size = ExportFrame.resolve(
            canvasPreset = CanvasPreset.FREE,
            reference = segment(widthPx = 1920, heightPx = 1080)
        )
        assertEquals(1920 to 1080, size)
    }

    @Test
    fun `a FREE canvas with unknown source pixels resolves to nothing`() {
        // "Free / Original" on a clip whose dimensions we never learned has
        // no size to pin, so the caller must omit the Presentation and let
        // Media3 keep whatever the decoder produces.
        assertNull(
            ExportFrame.resolve(
                canvasPreset = CanvasPreset.FREE,
                reference = segment(widthPx = 0, heightPx = 0)
            )
        )
    }

    @Test
    fun `FREE is the only preset that defers to the source`() {
        // If a preset ever gained a 0 export size by mistake it would start
        // tracking the source instead of the user's choice.
        val deferring = CanvasPreset.entries.filter { it.exportWidth <= 0 || it.exportHeight <= 0 }
        assertEquals(listOf(CanvasPreset.FREE), deferring)
    }

    // --- needsPresentation — when the size has to be pinned ---

    @Test
    fun `a sized preset always needs a presentation`() {
        assertTrue(ExportFrame.needsPresentation(CanvasPreset.VERTICAL_9_16, multiClip = false))
        assertTrue(ExportFrame.needsPresentation(CanvasPreset.VERTICAL_9_16, multiClip = true))
    }

    @Test
    fun `a FREE canvas needs one only for a multi-clip export`() {
        // A single FREE clip has nothing to pin, and leaving it alone is the
        // pre-existing behaviour — an untouched project's effect chain must
        // not change. Several clips do need pinning, because Media3 writes
        // one video track and every item must agree on the frame.
        assertFalse(ExportFrame.needsPresentation(CanvasPreset.FREE, multiClip = false))
        assertTrue(ExportFrame.needsPresentation(CanvasPreset.FREE, multiClip = true))
    }

    // --- layoutFor — how a clip is fitted into that frame ---

    @Test
    fun `FILL crops rather than letterboxing`() {
        // The regression this object exists for. Both used to be
        // LAYOUT_SCALE_TO_FIT, which is why FILL looked inert.
        assertTrue(
            "FILL must not resolve to the same layout as FIT",
            ExportFrame.layoutFor(FillMode.FIT) != ExportFrame.layoutFor(FillMode.FILL)
        )
        assertEquals(
            Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
            ExportFrame.layoutFor(FillMode.FILL)
        )
    }

    @Test
    fun `FIT keeps every source pixel`() {
        assertEquals(
            Presentation.LAYOUT_SCALE_TO_FIT,
            ExportFrame.layoutFor(FillMode.FIT)
        )
    }

    @Test
    fun `MANUAL fits because the pan and zoom are applied on top`() {
        // The preview also picks RESIZE_MODE_FIT for MANUAL and lets its
        // `graphicsLayer` do the transform, so the baseline has to be a fit.
        // Getting this wrong would double-apply the crop and shift every
        // manual pan.
        assertEquals(
            ExportFrame.layoutFor(FillMode.FIT),
            ExportFrame.layoutFor(FillMode.MANUAL)
        )
    }

    @Test
    fun `every fill mode maps to the layout the preview uses`() {
        // Exhaustive on purpose: a new FillMode should fail here rather than
        // silently inherit whichever `when` branch happens to be written
        // last. The values mirror EditorScreen's `playerResizeMode`:
        // FIT -> RESIZE_MODE_FIT, FILL -> RESIZE_MODE_ZOOM, MANUAL -> FIT.
        val expected = mapOf(
            FillMode.FIT to Presentation.LAYOUT_SCALE_TO_FIT,
            FillMode.FILL to Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
            FillMode.MANUAL to Presentation.LAYOUT_SCALE_TO_FIT
        )
        assertEquals(FillMode.entries.toSet(), expected.keys)
        FillMode.entries.forEach { mode ->
            assertEquals("layout for $mode", expected.getValue(mode), ExportFrame.layoutFor(mode))
        }
    }

    private fun segment(
        widthPx: Int = 1920,
        heightPx: Int = 1080,
        rotationDegrees: Int = 0,
        fillMode: FillMode = FillMode.FIT
    ) = RenderSegment(
        sourceUri = "file:///clip.mp4",
        sourceStartMs = 0L,
        sourceEndMs = 5_000L,
        speed = 1f,
        brightness = 1f,
        contrast = 1f,
        saturation = 1f,
        rotationDegrees = rotationDegrees,
        displayName = "clip.mp4",
        fillMode = fillMode,
        sourceWidthPx = widthPx,
        sourceHeightPx = heightPx
    )
}
