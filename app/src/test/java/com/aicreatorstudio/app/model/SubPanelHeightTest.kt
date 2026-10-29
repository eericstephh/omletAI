package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the editor sub-panel height policy.
 *
 * These are plain-JVM tests over plain floats — no `Dp`, no `Color`, no
 * Android types — which is deliberate: `unitTests.isReturnDefaultValues`
 * makes `android.*` return `0`/`0f` in unit tests, so a `Dp`-typed API would
 * be asserted against zeros and pass for the wrong reason.
 */
class SubPanelHeightTest {

    /** Top bar (52dp) + playback strip (56dp) — chrome above the sheet. */
    private val chromeDp = 108f

    /** Screens worth checking: a small phone up to a large tablet, in dp. */
    private val screenHeights = listOf(600f, 640f, 720f, 800f, 900f, 1000f, 1200f)

    // ── Classification ─────────────────────────────────────────

    @Test
    fun `every tool panel is classified and none is left unhandled`() {
        val panels = ToolPanel.values()
        assertTrue("ToolPanel should not be empty", panels.isNotEmpty())
        panels.forEach { panel ->
            val kind = SubPanelHeight.kindFor(panel)
            assertTrue(
                "Panel $panel got an unexpected kind $kind",
                kind == SubPanelHeight.Kind.COMPACT || kind == SubPanelHeight.Kind.GALLERY
            )
        }
    }

    @Test
    fun `slider and control panels are compact`() {
        val expected = listOf(
            ToolPanel.TRIM,
            ToolPanel.SPEED,
            ToolPanel.VOLUME,
            ToolPanel.FILTERS,
            ToolPanel.ADJUST,
            ToolPanel.OPACITY,
            ToolPanel.TRANSFORM,
            ToolPanel.CROP,
            ToolPanel.BACKGROUND,
            ToolPanel.AI_VOICE_CLEAN,
            // 2026-10-13 — the progress-bar panel is a toggle, two edge
            // chips, three sliders and one swatch row. That is the COMPACT
            // shape, not a gallery.
            ToolPanel.PROGRESS_BAR
        )
        expected.forEach { panel ->
            assertEquals(
                "$panel should be COMPACT",
                SubPanelHeight.Kind.COMPACT,
                SubPanelHeight.kindFor(panel)
            )
        }
    }

    @Test
    fun `gallery panels are not compact`() {
        // The panels the request named explicitly — the style/template
        // galleries and the font pickers — plus a few obvious list panels.
        val gallery = listOf(
            ToolPanel.SUBTITLE_STUDIO,
            ToolPanel.TEXT,
            ToolPanel.SUBTITLES,
            ToolPanel.EFFECTS,
            ToolPanel.ANIMATIONS,
            ToolPanel.TRANSITIONS,
            ToolPanel.AUDIO,
            ToolPanel.OVERLAY,
            ToolPanel.ASPECT_RATIO,
            ToolPanel.AI_MATTING
        )
        assertEquals(
            "gallery list should not repeat a panel",
            gallery.size,
            gallery.toSet().size
        )
        gallery.forEach { panel ->
            assertEquals(
                "$panel should be GALLERY",
                SubPanelHeight.Kind.GALLERY,
                SubPanelHeight.kindFor(panel)
            )
        }
    }

    @Test
    fun `unknown future panels default to the roomier gallery treatment`() {
        // Fails safe: a too-tall slider sheet is untidy, a too-short gallery
        // is unusable because its cards cannot be reached. Anything not
        // explicitly listed as COMPACT must therefore be GALLERY.
        val compact = ToolPanel.values().filter {
            SubPanelHeight.kindFor(it) == SubPanelHeight.Kind.COMPACT
        }
        assertEquals(
            "The COMPACT allow-list changed — update the doc comment in " +
                "SubPanelHeight.kindFor if this was intentional",
            11,
            compact.size
        )
        ToolPanel.values().forEach { panel ->
            if (panel !in compact) {
                assertEquals(
                    "$panel is not on the COMPACT allow-list so it must be GALLERY",
                    SubPanelHeight.Kind.GALLERY,
                    SubPanelHeight.kindFor(panel)
                )
            }
        }
    }

    @Test
    fun `fraction is higher for gallery than for compact`() {
        assertEquals(SubPanelHeight.CompactFraction, SubPanelHeight.fractionFor(ToolPanel.SPEED), 0.0001f)
        assertEquals(SubPanelHeight.GalleryFraction, SubPanelHeight.fractionFor(ToolPanel.TEXT), 0.0001f)
        assertTrue(SubPanelHeight.GalleryFraction > SubPanelHeight.CompactFraction)
    }

    @Test
    fun `compact and gallery bands are well formed`() {
        assertTrue("compact min must be below its max", SubPanelHeight.CompactMinDp < SubPanelHeight.CompactMaxDp)
        assertTrue("gallery min must be below its max", SubPanelHeight.GalleryMinDp < SubPanelHeight.GalleryMaxDp)
        assertTrue("gallery must be allowed more room than compact", SubPanelHeight.GalleryMaxDp > SubPanelHeight.CompactMaxDp)
        assertTrue("preview floor must be positive", SubPanelHeight.PreviewMinDp > 0f)
    }

    // ── The preview is never squashed ──────────────────────────

    @Test
    fun `the preview always keeps its minimum on every screen and panel`() {
        ToolPanel.values().forEach { panel ->
            screenHeights.forEach { screen ->
                val available = screen - chromeDp
                val sheet = SubPanelHeight.resolveHeightDp(available, panel)
                val preview = available - sheet
                assertTrue(
                    "$panel on a ${screen}dp screen left the preview only ${preview}dp",
                    preview >= SubPanelHeight.PreviewMinDp - 0.001f
                )
            }
        }
    }

