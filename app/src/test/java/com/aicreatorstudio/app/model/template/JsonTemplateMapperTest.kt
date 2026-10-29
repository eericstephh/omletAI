package com.aicreatorstudio.app.model.template

import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.ProgressBarShape
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 4 — JSON catalog → engine model mapping: every axis maps
 * independently, interims degrade visibly (never crash), and the
 * legacy migration resolves old ids to JSON equivalents.
 */
class JsonTemplateMapperTest {

    private fun catalog(): List<JsonTemplate> {
        val file = java.io.File("src/main/assets/subtitle_templates.json")
        return JsonTemplateParser.parse(file.readText()).catalog.templates
    }

    private fun resolved(id: String): ResolvedTemplate {
        val base = catalog().first { it.id == id }
        return ResolvedTemplate.resolve(base)
    }

    private fun mapped(id: String) =
        JsonTemplateMapper.toSubtitleTemplate(resolved(id))

    // ---- full mappings ----

    @Test
    fun `karaoke classic maps end to end`() {
        val t = mapped("karaoke_classic")
        assertEquals("karaoke_classic", t.id)
        assertEquals("کارائوکه کلاسیک", t.name)
        assertEquals("Karaoke Classic", t.nameEn)
        assertEquals("popular", t.category)
        assertTrue(t.builtIn)
        assertEquals(PersianFont.VAZIR.name, t.style.fontFamily)
        assertEquals(28f, t.style.fontSizeSp, 0.001f)
        assertEquals(0xFFFFFFFF.toInt(), t.style.textColorArgb)
        assertTrue(t.style.strokeEnabled)
        assertEquals(3f, t.style.strokeWidthSp, 0.001f)
        assertEquals(WordMotionKind.KARAOKE_FILL, t.motionKind)
        assertEquals(WordRevealMode.KARAOKE, t.wordRevealMode)
        assertEquals(0xFFFFD700.toInt(), t.activeWordColorArgb)
        assertEquals(TextOverlayAnimation.NONE, t.animationIn)
        assertEquals(260, t.animationDurationMs)
        assertEquals(CaptionBackgroundKind.NONE, t.backgroundKind)
        assertFalse(t.style.backgroundEnabled)
    }

    @Test
    fun `glitch cut maps black weight plus glitch motion`() {
        val t = mapped("glitch_cut")
        assertTrue(t.style.bold)
        assertEquals(28f * 1.05f, t.style.fontSizeSp, 0.001f)
        // PHASE 8 — glitchIn now maps to the GLITCH engine.
        assertEquals(WordMotionKind.GLITCH, t.motionKind)
        assertEquals(TextOverlayAnimation.GLITCH_IN, t.animationIn)
        assertEquals(TextOverlayAnimation.GLITCH_OUT, t.animationOut)
        assertEquals(0xFFFF3B3B.toInt(), t.activeWordColorArgb)
        assertTrue(t.style.backgroundEnabled)
        assertEquals(0x80000000.toInt(), t.style.backgroundColorArgb)
        assertEquals(4f, t.style.backgroundCornerRadiusDp, 0.001f)
    }

    @Test
    fun `neon glow maps glow shadow plus accent`() {
        val t = mapped("neon_glow")
        assertEquals(WordMotionKind.NEON_GLOW, t.motionKind)
        assertEquals(0xFF00FFF7.toInt(), t.activeWordColorArgb)
        assertEquals(12f, t.activeWordGlowRadiusDp, 0.001f)
        assertEquals(0xFFFF2ED1.toInt(), t.accentColorArgb)
        assertEquals(0xFFFF2ED1.toInt(), t.style.textColorArgb)
    }

    @Test
    fun `missing mono fonts fall back to vazirmatn`() {
        val t = mapped("typewriter_reveal")
        assertEquals(PersianFont.VAZIR.name, t.style.fontFamily)
        assertEquals(WordMotionKind.TYPEWRITER, t.motionKind)
        assertEquals(WordRevealMode.WORD_BY_WORD, t.wordRevealMode)
        assertEquals(0xFF00FF9C.toInt(), t.style.textColorArgb)
    }

    @Test
    fun `gradient maps first color plus accent end`() {
        val t = mapped("slide_reveal")
        assertEquals(CaptionBackgroundKind.GRADIENT, t.backgroundKind)
        assertEquals(0xFF001A33.toInt(), t.style.backgroundColorArgb)
        assertEquals(0xFF000000.toInt(), t.accentColorArgb)
        assertEquals(WordMotionKind.SLIDE_UP_WORD, t.motionKind)
        assertEquals(TextOverlayAnimation.SLIDE_UP, t.animationIn)
        assertEquals(0xFF4D9BFF.toInt(), t.activeWordColorArgb)
    }

