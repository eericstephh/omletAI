package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ProgressBarGeometry] — the progress fraction and the
 * pixel layout shared by the Compose preview and the Media3 export
 * overlay.
 *
 * These are the two things that must not be wrong:
 *
 *  1. the fraction is always inside `0..1` and never `NaN`, and
 *  2. the geometry is always inside the frame and never throws.
 *
 * Both renderers consume the values produced here, so a bug in this file
 * shows up as a wrong bar in *both* the preview and the exported file —
 * the one place a shared function cannot save you. Hence the density of
 * edge-case assertions.
 *
 * Everything is plain `Float`/`Long`/`Int`. `unitTests.isReturnDefaultValues
 * = true` makes `android.*` inert here, which is exactly why
 * [ProgressBarLayout] is not a `RectF`: a `RectF` would come back with
 * every field `0f` and these tests would pass for the wrong reason.
 */
class ProgressBarGeometryTest {

    private val default = ProgressBarSettings()

    // ── Progress fraction ───────────────────────────────────────

    @Test
    fun `progress is the ratio of position to duration`() {
        assertEquals(0f, ProgressBarGeometry.progressFraction(0L, 10_000L), 1e-6f)
        assertEquals(0.5f, ProgressBarGeometry.progressFraction(5_000L, 10_000L), 1e-6f)
        assertEquals(1f, ProgressBarGeometry.progressFraction(10_000L, 10_000L), 1e-6f)
    }

    @Test
    fun `a zero or negative duration reads as no progress, never as full`() {
        // The dangerous wrong answer here is `1f` (a bar that looks
        // finished before the video has played). A freshly-opened editor
        // and a degenerate trim both hit this path.
        assertEquals(0f, ProgressBarGeometry.progressFraction(0L, 0L), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.progressFraction(5_000L, 0L), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.progressFraction(5_000L, -1L), 1e-6f)
    }

    @Test
    fun `a position before the start or past the end is clamped`() {
        assertEquals(0f, ProgressBarGeometry.progressFraction(-1L, 10_000L), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.progressFraction(Long.MIN_VALUE, 10_000L), 1e-6f)
        assertEquals(1f, ProgressBarGeometry.progressFraction(10_001L, 10_000L), 1e-6f)
        assertEquals(1f, ProgressBarGeometry.progressFraction(Long.MAX_VALUE, 10_000L), 1e-6f)
    }

