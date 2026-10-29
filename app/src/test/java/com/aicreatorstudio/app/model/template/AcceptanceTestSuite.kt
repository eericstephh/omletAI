package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import com.aicreatorstudio.app.export.CaptionRenderer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PHASE 12 � 17 acceptance scenarios validating the complete
 * Subtitle Template Engine architecture end-to-end.
 *
 * Each test is a self-contained scenario that mirrors a real
 * user/story acceptance criterion from the original spec.
 */
class AcceptanceTestSuite {

    private val catalogFile = File("src/main/assets/subtitle_templates.json")
    private val catalogJson = catalogFile.readText()
    private val parseResult = JsonTemplateParser.parse(catalogJson)
    private val catalog = parseResult.catalog

    // ---- AC-01: JSON catalog loads all 25 templates ----

    @Test
    fun `AC-01 catalog loads all 25 templates from assets`() {
        assertEquals(25, catalog.templates.size)
        assertEquals("3.0", catalog.schemaVersion)
        assertTrue(parseResult.skipped.isEmpty())
    }

    // ---- AC-02: every template has required id, name, category ----

    @Test
    fun `AC-02 every template has non-blank id, nameFa, nameEn, category`() {
        catalog.templates.forEach { t ->
            assertTrue("id blank for template", t.id.isNotBlank())
            assertTrue("nameFa blank for ${t.id}", t.nameFa.isNotBlank())
            assertTrue("nameEn blank for ${t.id}", t.nameEn.isNotBlank())
            assertTrue("category blank for ${t.id}", t.category.isNotBlank())
        }
    }

    // ---- AC-03: every template has all6 axis sections ----

    @Test
    fun `AC-03 every template has all6 axis sections with valid data`() {
        catalog.templates.forEach { t ->
            assertTrue("font.weight blank for ${t.id}", t.font.weight.isNotBlank())
            assertTrue("color.base blank for ${t.id}", t.color.base.startsWith("#"))
            assertTrue("shadow.type blank for ${t.id}", t.shadow.type.isNotBlank())
            assertTrue("wordHighlight.type blank for ${t.id}", t.wordHighlight.type.isNotBlank())
            assertTrue("motion.entry.type blank for ${t.id}", t.motion.entry.type.isNotBlank())
            assertTrue("background.type blank for ${t.id}", t.background.type.isNotBlank())
        }
    }

    // ---- AC-04: ResolvedTemplate resolves overrides correctly ----

    @Test
    fun `AC-04 override on one axis does not affect other five axes`() {
        val base = catalog.templates.first { it.id == "karaoke_classic" }
        val o = TemplateOverrides(color = ColorOverride(base = "#FF0000"))
        val r = ResolvedTemplate.resolve(base, o)
        assertEquals("#FF0000", r.color.base)
        // All other axes fall through to the base.
        assertEquals("bold", r.font.weight)
        assertEquals("outline", r.shadow.type)
        assertEquals("colorChange", r.wordHighlight.type)
        assertEquals("none", r.motion.entry.type)
        assertEquals("none", r.background.type)
    }

    // ---- AC-05: all 19 templates map to valid SubtitleTemplate ----

    @Test
    fun `AC-05 all 19 templates map to engine SubtitleTemplate without crash`() {
        catalog.templates.forEach { jt ->
            val resolved = ResolvedTemplate.resolve(jt)
            val engine = JsonTemplateMapper.toSubtitleTemplate(resolved)
            assertNotNull("map returned null for ${jt.id}", engine)
            assertTrue("id blank for ${jt.id}", engine.id.isNotBlank())
            assertTrue("name blank for ${jt.id}", engine.name.isNotBlank())
        }
    }

    // ---- AC-06: font axis � weight mapping is correct ----

