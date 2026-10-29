package com.aicreatorstudio.app.model

/**
 * 2026-09-14 VIRAL MOTION SYSTEM — word-by-word motion engines
 * (CapCut/Veed-style) + rich caption backgrounds.
 *
 * (2026-09-19: these enums used to live next to the removed
 * `SubtitleMotionPreset` pack catalog; they are the shared motion
 * vocabulary the 20 Persian templates compose, so they stay.)
 *
 * Each kind drives BOTH the live preview and the export renderer
 * through the same per-word timing data.
 *
 * RTL NOTE — KARAOKE_FILL fills right-to-left for Persian text
 * (the natural reading direction), left-to-right for Latin.
 */
enum class WordMotionKind(val displayName: String) {
    /** Static caption, no word motion. */
    NONE("بدون موشن"),
    /** Spoken word changes color instantly. */
    HIGHLIGHT_COLOR("تغییر رنگ کلمه"),
    /** Spoken word pops/bounces (scale spring). */
    BOUNCE_POP("بونس / پاپ"),
    /** Words typed character by character as they are spoken. */
    TYPEWRITER("تایپ حرف‌به‌حرف"),
    /** Spoken word slides up into place. */
    SLIDE_UP_WORD("اسلاید بالا"),
    /** Spoken word fades in with a scale-up. */
    FADE_SCALE("فید + بزرگ‌شدن"),
    /** Spoken word shakes for emphasis. */
    SHAKE_WIGGLE("لرزش / تأکید"),
    /** Spoken word glows with a neon halo. */
    NEON_GLOW("نئون"),
    /** Progressive right-to-left color fill (karaoke fill). */
    KARAOKE_FILL("پرکردن کارائوکه‌ای"),
    /** Whole caption rises from the bottom with a bounce. */
    RISE_BOUNCE("بالا آمدن با بونس"),
    /** Spoken word glitches (deterministic digital distortion). */
    GLITCH("گلیچ / اختلال")
}

/**
 * Caption background renderers (the background pack).
 */
enum class CaptionBackgroundKind(val displayName: String) {
    /** No box — stroke/shadow carry legibility (legacy none). */
    NONE("بدون پس‌زمینه"),
    /** Solid rounded box (SubtitleStyle background fields). */
    SOLID_ROUNDED("جامد گرد"),
    /** Highlight ribbon only under the ACTIVE line/word. */
    PER_LINE_HIGHLIGHT("نوار هایلایت"),
    /** Two-color gradient behind the caption box. */
    GRADIENT("گرادیان"),
    /** Full-frame cinematic scope bars (top + bottom). */
    LETTERBOX_BARS("نوار سینمایی"),
    /** Frosted translucent blur panel. */
    BLUR("شیشه‌ای / محو"),
    /**
     * 2026-09-24 — filmstrip frame: a dark plate carrying two
     * perforated strips (top + bottom) so the caption reads like a
     * frame of film. Drawn per-frame with plain round-rects — no
     * blur, no shader.
     */
    FILMSTRIP("نوار فیلم");

    /**
     * Reverse mapping: engine kind → JSON template type string.
     * Used by the Background tab to serialize the override type.
     */
    val engineTypeString: String
        get() = when (this) {
            NONE -> "none"
            SOLID_ROUNDED -> "solidbox"
            GRADIENT -> "gradientbox"
            LETTERBOX_BARS -> "letterboxbars"
            BLUR -> "blurvideo"
            PER_LINE_HIGHLIGHT -> "perlinehighlight"
            FILMSTRIP -> "filmstrip"
        }
}
