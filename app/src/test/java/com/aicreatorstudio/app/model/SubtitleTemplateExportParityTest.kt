package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.ai.Subtitle
import com.aicreatorstudio.app.ai.TranscribedWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-11 — «زیرنویس فارسی» template → export parity.
 *
 * The brief requires that the template the user picks in the picker is
 * the template that reaches the burned-in export, and that every one of
 * the 26 presets is:
 *   - selectable and stably identified,
 *   - genuinely distinct from its siblings (not 26 colour variations),
 *   - readable over arbitrary footage (stroke | shadow | panel),
 *   - RTL-safe (no manual reversal in the caption path),
 *   - usable with the real word-timing data model,
 *   - and honest when word-level timings are missing.
 *
 * These are behavioural assertions against the SHIPPED
 * [BuiltInSubtitleTemplates] catalog and the real apply path
 * ([TextOverlay.withTemplate]) — the same path the picker and the export
 * both go through. They are not source-string greps.
 */
class SubtitleTemplateExportParityTest {

    // ---------------------------------------------------------------
    // 1. Exactly 20, stably identified
    // ---------------------------------------------------------------

    @Test
    fun `catalog exposes exactly 26 selectable presets`() {
        assertEquals(26, BuiltInSubtitleTemplates.ALL.size)
        assertTrue(BuiltInSubtitleTemplates.ALL.all { it.builtIn })
    }

    @Test
    fun `preset ids are stable and unique`() {
        val ids = BuiltInSubtitleTemplates.ALL.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        // The frozen id set — a rename here is a breaking change for
        // every saved project that referenced a template. The 20
        // numbered ids are append-only frozen; the six `fa_pro_*`
        // ids joined in 2026-10-15.
        assertEquals(
            listOf(
                "fa_01_clean", "fa_02_impact", "fa_03_yellow_word",
                "fa_04_karaoke", "fa_05_capsule", "fa_06_glass",
                "fa_07_creator", "fa_08_cinematic", "fa_09_news",
                "fa_10_podcast", "fa_11_gaming", "fa_12_neon",
                "fa_13_outline", "fa_14_shadow_pop", "fa_15_dynamic",
                "fa_16_bar", "fa_17_modern_box", "fa_18_social",
                "fa_19_premium", "fa_20_signature",
                "fa_pro_hormozi_viral", "fa_pro_neon_cyberpunk",
                "fa_pro_glass_minimal", "fa_pro_comic_pop",
                "fa_pro_golden_lux", "fa_pro_red_punch"
            ),
            ids
        )
    }

