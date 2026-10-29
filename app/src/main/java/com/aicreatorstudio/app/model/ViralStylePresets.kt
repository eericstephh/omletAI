package com.aicreatorstudio.app.model

import android.graphics.Color as AndroidColor

/**
 * PHASE B5.5 — viral text / caption style presets.
 *
 * A second named collection of [SubtitleStyle]s, separate from
 * [SubtitleStyle.AllPresets], built for "meme / platform-native /
 * attention-grab" looks (TikTok-style, neon, glitch, etc.) rather
 * than the legibility-first look of the six subtitle presets.
 *
 * Same data class, different collection. The renderer in
 * `EditorScreen.kt` is `subtitleStyle`-driven and works on any
 * `TextOverlay` (or decorative `TextOverlay` that the user
 * chose to style); it does not care which collection produced
 * the style. This is the explicit "one Style data class, two
 * named collections" decision from the B5.5 brief.
 *
 * Persistence: each style is a `SubtitleStyle` value, which
 * already round-trips through [com.aicreatorstudio.app.db.EditorStateJson]
 * (B5). No new persistent field is needed.
 *
 * Scope: a viral style can be applied to ANY `TextOverlay` —
 * subtitle (`subtitleSource != null`) or decorative
 * (`subtitleSource == null`). The renderer in `EditorScreen.kt`
 * already falls back to `SubtitleStyle.Default` when
 * `subtitleStyle == null`, so viral styles applied to
 * decorative text "just work."
 */
object ViralStylePresets {