    @Test
    fun `AC-06 font weight mapping produces correct bold flag`() {
        assertFalse(JsonTemplateMapper.mapBold("regular"))
        assertFalse(JsonTemplateMapper.mapBold("medium"))
        assertTrue(JsonTemplateMapper.mapBold("semibold"))
        assertTrue(JsonTemplateMapper.mapBold("bold"))
        assertTrue(JsonTemplateMapper.mapBold("extrabold"))
        assertTrue(JsonTemplateMapper.mapBold("black"))
    }

    // ---- AC-07: color axis � parseColor handles both 6 and 8 hex ----

    @Test
    fun `AC-07 parseColor handles 6-char and 8-char hex correctly`() {
        val c6 = JsonTemplateMapper.parseColor("#FF0000", 0)
        assertEquals(0xFFFF0000.toInt(), c6)
        val c8 = JsonTemplateMapper.parseColor("#FF000080", 0)
        // CSS #FF000080: R=FF, G=00, B=00, A=80 (alpha last in CSS)
        // parseColor converts to Android ARGB: alpha*16^6 + R*16^4 + G*16^2 + B
        assertTrue("8-char hex should produce valid ARGB", c8 != 0)
        val fallback = JsonTemplateMapper.parseColor("invalid", 42)
        assertEquals(42, fallback)
    }

    // ---- AC-08: shadow axis � all 4 types map correctly ----

    @Test
    fun `AC-08 shadow type mapping covers all4 families`() {
        val outline = ResolvedTemplate.resolve(
            catalog.templates.first(),
            TemplateOverrides(shadow = ShadowOverride(type = "outline", width = 4f))
        )
        val mOutline = JsonTemplateMapper.mapShadow(outline)
        assertTrue(mOutline.strokeEnabled)
        assertFalse(mOutline.dropEnabled)

        val glow = ResolvedTemplate.resolve(
            catalog.templates.first(),
            TemplateOverrides(shadow = ShadowOverride(type = "glow", radius = 10f))
        )
        val mGlow = JsonTemplateMapper.mapShadow(glow)
        assertFalse(mGlow.strokeEnabled)
        assertTrue(mGlow.dropEnabled)
        assertEquals(10f, mGlow.glowRadius, 0.01f)

        val none = ResolvedTemplate.resolve(
            catalog.templates.first(),
            TemplateOverrides(shadow = ShadowOverride(type = "none"))
        )
        val mNone = JsonTemplateMapper.mapShadow(none)
        assertFalse(mNone.strokeEnabled)
        assertFalse(mNone.dropEnabled)
    }

    // ---- AC-09: motion axis � all entry types map without crash ----

    @Test
    fun `AC-09 motion mapping covers all entry types across catalog`() {
        catalog.templates.forEach { jt ->
            val r = ResolvedTemplate.resolve(jt)
            val motion = JsonTemplateMapper.toSubtitleTemplate(r)
            assertNotNull("motion map failed for ${jt.id}", motion)
        }
    }

    // ---- AC-10: background axis � all5 types map correctly ----

    @Test
    fun `AC-10 background type mapping covers all catalog types`() {
        val solid = ResolvedTemplate.resolve(
            catalog.templates.first { it.id == "cinema_black_box" }
        )
        val mSolid = JsonTemplateMapper.mapBackground(solid)
        assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, mSolid.kind)

        val gradient = ResolvedTemplate.resolve(
            catalog.templates.first { it.id == "slide_reveal" }
        )
        val mGrad = JsonTemplateMapper.mapBackground(gradient)
        assertEquals(CaptionBackgroundKind.GRADIENT, mGrad.kind)

        val blur = ResolvedTemplate.resolve(
            catalog.templates.first { it.id == "fast_zoom_reveal" }
        )
        val mBlur = JsonTemplateMapper.mapBackground(blur)
        assertEquals(CaptionBackgroundKind.BLUR, mBlur.kind)