    @Test
    fun `progress bar degrades to visible highlight until phase 6`() {
        val t = mapped("progress_bar_underline")
        assertEquals(WordMotionKind.HIGHLIGHT_COLOR, t.motionKind)
        assertEquals(WordRevealMode.HIGHLIGHT, t.wordRevealMode)
        assertEquals(0xFFFF7A00.toInt(), t.activeWordColorArgb)
        assertEquals(CaptionBackgroundKind.NONE, t.backgroundKind)
    }

    @Test
    fun `letterbox bars map to LETTERBOX_BARS kind`() {
        val t = mapped("cinematic_bar")
        assertEquals(CaptionBackgroundKind.LETTERBOX_BARS, t.backgroundKind)
        // Frame-level bars; the caption box background is off.
        assertFalse(t.style.backgroundEnabled)
        // The text treatment itself still maps.
        assertEquals(0xFFF5F5F5.toInt(), t.style.textColorArgb)
        assertTrue(t.style.strokeEnabled)
    }

    @Test
    fun `keyword punch scale boost is clamped to the readability cap`() {
        val t = mapped("keyword_punch_zoom")
        // The template asks for 1.4; the mapper caps it at 1.12 — the
        // cap docs/persian-subtitle-template-research.md sets and
        // SubtitleTemplateExportParityTest enforces for the built-ins.
        assertEquals(1.12f, t.activeWordScale, 0.0001f)
        assertEquals(WordMotionKind.BOUNCE_POP, t.motionKind)
        assertEquals(TextOverlayAnimation.POP, t.animationIn)
        assertEquals(0xFFFF6A00.toInt(), t.activeWordColorArgb)
    }

    @Test
    fun `inverted highlight keeps inactive as base text`() {
        val t = mapped("cinema_yellow_script")
        assertEquals(PersianFont.LALEZAR.name, t.style.fontFamily)
        // `inactiveColor` is the base text — the "inverted" design
        // (yellow base rather than white).
        assertEquals(0xFFFFD400.toInt(), t.style.textColorArgb)
        // 2026-10-16 READABILITY FIX — the active word used to be white
        // `#FFFFFF` on that yellow base: 1.43:1, so the highlight was
        // barely visible (qa-report.md §5.4). It is now the SAME yellow
        // on its own dark chip, which keeps the yellow-script identity
        // and measures 14.67:1.
        assertEquals(0xFFFFD400.toInt(), t.activeWordColorArgb)
        assertEquals(0xCC000000.toInt(), t.activeWordBackgroundArgb)
        // `box` maps to the same engine kind as `colorChange`, so the
        // word-by-word highlight still tracks the spoken word.
        assertEquals(WordMotionKind.HIGHLIGHT_COLOR, t.motionKind)
    }

    @Test
    fun `black pill keeps its extreme corner radius`() {
        val t = mapped("cinema_black_pill")
        assertEquals(CaptionBackgroundKind.SOLID_ROUNDED, t.backgroundKind)
        assertEquals(999f, t.style.backgroundCornerRadiusDp, 0.001f)
    }

    @Test
    fun `blur video maps opacity into the frosted plate`() {
        val t = mapped("fast_zoom_reveal")
        assertEquals(CaptionBackgroundKind.BLUR, t.backgroundKind)
        assertEquals(0.15f, t.style.backgroundOpacity, 0.0001f)
    }

    // ---- 2026-09-24 Wave / Filmstrip (the two new engine axes) ----

    @Test
    fun `wave accent reuses the whole bar contract and only swaps the shape`() {
        // The point of this template is that NOTHING about the
        // accent contract changed — same word-timestamp sync, same
        // RTL direction awareness, same placement and height — only
        // the stroked path. If any of these drift, the template is
        // no longer "the progress bar as a wave".
        val t = mapped("wave_accent")
        assertEquals(WordMotionKind.HIGHLIGHT_COLOR, t.motionKind)
        assertEquals(WordRevealMode.HIGHLIGHT, t.wordRevealMode)
        val bar = requireNotNull(t.progressBar) {
            "wave_accent must enable the accent line, else nothing renders"
        }
        assertEquals(ProgressBarShape.WAVE, bar.shape)
        assertEquals(0xFFFFD400.toInt(), bar.colorArgb)
        assertEquals(0.18f, bar.heightRatio, 0.0001f)
        assertTrue("RTL must still grow from the reading edge", bar.directionAware)
    }