    /** Bold Shorts — chunky all-caps-friendly caption for 9:16. */
    val BoldShorts: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 40f,
        bold = true,
        italic = false,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.BLACK,
        strokeWidthSp = 4f,
        backgroundEnabled = false,
        positionY = 0.82f,
        maxTextWidthFraction = 0.85f
    )

    /** TikTok Bold — the platform's signature look: thick white + black. */
    val TikTokBold: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 34f,
        bold = true,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.BLACK,
        strokeWidthSp = 3.5f,
        shadowEnabled = true,
        shadowColorArgb = AndroidColor.BLACK,
        shadowOpacity = 0.5f,
        shadowOffsetYDp = 2f,
        backgroundEnabled = false,
        positionY = 0.78f
    )

    /** Impact — heavy black, white text, no fill, classic meme top/bottom. */
    val Impact: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 38f,
        bold = true,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.BLACK,
        strokeWidthSp = 4f,
        backgroundEnabled = false,
        positionY = 0.5f,
        verticalAlignment = SubtitleAlignmentV.CENTER
    )

    /** Clean Caption — the legibility default, no chrome. */
    val CleanCaption: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 26f,
        bold = true,
        strokeEnabled = false,
        shadowEnabled = true,
        shadowColorArgb = AndroidColor.BLACK,
        shadowOpacity = 0.4f,
        shadowOffsetYDp = 1.5f,
        backgroundEnabled = false
    )

    /** Classic White + Black Stroke — timeless subtitle. */
    val ClassicWhiteBlackStroke: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 28f,
        bold = true,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.BLACK,
        strokeWidthSp = 2.5f,
        backgroundEnabled = false
    )

    /**
     * Yellow Highlight — yellow text on a black rounded pill,
     * inspired by late-night clip shows.
     */
    val YellowHighlight: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 30f,
        bold = true,
        textColorArgb = AndroidColor.argb(255, 255, 230, 0),
        strokeEnabled = false,
        backgroundEnabled = true,
        backgroundColorArgb = AndroidColor.argb(255, 16, 16, 16),
        backgroundOpacity = 0.9f,
        backgroundCornerRadiusDp = 10f
    )

    /** Box Caption — clean dark pill, white text, generous corner radius. */
    val BoxCaption: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 26f,
        bold = true,
        strokeEnabled = false,
        backgroundEnabled = true,
        backgroundColorArgb = AndroidColor.argb(255, 24, 24, 24),
        backgroundOpacity = 0.9f,
        backgroundCornerRadiusDp = 14f
    )

    /**
     * Meme Caption — white Impact-style with thick black stroke,
     * pinned to the top of the canvas (meme "top text" tradition).
     */
    val MemeCaption: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 34f,
        bold = true,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.BLACK,
        strokeWidthSp = 4f,
        backgroundEnabled = false,
        positionY = 0.1f,
        verticalAlignment = SubtitleAlignmentV.TOP
    )

    /**
     * Neon — bright magenta-cyan look: white core text, magenta
     * outer stroke, soft glow shadow. A "night club caption" feel.
     */
    val Neon: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 32f,
        bold = true,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.argb(255, 255, 0, 200),
        strokeWidthSp = 2.5f,
        shadowEnabled = true,
        shadowColorArgb = AndroidColor.argb(255, 0, 220, 255),
        shadowOpacity = 0.7f,
        shadowOffsetYDp = 0f,
        backgroundEnabled = false
    )

    /**
     * Glitch — a hard cyan/black double-stroke that suggests a
     * chromatic-aberration look without animation. (B5.5 is
     * preview-only; the dynamic RGB-split is reserved for a
     * later phase.)
     */
    val Glitch: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 30f,
        bold = true,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.argb(255, 0, 255, 220),
        strokeWidthSp = 3f,
        shadowEnabled = true,
        shadowColorArgb = AndroidColor.argb(255, 255, 0, 80),
        shadowOpacity = 0.6f,
        shadowOffsetYDp = 2f,
        backgroundEnabled = false
    )

    /**
     * Typewriter — small, not bold, low contrast, no stroke. A
     * documentary / old-cassette-label feel. Sits in the top
     * band.
     */
    val Typewriter: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 20f,
        bold = false,
        italic = true,
        strokeEnabled = false,
        shadowEnabled = false,
        backgroundEnabled = true,
        backgroundColorArgb = AndroidColor.argb(255, 240, 230, 200),
        backgroundOpacity = 0.85f,
        backgroundCornerRadiusDp = 4f,
        textColorArgb = AndroidColor.argb(255, 40, 30, 20),
        positionY = 0.12f,
        verticalAlignment = SubtitleAlignmentV.TOP
    )

    /**
     * Cinematic — wide, gold-on-black widescreen caption, used
     * for trailers / opening titles. Centered, generous letter
     * spacing via the standard render (no kerning override).
     */
    val Cinematic: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 30f,
        bold = false,
        textColorArgb = AndroidColor.argb(255, 235, 200, 120),
        strokeEnabled = false,
        shadowEnabled = true,
        shadowColorArgb = AndroidColor.BLACK,
        shadowOpacity = 0.7f,
        shadowOffsetYDp = 3f,
        backgroundEnabled = false,
        positionY = 0.5f,
        verticalAlignment = SubtitleAlignmentV.CENTER
    )

    /**
     * Minimal — the same as the subtitle-Style "Minimal", kept
     * here so the viral picker doesn't have to defer to the
     * subtitle picker for the "no chrome" case. (A user picking
     * "Minimal" from the viral grid should see the same look as
     * picking "Minimal" from the subtitle grid; the two
     * collections intentionally share the entry.)
     */
    val Minimal: SubtitleStyle = SubtitleStyle(
        textColorArgb = AndroidColor.WHITE,
        strokeEnabled = false,
        shadowEnabled = true,
        shadowOpacity = 0.3f,
        shadowOffsetYDp = 1f,
        backgroundEnabled = false,
        bold = false
    )

    /**
     * Large Center — the only preset that pins the caption to
     * the visual center of the canvas. Used for "this is the
     * point of the video" frames.
     */
    val LargeCenter: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 44f,
        bold = true,
        strokeEnabled = true,
        strokeColorArgb = AndroidColor.BLACK,
        strokeWidthSp = 3.5f,
        backgroundEnabled = false,
        positionX = 0.5f,
        positionY = 0.5f,
        verticalAlignment = SubtitleAlignmentV.CENTER
    )

    /**
     * Bottom Caption — the default subtitle band look: bottom
     * of the canvas, semi-transparent black background, white
     * text. The "TikTok comment" baseline.
     */
    val BottomCaption: SubtitleStyle = SubtitleStyle(
        fontSizeSp = 28f,
        bold = true,
        strokeEnabled = false,
        backgroundEnabled = true,
        backgroundColorArgb = AndroidColor.BLACK,
        backgroundOpacity = 0.65f,
        backgroundCornerRadiusDp = 10f,
        positionY = 0.85f,
        verticalAlignment = SubtitleAlignmentV.BOTTOM
    )

    /**
     * Every viral preset, in the order they appear in the picker.
     * The list is the picker source of truth — adding, removing,
     * or renaming an entry is a UI change ONLY; no model code
     * changes are required. (The names are the user-visible
     * labels in the [com.aicreatorstudio.app.ui.screens.editor.ViralStylePanel].)
     *
     * Exactly 15 entries. Count is locked by
     * `ViralStylePresetsTest.count_is_15` so the picker never
     * accidentally drops a viral look.
     *
     * Declared AFTER the per-preset vals so the source order
     * matches Kotlin's top-down init order inside the object.
     */
    val All: List<Pair<String, SubtitleStyle>> = listOf(
        "Bold Shorts" to BoldShorts,
        "TikTok Bold" to TikTokBold,
        "Impact" to Impact,
        "Clean Caption" to CleanCaption,
        "Classic White + Black Stroke" to ClassicWhiteBlackStroke,
        "Yellow Highlight" to YellowHighlight,
        "Box Caption" to BoxCaption,
        "Meme Caption" to MemeCaption,
        "Neon" to Neon,
        "Glitch" to Glitch,
        "Typewriter" to Typewriter,
        "Cinematic" to Cinematic,
        "Minimal" to Minimal,
        "Large Center" to LargeCenter,
        "Bottom Caption" to BottomCaption
    )
}
