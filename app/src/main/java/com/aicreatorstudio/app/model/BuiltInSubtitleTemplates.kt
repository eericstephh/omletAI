package com.aicreatorstudio.app.model

import android.graphics.Color as AndroidColor

/**
 * 2026-09-19 — the Persian subtitle template catalog: the
 * **exactly-20** numbered `fa_01…fa_20` designs plus the
 * 2026-10-15 six-pro viral set (`fa_pro_*`) — 26 total.
 *
 * Each entry is a complete, unmistakable design (typography +
 * composition + background treatment + highlight behavior +
 * motion + spacing), built for vertical video (Reels / Shorts /
 * TikTok) with real RTL, correct Persian shaping and full
 * preview/export parity:
 *
 *  - every style pins a bundled Persian font (Vazirmatn & co.),
 *    never DEFAULT — no system-font fallback for Persian;
 *  - word-highlight templates (03/04/07/10/12/15/16/18/20) use
 *    real Whisper word timings via [TranscribedWord]; word-less
 *    captions fall back to sentence level (fake timing is never
 *    synthesized by the AI path);
 *  - positions + `maxTextWidthFraction` keep captions inside the
 *    safe area on 9:16, 16:9 and 1:1 (video coordinates, never
 *    screen pixels);
 *  - no `String.reverse()` anywhere in the pipeline — visual order
 *    comes from bidi + RTL layout only;
 *  - per-character typewriter is deliberately unused: mid-word
 *    truncation breaks cursive joining while animating, against
 *    the readability-first rule.
 *
 * The catalog is served by [com.aicreatorstudio.app.data.subtitle.SubtitleTemplateRepository]
 * together with user-saved templates; [ALL] holds exactly the 26
 * shipped entries (no more, no less).
 */
object BuiltInSubtitleTemplates {

    private fun argb(hex: Long): Int = hex.toInt()

    // ── 01 · CLEAN — minimal, educational ──────────────────────
    val CLEAN: SubtitleTemplate = SubtitleTemplate(
        id = "fa_01_clean",
        name = "تمیز",
        builtIn = true,
        category = "پایه",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR.name,
            fontSizeSp = 26f,
            textColorArgb = AndroidColor.WHITE,
            bold = false,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowColorArgb = AndroidColor.BLACK,
            shadowOpacity = 0.35f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = false,
            // 2026-10-11 SAFE AREA — was 0.86. The research
            // (docs/persian-subtitle-template-research.md §3) shows
            // 0.86+ lands inside the platform's lower UI band where
            // the caption/username overlay sits. Lifted to 0.84.
            positionY = 0.84f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE
    )