    @Test
    fun `existing bar templates keep the STRAIGHT shape`() {
        // The new shape axis must be opt-in: adding it must not
        // silently restyle every template that already shipped a bar.
        val bar = requireNotNull(mapped("progress_bar_underline").progressBar)
        assertEquals(ProgressBarShape.STRAIGHT, bar.shape)
    }

    @Test
    fun `filmstrip frame maps to the FILMSTRIP background kind`() {
        val t = mapped("filmstrip_frame")
        assertEquals(CaptionBackgroundKind.FILMSTRIP, t.backgroundKind)
        // The plate itself, plus the two perforation rows, are drawn
        // from the background fields — so these must survive mapping
        // or the frame renders as a bare rectangle.
        assertTrue("the plate must be enabled", t.style.backgroundEnabled)
        assertEquals(0xFF0A0A0A.toInt(), t.style.backgroundColorArgb)
        assertEquals(10f, t.style.backgroundCornerRadiusDp, 0.001f)
        assertEquals(0.9f, t.style.backgroundOpacity, 0.0001f)
    }

    @Test
    fun `wave and filmstrip accents survive a background-axis override`() {
        // The Background tab rebuilds the kind from a string. Both
        // new axes must be reachable there too, or picking them in
        // the toolbar and then touching the Background tab would
        // drop the user back to a plain box.
        val wave = mapped("wave_accent")
        assertEquals(
            ProgressBarShape.WAVE,
            wave.withHighlightOverride(
                HighlightOverride(type = "waveAccent")
            ).progressBar?.shape
        )
        val film = mapped("filmstrip_frame")
        assertEquals(
            CaptionBackgroundKind.FILMSTRIP,
            film.withBackgroundOverride(BackgroundOverride(type = "filmstrip")).backgroundKind
        )
    }

    // ---- axis units ----

    @Test
    fun `font family resolves fa then en then vazirmatn`() {
        fun family(fa: String, en: String): String {
            val base = JsonTemplate(
                id = "t",
                language = JsonLanguage(fontMapFa = fa, fontMapEn = en)
            )
            return JsonTemplateMapper.toSubtitleTemplate(
                ResolvedTemplate.resolve(base)
            ).style.fontFamily
        }
        assertEquals(PersianFont.VAZIR.name, family("Vazirmatn", "Inter"))
        assertEquals(PersianFont.POPPINS.name, family("Missing", "Poppins"))
        assertEquals(PersianFont.INTER.name, family("", "Inter"))
        assertEquals(PersianFont.VAZIR.name, family("Missing", "AlsoMissing"))
        assertEquals(PersianFont.LALEZAR.name, family("Lalezar", "Caveat"))
    }

    @Test
    fun `weights map to the bold flag`() {
        assertFalse(JsonTemplateMapper.mapBold("regular"))
        assertFalse(JsonTemplateMapper.mapBold("medium"))
        assertFalse(JsonTemplateMapper.mapBold("weird"))
        assertTrue(JsonTemplateMapper.mapBold("semibold"))
        assertTrue(JsonTemplateMapper.mapBold("bold"))
        assertTrue(JsonTemplateMapper.mapBold("extrabold"))
        assertTrue(JsonTemplateMapper.mapBold("black"))
        assertTrue(JsonTemplateMapper.mapBold("BOLD"))
    }

    @Test
    fun `parseColor handles rgb rgba and garbage`() {
        assertEquals(0xFFFFFFFF.toInt(), JsonTemplateMapper.parseColor("#FFFFFF", 0))
        // CSS order (#RRGGBBAA): alpha is LAST.
        assertEquals(0x80000000.toInt(), JsonTemplateMapper.parseColor("#00000080", 0))
        assertEquals(0x33FFFFFF.toInt(), JsonTemplateMapper.parseColor("#FFFFFF33", 0))
        assertEquals(0xCC000000.toInt(), JsonTemplateMapper.parseColor("#000000CC", 0))
        assertEquals(0xFFFFD400.toInt(), JsonTemplateMapper.parseColor("#ffd400", 0))
        assertEquals(42, JsonTemplateMapper.parseColor("zzz", 42))
        assertEquals(42, JsonTemplateMapper.parseColor(null, 42))
        assertEquals(42, JsonTemplateMapper.parseColor("#12345", 42))
    }

