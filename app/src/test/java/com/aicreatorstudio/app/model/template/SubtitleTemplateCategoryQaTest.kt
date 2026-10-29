package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.ai.SrtImportMapper
import com.aicreatorstudio.app.ai.SrtParser
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.ai.WordMotionEngine
import com.aicreatorstudio.app.export.CaptionRenderer
import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.KaraokeRanges
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import com.aicreatorstudio.app.model.withTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-16 SUBTITLE QA (Phase 3 + Phase 5) — a category-wide
 * validation matrix over the whole shipped catalog.
 *
 * ## What this class is for
 *
 * The existing suites test templates one at a time or one property at
 * a time. This one sweeps **every** template of **every** one of the
 * eight authoring categories through the same set of checks, so a
 * defect that only affects one preset (a colour that vanishes against
 * its own plate, a preset with no legibility aid, a template that no
 * longer round-trips) cannot hide behind a green single-property
 * test.
 *
 * ## Which checks are real here, and which are not
 *
 * Real, deterministic and JVM-verifiable:
 *  - catalog coverage and the taxonomy contract;
 *  - resolved style properties (font, size, safe width, anchors);
 *  - box placement inside a 9:16 frame, computed by the shared
 *    [CaptionRenderer.placeBox] the renderer itself uses;
 *  - WCAG relative-luminance contrast of the declared colours;
 *  - the apply path (`withTemplate`) and its effect on real word
 *    timings;
 *  - JSON save/reload round-trip;
 *  - the word-timing model (`activeWordAt`, `KaraokeRanges`) at
 *    arbitrary playheads, including the نیم‌فاصله case;
 *  - the active-word scale at each input boundary (Phase 5).
 *
 * NOT verifiable here — see `docs/subtitles/qa-report.md` §6:
 *  - glyph shaping, cursive joining and actual line breaking. Those
 *    need `android.graphics` (`StaticLayout` + a real `Canvas`); this
 *    project has no Robolectric, so JVM runs get stubbed graphics.
 *  - anything on a device: no emulator is available.
 *
 * ## Known issues (ratcheted, not hidden)
 *
 * The two allow-lists below are **measured defects reported in
 * `docs/subtitles/qa-report.md` §5**, not exemptions to make the
 * build green. Each is asserted *by id* so a regression cannot widen
 * the list silently, and so a fix has to update this file on purpose.
 */
class SubtitleTemplateCategoryQaTest {

    // ---------------------------------------------------------------
    // Catalog
    // ---------------------------------------------------------------

    /**
     * The 25 JSON presets, resolved exactly the way the app resolves
     * them (`JsonTemplateParser` → `ResolvedTemplate` → mapper).
     */
    private fun jsonTemplates(): List<SubtitleTemplate> {
        val file = File("src/main/assets/subtitle_templates.json")
        return JsonTemplateParser.parse(file.readText()).catalog.templates
            .map { JsonTemplateMapper.toSubtitleTemplate(ResolvedTemplate.resolve(it)) }
    }

    /**
     * The combined catalog the picker actually offers.
     *
     * Six ids (`fa_pro_*`) exist in BOTH sources; the JSON entry wins
     * because that is the order the picker concatenates them in. A
     * duplicate id would silently produce two cards, hence the
     * uniqueness test below.
     */
    private fun catalog(): List<SubtitleTemplate> {
        val json = jsonTemplates()
        val builtIn = BuiltInSubtitleTemplates.ALL
        return json + builtIn.filter { b -> json.none { it.id == b.id } }
    }

    // ---------------------------------------------------------------
    // Contrast / legibility model
    // ---------------------------------------------------------------

    private fun channelLum(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }

    /**
     * WCAG relative luminance of an ARGB colour, alpha composited over
     * an opaque BLACK backdrop. Comparing declared colours this way is
     * the design-intent check; the real plate is `backgroundOpacity`
     * over unknown video, so on-device contrast varies (see
     * `qa-report.md` §5.3).
     */
    private fun luminance(argb: Int): Double {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        val r = (((argb ushr 16) and 0xFF) * a)
        val g = (((argb ushr 8) and 0xFF) * a)
        val b = ((argb and 0xFF) * a)
        return 0.2126 * channelLum(r.toInt().coerceIn(0, 255)) +
            0.7152 * channelLum(g.toInt().coerceIn(0, 255)) +
            0.0722 * channelLum(b.toInt().coerceIn(0, 255))
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * Effective opacity of the caption plate for [t]. Mirrors the
     * renderer: several background kinds force a floor, so the plate
     * is more opaque than `style.backgroundOpacity` suggests.
     */
    private fun effectivePlateOpacity(t: SubtitleTemplate): Float = when (t.backgroundKind) {
        CaptionBackgroundKind.SOLID_ROUNDED,
        CaptionBackgroundKind.BLUR -> t.style.backgroundOpacity

        CaptionBackgroundKind.PER_LINE_HIGHLIGHT -> 0.45f

        CaptionBackgroundKind.FILMSTRIP ->
            t.style.backgroundOpacity.coerceAtLeast(0.62f)

        CaptionBackgroundKind.GRADIENT -> t.style.backgroundOpacity

        CaptionBackgroundKind.LETTERBOX_BARS -> t.style.backgroundOpacity

        CaptionBackgroundKind.NONE ->
            if (t.style.backgroundEnabled) t.style.backgroundOpacity else 0f
    }

    /** Opacity at which we treat the plate as a determinate backdrop. */
    private val OPAQUE_PLATE = 0.85f

    /** Some legibility aid: an outline, a shadow or a real plate. */
    private fun hasLegibilityAid(t: SubtitleTemplate): Boolean =
        t.style.strokeEnabled ||
            t.style.shadowEnabled ||
            effectivePlateOpacity(t) >= 0.35f ||
            t.backgroundKind != CaptionBackgroundKind.NONE

    /**
     * The colour actually behind the active word: the pill when the
     * template draws one, otherwise the plate. Null = video-dependent,
     * which is not measurable here.
     */
    private fun activeWordBacking(t: SubtitleTemplate): Int? =
        t.activeWordBackgroundArgb ?: run {
            if (effectivePlateOpacity(t) >= OPAQUE_PLATE) {
                t.style.backgroundColorArgb
            } else null
        }

    /** Reveals the active word by motion rather than by colour. */
    private fun wordDifferentiated(t: SubtitleTemplate): Boolean =
        t.activeWordColorArgb != t.style.textColorArgb ||
            t.activeWordBackgroundArgb != null ||
            t.activeWordBold ||
            t.activeWordUnderline ||
            t.activeWordGlowRadiusDp > 0f ||
            t.motionKind == WordMotionKind.TYPEWRITER ||
            t.wordRevealMode == WordRevealMode.WORD_BY_WORD

    private fun isWordSynchronised(t: SubtitleTemplate): Boolean =
        t.wordRevealMode != WordRevealMode.ALL_AT_ONCE ||
            t.motionKind != WordMotionKind.NONE

    // ---- reported defects (qa-report.md §5) ----
    //
    // PHASE 5 (2026-10-16) — both allow-lists are now EMPTY: the three
    // reported readability defects were fixed in the catalog. The
    // constants are kept deliberately (not deleted) so the ratchet
    // still exists — any NEW offender fails the suite instead of being
    // absorbed into a widened list. The fixes and their measured
    // contrast are pinned by the tests in section 3b below.

    /**
     * Presets that ship with no outline, no shadow and no plate.
     *
     * Was `{cinema_plain_white, cinema_yellow_script}`; both gained a
     * subtle dark outline in Phase 5.
     */
    private val noLegibilityAid = emptySet<String>()

    /**
     * Presets whose active-word colour is unreadable on its own backing.
     *
     * Was `{bounce_text_pop}` (mint `#00E5A0` on its own white plate =
     * 1.65:1); its active colour is now the deep green `#0B7A4B`
     * (5.39:1), and `cinema_yellow_script` moved its active word onto a
     * dark chip (14.67:1).
     */
    private val weakActiveWordContrast = emptySet<String>()

    // ---------------------------------------------------------------
    // 1. Coverage — all eight categories, pinned counts
    // ---------------------------------------------------------------

    @Test
    fun `the combined catalog is the 25 json presets plus the hand built designs`() {
        val all = catalog()
        assertEquals(25, jsonTemplates().size)
        assertEquals(26, BuiltInSubtitleTemplates.ALL.size)
        // Six fa_pro_* ids are defined in BOTH sources and dedupe.
        assertEquals(45, all.size)
        assertEquals(
            "duplicate template ids would render two identical cards",
            all.size, all.map { it.id }.toSet().size
        )
    }

    @Test
    fun `all eight categories are populated with the pinned template counts`() {
        val grouped = SubtitleTemplateTaxonomy.group(catalog())
        // group() drops empty buckets, so an exact size of 8 IS the
        // assertion that no requested category is empty.
        assertEquals(
            "every one of the eight requested categories must have templates",
            SubtitleTemplateCategory.entries.size, grouped.size
        )
        val expected = mapOf(
            SubtitleTemplateCategory.VIRAL_BOLD to 13,
            SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD to 7,
            SubtitleTemplateCategory.PODCAST_INTERVIEW to 5,
            SubtitleTemplateCategory.MINIMAL_CINEMATIC to 6,
            SubtitleTemplateCategory.EDUCATIONAL to 1,
            SubtitleTemplateCategory.KEYWORD_EMPHASIS to 5,
            SubtitleTemplateCategory.CLEAN_PERSIAN to 4,
            SubtitleTemplateCategory.ROUNDED_OUTLINED to 4
        )
        // Update these counts deliberately when the catalog changes;
        // they are the contract documented in the implementation plan.
        assertEquals(expected, grouped.associate { it.first to it.second.size })
        assertEquals(catalog().size, expected.values.sum())
    }

    @Test
    fun `classification is total and counts every template exactly once`() {
        val all = catalog()
        assertEquals(all.size, SubtitleTemplateTaxonomy.coverage(all).values.sum())
        all.forEach { t ->
            assertTrue(
                "${t.id} classified to a category outside the eight",
                SubtitleTemplateTaxonomy.classify(t) in SubtitleTemplateCategory.entries
            )
        }
    }

    // ---- the two registries, verified separately (Phase 5 / Task 4) ----

    @Test
    fun `the picker served json registry holds its own pinned per-category counts`() {
        // The Subtitle Studio serves ONLY the mapped JSON catalog
        // (EditorScreen: `catalogTemplates = uiState.jsonCatalogTemplates`),
        // not the union. Keeping both count sets pinned keeps the
        // distinction honest — the union below is what the taxonomy
        // sweeps, the 25 here are what a user actually picks from.
        val json = jsonTemplates()
        assertEquals(25, json.size)
        val expected = mapOf(
            SubtitleTemplateCategory.VIRAL_BOLD to 7,
            SubtitleTemplateCategory.KARAOKE_WORD_BY_WORD to 4,
            SubtitleTemplateCategory.PODCAST_INTERVIEW to 3,
            SubtitleTemplateCategory.MINIMAL_CINEMATIC to 4,
            SubtitleTemplateCategory.EDUCATIONAL to 1,
            SubtitleTemplateCategory.KEYWORD_EMPHASIS to 3,
            SubtitleTemplateCategory.CLEAN_PERSIAN to 2,
            SubtitleTemplateCategory.ROUNDED_OUTLINED to 1
        )
        assertEquals(
            expected,
            SubtitleTemplateTaxonomy.group(json).associate { it.first to it.second.size }
        )
        assertEquals(25, expected.values.sum())
    }

    @Test
    fun `the hand built registry adds twenty new ids and duplicates six json ids`() {
        val jsonIds = jsonTemplates().map { it.id }.toSet()
        val builtIn = BuiltInSubtitleTemplates.ALL
        val shared = builtIn.map { it.id }.filter { it in jsonIds }.toSet()
        assertEquals(
            "the fa_pro_* set is defined in BOTH registries and dedupes by id",
            setOf(
                "fa_pro_hormozi_viral", "fa_pro_comic_pop", "fa_pro_red_punch",
                "fa_pro_golden_lux", "fa_pro_neon_cyberpunk", "fa_pro_glass_minimal"
            ),
            shared
        )
        // 26 built-ins = 6 shared + 20 hand-authored-only.
        assertEquals(26, builtIn.size)
        assertEquals(20, builtIn.count { it.id !in jsonIds })
        // 25 JSON + 20 new = the 45 the taxonomy sweeps.
        assertEquals(45, jsonIds.size + builtIn.count { it.id !in jsonIds })
    }

    // ---------------------------------------------------------------
    // 2. Typography and safe area
    // ---------------------------------------------------------------

    @Test
    fun `every template in every category uses a bundled offline persian family`() {
        catalog().forEach { t ->
            val font = PersianFont.from(t.style.fontFamily)
            assertTrue(
                "${t.id} font=${t.style.fontFamily} is not a bundled Persian family — " +
                    "a system fallback shapes Persian broken on some OEMs",
                font in PersianFont.PersianFamilies
            )
        }
    }

    @Test
    fun `every template keeps its text inside the horizontal and vertical safe area`() {
        catalog().forEach { t ->
            val s = t.style
            assertTrue(
                "${t.id} maxTextWidthFraction=${s.maxTextWidthFraction} leaves no side margin",
                s.maxTextWidthFraction in 0.50f..0.95f
            )
            val (ax, ay) = CaptionRenderer.anchorFractions(s)
            assertTrue(
                "${t.id} horizontal anchor $ax is outside the safe band",
                ax in 0.10f..0.90f
            )
            assertTrue(
                "${t.id} vertical anchor $ay is outside the safe band",
                ay in 0.10f..0.92f
            )
        }
    }

    @Test
    fun `every template's caption box stays inside a 9 by 16 frame`() {
        val w = 1080
        val h = 1920
        catalog().forEach { t ->
            val s = t.style
            val fontPx = CaptionRenderer.fontSizePx(s.fontSizeSp, h)
            // Worst case: a three-line caption plus box padding.
            val boxW = CaptionRenderer.maxTextWidthPx(s.maxTextWidthFraction, w).toFloat()
            val boxH = fontPx * 4.2f
            assertTrue("${t.id} box (${boxH}px) is taller than the frame", boxH <= h)
            val (ax, ay) = CaptionRenderer.anchorFractions(s)
            // The renderer's own placement, with no user drag.
            val (left, top) = CaptionRenderer.placeBox(w, h, ax, ay, 0.5f, 0.5f, boxW, boxH)
            assertTrue("${t.id} left=$left escapes the frame", left >= -0.01f)
            assertTrue("${t.id} top=$top escapes the frame", top >= -0.01f)
            assertTrue(
                "${t.id} right=${left + boxW} escapes the frame",
                left + boxW <= w + 0.01f
            )
            assertTrue(
                "${t.id} bottom=${top + boxH} escapes the frame",
                top + boxH <= h + 0.01f
            )
        }
    }

    @Test
    fun `every template's animation duration is inside the model bounds`() {
        catalog().forEach { t ->
            assertTrue(
                "${t.id} animationDurationMs=${t.animationDurationMs}",
                t.animationDurationMs in 60..1500
            )
            t.animationDurationMsOut?.let {
                assertTrue("${t.id} animationDurationMsOut=$it", it in 60..1500)
            }
        }
    }

    // ---------------------------------------------------------------
    // 3. Legibility and contrast
    // ---------------------------------------------------------------

    @Test
    fun `every template has a legibility aid`() {
        val offenders = catalog().filter { !hasLegibilityAid(it) }
            .map { it.id }.toSortedSet()
        assertEquals(
            "A subtitle drawn over arbitrary video with no outline, no shadow and no " +
                "plate can vanish against a bright clip (qa-report.md §5.2). The two " +
                "presets that used to trip this (cinema_plain_white, " +
                "cinema_yellow_script) now carry a subtle dark outline — the " +
                "allow-list is empty on purpose, so a NEW offender fails here.",
            noLegibilityAid.toSortedSet(), offenders
        )
    }

    @Test
    fun `text on an opaque plate meets wcag aa for normal text`() {
        var checked = 0
        catalog().forEach { t ->
            if (effectivePlateOpacity(t) < OPAQUE_PLATE) return@forEach
            checked++
            val ratio = contrastRatio(t.style.textColorArgb, t.style.backgroundColorArgb)
            assertTrue(
                "%s text %08X on plate %08X is %.2f:1, below the 4.5:1 AA floor".format(
                    t.id, t.style.textColorArgb, t.style.backgroundColorArgb, ratio
                ),
                ratio >= 4.5
            )
        }
        // An exact count, not a lower bound: if the catalog grows or a
        // plate's opacity changes, this must be updated on purpose.
        assertEquals(
            "number of templates with an opaque (>=85%) plate changed",
            19,
            checked
        )
    }

    @Test
    fun `the active word is legible on its own backing`() {
        // WCAG AA for LARGE text is 3:1 — these captions render at
        // ~128px on a 1080x1920 canvas and the active word is bold, so
        // 3:1 is the catalogue-wide floor here. The presets fixed in
        // Phase 5 are additionally held to the stricter 4.5:1 target by
        // the dedicated tests in section 3b.
        val offenders = mutableListOf<String>()
        catalog().forEach { t ->
            val backing = activeWordBacking(t) ?: return@forEach
            val ratio = contrastRatio(t.activeWordColorArgb, backing)
            if (ratio < 3.0) {
                offenders += "${t.id} (%.2f:1 on %08X vs %08X)".format(
                    ratio, backing, t.activeWordColorArgb
                )
            }
        }
        assertEquals(
            "The highlighted word must be MORE legible than the text it replaces, " +
                "not less (qa-report.md §5.1). The allow-list is empty by design " +
                "since the Phase 5 fix — a new offender must fail here.",
            emptyList<String>(),
            offenders
        )
        assertTrue(
            "the Phase 5 allow-list is deliberately empty — see the fix notes above",
            weakActiveWordContrast.isEmpty()
        )
    }

    @Test
    fun `every word synchronised template differentiates its active word`() {
        catalog().filter { isWordSynchronised(it) }.forEach { t ->
            assertTrue(
                "${t.id} highlights the spoken word but nothing changes visually " +
                    "(no colour, pill, weight, underline, glow, reveal or motion)",
                wordDifferentiated(t)
            )
        }
    }

    // ---------------------------------------------------------------
    // 3b. Phase 5 — the three readability fixes, each pinned by value
    // ---------------------------------------------------------------

    /** WCAG AA for normal-size text — the target for the fixed presets. */
    private val AA_NORMAL = 4.5

    private fun preset(id: String): SubtitleTemplate =
        catalog().firstOrNull { it.id == id } ?: error("missing preset $id")

    @Test
    fun `bounce_text_pop's active word now reads on its own white plate`() {
        val t = preset("bounce_text_pop")
        // The plate is opaque white, so the active word's REAL backing
        // is the plate itself — this is the pair that measured 1.65:1.
        assertEquals(0xFFFFFFFF.toInt(), t.style.backgroundColorArgb)
        val ratio = contrastRatio(t.activeWordColorArgb, t.style.backgroundColorArgb)
        assertTrue(
            "active %08X on its white plate is %.2f:1, below the %.1f:1 target".format(
                t.activeWordColorArgb, ratio, AA_NORMAL
            ),
            ratio >= AA_NORMAL
        )
        // Identity preserved: the pop is still green, the base text is
        // still the near-black the white plate was chosen for, and the
        // motion is still the bounce the preset is named after.
        assertEquals(0xFF0B7A4B.toInt(), t.activeWordColorArgb)
        assertEquals(0xFF111111.toInt(), t.style.textColorArgb)
        assertEquals(WordMotionKind.BOUNCE_POP, t.motionKind)
    }

    @Test
    fun `cinema_yellow_script now separates its active word from the yellow base`() {
        val t = preset("cinema_yellow_script")
        // Identity preserved: the base is STILL the yellow script, in
        // the script face the preset is named for.
        assertEquals(0xFFFFD400.toInt(), t.style.textColorArgb)
        assertEquals(PersianFont.LALEZAR.name, t.style.fontFamily)
        // The active word now sits on its own dark chip...
        val chip = requireNotNull(t.activeWordBackgroundArgb) {
            "the active word must carry a determinate backing to be measurable"
        }
        assertEquals(0xCC000000.toInt(), chip)
        // ...so yellow-on-chip clears the normal-text target.
        val ratio = contrastRatio(t.activeWordColorArgb, chip)
        assertTrue(
            "active %08X on chip %08X is %.2f:1, below the %.1f:1 target".format(
                t.activeWordColorArgb, chip, ratio, AA_NORMAL
            ),
            ratio >= AA_NORMAL
        )
        // Record WHY the change was made: the old white-on-yellow pair.
        val old = contrastRatio(0xFFFFFFFF.toInt(), 0xFFFFD400.toInt())
        assertTrue("the old white-on-yellow highlight really was ~1.43:1", old < 1.5)
        // Word-by-word behaviour is unchanged: `box` maps to the same
        // engine kind as `colorChange`, so the highlight still tracks
        // the spoken word (verified by JsonTemplateMapperTest too).
        assertEquals(WordMotionKind.HIGHLIGHT_COLOR, t.motionKind)
    }

    @Test
    fun `both previously plain presets now carry a subtle legibility aid`() {
        listOf("cinema_plain_white", "cinema_yellow_script").forEach { id ->
            val t = preset(id)
            assertTrue(
                "$id still draws bare text over arbitrary footage",
                hasLegibilityAid(t)
            )
            assertTrue("$id should take its aid from an outline", t.style.strokeEnabled)
            assertEquals(0xFF000000.toInt(), t.style.strokeColorArgb)
            assertTrue(
                "$id outline width ${t.style.strokeWidthSp} is not the subtle 2sp",
                t.style.strokeWidthSp <= 2f
            )
        }
    }

    @Test
    fun `cinema_plain_white keeps its plain white identity`() {
        val t = preset("cinema_plain_white")
        // The fix adds an outline; it must NOT turn the preset into a
        // boxed one or recolour its text.
        assertEquals(0xFFFFFFFF.toInt(), t.style.textColorArgb)
        assertFalse(
            "a plate was added where the design calls for none",
            t.style.backgroundEnabled
        )
        assertEquals(CaptionBackgroundKind.NONE, t.backgroundKind)
        assertEquals(0xFFFF3B3B.toInt(), t.activeWordColorArgb)
    }

    @Test
    fun `the three fixed presets are no longer on either allow list`() {
        // Guards the ratchet itself: the allow-lists may only shrink, and
        // the ids they used to carry must now pass the measured floors.
        val legacy = listOf("bounce_text_pop", "cinema_plain_white", "cinema_yellow_script")
        legacy.forEach { id ->
            assertFalse("$id re-entered noLegibilityAid", id in noLegibilityAid)
            assertFalse("$id re-entered weakActiveWordContrast", id in weakActiveWordContrast)
        }
    }

    // ---------------------------------------------------------------
    // 4. Applying a template (selection state)
    // ---------------------------------------------------------------

    private val sampleOverlay = TextOverlay(
        text = "سلام دنیا",
        startTimeMs = 1_000L,
        endTimeMs = 4_000L
    )

    @Test
    fun `applying a template copies every template field onto the overlay`() {
        catalog().forEach { t ->
            val applied = sampleOverlay.withTemplate(t)
            assertEquals("${t.id} templateId", t.id, applied.templateId)
            assertEquals("${t.id} style", t.style, applied.subtitleStyle)
            assertEquals("${t.id} activeWordColor", t.activeWordColorArgb, applied.activeWordColorArgb)
            assertEquals("${t.id} activeWordScale", t.activeWordScale, applied.activeWordScale, 0f)
            assertEquals("${t.id} activeWordBold", t.activeWordBold, applied.activeWordBold)
            assertEquals(
                "${t.id} activeWordBackground",
                t.activeWordBackgroundArgb, applied.activeWordBackgroundArgb
            )
            assertEquals("${t.id} motionKind", t.motionKind, applied.motionKind)
            assertEquals("${t.id} backgroundKind", t.backgroundKind, applied.backgroundKind)
            assertEquals("${t.id} wordRevealMode", t.wordRevealMode, applied.wordRevealMode)
            assertEquals("${t.id} animationIn", t.animationIn, applied.textAnimation)
            assertEquals("${t.id} animationOut", t.animationOut, applied.textAnimationOut)
            assertEquals("${t.id} progressBar", t.progressBar, applied.progressBar)
            assertEquals("${t.id} accentColor", t.accentColorArgb, applied.accentColorArgb)
            assertEquals(
                "${t.id} glow", t.activeWordGlowRadiusDp, applied.activeWordGlowRadiusDp, 0f
            )
            assertEquals("${t.id} fontSizeSp", t.style.fontSizeSp, applied.fontSizeSp, 0f)
            assertEquals("${t.id} fontFamily", t.style.fontFamily, applied.fontFamily)
            // The picker's "selected" ring and the karaoke path both
            // read this mirror of the reveal mode.
            assertEquals(
                "${t.id} karaokeEnabled",
                t.wordRevealMode != WordRevealMode.ALL_AT_ONCE, applied.karaokeEnabled
            )
        }
    }

    @Test
    fun `applying a template never invents or rewrites word timings`() {
        val words = listOf(
            TranscribedWord(id = "w1", text = "سلام", startTimeMs = 100L, endTimeMs = 420L),
            TranscribedWord(id = "w2", text = "دنیا", startTimeMs = 700L, endTimeMs = 1_180L)
        )
        val durations = words.map { it.endTimeMs - it.startTimeMs }
        // Deliberately uneven — a template must not equalise them.
        assertEquals(2, durations.toSet().size)

        val overlay = sampleOverlay.copy(
            text = "سلام دنیا", startTimeMs = 100L, endTimeMs = 1_180L, words = words
        )
        catalog().forEach { t ->
            val applied = overlay.withTemplate(t)
            assertEquals("${t.id} changed the word list", words, applied.words)
            assertEquals(
                "${t.id} changed the word windows",
                durations, applied.words.map { it.endTimeMs - it.startTimeMs }
            )
        }
    }

    // ---------------------------------------------------------------
    // 5. Save / reload
    // ---------------------------------------------------------------

    @Test
    fun `every template survives a json save and reload`() {
        catalog().forEach { t ->
            val reloaded = SubtitleTemplate.fromJson(t.toJson())
            assertNotNull("${t.id} did not decode", reloaded)
            assertEquals("${t.id} lost data across a save/reload", t, reloaded)
        }
    }

    // ---------------------------------------------------------------
    // 6. Seeking / playback position
    // ---------------------------------------------------------------

    @Test
    fun `seek resolves the active word from real timings at any playhead`() {
        // Includes a نیم‌فاصله word so the range lookup is exercised on
        // the exact character that a naive split would break.
        val caption = "می\u200Cخواهم بروم"
        val words = listOf(
            TranscribedWord(id = "w1", text = "می\u200Cخواهم", startTimeMs = 0L, endTimeMs = 620L),
            TranscribedWord(id = "w2", text = "بروم", startTimeMs = 900L, endTimeMs = 1_400L)
        )
        val overlay = TextOverlay(
            text = caption, startTimeMs = 0L, endTimeMs = 1_400L, words = words
        )

        assertEquals("w1", overlay.activeWordAt(0L)!!.id)        // start inclusive
        assertEquals("w1", overlay.activeWordAt(619L)!!.id)
        assertNull(overlay.activeWordAt(620L))                   // end exclusive
        assertNull(overlay.activeWordAt(700L))                   // gap between words
        assertEquals("w2", overlay.activeWordAt(900L)!!.id)
        assertNull(overlay.activeWordAt(1_400L))
        assertNull(overlay.activeWordAt(5_000L))                 // past the end

        // The karaoke span must land on the whole word in LOGICAL
        // order, including the ZWNJ — never a reversed or split range.
        val r1 = KaraokeRanges.activeRange(caption, words.map { it.text }, 0)
        assertNotNull("ZWNJ word not located in the caption", r1)
        assertEquals("می\u200Cخواهم", caption.substring(r1!!.first, r1.last + 1))
        val r2 = KaraokeRanges.activeRange(caption, words.map { it.text }, 1)
        assertNotNull(r2)
        assertEquals("بروم", caption.substring(r2!!.first, r2.last + 1))
    }

    // ---------------------------------------------------------------
    // 7. Phase 5 — the active-word scale at every input boundary
    // ---------------------------------------------------------------

    @Test
    fun `no shipped template exceeds the 1_12 active word scale cap`() {
        val over = catalog().filter { it.activeWordScale > 1.12f + 1e-6f }
        assertTrue(
            "templates above the 1.12 cap: ${over.map { "${it.id}=${it.activeWordScale}" }}",
            over.isEmpty()
        )
        catalog().forEach { t ->
            assertTrue(
                "${t.id} activeWordScale=${t.activeWordScale} is below the model floor",
                t.activeWordScale >= 0.8f
            )
        }
    }

    @Test
    fun `bounce pop exceeds the template cap because the engine adds the bounce on top`() {
        // Phase 5 finding: the 1.12 cap bounds the TEMPLATE value at the
        // JSON boundary only. WordMotionEngine adds its overshoot on
        // top, and CaptionRenderer clamps at 2.5 — so the scale that
        // actually gets drawn is not bounded by 1.12.
        val words = listOf(
            TranscribedWord(id = "w", text = "سلام", startTimeMs = 0L, endTimeMs = 600L)
        )
        val overlay = TextOverlay(
            text = "سلام", startTimeMs = 0L, endTimeMs = 600L, words = words,
            motionKind = WordMotionKind.BOUNCE_POP,
            activeWordScale = 1.12f,
            animationDurationMs = 260
        )
        var maxScale = 0f
        var atMs = 0L
        for (ms in 0L..600L) {
            val scale = WordMotionEngine.wordStateAt(overlay, words, 0, ms).scale
            if (scale > maxScale) {
                maxScale = scale
                atMs = ms
            }
        }
        assertTrue("bounce never engaged; the motion path is broken", maxScale > 1.12f)
        assertTrue(
            "bounce overshoot %.3f at %dms is larger than the documented 0.35".format(
                maxScale, atMs
            ),
            maxScale <= 1.12f + 0.35f + 1e-3f
        )
        // The renderer's own clamp is 2.5, NOT 1.12 — recording that
        // here is the point of the test.
        assertTrue(maxScale < 2.5f)
    }

    @Test
    fun `bounce pop is the only motion kind that pushes the active word above the cap`() {
        val cappedMotions = mapOf(
            WordMotionKind.SLIDE_UP_WORD to 1.12f,
            WordMotionKind.FADE_SCALE to 1.12f,
            WordMotionKind.SHAKE_WIGGLE to 1f + 1.12f * 0.1f
        )
        val words = listOf(
            TranscribedWord(id = "w", text = "سلام", startTimeMs = 0L, endTimeMs = 600L)
        )
        cappedMotions.forEach { (kind, ceiling) ->
            val overlay = TextOverlay(
                text = "سلام", startTimeMs = 0L, endTimeMs = 600L, words = words,
                motionKind = kind, activeWordScale = 1.12f, animationDurationMs = 260
            )
            var maxScale = 0f
            for (ms in 0L..600L) {
                maxScale = maxOf(maxScale, WordMotionEngine.wordStateAt(overlay, words, 0, ms).scale)
            }
            assertTrue(
                "$kind reached %.3f, above its documented ceiling %.3f".format(maxScale, ceiling),
                maxScale <= ceiling + 1e-3f
            )
        }
    }

    // ---------------------------------------------------------------
    // 8. Phase 2 — the Persian fixture
    // ---------------------------------------------------------------

    private fun readFixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("srt-fixtures/$name")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("Test fixture not found on classpath: $name")

    private fun fixtureCues() =
        SrtParser.parse(readFixture(FIXTURE), FIXTURE).cues

    @Test
    fun `the persian rtl stress fixture preserves text, line breaks and timings`() {
        val body = readFixture(FIXTURE)
        val cues = fixtureCues()
        assertEquals(5, cues.size)

        // One-line, two-line and three-line cues are all represented.
        assertEquals(
            listOf(1, 2, 3, 2, 1),
            cues.map { cue -> cue.text.count { it == '\n' } + 1 }
        )

        // نیم‌فاصله survives byte-exactly — 7 of them, none adjacent to
        // a space (which would mean it was really a space all along).
        assertEquals(7, body.count { it == '\u200C' })
        assertTrue(cues[0].text.contains('\u200C'))
        body.forEachIndexed { i, c ->
            if (c == '\u200C') {
                assertTrue(
                    "ZWNJ at $i is adjacent to a space",
                    body.getOrNull(i - 1) != ' ' && body.getOrNull(i + 1) != ' '
                )
            }
        }

        // Persian-indic digits, mixed Latin runs and punctuation.
        assertTrue(cues[1].text.contains("۲.۰"))
        assertTrue(cues[2].text.contains("۱.۲.۳"))
        assertTrue(cues[1].text.contains("ChatGPT"))
        assertTrue(cues[3].text.contains("synchronization"))
        assertTrue(cues[2].text.contains("«") && cues[2].text.contains("»"))
        assertTrue(cues[4].text.contains("!"))

        // Persian letter forms, never the Arabic look-alikes.
        assertTrue(cues.any { it.text.contains('\u06CC') })  // ی
        assertTrue(cues.any { it.text.contains('\u06A9') })  // ک
        assertFalse("Arabic yeh leaked in", body.contains('\u064A'))
        assertFalse("Arabic kaf leaked in", body.contains('\u0643'))

        // Timings: ordered, distinct starts, all non-degenerate.
        val starts = cues.map { it.startTimeMs }
        assertEquals(starts.sorted(), starts)
        assertEquals(starts.size, starts.toSet().size)
        cues.forEach {
            assertTrue("cue ${it.index} has a non-positive duration", it.endTimeMs > it.startTimeMs)
        }

        // Every cue is Persian, so every cue must lay out RTL.
        cues.forEach {
            assertTrue("cue ${it.index} was not detected as RTL", CaptionRenderer.isRtl(it.text))
        }

        // The real import path keeps text and timings intact.
        val overlays = SrtImportMapper.toTextOverlays(cues)
        assertEquals(cues.size, overlays.size)
        cues.forEachIndexed { i, cue ->
            assertEquals(cue.text, overlays[i].text)
            assertEquals(cue.startTimeMs, overlays[i].startTimeMs)
            assertEquals(cue.endTimeMs, overlays[i].endTimeMs)
        }
    }

    @Test
    fun `the fixture drives the word timing model without faking equal division`() {
        val cue = fixtureCues()[2]  // the three-line cue
        val tokens = cue.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        assertTrue("fixture cue is unexpectedly short", tokens.size >= 8)

        // Hand-authored, UNEVEN windows (proportional to token length,
        // with a gap between words). This is a deterministic input for
        // the timing model — it is NOT speech recognition output, and
        // nothing here measures transcription accuracy.
        val span = cue.endTimeMs - cue.startTimeMs
        val gap = 25L
        val totalChars = tokens.sumOf { it.length }
        var cursor = cue.startTimeMs
        val words = tokens.mapIndexed { i, token ->
            val dur = ((span - gap * tokens.size) * token.length / totalChars).coerceAtLeast(40L)
            TranscribedWord(
                id = "k$i",
                text = token,
                startTimeMs = cursor,
                endTimeMs = cursor + dur
            ).also { cursor += dur + gap }
        }
        val durations = words.map { it.endTimeMs - it.startTimeMs }
        val equalDivision = span / tokens.size
        assertTrue(
            "word windows are exactly equal division — that would be a faked timing",
            durations.any { it != equalDivision }
        )
        assertTrue("word windows must not all be identical", durations.toSet().size > 1)
        assertTrue(
            "word windows overrun the cue",
            words.last().endTimeMs <= cue.endTimeMs
        )

        val overlay = TextOverlay(
            text = cue.text,
            startTimeMs = cue.startTimeMs,
            endTimeMs = cue.endTimeMs,
            words = words
        )
        words.forEachIndexed { i, word ->
            assertEquals("word $i not active at its own start", word, overlay.activeWordAt(word.startTimeMs))
            assertNull(
                "word $i is still active at its end (must be end-exclusive)",
                overlay.activeWordAt(word.endTimeMs)?.takeIf { it.id == word.id }
            )
            // Any token that actually carries Persian letters must lay
            // out RTL. Tokens that are only digits/punctuation/Latin
            // (e.g. "۱)" or "synchronization") are legitimately not.
            if (word.text.any { c -> c.isLetter() && c.code in 0x0600..0x06FF }) {
                assertTrue(
                    "word ${word.text} has Persian letters but was not detected as RTL",
                    CaptionRenderer.isRtl(word.text)
                )
            }
            // The active word must always be locatable inside the caption.
            assertNotNull(
                "word ${word.text} cannot be located in the caption",
                KaraokeRanges.activeRange(cue.text, words.map { it.text }, i)
            )
        }
    }

    private companion object {
        const val FIXTURE = "K-persian-rtl-stress.srt"
    }
}