    // ── 02 · BOLD IMPACT — large display type for Shorts ───────
    val IMPACT: SubtitleTemplate = SubtitleTemplate(
        id = "fa_02_impact",
        name = "ایمپکت",
        builtIn = true,
        category = "وایرال",
        style = SubtitleStyle(
            fontFamily = PersianFont.LALEZAR.name,
            fontSizeSp = 42f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 4.5f,
            shadowEnabled = true,
            shadowOpacity = 0.5f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false,
            positionY = 0.78f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        animationIn = TextOverlayAnimation.POP,
        animationOut = TextOverlayAnimation.NONE,
        animationDurationMs = 180,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE
    )

    // ── 03 · YELLOW WORD — white base, yellow active word ──────
    val YELLOW_WORD: SubtitleTemplate = SubtitleTemplate(
        id = "fa_03_yellow_word",
        name = "کلمه زرد",
        builtIn = true,
        category = "سوشال",
        style = SubtitleStyle(
            fontFamily = PersianFont.SAMIM.name,
            fontSizeSp = 30f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 3f,
            shadowEnabled = true,
            shadowOpacity = 0.4f,
            shadowOffsetYDp = 1.5f,
            backgroundEnabled = false,
            positionY = 0.84f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        activeWordBold = true,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR
    )

    // ── 04 · KARAOKE — smooth timestamp karaoke ────────────────
    val KARAOKE: SubtitleTemplate = SubtitleTemplate(
        id = "fa_04_karaoke",
        name = "کارائوکه",
        builtIn = true,
        category = "وایرال",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR.name,
            fontSizeSp = 30f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.4f,
            shadowOffsetYDp = 1.5f,
            backgroundEnabled = false,
            positionY = 0.82f
        ),
        activeWordColorArgb = argb(0xFF30D158),
        activeWordBold = true,
        animationIn = TextOverlayAnimation.NONE,
        animationOut = TextOverlayAnimation.NONE,
        wordRevealMode = WordRevealMode.KARAOKE,
        motionKind = WordMotionKind.KARAOKE_FILL,
        accentColorArgb = argb(0xFF30D158)
    )

    // ── 05 · BLACK CAPSULE — translucent pill ──────────────────
    val CAPSULE: SubtitleTemplate = SubtitleTemplate(
        id = "fa_05_capsule",
        name = "کپسول مشکی",
        builtIn = true,
        category = "پایه",
        style = SubtitleStyle(
            fontFamily = PersianFont.SAHEL.name,
            fontSizeSp = 28f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.35f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = true,
            backgroundColorArgb = AndroidColor.BLACK,
            backgroundOpacity = 0.75f,
            backgroundCornerRadiusDp = 18f,
            positionY = 0.84f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE,
        backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED
    )

    // ── 06 · GLASS CARD — frosted dark glass ───────────────────
    val GLASS: SubtitleTemplate = SubtitleTemplate(
        id = "fa_06_glass",
        name = "شیشه‌ای",
        builtIn = true,
        category = "پریمیوم",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR.name,
            fontSizeSp = 27f,
            textColorArgb = AndroidColor.WHITE,
            bold = false,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.35f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xFF14141A),
            backgroundOpacity = 0.55f,
            backgroundCornerRadiusDp = 20f,
            // 2026-10-11 SAFE AREA — was 0.86; the frosted panel is
            // tall, so its bottom edge sat deep inside the platform
            // chrome. Lifted to 0.82.
            positionY = 0.82f
        ),
        activeWordColorArgb = argb(0xFF00E5FF),
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 260,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE,
        backgroundKind = CaptionBackgroundKind.BLUR
    )

    // ── 07 · CREATOR — bold + bounce accent ────────────────────
    val CREATOR: SubtitleTemplate = SubtitleTemplate(
        id = "fa_07_creator",
        name = "کریتور",
        builtIn = true,
        category = "وایرال",
        style = SubtitleStyle(
            fontFamily = PersianFont.ESTEDAD.name,
            fontSizeSp = 34f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 3.5f,
            shadowEnabled = true,
            shadowOpacity = 0.5f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false,
            positionY = 0.75f
        ),
        activeWordColorArgb = argb(0xFF00E5FF),
        // 2026-10-11 READABILITY CAP — was 1.25. The active word sits inside a WORD_BY_WORD
        // reveal, so its scale feeds back into the line's measured width; 1.25 reflowed the
        // block and made the caption visibly jump between words. Capped to the catalog max.
        activeWordScale = 1.12f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.POP,
        animationOut = TextOverlayAnimation.NONE,
        animationDurationMs = 180,
        wordRevealMode = WordRevealMode.WORD_BY_WORD,
        motionKind = WordMotionKind.BOUNCE_POP,
        accentColorArgb = argb(0xFF00E5FF)
    )

    // ── 08 · CINEMATIC — elegant light type, slow fade ─────────
    val CINEMATIC: SubtitleTemplate = SubtitleTemplate(
        id = "fa_08_cinematic",
        name = "سینمایی",
        builtIn = true,
        category = "سینمایی",
        style = SubtitleStyle(
            fontFamily = PersianFont.NAHID.name,
            fontSizeSp = 28f,
            textColorArgb = AndroidColor.WHITE,
            bold = false,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.6f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false,
            // 2026-10-11 SAFE AREA — deliberately KEPT at 0.88 while
            // every other template moved up. A dialogue subtitle
            // pinned to the bottom edge is the established cinematic
            // convention; the research (…research.md §3) records this
            // as the one intentional exception to the safe-area lift.
            positionY = 0.88f
        ),
        activeWordColorArgb = argb(0xFFE8E8E8),
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 420,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE
    )