    @Test
    fun `durations and scales coerce into engine ranges`() {
        val base = JsonTemplate(
            id = "t",
            motion = JsonMotion(
                entry = JsonMotionPhase(type = "fadeIn", duration = 0.01),
                exit = JsonMotionPhase(type = "fadeOut", duration = 99.0)
            ),
            wordHighlight = JsonWordHighlight(
                type = "colorChange",
                activeColor = "#FF0000",
                keywordScaleBoost = 9f
            )
        )
        val t = JsonTemplateMapper.toSubtitleTemplate(ResolvedTemplate.resolve(base))
        // Entry wins the duration field; both clamp into 60..1500.
        assertEquals(60, t.animationDurationMs)
        // An absurd boost (9f) still respects the 1.12 readability cap.
        assertEquals(1.12f, t.activeWordScale, 0.0001f)
    }

    @Test
    fun `the whole served catalog respects the readability cap`() {
        val mapped = catalog().map {
            JsonTemplateMapper.toSubtitleTemplate(ResolvedTemplate.resolve(it))
        }
        assertTrue("catalog should not be empty", mapped.isNotEmpty())
        mapped.forEach { t ->
            assertTrue(
                "${t.id} active word scale ${t.activeWordScale} exceeds the 1.12 readability cap",
                t.activeWordScale <= 1.12f + 0.0001f
            )
        }
    }

    // ---- grouping + migration ----

    @Test
    fun `groups follow catalog order and skip empties`() {
        val all = catalog().map {
            JsonTemplateMapper.toSubtitleTemplate(ResolvedTemplate.resolve(it))
        }
        val groups = JsonTemplateMapper.groupByCategory(all)
        assertEquals(
            listOf("popular", "dynamic", "fantasy", "standard", "cinema"),
            groups.map { it.first }
        )
        assertEquals(25, groups.sumOf { it.second.size })
        assertTrue(JsonTemplateMapper.groupByCategory(emptyList()).isEmpty())
    }

    @Test
    fun `category labels are persian with raw fallback`() {
        assertEquals("داینامیک", JsonTemplateMapper.categoryFaLabel("dynamic"))
        assertEquals("فانتزی", JsonTemplateMapper.categoryFaLabel("fantasy"))
        assertEquals("محبوب", JsonTemplateMapper.categoryFaLabel("popular"))
        assertEquals("استاندارد", JsonTemplateMapper.categoryFaLabel("standard"))
        assertEquals("سینمایی", JsonTemplateMapper.categoryFaLabel("cinema"))
        assertEquals("weird", JsonTemplateMapper.categoryFaLabel("weird"))
    }

    @Test
    fun `legacy ids migrate to json equivalents`() {
        assertEquals("karaoke_classic", JsonTemplateMapper.migrateToJsonId("fa_04_karaoke"))
        assertEquals("minimal_clean", JsonTemplateMapper.migrateToJsonId("tmpl_classic"))
        assertEquals("neon_glow", JsonTemplateMapper.migrateToJsonId("viral_neon"))
        assertEquals("cinema_black_pill", JsonTemplateMapper.migrateToJsonId("fa_05_capsule"))
        assertNull(JsonTemplateMapper.migrateToJsonId("nope"))
    }

    @Test
    fun `weights flow into the style channel`() {
        fun weightOf(id: String): Int {
            val base = catalog().first { it.id == id }
            return JsonTemplateMapper.toSubtitleTemplate(
                ResolvedTemplate.resolve(base)
            ).style.fontWeightValue
        }
        assertEquals(700, weightOf("karaoke_classic"))
        assertEquals(400, weightOf("typewriter_reveal"))
        assertEquals(900, weightOf("keyword_punch_zoom"))
        assertEquals(500, weightOf("cinema_black_box"))
        assertEquals(600, weightOf("progress_bar_underline"))
    }

    @Test
    fun `progress bar template carries a live bar spec`() {
        val t = mapped("progress_bar_underline")
        val bar = t.progressBar
        org.junit.Assert.assertNotNull(bar)
        assertEquals(0xFFFF7A00.toInt(), bar!!.colorArgb)
        assertEquals(0x33FFFFFF, bar.trackArgb)
        assertEquals(0.12f, bar.heightRatio, 0.0001f)
        assertTrue(bar.directionAware)
    }

    @Test
    fun `non bar templates carry no bar spec`() {
        assertNull(mapped("karaoke_classic").progressBar)
        assertNull(mapped("minimal_clean").progressBar)
    }