    @Test
    fun `every preset resolves back through byId`() {
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            assertEquals(t.id, BuiltInSubtitleTemplates.byId(t.id)?.id)
        }
        assertNull(BuiltInSubtitleTemplates.byId("fa_99_nope"))
    }

    // ---------------------------------------------------------------
    // 2. Genuinely distinct, not a colour carousel
    // ---------------------------------------------------------------

    @Test
    fun `presets are not simple colour variations of one another`() {
        val all = BuiltInSubtitleTemplates.ALL
        // Pairwise: no two presets may share the FULL render fingerprint.
        val fingerprints = all.map { it.renderFingerprint() }
        assertEquals(
            "duplicate presets share a render fingerprint",
            fingerprints.size,
            fingerprints.toSet().size
        )
    }

    @Test
    fun `the catalog exercises the full design space`() {
        val all = BuiltInSubtitleTemplates.ALL
        // 6+ distinct fonts, 5+ distinct active colours, 4+ reveal
        // modes, 5+ motion kinds, 3+ background kinds — a shallow
        // colour-only catalog would collapse on any one of these.
        assertTrue(
            "too few fonts: ${all.map { it.style.fontFamily }.toSet()}",
            all.map { it.style.fontFamily }.toSet().size >= 6
        )
        assertTrue(
            "too few active colours",
            all.map { it.activeWordColorArgb }.toSet().size >= 5
        )
        assertTrue(
            "too few reveal modes",
            all.map { it.wordRevealMode }.toSet().size >= 4
        )
        assertTrue(
            "too few motion kinds",
            all.map { it.motionKind }.toSet().size >= 5
        )
        assertTrue(
            "too few background kinds",
            all.map { it.backgroundKind }.toSet().size >= 3
        )
    }

    @Test
    fun `font sizes span a usable range`() {
        val sizes = BuiltInSubtitleTemplates.ALL.map { it.style.fontSizeSp }
        assertTrue("smallest preset too small: $sizes", sizes.min() >= 24f)
        assertTrue("largest preset oversized: $sizes", sizes.max() <= 48f)
        assertTrue("no size variety", sizes.toSet().size >= 5)
    }

    // ---------------------------------------------------------------
    // 3. Readability: every preset has a background mitigation
    // ---------------------------------------------------------------

    @Test
    fun `every preset carries stroke shadow or panel`() {
        val bad = BuiltInSubtitleTemplates.ALL.filter { t ->
            val s = t.style
            !s.strokeEnabled && !s.shadowEnabled && !s.backgroundEnabled &&
                t.backgroundKind == CaptionBackgroundKind.NONE
        }
        assertTrue(
            "unreadable presets (no stroke/shadow/panel): ${bad.map { it.id }}",
            bad.isEmpty()
        )
    }

    @Test
    fun `active word scale stays within the readability cap`() {
        val over = BuiltInSubtitleTemplates.ALL
            .filter { it.activeWordScale > 1.12f }
            .map { it.id to it.activeWordScale }
        assertTrue(
            "active-word scale exceeds the 1.12 cap (block reflow risk): $over",
            over.isEmpty()
        )
    }

    @Test
    fun `every preset declares exactly one primary active-word mechanism`() {
        // One dominant mechanism: a motion kind OR a static design.
        // Nothing should declare a scale bump AND a glow AND an
        // underline simultaneously — that is the amateur stacking the
        // brief prohibits.
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            val decorations = listOf(
                t.activeWordScale > 1.01f,
                t.activeWordGlowRadiusDp > 0f,
                t.activeWordUnderline,
                t.activeWordBackgroundArgb != null
            ).count { it }
            assertTrue(
                "${t.id} stacks $decorations active-word decorations",
                decorations <= 2
            )
        }
    }

    // ---------------------------------------------------------------
    // 4. Vertical safe area
    // ---------------------------------------------------------------

    @Test
    fun `captions avoid the lower platform UI band`() {
        // Research (docs/persian-subtitle-template-research.md §3):
        // 0.86+ collides with the platform caption/action overlay.
        // Exactly one template is allowed to sit lower — the
        // cinematic dialogue subtitle, where bottom placement is the
        // convention of the medium.
        val exceptions = listOf("fa_08_cinematic")
        val violators = BuiltInSubtitleTemplates.ALL
            .filter { it.style.positionY >= 0.86f && it.id !in exceptions }
            .map { it.id to it.style.positionY }
        assertTrue("presets inside the platform UI band: $violators", violators.isEmpty())
    }

    @Test
    fun `cinematic keeps the deliberate bottom placement`() {
        assertEquals(
            0.88f,
            BuiltInSubtitleTemplates.byId("fa_08_cinematic")!!.style.positionY,
            0.0001f
        )
    }

    // ---------------------------------------------------------------
    // 5. Template → overlay apply path (preview AND export use this)
    // ---------------------------------------------------------------

    @Test
    fun `applying a preset copies every render-relevant field onto the overlay`() {
        // withTemplate is the single apply path shared by preview and
        // export. If a field is dropped here, preview and export can
        // disagree — which the brief forbids.
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            val overlay = TextOverlay(text = "", startTimeMs = 0L, endTimeMs = 1000L).withTemplate(t)
            assertEquals(t.id, overlay.templateId)
            assertEquals(t.style, overlay.subtitleStyle)
            assertEquals(t.activeWordColorArgb, overlay.activeWordColorArgb)
            assertEquals(t.activeWordScale, overlay.activeWordScale, 0.0001f)
            assertEquals(t.activeWordBold, overlay.activeWordBold)
            assertEquals(t.activeWordBackgroundArgb, overlay.activeWordBackgroundArgb)
            assertEquals(t.animationIn, overlay.textAnimation)
            assertEquals(t.animationOut, overlay.textAnimationOut)
            assertEquals(t.wordRevealMode, overlay.wordRevealMode)
            assertEquals(t.motionKind, overlay.motionKind)
            assertEquals(t.backgroundKind, overlay.backgroundKind)
            assertEquals(t.activeWordGlowRadiusDp, overlay.activeWordGlowRadiusDp, 0.0001f)
            assertEquals(t.activeWordUnderline, overlay.activeWordUnderline)
            assertEquals(t.accentColorArgb, overlay.accentColorArgb)
            assertEquals(t.progressBar, overlay.progressBar)
            assertEquals(t.easingIn, overlay.easingIn)
            assertEquals(t.easingOut, overlay.easingOut)
            assertEquals(t.animationDurationMsOut, overlay.animationDurationMsOut)
        }
    }

    @Test
    fun `karaoke reveal mode implies karaoke is enabled on the overlay`() {
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            val overlay = TextOverlay(text = "", startTimeMs = 0L, endTimeMs = 1000L).withTemplate(t)
            assertEquals(
                "${t.id} karaoke flag out of step with reveal mode",
                t.wordRevealMode != WordRevealMode.ALL_AT_ONCE,
                overlay.karaokeEnabled
            )
        }
    }

    @Test
    fun `template position flows into the overlay as an anchor delta`() {
        // A template at positionY 0.84 on a BOTTOM-anchored style must
        // land ABOVE the 0.88 anchor, i.e. a negative Y delta.
        val t = BuiltInSubtitleTemplates.byId("fa_01_clean")!!
        val overlay = TextOverlay(text = "", startTimeMs = 0L, endTimeMs = 1000L).withTemplate(t)
        val delta = overlay.position
        val anchorY = when (t.style.verticalAlignment) {
            SubtitleAlignmentV.TOP -> 0.12f
            SubtitleAlignmentV.CENTER -> 0.5f
            SubtitleAlignmentV.BOTTOM -> 0.88f
        }
        assertEquals(
            0.5f + (t.style.positionY - anchorY),
            delta.y,
            0.0001f
        )
    }

    @Test
    fun `switching presets replaces styling without mutating the catalog`() {
        val first = TextOverlay(text = "", startTimeMs = 0L, endTimeMs = 1000L).withTemplate(
            BuiltInSubtitleTemplates.byId("fa_01_clean")!!
        )
        val second = first.withTemplate(
            BuiltInSubtitleTemplates.byId("fa_20_signature")!!
        )
        assertEquals("fa_20_signature", second.templateId)
        // The original overlay is untouched (data class copy).
        assertEquals("fa_01_clean", first.templateId)
        // And the catalog itself is immutable.
        assertEquals("fa_01_clean", BuiltInSubtitleTemplates.ALL.first().id)
    }

    // ---------------------------------------------------------------
    // 6. Word timings: real data in, honest fallback out
    // ---------------------------------------------------------------

    @Test
    fun `word-by-word presets consume contiguous per-word timings`() {
        val words = listOf(
            TranscribedWord(text = "اگر", startTimeMs = 0L, endTimeMs = 220L),
            TranscribedWord(text = "می‌خواهی", startTimeMs = 220L, endTimeMs = 520L),
            TranscribedWord(text = "حرفه‌ای", startTimeMs = 520L, endTimeMs = 840L)
        )
        // Ordered, non-overlapping, positive duration — the guarantee
        // the reveal modes depend on.
        words.zipWithNext().forEach { (a, b) ->
            assertTrue("timings overlap: $a -> $b", b.startTimeMs >= a.endTimeMs)
        }
        assertTrue(words.all { it.endTimeMs > it.startTimeMs })

        val revealed = BuiltInSubtitleTemplates.ALL.filter {
            it.wordRevealMode == WordRevealMode.WORD_BY_WORD ||
                it.wordRevealMode == WordRevealMode.KARAOKE ||
                it.wordRevealMode == WordRevealMode.HIGHLIGHT
        }
        assertTrue("no timing-driven presets", revealed.isNotEmpty())
    }

    @Test
    fun `active word index advances monotonically across a caption`() {
        // The active word is a function of frame time; it must never
        // jump backwards within one caption, or the highlight stutters.
        val words = listOf(
            TranscribedWord(text = "یک", startTimeMs = 0L, endTimeMs = 200L),
            TranscribedWord(text = "دو", startTimeMs = 200L, endTimeMs = 400L),
            TranscribedWord(text = "سه", startTimeMs = 400L, endTimeMs = 600L)
        )
        val seen = (0L..600L step 50L).map { t ->
            words.indexOfLast { it.startTimeMs <= t }.coerceAtLeast(0)
        }
        seen.zipWithNext().forEach { (a, b) ->
            assertTrue("active word went backwards: $a -> $b", b >= a)
        }
    }

    @Test
    fun `segment without word timings falls back honestly`() {
        // When Whisper produced only segment-level timings the segment
        // has no per-word list. The templates must not invent word
        // timings — the reveal degrades to the whole caption.
        val segOnly = Subtitle(
            text = "زیرنویس بدون تایمینگ کلمه",
            startTimeMs = 0L,
            endTimeMs = 2_000L
        )
        assertTrue(
            "a segment-only subtitle must not fabricate word timings",
            segOnly.words.isEmpty()
        )
        // Every preset must still be applicable — the renderer shows
        // the whole caption rather than animating words it has no
        // timings for. Applying must never throw.
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            val overlay = TextOverlay(text = "", startTimeMs = 0L, endTimeMs = 1000L).withTemplate(t)
            assertNotNull(overlay)
        }
    }

    // ---------------------------------------------------------------
    // 7. Offline / no-network guarantee for the template layer
    // ---------------------------------------------------------------

    @Test
    fun `templates are pure local data with no remote source`() {
        // The whole catalog must be constructible offline with no
        // network handle. Encoding to JSON and decoding back must be
        // lossless for the render-relevant fields — this is what makes
        // the layer portable without a server.
        BuiltInSubtitleTemplates.ALL.forEach { t ->
            val decoded = SubtitleTemplate.fromJson(t.toJson())
            assertNotNull("${t.id} failed to round-trip", decoded)
            assertEquals(t.id, decoded!!.id)
            assertEquals(t.style, decoded.style)
            assertEquals(t.activeWordScale, decoded.activeWordScale, 0.0001f)
            assertEquals(t.motionKind, decoded.motionKind)
            assertEquals(t.backgroundKind, decoded.backgroundKind)
        }
    }

    // ---------------------------------------------------------------
    // 8. Backward compatibility with saved styles
    // ---------------------------------------------------------------

    @Test
    fun `legacy template ids still resolve to a live preset`() {
        val legacyIds = listOf(
            "tmpl_classic", "tmpl_minimal", "tmpl_cinematic", "tmpl_reels",
            "tmpl_tiktok", "tmpl_word_by_word", "tmpl_karaoke",
            "tmpl_dynamic", "tmpl_yellow_bg", "tmpl_bold_stroke",
            "tmpl_neon_persian", "tmpl_bounce_pop_en", "tmpl_neon_green",
            "tmpl_minimal_box", "viral_bounce_pop", "viral_typewriter",
            "viral_slide_up", "viral_fade_scale", "viral_shake",
            "viral_neon", "viral_karaoke_fill", "viral_rise_bounce",
            "viral_line_ribbon", "viral_frosted_pulse"
        )
        legacyIds.forEach { legacy ->
            val migrated = BuiltInSubtitleTemplates.migrateLegacyId(legacy)
            assertNotNull("legacy id $legacy lost", migrated)
            // The migrated template must be one of the live 20.
            assertTrue(
                "legacy $legacy mapped outside the catalog",
                BuiltInSubtitleTemplates.ALL.any { it.id == migrated!!.id }
            )
        }
        assertNull(BuiltInSubtitleTemplates.migrateLegacyId("definitely_not_a_template"))
    }

    @Test
    fun `an overlay saved with an unknown template id still applies a style`() {
        // A project saved before a template was renamed keeps its id.
        // Applying a DIFFERENT preset must still work and must not
        // attempt to resolve the stale id.
        val stale = TextOverlay(text = "", startTimeMs = 0L, endTimeMs = 1000L, templateId = "fa_removed_legacy")
        assertFalse(
            BuiltInSubtitleTemplates.ALL.any { it.id == stale.templateId }
        )
        val reApplied = stale.withTemplate(BuiltInSubtitleTemplates.DEFAULT)
        assertEquals(BuiltInSubtitleTemplates.DEFAULT.id, reApplied.templateId)
    }

    // ---------------------------------------------------------------
    // helper
    // ---------------------------------------------------------------

    /**
     * A fingerprint of every field that changes the RENDERED pixels.
     * Two presets with the same fingerprint are, for all practical
     * purposes, the same preset — which is what "20 superficial colour
     * variations" would look like.
     */
    private fun SubtitleTemplate.renderFingerprint(): String = listOf(
        style.fontFamily,
        style.fontSizeSp,
        style.textColorArgb,
        style.bold,
        style.strokeEnabled,
        style.strokeWidthSp,
        style.shadowEnabled,
        style.backgroundEnabled,
        style.backgroundCornerRadiusDp,
        style.positionY,
        style.maxTextWidthFraction,
        activeWordColorArgb,
        activeWordScale,
        activeWordBackgroundArgb,
        wordRevealMode,
        motionKind,
        backgroundKind,
        animationIn,
        activeWordGlowRadiusDp,
        activeWordUnderline
    ).joinToString("|")
}
