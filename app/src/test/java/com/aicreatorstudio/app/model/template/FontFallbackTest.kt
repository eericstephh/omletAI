package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.PersianFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 5 — missing fonts never crash, never pick the system font
 * for Persian, and weights resolve to real bundled TTFs
 * (minSdk-safe: plain resource names, no API-28 calls).
 */
class FontFallbackTest {

    @Test
    fun `matchBundled finds display enum and case variants`() {
        assertEquals(PersianFont.VAZIR, FontFallback.matchBundled("Vazirmatn"))
        assertEquals(PersianFont.VAZIR, FontFallback.matchBundled("vazirmatn"))
        assertEquals(PersianFont.VAZIR, FontFallback.matchBundled("VAZIR"))
        assertEquals(PersianFont.PLAYFAIR_DISPLAY, FontFallback.matchBundled("PlayfairDisplay"))
        assertEquals(PersianFont.PLAYFAIR_DISPLAY, FontFallback.matchBundled("Playfair Display"))
        assertEquals(PersianFont.INTER, FontFallback.matchBundled("Inter"))
        assertNull(FontFallback.matchBundled("Orbitron"))
        assertNull(FontFallback.matchBundled("JetBrainsMono"))
        assertNull(FontFallback.matchBundled("IRANSansMono"))
        assertNull(FontFallback.matchBundled("Caveat"))
        assertNull(FontFallback.matchBundled("Archivo"))
        assertNull(FontFallback.matchBundled(""))
        assertNull(FontFallback.matchBundled("   "))
    }

    @Test
    fun `familyFor never returns default`() {
        // Persian miss → Vazirmatn (never system).
        assertEquals(PersianFont.VAZIR, FontFallback.familyFor("Orbitron", true))
        assertEquals(PersianFont.VAZIR, FontFallback.familyFor("", true))
        assertEquals(PersianFont.VAZIR, FontFallback.familyFor("???", true))
        // Latin miss → bundled Roboto (deterministic sans).
        assertEquals(PersianFont.ROBOTO, FontFallback.familyFor("Orbitron", false))
        assertEquals(PersianFont.ROBOTO, FontFallback.familyFor("", false))
        // Hits pass through untouched.
        assertEquals(PersianFont.LALEZAR, FontFallback.familyFor("Lalezar", true))
        assertEquals(PersianFont.POPPINS, FontFallback.familyFor("Poppins", false))
    }

    @Test
    fun `templateFamily prefers fa then en then vazirmatn`() {
        assertEquals(
            PersianFont.VAZIR,
            FontFallback.templateFamily("Vazirmatn", "Inter")
        )
        assertEquals(
            PersianFont.POPPINS,
            FontFallback.templateFamily("Missing", "Poppins")
        )
        assertEquals(
            PersianFont.VAZIR,
            FontFallback.templateFamily("IRANSansMono", "JetBrainsMono")
        )
        assertEquals(PersianFont.VAZIR, FontFallback.templateFamily("", ""))
    }

    @Test
    fun `weightValue covers 400 to 900 with safe default`() {
        assertEquals(400, FontFallback.weightValue("regular"))
        assertEquals(500, FontFallback.weightValue("medium"))
        assertEquals(600, FontFallback.weightValue("semibold"))
        assertEquals(700, FontFallback.weightValue("bold"))
        assertEquals(800, FontFallback.weightValue("extrabold"))
        assertEquals(900, FontFallback.weightValue("black"))
        assertEquals(900, FontFallback.weightValue("BLACK"))
        assertEquals(400, FontFallback.weightValue("weird"))
        assertEquals(400, FontFallback.weightValue(""))
    }

    @Test
    fun `weightResName picks real bundled files with base fallback`() {
        assertEquals("vazirmatn", FontFallback.weightResName(PersianFont.VAZIR, 400))
        assertEquals("vazirmatn_medium", FontFallback.weightResName(PersianFont.VAZIR, 500))
        assertEquals("vazirmatn_bold", FontFallback.weightResName(PersianFont.VAZIR, 700))
        assertEquals("vazirmatn_black", FontFallback.weightResName(PersianFont.VAZIR, 900))
        assertEquals("vazirmatn_light", FontFallback.weightResName(PersianFont.VAZIR, 100))
        assertEquals("sahel", FontFallback.weightResName(PersianFont.SAHEL, 500))
        assertEquals("sahel_black", FontFallback.weightResName(PersianFont.SAHEL, 900))
        assertEquals("samim", FontFallback.weightResName(PersianFont.SAMIM, 400))
        assertEquals("samim_bold", FontFallback.weightResName(PersianFont.SAMIM, 800))
        assertEquals("parastoo", FontFallback.weightResName(PersianFont.PARASTOO, 400))
        assertEquals("parastoo_bold", FontFallback.weightResName(PersianFont.PARASTOO, 700))
        assertEquals("estedad_medium", FontFallback.weightResName(PersianFont.ESTEDAD, 500))
        // Singles always return their base file (fake-bold downstream).
        assertEquals("gandom", FontFallback.weightResName(PersianFont.GANDOM, 900))
        assertEquals("lalezar", FontFallback.weightResName(PersianFont.LALEZAR, 400))
        assertEquals("roboto", FontFallback.weightResName(PersianFont.ROBOTO, 700))
        assertEquals("vazir_code", FontFallback.weightResName(PersianFont.VAZIR_CODE, 700))
    }

    @Test
    fun `every weightResName result is a plausible res name`() {
        for (font in PersianFont.entries) {
            if (font == PersianFont.DEFAULT) continue
            for (w in listOf(100, 400, 500, 600, 700, 800, 900)) {
                val name = FontFallback.weightResName(font, w)
                assertTrue("$font@$w -> '$name'", name.isNotBlank())
                assertFalse("$font@$w must not be a family xml", name.endsWith("_family"))
            }
        }
    }
}