    @Test
    fun `highlight override touches only highlight fields`() {
        val base = mapped("karaoke_classic")
        val changed = with(base) {
            withHighlightOverride(HighlightOverride(type = "box", activeColor = "#FF0000"))
        }
        // Highlight axis moved…
        assertEquals(
            com.aicreatorstudio.app.model.WordMotionKind.HIGHLIGHT_COLOR,
            changed.motionKind
        )
        assertEquals(
            com.aicreatorstudio.app.model.WordRevealMode.HIGHLIGHT,
            changed.wordRevealMode
        )
        assertEquals(0xFFFF0000.toInt(), changed.activeWordColorArgb)
        assertEquals(0xFFFF0000.toInt(), changed.activeWordBackgroundArgb)
        assertNull(changed.progressBar)
        // …everything else is byte-identical.
        assertEquals(base.style, changed.style)
        assertEquals(base.activeWordScale, changed.activeWordScale, 0.0001f)
        assertEquals(base.animationIn, changed.animationIn)
        assertEquals(base.animationOut, changed.animationOut)
        assertEquals(base.animationDurationMs, changed.animationDurationMs)
        assertEquals(base.backgroundKind, changed.backgroundKind)
        assertEquals(base.accentColorArgb, changed.accentColorArgb)
        assertEquals(base.id, changed.id)
        assertEquals(base.nameEn, changed.nameEn)
    }

    @Test
    fun `highlight override null is a no-op and none clears motion`() {
        val base = mapped("karaoke_classic")
        assertEquals(base, base.withHighlightOverride(null))
        val cleared = base.withHighlightOverride(HighlightOverride(type = "none"))
        assertEquals(
            com.aicreatorstudio.app.model.WordMotionKind.NONE,
            cleared.motionKind
        )
        assertEquals(
            com.aicreatorstudio.app.model.WordRevealMode.ALL_AT_ONCE,
            cleared.wordRevealMode
        )
        assertNull(cleared.progressBar)
        // Non-highlight fields survive the reset too.
        assertEquals(base.style, cleared.style)
        assertEquals(base.backgroundKind, cleared.backgroundKind)
    }

    @Test
    fun `progressbar override synthesizes a bar but keeps an existing one`() {
        val base = mapped("minimal_clean")
        val added = base.withHighlightOverride(HighlightOverride(type = "progressBar"))
        org.junit.Assert.assertNotNull(added.progressBar)
        assertEquals(0.12f, added.progressBar!!.heightRatio, 0.0001f)
        assertTrue(added.progressBar!!.directionAware)

        val barBase = mapped("progress_bar_underline")
        val kept = barBase.withHighlightOverride(
            HighlightOverride(type = "progressBar", activeColor = "#00FF00")
        )
        // Existing spec object survives; only the active color moves.
        assertEquals(barBase.progressBar, kept.progressBar)
        assertEquals(0xFF00FF00.toInt(), kept.activeWordColorArgb)
    }

    @Test
    fun `mapCatalogState publishes ready catalog or empty fallback`() {
        val file = java.io.File("src/main/assets/subtitle_templates.json")
        val loaded = JsonTemplateParser.parse(file.readText())
        val ready = JsonTemplateMapper.mapCatalogState(
            CatalogState(
                templates = loaded.catalog.templates,
                schemaVersion = loaded.catalog.schemaVersion,
                source = CatalogState.Source.ASSET
            )
        )
        assertEquals(JsonCatalogStatus.READY, ready.status)
        assertEquals(25, ready.templates.size)

        val empty = JsonTemplateMapper.mapCatalogState(CatalogState.Empty)
        assertEquals(JsonCatalogStatus.EMPTY, empty.status)
        assertTrue(empty.templates.isEmpty())
    }

    @Test
    fun `pickTemplate prefers direct hits then migration`() {
        val all = catalog().map {
            JsonTemplateMapper.toSubtitleTemplate(ResolvedTemplate.resolve(it))
        }
        assertEquals(
            "neon_glow",
            JsonTemplateMapper.pickTemplate(all, "neon_glow")!!.id
        )
        assertEquals(
            "karaoke_classic",
            JsonTemplateMapper.pickTemplate(all, "fa_04_karaoke")!!.id
        )
        assertNull(JsonTemplateMapper.pickTemplate(all, null))
        assertNull(JsonTemplateMapper.pickTemplate(all, ""))
        assertNull(JsonTemplateMapper.pickTemplate(all, "nope"))
    }
}
