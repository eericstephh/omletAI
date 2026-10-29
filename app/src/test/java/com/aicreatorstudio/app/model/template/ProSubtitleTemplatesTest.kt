package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-15 — the SIX-PRO VIRAL set (`fa_pro_*`).
 *
 * The same six designs ship through TWO catalogs:
 *   1. [BuiltInSubtitleTemplates] — hand-built engine templates
 *      (the EditPanel one-tap surface), and
 *   2. `assets/subtitle_templates.json` — the 6-axis authoring
 *      format, mapped to engine templates by [JsonTemplateMapper]
 *      (the Subtitle Studio picker surface).
 *
 * The user-facing requirement is that BOTH routes produce the SAME
 * design: same bundled Persian font, same colors, same stroke/glow
 * numbers, same background kind. Preview and export share the
 * renderer, so once the field values agree here, the pixels agree
 * everywhere. These tests pin that agreement per template plus the
 * safety band every template must respect.
 */
class ProSubtitleTemplatesTest {

    private val proIds = listOf(
        "fa_pro_hormozi_viral",
        "fa_pro_neon_cyberpunk",
        "fa_pro_glass_minimal",
        "fa_pro_comic_pop",
        "fa_pro_golden_lux",
        "fa_pro_red_punch"
    )

    private fun catalogJson(): String {
        val file = java.io.File("src/main/assets/subtitle_templates.json")
        assertTrue("asset missing: ${file.absolutePath}", file.exists())
        return file.readText()
    }

    private fun mappedPro(): Map<String, com.aicreatorstudio.app.model.SubtitleTemplate> {
        val parsed = JsonTemplateParser.parse(catalogJson()).catalog
        return proIds.associate { id ->
            val json = parsed.templates.firstOrNull { it.id == id }
            assertNotNull("JSON catalog must ship $id", json)
            id to JsonTemplateMapper.toSubtitleTemplate(ResolvedTemplate.resolve(json!!))
        }
    }

    private fun builtInPro(): Map<String, com.aicreatorstudio.app.model.SubtitleTemplate> =
        proIds.associateWith { id ->
            val t = BuiltInSubtitleTemplates.byId(id)
            assertNotNull("BuiltInSubtitleTemplates must ship $id", t)
            t!!
        }

    // ---- both catalogs ship the set ----

    @Test
    fun `both catalogs ship exactly the six pro ids`() {
        val builtIn = builtInPro()
        val mapped = mappedPro()
        assertEquals(proIds.toSet(), builtIn.keys.toSet())
        assertEquals(proIds.toSet(), mapped.keys.toSet())
    }

    @Test
    fun `the six pro designs carry their Persian identity`() {
        val names = mapOf(
            "fa_pro_hormozi_viral" to "هرموزی وایرال",
            "fa_pro_neon_cyberpunk" to "نئون سایبرپانک",
            "fa_pro_glass_minimal" to "شیشه‌ای مینیمال",
            "fa_pro_comic_pop" to "کمیک پاپ",
            "fa_pro_golden_lux" to "طلایی لوکس",
            "fa_pro_red_punch" to "پانچ قرمز"
        )
        builtInPro().forEach { (id, t) ->
            assertEquals("built-in name of $id", names[id], t.name)
            assertTrue("built-in flag for $id", t.builtIn)
        }
        mappedPro().forEach { (id, t) ->
            assertEquals("mapped name of $id", names[id], t.name)
        }
    }

    // ---- per-template design contracts (JSON ↔ built-in parity) ----