    @Test
    fun `a tiny screen shrinks the sheet rather than the preview`() {
        // 300dp of post-chrome space: the preview floor is 180, so the sheet
        // may only take 120 even though SPEED would like 170.
        val sheet = SubPanelHeight.resolveHeightDp(300f, ToolPanel.SPEED)
        assertEquals(120f, sheet, 0.001f)
        assertEquals(180f, 300f - sheet, 0.001f)
    }

    @Test
    fun `space smaller than the preview floor collapses the sheet to zero`() {
        val sheet = SubPanelHeight.resolveHeightDp(150f, ToolPanel.TEXT)
        assertEquals(0f, sheet, 0.001f)
    }

    // ── The requested percentage bands ─────────────────────────

    @Test
    fun `compact panels stay under 28 percent of screen height`() {
        val compactPanels = ToolPanel.values().filter {
            SubPanelHeight.kindFor(it) == SubPanelHeight.Kind.COMPACT
        }
        // 640dp is the shortest screen where the 170dp floor is itself under
        // 28%; below that the floor wins, which is the documented trade-off.
        screenHeights.filter { it >= 640f }.forEach { screen ->
            val available = screen - chromeDp
            compactPanels.forEach { panel ->
                val share = SubPanelHeight.resolveHeightDp(available, panel) / screen
                assertTrue(
                    "$panel took ${share * 100}% of a ${screen}dp screen",
                    share <= 0.28f + 0.0001f
                )
            }
        }
    }

    @Test
    fun `gallery panels stay under 38 percent of screen height`() {
        val galleryPanels = ToolPanel.values().filter {
            SubPanelHeight.kindFor(it) == SubPanelHeight.Kind.GALLERY
        }
        screenHeights.forEach { screen ->
            val available = screen - chromeDp
            galleryPanels.forEach { panel ->
                val share = SubPanelHeight.resolveHeightDp(available, panel) / screen
                assertTrue(
                    "$panel took ${share * 100}% of a ${screen}dp screen",
                    share <= 0.38f + 0.0001f
                )
            }
        }
    }

    @Test
    fun `compact panels land in the requested 170 to 240 dp band`() {
        screenHeights.forEach { screen ->
            val available = screen - chromeDp
            val sheet = SubPanelHeight.resolveHeightDp(available, ToolPanel.VOLUME)
            assertTrue(
                "VOLUME was ${sheet}dp on a ${screen}dp screen",
                sheet >= SubPanelHeight.CompactMinDp - 0.001f &&
                    sheet <= SubPanelHeight.CompactMaxDp + 0.001f
            )
        }
    }

    @Test
    fun `the sheet is never the old flat 50 percent`() {
        // Regression guard for the bug this file exists to fix: on a
        // 800dp-tall phone the sheet used to take half of the post-chrome
        // space (346dp) and leave the preview ~346dp. It must now be far
        // smaller for a slider panel.
        val available = 800f - chromeDp
        val sheet = SubPanelHeight.resolveHeightDp(available, ToolPanel.SPEED)
        val oldBehaviour = available * 0.5f
        assertTrue("sheet is still half the space: $sheet", sheet < oldBehaviour * 0.75f)
    }

    // ── Monotonicity and degenerate input ──────────────────────

    @Test
    fun `a taller screen never yields a shorter sheet`() {
        ToolPanel.values().forEach { panel ->
            var previous = -1f
            (300..1400 step 20).forEach { screen ->
                val sheet = SubPanelHeight.resolveHeightDp(screen.toFloat() - chromeDp, panel)
                assertTrue(
                    "$panel went down when the screen grew: $previous -> $sheet",
                    sheet >= previous - 0.001f
                )
                previous = sheet
            }
        }
    }

    @Test
    fun `degenerate input returns zero instead of inventing a size`() {
        assertEquals(0f, SubPanelHeight.resolveHeightDp(0f, ToolPanel.SPEED), 0.001f)
        assertEquals(0f, SubPanelHeight.resolveHeightDp(-100f, ToolPanel.SPEED), 0.001f)
        assertEquals(0f, SubPanelHeight.resolveHeightDp(Float.NaN, ToolPanel.SPEED), 0.001f)
        assertEquals(0f, SubPanelHeight.resolveHeightDp(Float.POSITIVE_INFINITY, ToolPanel.SPEED), 0.001f)
        assertEquals(0f, SubPanelHeight.resolveHeightDp(500f, ToolPanel.SPEED, previewMinDp = -1f), 0.001f)
        assertEquals(0f, SubPanelHeight.resolveHeightDp(500f, ToolPanel.SPEED, previewMinDp = Float.NaN), 0.001f)
    }

    @Test
    fun `the sheet is always a finite non-negative number`() {
        ToolPanel.values().forEach { panel ->
            listOf(0f, 1f, 200f, 500f, 5000f).forEach { available ->
                val sheet = SubPanelHeight.resolveHeightDp(available, panel)
                assertTrue("$panel/$available produced $sheet", sheet.isFinite())
                assertFalse("$panel/$available produced a negative height", sheet < 0f)
            }
        }
    }

    @Test
    fun `an oversized preview floor never produces a negative sheet`() {
        val sheet = SubPanelHeight.resolveHeightDp(400f, ToolPanel.TEXT, previewMinDp = 900f)
        assertEquals(0f, sheet, 0.001f)
    }
}