    @Test
    fun `progress is always inside the unit interval`() {
        val durations = listOf(0L, 1L, 33L, 1_000L, 60_000L, 3_600_000L)
        val positions = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 16L, 999L, 60_000L, Long.MAX_VALUE)
        durations.forEach { duration ->
            positions.forEach { position ->
                val p = ProgressBarGeometry.progressFraction(position, duration)
                assertTrue("$position/$duration produced $p", p.isFinite())
                assertTrue("$position/$duration produced $p", p in 0f..1f)
            }
        }
    }

    @Test
    fun `a long project does not lose precision at the midpoint`() {
        // 3.6e6 ms is an hour; a naive `Long` division would floor the
        // midpoint to 0.5 exactly only by luck.
        assertEquals(
            0.5f,
            ProgressBarGeometry.progressFraction(1_800_000L, 3_600_000L),
            1e-4f
        )
    }

    // ── dp → px ─────────────────────────────────────────────────

    @Test
    fun `dp converts against the shared 720 unit canvas, not screen density`() {
        // THE parity convention. It is the same constant
        // `CaptionRenderer.CANVAS_UNIT_PX` uses for subtitles, which is
        // what makes a 4dp bar the same fraction of the frame at every
        // export resolution.
        assertEquals(720f, ProgressBarGeometry.CANVAS_UNIT_PX, 1e-6f)
        assertEquals(4f, ProgressBarGeometry.dpToPx(4f, 720f), 1e-6f)
        assertEquals(6f, ProgressBarGeometry.dpToPx(4f, 1080f), 1e-6f)
        assertEquals(8f, ProgressBarGeometry.dpToPx(4f, 1440f), 1e-6f)
        assertEquals(2f, ProgressBarGeometry.dpToPx(4f, 360f), 1e-6f)
    }

    @Test
    fun `dp conversion refuses to invent a size from bad input`() {
        // A NaN reaching `Canvas.drawRoundRect` throws, which aborts the
        // export rather than merely looking wrong.
        assertEquals(0f, ProgressBarGeometry.dpToPx(Float.NaN, 1080f), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.dpToPx(Float.POSITIVE_INFINITY, 1080f), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.dpToPx(-4f, 1080f), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.dpToPx(4f, 0f), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.dpToPx(4f, -1f), 1e-6f)
        assertEquals(0f, ProgressBarGeometry.dpToPx(4f, Float.NaN), 1e-6f)
    }

    // ── Layout: horizontal span ─────────────────────────────────

    @Test
    fun `the track spans the full canvas width when there is no margin`() {
        val layout = ProgressBarGeometry.layout(default, 1080f, 1920f, 0f)
        assertEquals(0f, layout.trackLeft, 1e-4f)
        assertEquals(1080f, layout.trackRight, 1e-4f)
        assertEquals(1080f, layout.trackWidth, 1e-4f)
    }

    @Test
    fun `the margin insets the track on both sides`() {
        // 24dp at a 720-tall canvas is 24px per side.
        val layout = ProgressBarGeometry.layout(
            default.copy(marginDp = 24f),
            canvasWidthPx = 720f,
            canvasHeightPx = 720f,
            progress = 0f
        )
        assertEquals(24f, layout.trackLeft, 1e-3f)
        assertEquals(720f - 24f, layout.trackRight, 1e-3f)
    }

    // ── Layout: vertical placement ──────────────────────────────

    @Test
    fun `the bottom position pins the track to the bottom edge`() {
        // 4dp at 1080 → 6px tall, flush with the bottom.
        val layout = ProgressBarGeometry.layout(
            default.copy(position = ProgressBarPosition.BOTTOM),
            canvasWidthPx = 1920f,
            canvasHeightPx = 1080f,
            progress = 0f
        )
        assertEquals(1080f - 6f, layout.trackTop, 1e-3f)
        assertEquals(1080f, layout.trackBottom, 1e-3f)
        assertEquals(6f, layout.trackHeight, 1e-3f)
    }

    @Test
    fun `the top position pins the track to the top edge`() {
        val layout = ProgressBarGeometry.layout(
            default.copy(position = ProgressBarPosition.TOP),
            canvasWidthPx = 1920f,
            canvasHeightPx = 1080f,
            progress = 0f
        )
        assertEquals(0f, layout.trackTop, 1e-3f)
        assertEquals(6f, layout.trackBottom, 1e-3f)
    }

    @Test
    fun `the margin also insets the pinned edge`() {
        val layout = ProgressBarGeometry.layout(
            default.copy(position = ProgressBarPosition.TOP, marginDp = 12f),
            canvasWidthPx = 720f,
            canvasHeightPx = 720f,
            progress = 0f
        )
        // 12dp at a 720-tall canvas is 12px.
        assertEquals(12f, layout.trackTop, 1e-3f)
    }

    @Test
    fun `an absurd margin is pulled back inside the frame instead of pushing the bar off it`() {
        val layout = ProgressBarGeometry.layout(
            default.copy(position = ProgressBarPosition.BOTTOM, marginDp = 10_000f),
            canvasWidthPx = 720f,
            canvasHeightPx = 720f,
            progress = 0f
        )
        assertFalse(layout.isEmpty)
        assertTrue("trackTop=${layout.trackTop}", layout.trackTop >= 0f)
        assertTrue("trackBottom=${layout.trackBottom}", layout.trackBottom <= 720f + 1e-3f)
        assertTrue("a 1px sliver of bar must survive", layout.trackWidth >= 1f - 1e-3f)
    }

    @Test
    fun `a bar taller than the frame is clamped to the frame`() {
        val layout = ProgressBarGeometry.layout(
            default.copy(heightDp = 10_000f),
            canvasWidthPx = 720f,
            canvasHeightPx = 720f,
            progress = 0.5f
        )
        assertFalse(layout.isEmpty)
        assertEquals(0f, layout.trackTop, 1e-3f)
        assertEquals(720f, layout.trackBottom, 1e-3f)
    }

    // ── Layout: fill ────────────────────────────────────────────

    @Test
    fun `the fill shares the track's left edge and vertical extent`() {
        val layout = ProgressBarGeometry.layout(default, 1080f, 1920f, 0.5f)
        assertEquals(layout.trackLeft, layout.fillLeft, 1e-4f)
        assertEquals(layout.trackTop, layout.fillTop, 1e-4f)
        assertEquals(layout.trackBottom, layout.fillBottom, 1e-4f)
        assertEquals(layout.trackLeft + 540f, layout.fillRight, 1e-3f)
    }

    @Test
    fun `progress 0 draws no fill and progress 1 fills the whole track`() {
        val empty = ProgressBarGeometry.layout(default, 1080f, 1920f, 0f)
        assertFalse(empty.isFillVisible)

        val full = ProgressBarGeometry.layout(default, 1080f, 1920f, 1f)
        assertTrue(full.isFillVisible)
        assertEquals(full.trackRight, full.fillRight, 1e-3f)
    }

    @Test
    fun `an out-of-range progress is clamped before it becomes geometry`() {
        // The brief's contract is 0.0–1.0; anything outside it must be
        // absorbed here rather than drawn past the canvas edge.
        val over = ProgressBarGeometry.layout(default, 1080f, 1920f, 4f)
        assertEquals(over.trackRight, over.fillRight, 1e-3f)

        val under = ProgressBarGeometry.layout(default, 1080f, 1920f, -4f)
        assertEquals(under.trackLeft, under.fillRight, 1e-3f)
        assertFalse(under.isFillVisible)
    }

    @Test
    fun `a non-finite progress is read as zero, not as NaN geometry`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            val layout = ProgressBarGeometry.layout(default, 1080f, 1920f, bad)
            assertTrue("$bad leaked into fillRight", layout.fillRight.isFinite())
            assertTrue("$bad leaked into cornerRadiusPx", layout.cornerRadiusPx.isFinite())
            assertFalse("$bad produced a visible fill", layout.isFillVisible)
        }
    }

    // ── Layout: corner radius ───────────────────────────────────

    @Test
    fun `a zero corner radius stays square`() {
        val layout = ProgressBarGeometry.layout(
            default.copy(cornerRadiusDp = 0f),
            1080f,
            1080f,
            0.5f
        )
        assertEquals(0f, layout.cornerRadiusPx, 1e-4f)
    }

    @Test
    fun `the corner radius is capped at half the bar height so a pill never bows inward`() {
        // 4dp tall at 1080 → 6px; a 12dp radius would be 18px, which is
        // more than half the height.
        val layout = ProgressBarGeometry.layout(
            default.copy(cornerRadiusDp = 12f),
            canvasWidthPx = 1080f,
            canvasHeightPx = 1080f,
            progress = 0.5f
        )
        assertEquals(3f, layout.cornerRadiusPx, 1e-3f)
        assertTrue(layout.cornerRadiusPx <= layout.trackHeight / 2f + 1e-4f)
    }

    // ── Layout: degenerate input ────────────────────────────────

    @Test
    fun `a degenerate canvas produces an empty layout instead of throwing`() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            assertTrue(
                "width=$bad should be empty",
                ProgressBarGeometry.layout(default, bad, 1080f, 0.5f).isEmpty
            )
            assertTrue(
                "height=$bad should be empty",
                ProgressBarGeometry.layout(default, 1080f, bad, 0.5f).isEmpty
            )
        }
    }

    @Test
    fun `a zero-height bar produces an empty layout`() {
        val layout = ProgressBarGeometry.layout(default.copy(heightDp = 0f), 1080f, 1080f, 0.5f)
        assertTrue(layout.isEmpty)
    }

    @Test
    fun `an empty layout still carries the colours so a caller can draw unconditionally`() {
        val layout = ProgressBarGeometry.layout(
            default.copy(colorArgb = 0xFF00FF00.toInt(), backgroundColorArgb = 0x40FF0000),
            0f,
            0f,
            0.5f
        )
        assertTrue(layout.isEmpty)
        assertEquals(0xFF00FF00.toInt(), layout.fillColorArgb)
        assertEquals(0x40FF0000, layout.trackColorArgb)
    }

    @Test
    fun `layout never escapes the frame for any legal settings`() {
        val heights = listOf(2f, 4f, 8f, 12f)
        val margins = listOf(0f, 8f, 24f, 48f)
        val radii = listOf(0f, 3f, 12f)
        val progresses = listOf(0f, 0.25f, 0.5f, 1f)
        val canvases = listOf(360f to 640f, 1080f to 1920f, 1920f to 1080f, 1080f to 1080f)
        ProgressBarPosition.entries.forEach { position ->
            canvases.forEach { (w, h) ->
                heights.forEach { height ->
                    margins.forEach { margin ->
                        radii.forEach { radius ->
                            progresses.forEach { progress ->
                                val s = ProgressBarSettings(
                                    isEnabled = true,
                                    position = position,
                                    heightDp = height,
                                    cornerRadiusDp = radius,
                                    marginDp = margin
                                ).clamped()
                                val l = ProgressBarGeometry.layout(s, w, h, progress)
                                val label = "$position ${w}x$h h=$height m=$margin r=$radius p=$progress"
                                assertFalse("$label was empty", l.isEmpty)
                                assertTrue("$label left=${l.trackLeft}", l.trackLeft >= -1e-3f)
                                assertTrue("$label right=${l.trackRight}", l.trackRight <= w + 1e-3f)
                                assertTrue("$label top=${l.trackTop}", l.trackTop >= -1e-3f)
                                assertTrue("$label bottom=${l.trackBottom}", l.trackBottom <= h + 1e-3f)
                                assertTrue("$label fill=${l.fillRight}", l.fillRight <= l.trackRight + 1e-3f)
                                assertTrue("$label fill=${l.fillRight}", l.fillRight >= l.trackLeft - 1e-3f)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── clamped() ───────────────────────────────────────────────

    @Test
    fun `clamping the default settings is the identity`() {
        // Load-bearing: `clamped()` runs on every decode and every write,
        // so if it altered the default, every project would drift a little
        // on each save.
        assertEquals(ProgressBarSettings.Disabled, ProgressBarSettings.Disabled.clamped())
        assertEquals(default, default.clamped())
    }

    @Test
    fun `clamping is idempotent`() {
        val wild = ProgressBarSettings(
            isEnabled = true,
            position = ProgressBarPosition.TOP,
            heightDp = 999f,
            colorArgb = 0xFF123456.toInt(),
            backgroundColorArgb = 0x11ABCDEF,
            cornerRadiusDp = 999f,
            marginDp = 999f
        )
        val once = wild.clamped()
        assertEquals(once, once.clamped())
    }

    @Test
    fun `clamping pulls every knob into its legal band`() {
        val wild = ProgressBarSettings(
            isEnabled = true,
            position = ProgressBarPosition.TOP,
            heightDp = 999f,
            colorArgb = 0xFF123456.toInt(),
            backgroundColorArgb = 0x11ABCDEF,
            cornerRadiusDp = 999f,
            marginDp = 999f
        ).clamped()
        assertEquals(ProgressBarGeometry.MAX_HEIGHT_DP, wild.heightDp, 1e-4f)
        assertEquals(ProgressBarGeometry.MAX_CORNER_RADIUS_DP, wild.cornerRadiusDp, 1e-4f)
        assertEquals(ProgressBarGeometry.MAX_MARGIN_DP, wild.marginDp, 1e-4f)
        // Nothing else is touched: the bar's look must survive a clamp.
        assertTrue(wild.isEnabled)
        assertEquals(ProgressBarPosition.TOP, wild.position)
        assertEquals(0xFF123456.toInt(), wild.colorArgb)
        assertEquals(0x11ABCDEF, wild.backgroundColorArgb)
    }

    @Test
    fun `clamping a non-finite value falls back to the default rather than to a bound`() {
        // `NaN.coerceIn(min, max)` returns NaN, so the naive
        // implementation would hand NaN straight to the renderers.
        val wild = ProgressBarSettings(
            heightDp = Float.NaN,
            cornerRadiusDp = Float.POSITIVE_INFINITY,
            marginDp = Float.NEGATIVE_INFINITY
        ).clamped()
        assertEquals(default.heightDp, wild.heightDp, 1e-4f)
        assertEquals(default.cornerRadiusDp, wild.cornerRadiusDp, 1e-4f)
        assertEquals(default.marginDp, wild.marginDp, 1e-4f)
    }

    @Test
    fun `clamping raises a too-small height to the slider minimum`() {
        assertEquals(
            ProgressBarGeometry.MIN_HEIGHT_DP,
            ProgressBarSettings(heightDp = 0.5f).clamped().heightDp,
            1e-4f
        )
        assertEquals(
            ProgressBarGeometry.MIN_HEIGHT_DP,
            ProgressBarSettings(heightDp = -3f).clamped().heightDp,
            1e-4f
        )
    }

    @Test
    fun `the slider bands are well formed`() {
        assertTrue(ProgressBarGeometry.MIN_HEIGHT_DP > 0f)
        assertTrue(ProgressBarGeometry.MAX_HEIGHT_DP > ProgressBarGeometry.MIN_HEIGHT_DP)
        assertTrue(ProgressBarGeometry.MAX_CORNER_RADIUS_DP > 0f)
        assertTrue(ProgressBarGeometry.MAX_MARGIN_DP > 0f)
        // The default must sit inside the band, or the panel would open
        // with a thumb pinned to an end stop.
        assertTrue(
            default.heightDp in ProgressBarGeometry.MIN_HEIGHT_DP..ProgressBarGeometry.MAX_HEIGHT_DP
        )
        assertTrue(default.cornerRadiusDp in 0f..ProgressBarGeometry.MAX_CORNER_RADIUS_DP)
        assertTrue(default.marginDp in 0f..ProgressBarGeometry.MAX_MARGIN_DP)
    }

    @Test
    fun `the default bar is off so an untouched project renders nothing`() {
        assertFalse(ProgressBarSettings.Disabled.isEnabled)
        assertEquals(ProgressBarPosition.BOTTOM, ProgressBarSettings.Disabled.position)
    }

    @Test
    fun `clampOr passes through an in-band value unchanged`() {
        assertEquals(7f, ProgressBarGeometry.clampOr(7f, 2f, 12f, 4f), 1e-6f)
        assertEquals(4f, ProgressBarGeometry.clampOr(Float.NaN, 2f, 12f, 4f), 1e-6f)
        assertEquals(2f, ProgressBarGeometry.clampOr(0f, 2f, 12f, 4f), 1e-6f)
        assertEquals(12f, ProgressBarGeometry.clampOr(50f, 2f, 12f, 4f), 1e-6f)
    }
}