    @Test
    fun `hormozi viral — white display type on a 5sp stroke with a yellow pop in a pill`() {
        val b = builtInPro().getValue("fa_pro_hormozi_viral")
        val m = mappedPro().getValue("fa_pro_hormozi_viral")
        // Font parity through both routes.
        assertEquals(PersianFont.LALEZAR.name, b.style.fontFamily)
        assertEquals(b.style.fontFamily, m.style.fontFamily)
        // White body + 5sp black stroke, BOTH routes.
        assertEquals(0xFFFFFFFF.toInt(), b.style.textColorArgb)
        assertEquals(0xFFFFFFFF.toInt(), m.style.textColorArgb)
        assertTrue(b.style.strokeEnabled)
        assertTrue(m.style.strokeEnabled)
        assertEquals(5f, b.style.strokeWidthSp, 0.001f)
        assertEquals(5f, m.style.strokeWidthSp, 0.001f)
        assertEquals(0xFF000000.toInt(), b.style.strokeColorArgb)
        assertEquals(0xFF000000.toInt(), m.style.strokeColorArgb)
        // Electric-yellow active word popping at the 1.12 readability
        // cap (the brief's 1.2x exceeds the catalog's research cap),
        // BOTH routes.
        assertEquals(0xFFFFD60A.toInt(), b.activeWordColorArgb)
        assertEquals(0xFFFFD60A.toInt(), m.activeWordColorArgb)
        assertEquals(1.12f, b.activeWordScale, 0.001f)
        assertEquals(1.12f, m.activeWordScale, 0.001f)
        assertEquals(WordMotionKind.BOUNCE_POP, b.motionKind)
        assertEquals(WordMotionKind.BOUNCE_POP, m.motionKind)
        assertEquals(WordRevealMode.HIGHLIGHT, b.wordRevealMode)
        assertEquals(WordRevealMode.HIGHLIGHT, m.wordRevealMode)
        // Frosted black pill: near-opaque dark plate, extreme radius.
        assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, b.backgroundKind)
        assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, m.backgroundKind)
        assertEquals(999f, m.style.backgroundCornerRadiusDp, 0.001f)
        assertEquals(999f, b.style.backgroundCornerRadiusDp, 0.001f)
        assertEquals(0.85f, m.style.backgroundOpacity, 0.001f)
        assertEquals(0.85f, b.style.backgroundOpacity, 0.001f)
        assertEquals(TextOverlayAnimation.POP, b.animationIn)
        assertEquals(TextOverlayAnimation.POP, m.animationIn)
    }

    @Test
    fun `neon cyberpunk — cyan body, hot-pink word, wide neon halo`() {
        val b = builtInPro().getValue("fa_pro_neon_cyberpunk")
        val m = mappedPro().getValue("fa_pro_neon_cyberpunk")
        assertEquals(PersianFont.SHABNAM.name, b.style.fontFamily)
        assertEquals(b.style.fontFamily, m.style.fontFamily)
        // Cyan body text on BOTH routes.
        assertEquals(0xFF00E5FF.toInt(), b.style.textColorArgb)
        assertEquals(0xFF00E5FF.toInt(), m.style.textColorArgb)
        // Hot-pink active word on BOTH routes.
        assertEquals(0xFFFF2ED1.toInt(), b.activeWordColorArgb)
        assertEquals(0xFFFF2ED1.toInt(), m.activeWordColorArgb)
        // High-blur neon halo: 18dp glow, BOTH routes.
        assertEquals(18f, b.activeWordGlowRadiusDp, 0.001f)
        assertEquals(18f, m.activeWordGlowRadiusDp, 0.001f)
        assertEquals(WordMotionKind.NEON_GLOW, b.motionKind)
        assertEquals(WordMotionKind.NEON_GLOW, m.motionKind)
        // The glow channel paints cyan on both routes.
        assertEquals(0xFF00E5FF.toInt(), b.accentColorArgb)
        assertEquals(0xFF00E5FF.toInt(), m.accentColorArgb)
    }

    @Test
    fun `glass minimal — frosted dark plate at 20dp with a subtle scale`() {
        val b = builtInPro().getValue("fa_pro_glass_minimal")
        val m = mappedPro().getValue("fa_pro_glass_minimal")
        assertEquals(PersianFont.VAZIR.name, b.style.fontFamily)
        assertEquals(b.style.fontFamily, m.style.fontFamily)
        // White text on a frosted (BLUR) dark plate, BOTH routes.
        assertEquals(0xFFFFFFFF.toInt(), b.style.textColorArgb)
        assertEquals(0xFFFFFFFF.toInt(), m.style.textColorArgb)
        assertEquals(CaptionBackgroundKind.BLUR, b.backgroundKind)
        assertEquals(CaptionBackgroundKind.BLUR, m.backgroundKind)
        // The user-pinned 20dp radius must survive the JSON mapping.
        assertEquals(20f, b.style.backgroundCornerRadiusDp, 0.001f)
        assertEquals(20f, m.style.backgroundCornerRadiusDp, 0.001f)
        assertEquals(0.45f, b.style.backgroundOpacity, 0.001f)
        assertEquals(0.45f, m.style.backgroundOpacity, 0.001f)
        // Subtle scale — the quietest active word in the set.
        assertEquals(1.08f, b.activeWordScale, 0.001f)
        assertEquals(1.08f, m.activeWordScale, 0.001f)
    }

    @Test
    fun `comic pop — thick outline with a green pop`() {
        val b = builtInPro().getValue("fa_pro_comic_pop")
        val m = mappedPro().getValue("fa_pro_comic_pop")
        assertEquals(PersianFont.ESTEDAD.name, b.style.fontFamily)
        assertEquals(b.style.fontFamily, m.style.fontFamily)
        // The thickest outline in the catalog, BOTH routes.
        assertEquals(6f, b.style.strokeWidthSp, 0.001f)
        assertEquals(6f, m.style.strokeWidthSp, 0.001f)
        // Green active word popping at the 1.12 readability cap, BOTH routes.
        assertEquals(0xFF30D158.toInt(), b.activeWordColorArgb)
        assertEquals(0xFF30D158.toInt(), m.activeWordColorArgb)
        assertEquals(1.12f, b.activeWordScale, 0.001f)
        assertEquals(1.12f, m.activeWordScale, 0.001f)
        assertEquals(WordMotionKind.BOUNCE_POP, b.motionKind)
        assertEquals(WordMotionKind.BOUNCE_POP, m.motionKind)
        // No plate — the outline carries legibility.
        assertFalse(b.style.backgroundEnabled)
        assertFalse(m.style.backgroundEnabled)
        // The yellow secondary accent rides the accent channel on
        // the built-in (inert for this engine combo, honoring the
        // green/yellow brief).
        assertEquals(0xFFFFD60A.toInt(), b.accentColorArgb)
    }

    @Test
    fun `golden lux — champagne text on a 45-degree gold gradient`() {
        val b = builtInPro().getValue("fa_pro_golden_lux")
        val m = mappedPro().getValue("fa_pro_golden_lux")
        assertEquals(PersianFont.VAZIR.name, b.style.fontFamily)
        assertEquals(b.style.fontFamily, m.style.fontFamily)
        // Champagne body + gold active word, BOTH routes.
        assertEquals(0xFFF7E7CE.toInt(), b.style.textColorArgb)
        assertEquals(0xFFF7E7CE.toInt(), m.style.textColorArgb)
        assertEquals(0xFFFFD700.toInt(), b.activeWordColorArgb)
        assertEquals(0xFFFFD700.toInt(), m.activeWordColorArgb)
        // Gradient plate (deep charcoal → gold), BOTH routes.
        assertEquals(CaptionBackgroundKind.GRADIENT, b.backgroundKind)
        assertEquals(CaptionBackgroundKind.GRADIENT, m.backgroundKind)
        assertEquals(45f, b.gradientAngleDeg ?: -1f, 0.001f)
        assertEquals(45f, m.gradientAngleDeg ?: -1f, 0.001f)
        // The accent IS the gradient's gold end, BOTH routes.
        assertEquals(0xFFC9A227.toInt(), b.accentColorArgb)
        assertEquals(0xFFC9A227.toInt(), m.accentColorArgb)
        // Deep soft shadow on both routes.
        assertTrue(b.style.shadowEnabled)
        assertTrue(m.style.shadowEnabled)
        assertTrue("deep shadow needs real offset", b.style.shadowOffsetYDp >= 2f)
    }

    @Test
    fun `red punch — red badge with an inverted white chip word`() {
        val b = builtInPro().getValue("fa_pro_red_punch")
        val m = mappedPro().getValue("fa_pro_red_punch")
        assertEquals(PersianFont.ESTEDAD.name, b.style.fontFamily)
        assertEquals(b.style.fontFamily, m.style.fontFamily)
        // White text on an opaque red pill badge, BOTH routes.
        assertEquals(0xFFFFFFFF.toInt(), b.style.textColorArgb)
        assertEquals(0xFFFFFFFF.toInt(), m.style.textColorArgb)
        assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, b.backgroundKind)
        assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, m.backgroundKind)
        assertEquals(0xFFE6203A.toInt(), b.style.backgroundColorArgb)
        assertEquals(0xFFE6203A.toInt(), m.style.backgroundColorArgb)
        assertEquals(999f, b.style.backgroundCornerRadiusDp, 0.001f)
        assertEquals(999f, m.style.backgroundCornerRadiusDp, 0.001f)
        // THE INVERTED CHIP — the box highlight draws a WHITE pill
        // behind a RED word. This only works because the mapper
        // honors `boxTextColor`; without it the word and the pill
        // share one color and the active word vanishes.
        assertEquals(0xFFFFFFFF.toInt(), b.activeWordBackgroundArgb)
        assertEquals(0xFFFFFFFF.toInt(), m.activeWordBackgroundArgb)
        assertEquals(0xFFE6203A.toInt(), b.activeWordColorArgb)
        assertEquals(0xFFE6203A.toInt(), m.activeWordColorArgb)
        assertEquals(WordMotionKind.HIGHLIGHT_COLOR, b.motionKind)
        assertEquals(WordMotionKind.HIGHLIGHT_COLOR, m.motionKind)
        assertEquals(TextOverlayAnimation.POP, b.animationIn)
        assertEquals(TextOverlayAnimation.POP, m.animationIn)
    }

    // ---- boxTextColor contract (schema + override paths) ----

    @Test
    fun `boxTextColor decodes from the JSON and drives the chip text`() {
        val parsed = JsonTemplateParser.parse(catalogJson()).catalog
        val red = parsed.templates.first { it.id == "fa_pro_red_punch" }
        assertEquals("#FFFFFF", red.wordHighlight.activeColor)
        assertEquals("#E6203A", red.wordHighlight.boxTextColor)
        // Non-box templates never set it (null = historical contract).
        val hormozi = parsed.templates.first { it.id == "fa_pro_hormozi_viral" }
        assertEquals(null, hormozi.wordHighlight.boxTextColor)
    }

    @Test
    fun `boxTextColor survives the override path for box highlights`() {
        val red = mappedPro().getValue("fa_pro_red_punch")
        val overridden = red.withHighlightOverride(
            HighlightOverride(
                type = "box",
                activeColor = "#FFFFFF",
                boxTextColor = "#E6203A"
            )
        )
        assertEquals(0xFFFFFFFF.toInt(), overridden.activeWordBackgroundArgb)
        assertEquals(0xFFE6203A.toInt(), overridden.activeWordColorArgb)
    }

    // ---- safety band ----

    @Test
    fun `every pro template resolves a bundled Persian font`() {
        (builtInPro() + mappedPro()).forEach { (id, t) ->
            val family = PersianFont.from(t.style.fontFamily)
            assertTrue(
                "$id must pin a bundled Persian family (got ${t.style.fontFamily})",
                family in PersianFont.PersianFamilies
            )
        }
    }

    @Test
    fun `every pro template sits inside the engine validation band`() {
        (builtInPro() + mappedPro()).forEach { (id, t) ->
            assertTrue("$id duration", t.animationDurationMs in 60..1500)
            assertTrue("$id active scale", t.activeWordScale in 0.8f..2.5f)
            assertTrue("$id positionY", t.style.positionY in 0f..1f)
            assertTrue(
                "$id maxTextWidthFraction",
                t.style.maxTextWidthFraction in 0.1f..1f
            )
            assertTrue("$id name", t.name.isNotBlank())
        }
    }

    @Test
    fun `the six pro designs are distinct from each other and the classic 20`() {
        val all = BuiltInSubtitleTemplates.ALL
        val fingerprints = all.map { it.id to fingerprint(it) }
        assertEquals(
            "duplicate presets share a render fingerprint",
            all.size,
            fingerprints.map { it.second }.toSet().size
        )
    }

    /** Same render-pixel shape the export-parity test pins. */
    private fun fingerprint(t: com.aicreatorstudio.app.model.SubtitleTemplate): String =
        listOf(
            t.style.fontFamily,
            t.style.fontSizeSp,
            t.style.textColorArgb,
            t.style.strokeEnabled,
            t.style.strokeWidthSp,
            t.style.backgroundEnabled,
            t.style.backgroundCornerRadiusDp,
            t.style.positionY,
            t.activeWordColorArgb,
            t.activeWordScale,
            t.activeWordBackgroundArgb,
            t.wordRevealMode,
            t.motionKind,
            t.backgroundKind,
            t.animationIn,
            t.activeWordGlowRadiusDp
        ).joinToString("|")
}
