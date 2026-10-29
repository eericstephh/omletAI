package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-19 — «زیرنویس فارسی» template system tests: the 20
 * numbered Persian templates plus the 2026-10-15 six-pro viral
 * set (26 total), their JSON round trip (including the category
 * field), and word-timing distribution for manual captions.
 */
class SubtitleTemplateTest {

    private val testText =
        "اگر می‌خواهی ویدئوهایت حرفه‌ای‌تر دیده شوند، زیرنویس درست یکی از مهم‌ترین ابزارهاست."

    @Test
    fun `built-in catalog holds exactly 26 templates with unique ids`() {
        assertEquals(26, BuiltInSubtitleTemplates.ALL.size)
        assertEquals(
            BuiltInSubtitleTemplates.ALL.size,
            BuiltInSubtitleTemplates.ALL.map { it.id }.distinct().size
        )
        assertTrue(BuiltInSubtitleTemplates.ALL.all { it.builtIn })
        val names = BuiltInSubtitleTemplates.ALL.map { it.name }
        listOf(
            "تمیز", "ایمپکت", "کلمه زرد", "کارائوکه", "کپسول مشکی",
            "شیشه‌ای", "کریتور", "سینمایی", "خبری", "پادکست",
            "گیمینگ", "نئون", "خط‌دار", "سایه‌برجسته", "داینامیک",
            "نوار هایلایت", "باکس مدرن", "سوشال استک", "پرمیوم مینیمال",
            "سیگنچر",
            // 2026-10-15 — the six-pro viral set.
            "هرموزی وایرال", "نئون سایبرپانک", "شیشه‌ای مینیمال",
            "کمیک پاپ", "طلایی لوکس", "پانچ قرمز"
        ).forEach { expected ->
            assertTrue("$expected missing", names.contains(expected))
        }
    }

    @Test
    fun `every template carries a non-blank category`() {
        val blank = BuiltInSubtitleTemplates.ALL.filter { it.category.isBlank() }
        assertTrue("templates without category: ${blank.map { it.id }}", blank.isEmpty())
    }

    @Test
    fun `template json round trip preserves style extras and category`() {
        val t = BuiltInSubtitleTemplates.byId("fa_07_creator")!!
        val decoded = SubtitleTemplate.fromJson(t.toJson())
        assertNotNull(decoded)
        assertEquals(t.id, decoded!!.id)
        assertEquals(t.name, decoded.name)
        assertEquals(t.category, decoded.category)
        assertEquals("وایرال", decoded.category)
        assertEquals(t.style, decoded.style)
        assertEquals(t.activeWordColorArgb, decoded.activeWordColorArgb)
        assertEquals(t.activeWordScale, decoded.activeWordScale, 0.0001f)
        assertEquals(t.wordRevealMode, decoded.wordRevealMode)
        assertEquals(t.animationIn, decoded.animationIn)
        assertEquals(t.animationOut, decoded.animationOut)
        assertEquals(t.activeWordBackgroundArgb, decoded.activeWordBackgroundArgb)
        assertEquals(t.motionKind, decoded.motionKind)
        assertEquals(t.backgroundKind, decoded.backgroundKind)
    }

    @Test
    fun `legacy json without category decodes to the default group`() {
        // A pre-category blob: category key simply absent.
        val json = org.json.JSONObject()
        json.put("id", "x")
        json.put("name", "قدیمی")
        val decoded = SubtitleTemplate.fromJson(json.toString())
        assertNotNull(decoded)
        assertEquals("عمومی", decoded!!.category)
    }

    @Test
    fun `malformed json decodes to null`() {
        assertEquals(null, SubtitleTemplate.fromJson("not json"))
        assertEquals(null, SubtitleTemplate.fromJson("{}"))
    }

    @Test
    fun `social template targets vertical video`() {
        val social = BuiltInSubtitleTemplates.byId("fa_18_social")!!
        assertTrue("Social must be big", social.style.fontSizeSp >= 30f)
        assertTrue("Social must sit mid-frame", social.style.positionY < 0.75f)
        assertEquals(WordRevealMode.WORD_BY_WORD, social.wordRevealMode)
        assertEquals(TextOverlayAnimation.POP, social.animationIn)
        assertTrue(social.style.strokeEnabled || social.style.shadowEnabled)
    }

    @Test
    fun `karaoke template uses exact word timings`() {
        // KARAOKE/HIGHLIGHT modes rely on per-word timings; the
        // TranscribedWord IS the WordTiming struct.
        val words = testText.split(" ").mapIndexed { i, token ->
            com.aicreatorstudio.app.ai.TranscribedWord(
                text = token,
                startTimeMs = i * 250L,
                endTimeMs = i * 250L + 240L
            )
        }
        // Timing integrity: contiguous, ordered, non-overlapping.
        words.zipWithNext().forEach { (a, b) ->
            assertTrue(b.startTimeMs >= a.endTimeMs)
        }
        val karaoke = BuiltInSubtitleTemplates.byId("fa_04_karaoke")!!
        assertEquals(WordRevealMode.KARAOKE, karaoke.wordRevealMode)
        assertEquals(WordMotionKind.KARAOKE_FILL, karaoke.motionKind)
        assertTrue(words.all { it.endTimeMs > it.startTimeMs })
    }