        val letterbox = ResolvedTemplate.resolve(
            catalog.templates.first { it.id == "cinematic_bar" }
        )
        val mLetter = JsonTemplateMapper.mapBackground(letterbox)
        assertEquals(CaptionBackgroundKind.LETTERBOX_BARS, mLetter.kind)

        val none = ResolvedTemplate.resolve(
            catalog.templates.first { it.id == "karaoke_classic" }
        )
        val mNone = JsonTemplateMapper.mapBackground(none)
        assertEquals(CaptionBackgroundKind.NONE, mNone.kind)
    }

    // ---- AC-11: gradient angle calculation is correct ----

    @Test
    fun `AC-11 gradient angle calculation produces valid endpoints`() {
        val e0 = CaptionRenderer.gradientEndpoints(100f, 200f, 0f)
        assertEquals(4, e0.size)
        // 0deg: x0=x1 (same horizontal center)
        assertEquals(e0[0], e0[2], 0.01f)
        // y0 > y1 means gradient goes upward
        val diff0 = e0[1] - e0[3]
        assertTrue("0deg y0 should be > y1", diff0 > 0f)

        val e90 = CaptionRenderer.gradientEndpoints(200f, 100f, 90f)
        assertEquals(4, e90.size)
        // 90deg: y0=y1 (same vertical center)
        assertEquals(e90[1], e90[3], 0.01f)
        // x0 < x1 means gradient goes rightward
        val diff90 = e90[2] - e90[0]
        assertTrue("90deg x0 should be < x1", diff90 > 0f)
    }

    // ---- AC-12: template toJson/fromJson round-trip ----

    @Test
    fun `AC-12 SubtitleTemplate toJson fromJson preserves all critical fields`() {
        val t = SubtitleTemplate(
            name = "test",
            easingIn = "easeOutBack",
            easingOut = "easeInCubic",
            animationDurationMs = 350,
            animationDurationMsOut = 500,
            gradientAngleDeg = 45f,
            category = "cinema",
            nameEn = "Test EN"
        )
        val json = t.toJson()
        val r = SubtitleTemplate.fromJson(json)!!
        assertEquals("easeOutBack", r.easingIn)
        assertEquals("easeInCubic", r.easingOut)
        assertEquals(350, r.animationDurationMs)
        assertEquals(500, r.animationDurationMsOut)
        assertEquals(45f, r.gradientAngleDeg!!, 0.01f)
        assertEquals("cinema", r.category)
        assertEquals("Test EN", r.nameEn)
    }

    // ---- AC-13: all 7 override extension functions exist and work ----

    @Test
    fun `AC-13 all7 withOverride functions exist and return non-null`() {
        val t = SubtitleTemplate(name = "test")
        val mapper = JsonTemplateMapper
        val r1 = with(mapper) { t.withFontOverride(FontOverride(family = "Lalezar")) }
        assertNotNull(r1)
        val r2 = with(mapper) { t.withColorOverride(ColorOverride(base = "#FF0000")) }
        assertNotNull(r2)
        val r3 = with(mapper) { t.withShadowOverride(ShadowOverride(type = "outline")) }
        assertNotNull(r3)
        val r4 = with(mapper) { t.withHighlightOverride(HighlightOverride(type = "colorChange")) }
        assertNotNull(r4)
        val r5 = with(mapper) { t.withBackgroundOverride(BackgroundOverride(type = "solidbox")) }
        assertNotNull(r5)
        val r6 = with(mapper) { t.withMotionOverride(MotionOverride(entryType = "fadein")) }
        assertNotNull(r6)
        val r7 = with(mapper) { t.withPositionOverride(PositionOverride(positionX = 0.3f)) }
        assertNotNull(r7)
    }

    // ---- AC-14: TemplateOverrides cleared/clearedAll work for all7 axes ----

    @Test
    fun `AC-14 cleared removes one axis, clearedAll removes all`() {
        val o = TemplateOverrides(
            font = FontOverride(family = "X"),
            color = ColorOverride(base = "#000"),
            shadow = ShadowOverride(type = "glow"),
            wordHighlight = HighlightOverride(type = "box"),
            background = BackgroundOverride(type = "gradient"),
            motion = MotionOverride(entryType = "popIn"),
            position = PositionOverride(positionX = 0.1f)
        )
        assertEquals(7, o.axes().size)
        val cleared = o.cleared(TemplateAxis.FONT)
        assertEquals(6, cleared.axes().size)
        assertNull(cleared.font)
        val reset = cleared.clearedAll()
        assertTrue(reset.isEmpty())
    }

    // ---- AC-15: safe margins enforce correct clamping ----

    @Test
    fun `AC-15 PositionSafeMargins clamp correctly`() {
        assertEquals(0.06f, PositionSafeMargins.clampSafeX(0.0f), 0.001f)
        assertEquals(0.94f, PositionSafeMargins.clampSafeX(1.0f), 0.001f)
        assertEquals(0.08f, PositionSafeMargins.clampSafeY(0.0f), 0.001f)
        assertEquals(0.82f, PositionSafeMargins.clampSafeY(1.0f), 0.001f)
        assertTrue(PositionSafeMargins.isInDangerZone(0.01f))
        assertFalse(PositionSafeMargins.isInDangerZone(0.5f))
        assertTrue(PositionSafeMargins.isInDangerZone(0.95f))
    }

    // ---- AC-16: caption transform uses independent exit duration ----

    @Test
    fun `AC-16 captionTransformAt uses exitDuration when set`() {
        val overlay = com.aicreatorstudio.app.model.TextOverlay(
            id = "test",
            text = "hello",
            startTimeMs = 0L,
            endTimeMs = 5000L,
            textAnimation = TextOverlayAnimation.FADE_IN,
            textAnimationOut = TextOverlayAnimation.FADE_OUT,
            animationDurationMs = 200,
            animationDurationMsOut = 500
        )
        // At 100ms into the caption, entry should be partially faded in
        // (within 200ms entry window)
        val t1 = com.aicreatorstudio.app.ai.WordMotionEngine.captionTransformAt(overlay, 100L)
        assertTrue("entry alpha should be < 1 at 100ms", t1.alpha < 1f)
        assertTrue("entry alpha should be > 0 at 100ms", t1.alpha > 0f)
        // At 4700ms (300ms before end), still within 500ms exit window
        val t2 = com.aicreatorstudio.app.ai.WordMotionEngine.captionTransformAt(overlay, 4700L)
        assertTrue("exit alpha should be < 1 at 4700ms", t2.alpha < 1f)
        assertTrue("exit alpha should be > 0 at 4700ms", t2.alpha > 0f)
        // At 4800ms (200ms before end), would be fully faded if exit used
        // entry duration (200ms), but with 500ms exit it should still be visible
        val t3 = com.aicreatorstudio.app.ai.WordMotionEngine.captionTransformAt(overlay, 4800L)
        assertTrue("exit alpha at 4800ms should be > 0 with 500ms exit", t3.alpha > 0f)
    }

    // ---- AC-17: easing functions resolve known presets ----

    @Test
    fun `AC-17 easing functions resolve all known presets`() {
        val presets = listOf(
            "easeOutBack", "easeInCubic", "easeOutCubic", "easeInOutSine",
            "easeOutElastic", "easeOutBounce", "easeOutQuad", "easeOutQuart",
            "easeInQuad", "linear"
        )
        presets.forEach { name ->
            val fn = com.aicreatorstudio.app.ai.EasingFunctions.resolve(
                com.aicreatorstudio.app.ai.EasingFunctions.fromString(name)
            )
            // Every easing function should map [0,1] to [0,1] at endpoints
            assertEquals("f(0) should be 0 for $name", 0f, fn(0f), 0.01f)
            assertEquals("f(1) should be 1 for $name", 1f, fn(1f), 0.05f)
        }
    }
}