    // ── 09 · NEWS — RTL-correct lower third ────────────────────
    // Right-docked (the RTL reading origin) compact label with a
    // dark bar — the RTL-correct mirror of an LTR news lower third.
    val NEWS: SubtitleTemplate = SubtitleTemplate(
        id = "fa_09_news",
        name = "خبری",
        builtIn = true,
        category = "خبری",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR_CODE.name,
            fontSizeSp = 26f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.4f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xFF14141A),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 6f,
            horizontalAlignment = SubtitleAlignmentH.RIGHT,
            positionX = 0.85f,
            // 2026-10-11 SAFE AREA — was 0.85. A lower-third news bar
            // with a solid panel is the most likely to collide with
            // platform chrome, so it sits at 0.82.
            positionY = 0.82f,
            maxTextWidthFraction = 0.92f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        activeWordBold = true,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationDurationMs = 160,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR,
        backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED
    )

    // ── 10 · PODCAST — big readable talk backing ───────────────
    val PODCAST: SubtitleTemplate = SubtitleTemplate(
        id = "fa_10_podcast",
        name = "پادکست",
        builtIn = true,
        category = "سوشال",
        style = SubtitleStyle(
            fontFamily = PersianFont.PARASTOO.name,
            fontSizeSp = 32f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.45f,
            shadowOffsetYDp = 1.5f,
            backgroundEnabled = true,
            backgroundColorArgb = AndroidColor.BLACK,
            backgroundOpacity = 0.8f,
            backgroundCornerRadiusDp = 14f,
            positionY = 0.72f,
            maxTextWidthFraction = 0.86f
        ),
        activeWordColorArgb = argb(0xFF00E5FF),
        activeWordScale = 1.1f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR,
        backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED
    )

    // ── 11 · GAMING — controlled energetic accent ──────────────
    val GAMING: SubtitleTemplate = SubtitleTemplate(
        id = "fa_11_gaming",
        name = "گیمینگ",
        builtIn = true,
        category = "گیمینگ",
        style = SubtitleStyle(
            fontFamily = PersianFont.GANDOM.name,
            fontSizeSp = 34f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 4f,
            shadowEnabled = true,
            shadowOpacity = 0.5f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false,
            positionY = 0.78f
        ),
        activeWordColorArgb = argb(0xFFFF9F0A),
        // 2026-10-11 READABILITY CAP — was 1.2 (research §5 caps the catalog at 1.12).
        activeWordScale = 1.12f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.POP,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.FADE_SCALE,
        accentColorArgb = argb(0xFFFF9F0A)
    )

    // ── 12 · NEON — restrained cyan glow ───────────────────────
    val NEON: SubtitleTemplate = SubtitleTemplate(
        id = "fa_12_neon",
        name = "نئون",
        builtIn = true,
        category = "وایرال",
        style = SubtitleStyle(
            fontFamily = PersianFont.SHABNAM.name,
            fontSizeSp = 32f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowColorArgb = argb(0xFF00E5FF),
            shadowOpacity = 0.9f,
            shadowOffsetYDp = 0f,
            backgroundEnabled = false,
            positionY = 0.80f
        ),
        activeWordColorArgb = argb(0xFF00FFC2),
        activeWordBold = true,
        activeWordGlowRadiusDp = 12f,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 260,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.NEON_GLOW,
        accentColorArgb = argb(0xFF00FFC2)
    )

    // ── 13 · OUTLINE — hollow type for bright scenes ───────────
    val OUTLINE: SubtitleTemplate = SubtitleTemplate(
        id = "fa_13_outline",
        name = "خط‌دار",
        builtIn = true,
        category = "پایه",
        style = SubtitleStyle(
            fontFamily = PersianFont.TANHA.name,
            fontSizeSp = 34f,
            textColorArgb = AndroidColor.TRANSPARENT,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.WHITE,
            strokeWidthSp = 3.5f,
            shadowEnabled = true,
            shadowColorArgb = AndroidColor.BLACK,
            shadowOpacity = 0.6f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false,
            positionY = 0.78f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        activeWordBold = true,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE
    )

    // ── 14 · SHADOW POP — hard offset shadow + pop ─────────────
    val SHADOW_POP: SubtitleTemplate = SubtitleTemplate(
        id = "fa_14_shadow_pop",
        name = "سایه‌برجسته",
        builtIn = true,
        category = "وایرال",
        style = SubtitleStyle(
            fontFamily = PersianFont.SAHEL.name,
            fontSizeSp = 38f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowColorArgb = AndroidColor.BLACK,
            shadowOpacity = 0.7f,
            shadowOffsetYDp = 4f,
            backgroundEnabled = false,
            positionY = 0.80f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        animationIn = TextOverlayAnimation.POP,
        animationDurationMs = 180,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE
    )

    // ── 15 · DYNAMIC WORD — slide-up word reveal ───────────────
    val DYNAMIC: SubtitleTemplate = SubtitleTemplate(
        id = "fa_15_dynamic",
        name = "داینامیک",
        builtIn = true,
        category = "وایرال",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR.name,
            fontSizeSp = 32f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 3.5f,
            shadowEnabled = true,
            shadowOpacity = 0.45f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false,
            positionY = 0.78f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        // 2026-10-11 READABILITY CAP — was 1.3, the largest in the catalog and the worst case:
        // WORD_BY_WORD + SLIDE_UP_WORD on every word. Capped to the catalog max.
        activeWordScale = 1.12f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.SLIDE_UP,
        animationDurationMs = 220,
        wordRevealMode = WordRevealMode.WORD_BY_WORD,
        motionKind = WordMotionKind.SLIDE_UP_WORD
    )

    // ── 16 · HIGHLIGHT BAR — travelling word pill ──────────────
    val BAR: SubtitleTemplate = SubtitleTemplate(
        id = "fa_16_bar",
        name = "نوار هایلایت",
        builtIn = true,
        category = "سوشال",
        style = SubtitleStyle(
            fontFamily = PersianFont.PARASTOO.name,
            fontSizeSp = 30f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.35f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = false,
            positionY = 0.84f
        ),
        activeWordColorArgb = AndroidColor.WHITE,
        activeWordBold = true,
        activeWordBackgroundArgb = argb(0xFFFF375F),
        animationIn = TextOverlayAnimation.FADE_IN,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR,
        accentColorArgb = argb(0xFFFF375F)
    )

    // ── 17 · MODERN BOX — editorial dark rectangle ─────────────
    val MODERN_BOX: SubtitleTemplate = SubtitleTemplate(
        id = "fa_17_modern_box",
        name = "باکس مدرن",
        builtIn = true,
        category = "ادیتوریال",
        style = SubtitleStyle(
            fontFamily = PersianFont.SAHEL.name,
            fontSizeSp = 28f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.4f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xFF18181B),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 8f,
            positionY = 0.82f,
            maxTextWidthFraction = 0.88f
        ),
        activeWordColorArgb = argb(0xFFC084FC),
        animationIn = TextOverlayAnimation.FADE_IN,
        animationDurationMs = 160,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE,
        backgroundKind = CaptionBackgroundKind.BLUR
    )

    // ── 18 · SOCIAL STACK — big shorts composition ─────────────
    val SOCIAL: SubtitleTemplate = SubtitleTemplate(
        id = "fa_18_social",
        name = "سوشال استک",
        builtIn = true,
        category = "سوشال",
        style = SubtitleStyle(
            fontFamily = PersianFont.SHABNAM.name,
            fontSizeSp = 38f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 4f,
            shadowEnabled = true,
            shadowOpacity = 0.5f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false,
            positionY = 0.62f,
            maxTextWidthFraction = 0.88f
        ),
        activeWordColorArgb = argb(0xFF30D158),
        // 2026-10-11 READABILITY CAP — was 1.2; the brief prohibits oversized active words.
        activeWordScale = 1.12f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.POP,
        animationDurationMs = 200,
        wordRevealMode = WordRevealMode.WORD_BY_WORD,
        motionKind = WordMotionKind.FADE_SCALE,
        accentColorArgb = argb(0xFF30D158)
    )

    // ── 19 · PREMIUM MINIMAL — quiet luxury ────────────────────
    val PREMIUM: SubtitleTemplate = SubtitleTemplate(
        id = "fa_19_premium",
        name = "پرمیوم مینیمال",
        builtIn = true,
        category = "پریمیوم",
        style = SubtitleStyle(
            fontFamily = PersianFont.TANHA.name,
            fontSizeSp = 30f,
            textColorArgb = AndroidColor.WHITE,
            bold = false,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowColorArgb = AndroidColor.BLACK,
            shadowOpacity = 0.25f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = false,
            positionY = 0.84f,
            maxTextWidthFraction = 0.8f
        ),
        activeWordColorArgb = argb(0xFFFFFFFF),
        animationIn = TextOverlayAnimation.FADE_IN,
        animationDurationMs = 320,
        wordRevealMode = WordRevealMode.ALL_AT_ONCE
    )

    // ── 20 · SIGNATURE — the hero template ─────────────────────
    // Persian-first Vazirmatn, gold karaoke on a dark→gold gradient
    // pill with a soft gold glow: the app's signature look.
    val SIGNATURE: SubtitleTemplate = SubtitleTemplate(
        id = "fa_20_signature",
        name = "سیگنچر",
        builtIn = true,
        category = "شاخص",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR.name,
            fontSizeSp = 34f,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.4f,
            shadowOffsetYDp = 1.5f,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xFF14141A),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 16f,
            positionY = 0.78f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        activeWordBold = true,
        activeWordGlowRadiusDp = 6f,
        animationIn = TextOverlayAnimation.SLIDE_UP,
        animationDurationMs = 280,
        wordRevealMode = WordRevealMode.KARAOKE,
        motionKind = WordMotionKind.KARAOKE_FILL,
        backgroundKind = CaptionBackgroundKind.GRADIENT,
        accentColorArgb = argb(0xFFFFD60A)
    )

    // ── 21 · PRO HORMOZI VIRAL — Alex-Hormozi-style punch ──────
    // White Lalezar display type on a 5sp black stroke, electric
    // yellow active word popping at the catalog cap (1.12x) inside
    // a frosted black pill. The bounce-pop engine rides the word
    // timings.
    val HORMOZI_VIRAL: SubtitleTemplate = SubtitleTemplate(
        id = "fa_pro_hormozi_viral",
        name = "هرموزی وایرال",
        nameEn = "Hormozi Viral",
        builtIn = true,
        category = "وایرال",
        style = SubtitleStyle(
            fontFamily = PersianFont.LALEZAR.name,
            fontSizeSp = 35f,
            fontWeightValue = 700,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 5f,
            shadowEnabled = false,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xD9000000),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 999f,
            positionY = 0.78f
        ),
        activeWordColorArgb = argb(0xFFFFD60A),
        // 2026-10-15 READABILITY CAP — the brief asked for a 1.2x
        // word bounce; the catalog's research cap is 1.12 (the active
        // scale feeds back into the measured line width and reflows
        // the block between words). The pop still reads via BOUNCE_POP.
        activeWordScale = 1.12f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.POP,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 220,
        easingIn = "easeOutBack",
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.BOUNCE_POP,
        backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED,
        accentColorArgb = argb(0xFFFFD60A)
    )

    // ── 22 · PRO NEON CYBERPUNK — cyan body, hot-pink word ─────
    // Cyan Shabnam with a hot-pink active word wrapped in a wide
    // neon halo (18dp glow) over a near-black plate.
    val NEON_CYBERPUNK: SubtitleTemplate = SubtitleTemplate(
        id = "fa_pro_neon_cyberpunk",
        name = "نئون سایبرپانک",
        nameEn = "Neon Cyberpunk",
        builtIn = true,
        category = "فانتزی",
        style = SubtitleStyle(
            fontFamily = PersianFont.SHABNAM.name,
            fontSizeSp = 32f,
            fontWeightValue = 800,
            textColorArgb = argb(0xFF00E5FF),
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowColorArgb = argb(0xFF00E5FF),
            shadowOpacity = 0.9f,
            shadowOffsetYDp = 0f,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xFF0A0014),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 8f,
            positionY = 0.80f
        ),
        activeWordColorArgb = argb(0xFFFF2ED1),
        activeWordBold = true,
        activeWordGlowRadiusDp = 18f,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 280,
        easingIn = "easeOutQuad",
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.NEON_GLOW,
        backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED,
        accentColorArgb = argb(0xFF00E5FF)
    )

    // ── 23 · PRO GLASS MINIMAL — frosted plate, subtle scale ───
    // Medium-weight Vazirmatn on a frosted dark plate (20dp
    // radius); the active word stays white and only scales 1.08x.
    val GLASS_MINIMAL: SubtitleTemplate = SubtitleTemplate(
        id = "fa_pro_glass_minimal",
        name = "شیشه‌ای مینیمال",
        nameEn = "Glass Minimal",
        builtIn = true,
        category = "استاندارد",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR.name,
            fontSizeSp = 30f,
            fontWeightValue = 500,
            textColorArgb = AndroidColor.WHITE,
            bold = false,
            strokeEnabled = false,
            shadowEnabled = false,
            backgroundEnabled = true,
            backgroundColorArgb = AndroidColor.BLACK,
            backgroundOpacity = 0.45f,
            backgroundCornerRadiusDp = 20f,
            positionY = 0.82f
        ),
        activeWordColorArgb = AndroidColor.WHITE,
        activeWordScale = 1.08f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 220,
        easingIn = "easeInOutSine",
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR,
        backgroundKind = CaptionBackgroundKind.BLUR,
        accentColorArgb = AndroidColor.WHITE
    )

    // ── 24 · PRO COMIC POP — thick outline, green word pop ─────
    // Estedad Black on a 6sp black outline (the classic comic
    // ink); the active word pops green at 1.2x. The brief's
    // "tilted active word" has no per-word tilt axis in the
    // engine, so the tilt degrades honestly to the bounce-pop
    // scale (identical in preview and export — never faked).
    val COMIC_POP: SubtitleTemplate = SubtitleTemplate(
        id = "fa_pro_comic_pop",
        name = "کمیک پاپ",
        nameEn = "Comic Pop",
        builtIn = true,
        category = "فانتزی",
        style = SubtitleStyle(
            fontFamily = PersianFont.ESTEDAD.name,
            fontSizeSp = 34f,
            fontWeightValue = 900,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 6f,
            shadowEnabled = false,
            backgroundEnabled = false,
            positionY = 0.78f
        ),
        activeWordColorArgb = argb(0xFF30D158),
        // 2026-10-15 READABILITY CAP — 1.2 → 1.12 (catalog max, see HORMOZI_VIRAL).
        activeWordScale = 1.12f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.POP,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 300,
        easingIn = "easeOutElastic",
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.BOUNCE_POP,
        backgroundKind = CaptionBackgroundKind.NONE,
        accentColorArgb = argb(0xFFFFD60A)
    )

    // ── 25 · PRO GOLDEN LUX — champagne on gold gradient ───────
    // Champagne Vazirmatn over a deep-charcoal → gold gradient
    // pill (45°) with a deep soft shadow and a gold active word.
    val GOLDEN_LUX: SubtitleTemplate = SubtitleTemplate(
        id = "fa_pro_golden_lux",
        name = "طلایی لوکس",
        nameEn = "Golden Lux",
        builtIn = true,
        category = "سینمایی",
        style = SubtitleStyle(
            fontFamily = PersianFont.VAZIR.name,
            fontSizeSp = 30f,
            fontWeightValue = 700,
            textColorArgb = argb(0xFFF7E7CE),
            bold = true,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowColorArgb = AndroidColor.BLACK,
            shadowOpacity = 0.55f,
            shadowOffsetYDp = 3f,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xFF1A1408),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 14f,
            positionY = 0.82f
        ),
        activeWordColorArgb = argb(0xFFFFD700),
        activeWordBold = true,
        animationIn = TextOverlayAnimation.FADE_IN,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 260,
        easingIn = "easeInOutSine",
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR,
        backgroundKind = CaptionBackgroundKind.GRADIENT,
        gradientAngleDeg = 45f,
        accentColorArgb = argb(0xFFC9A227)
    )

    // ── 26 · PRO RED PUNCH — red badge, white chip word ────────
    // White Estedad Black on an opaque red pill badge; the active
    // word flips into a white chip with red text (the inverted
    // chip needs boxTextColor, supported by the JSON mapper).
    val RED_PUNCH: SubtitleTemplate = SubtitleTemplate(
        id = "fa_pro_red_punch",
        name = "پانچ قرمز",
        nameEn = "Red Punch",
        builtIn = true,
        category = "سینمایی",
        style = SubtitleStyle(
            fontFamily = PersianFont.ESTEDAD.name,
            fontSizeSp = 30f,
            fontWeightValue = 900,
            textColorArgb = AndroidColor.WHITE,
            bold = true,
            strokeEnabled = false,
            shadowEnabled = false,
            backgroundEnabled = true,
            backgroundColorArgb = argb(0xFFE6203A),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 999f,
            positionY = 0.82f
        ),
        activeWordColorArgb = argb(0xFFE6203A),
        activeWordBackgroundArgb = argb(0xFFFFFFFF),
        activeWordScale = 1.1f,
        activeWordBold = true,
        animationIn = TextOverlayAnimation.POP,
        animationOut = TextOverlayAnimation.FADE_OUT,
        animationDurationMs = 200,
        easingIn = "easeOutBack",
        wordRevealMode = WordRevealMode.HIGHLIGHT,
        motionKind = WordMotionKind.HIGHLIGHT_COLOR,
        backgroundKind = CaptionBackgroundKind.SOLID_ROUNDED,
        accentColorArgb = argb(0xFFFFFFFF)
    )

    /** The default template (fresh installs, unset studio default). */
    val DEFAULT: SubtitleTemplate = CLEAN

    /**
     * All 26 shipped templates, in picker order: the 20 numbered
     * `fa_NN_*` entries (frozen ids — saved projects reference
     * them) followed by the 2026-10-15 six-pro viral set.
     */
    val ALL: List<SubtitleTemplate> = listOf(
        CLEAN, IMPACT, YELLOW_WORD, KARAOKE, CAPSULE,
        GLASS, CREATOR, CINEMATIC, NEWS, PODCAST,
        GAMING, NEON, OUTLINE, SHADOW_POP, DYNAMIC,
        BAR, MODERN_BOX, SOCIAL, PREMIUM, SIGNATURE,
        HORMOZI_VIRAL, NEON_CYBERPUNK, GLASS_MINIMAL,
        COMIC_POP, GOLDEN_LUX, RED_PUNCH
    )

    fun byId(id: String): SubtitleTemplate? = ALL.firstOrNull { it.id == id }

    /**
     * 2026-09-19 migration: the pre-20 catalog (14 legacy `tmpl_*`
     * templates + 10 `viral_*` motion packs) was removed. Saved
     * projects / studio defaults referencing those ids resolve to
     * their closest equivalent here instead of silently losing
     * their styling. Unknown ids return null.
     */
    fun migrateLegacyId(id: String): SubtitleTemplate? = when (id) {
        "tmpl_classic" -> CLEAN
        "tmpl_minimal" -> PREMIUM
        "tmpl_cinematic" -> CINEMATIC
        "tmpl_reels" -> SOCIAL
        "tmpl_tiktok" -> DYNAMIC
        "tmpl_word_by_word" -> CREATOR
        "tmpl_karaoke" -> KARAOKE
        "tmpl_dynamic" -> DYNAMIC
        "tmpl_yellow_bg" -> YELLOW_WORD
        "tmpl_bold_stroke" -> IMPACT
        "tmpl_neon_persian" -> NEON
        "tmpl_bounce_pop_en" -> CREATOR
        "tmpl_neon_green" -> NEON
        "tmpl_minimal_box" -> MODERN_BOX
        "viral_bounce_pop" -> CREATOR
        "viral_typewriter" -> OUTLINE
        "viral_slide_up" -> DYNAMIC
        "viral_fade_scale" -> SOCIAL
        "viral_shake" -> GAMING
        "viral_neon" -> NEON
        "viral_karaoke_fill" -> KARAOKE
        "viral_rise_bounce" -> SIGNATURE
        "viral_line_ribbon" -> BAR
        "viral_frosted_pulse" -> GLASS
        else -> null
    }
}