    @Test
    fun `word highlight templates all declare a motion plus reveal mode`() {
        val ids = listOf(
            "fa_03_yellow_word", "fa_04_karaoke", "fa_07_creator",
            "fa_10_podcast", "fa_12_neon", "fa_15_dynamic",
            "fa_16_bar", "fa_18_social", "fa_20_signature"
        )
        ids.forEach { id ->
            val t = BuiltInSubtitleTemplates.byId(id)
            assertNotNull("$id missing", t)
            assertTrue(
                "$id must animate words (motion=${t!!.motionKind})",
                t.motionKind != WordMotionKind.NONE
            )
            assertTrue(
                "$id must reveal words (mode=${t.wordRevealMode})",
                t.wordRevealMode == WordRevealMode.HIGHLIGHT ||
                    t.wordRevealMode == WordRevealMode.KARAOKE ||
                    t.wordRevealMode == WordRevealMode.WORD_BY_WORD
            )
        }
    }

    @Test
    fun `legacy ids migrate to the 20 catalog instead of vanishing`() {
        assertEquals(
            "fa_04_karaoke",
            BuiltInSubtitleTemplates.migrateLegacyId("viral_karaoke_fill")!!.id
        )
        assertEquals(
            "fa_01_clean",
            BuiltInSubtitleTemplates.migrateLegacyId("tmpl_classic")!!.id
        )
        assertEquals(null, BuiltInSubtitleTemplates.migrateLegacyId("nope"))
        // Migrated ids resolve through byId as well.
        assertNotNull(BuiltInSubtitleTemplates.byId("fa_20_signature"))
    }

    @Test
    fun `mixed persian english text keeps latin tokens intact`() {
        val tokens = "این زیرنویس with ChatGPT ساخته شد".split(" ")
        // The renderer treats every token opaquely; Latin runs must
        // survive split/join round trips untouched.
        assertEquals("این زیرنویس with ChatGPT ساخته شد", tokens.joinToString(" "))
        assertTrue(tokens.contains("ChatGPT"))
        assertTrue(tokens.contains("with"))
    }

    @Test
    fun `rtl text direction is preserved through word list order`() {
        // The preview renders tokens in LOGICAL order; the platform
        // text layout (with RTL direction) handles the visual order.
        val tokens = testText.split(" ")
        assertEquals("اگر", tokens.first())
        assertEquals("ابزارهاست.", tokens.last())
    }

    @Test
    fun `template validation rejects bad values`() {
        try {
            SubtitleTemplate(name = "bad", activeWordScale = 5f)
            fail("expected activeWordScale validation")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            SubtitleTemplate(name = "  ")
            fail("expected name validation")
        } catch (expected: IllegalArgumentException) {
        }
        try {
            SubtitleTemplate(name = "bad", animationDurationMs = 10)
            fail("expected animationDurationMs validation")
        } catch (expected: IllegalArgumentException) {
        }
    }

    private fun fail(message: String): Nothing =
        throw AssertionError(message)

    // ---- PHASE 7: gradientAngleDeg codec round-trip ----

    @Test
    fun `gradientAngleDeg survives JSON round-trip`() {
        val t = BuiltInSubtitleTemplates.DEFAULT.copy(gradientAngleDeg = 135f)
        val json = t.toJson()
        val back = SubtitleTemplate.fromJson(json)
        assertNotNull(back)
        assertEquals(135f, back!!.gradientAngleDeg!!, 0.01f)
    }

    @Test
    fun `null gradientAngleDeg is serialized as null in JSON`() {
        val t = BuiltInSubtitleTemplates.DEFAULT.copy(gradientAngleDeg = null)
        val json = t.toJson()
        val back = SubtitleTemplate.fromJson(json)
        assertNotNull(back)
        assertNull(back!!.gradientAngleDeg)
    }

    @Test
    fun `gradientAngleDeg 0f survives round-trip`() {
        val t = BuiltInSubtitleTemplates.DEFAULT.copy(gradientAngleDeg = 0f)
        val json = t.toJson()
        val back = SubtitleTemplate.fromJson(json)
        assertNotNull(back)
        assertEquals(0f, back!!.gradientAngleDeg!!, 0.01f)
    }

    @Test
    fun `template with progress bar also round-trips gradient angle`() {
        val t = BuiltInSubtitleTemplates.DEFAULT.copy(
            gradientAngleDeg = 270f,
            progressBar = ProgressBarSpec(
                colorArgb = 0xFFFF7A00.toInt(),
                trackArgb = 0x33FFFFFF,
                heightRatio = 0.12f,
                directionAware = true
            )
        )
        val json = t.toJson()
        val back = SubtitleTemplate.fromJson(json)
        assertNotNull(back)
        assertEquals(270f, back!!.gradientAngleDeg!!, 0.01f)
        assertNotNull(back.progressBar)
        assertEquals(0xFFFF7A00.toInt(), back.progressBar!!.colorArgb)
    }
}
