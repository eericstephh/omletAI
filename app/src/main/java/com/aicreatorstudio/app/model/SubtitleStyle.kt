package com.aicreatorstudio.app.model

import android.graphics.Color as AndroidColor

/**
 * PHASE B5 — production subtitle styling.
 *
 * A typed bundle of every style field the subtitle renderer can
 * honor. Lives on [TextOverlay.subtitleStyle] (nullable, default
 * null). Decorative text overlays (titles, watermarks) never read
 * this field; only AI subtitles (subtitleSource = "ai") and
 * SRT-imported subtitles (subtitleSource = "srt") carry a style.
 *
 * Conventions:
 *  - Color values are stored as `Int` ARGB (not Compose `Color`)
 *    so the data class is free of the Compose runtime at the
 *    model layer — the renderer maps Int -> Color.
 *  - Positions are normalized 0..1 against the **video canvas**
 *    (not device screen).
 *  - All distance / size fields use sp or dp (the renderer
 *    decides which based on context).
 *  - [Default] is the renderer's fallback for any overlay that
 *    has `subtitleStyle == null` (legacy) or `subtitleStyle ==
 *    SubtitleStyle.Default`.
 */
data class SubtitleStyle(
    val fontFamily: String = PersianFont.DEFAULT.name,
    val fontSizeSp: Float = 24f,
    val textColorArgb: Int = AndroidColor.WHITE,
    val bold: Boolean = true,
    /**
     * 2026-09-19 PHASE 5 — CSS font weight (400/500/600/700/800/900).
     * The renderers resolve the closest REAL bundled TTF weight
     * (minSdk-safe file lookup, never synthetic-only); [bold] stays
     * as the fake-bold emphasis switch + legacy behavior. Defaults
     * to 400 so every existing construction and saved blob is
     * unaffected.
     */
    val fontWeightValue: Int = 400,
    val italic: Boolean = false,
    val strokeEnabled: Boolean = true,
    val strokeColorArgb: Int = AndroidColor.BLACK,
    val strokeWidthSp: Float = 2f,
    val shadowEnabled: Boolean = false,
    val shadowColorArgb: Int = AndroidColor.BLACK,
    val shadowOpacity: Float = 0.5f,
    val shadowOffsetYDp: Float = 2f,
    val backgroundEnabled: Boolean = true,
    val backgroundColorArgb: Int = AndroidColor.BLACK,
    val backgroundOpacity: Float = 0.55f,
    val backgroundCornerRadiusDp: Float = 8f,
    val horizontalAlignment: SubtitleAlignmentH = SubtitleAlignmentH.CENTER,
    val verticalAlignment: SubtitleAlignmentV = SubtitleAlignmentV.BOTTOM,
    /** Normalized 0..1 (canvas). 0.5 = horizontal center. */
    val positionX: Float = 0.5f,
    /** Normalized 0..1 (canvas). 0.85 = bottom of the safe area. */
    val positionY: Float = 0.85f,
    /** Maximum text width as a fraction of canvas width (0..1). */
    val maxTextWidthFraction: Float = 0.9f
) {
    /**
     * True when every field matches the values produced by the
     * `SubtitleStyle()` zero-arg constructor. Lets the JSON
     * codec skip the field entirely for "no styling" overlays
     * (legacy blobs decode to `null`, not Default).
     */
    fun isDefault(): Boolean = this == Default

    companion object {
        /**
         * Sentinel used by the renderer when a subtitle carries
         * no `subtitleStyle` (legacy decorative overlays). Field
         * values mirror the data class defaults.
         */
        val Default: SubtitleStyle = SubtitleStyle()

        /** Classic: bold white text with a black stroke. No background. */
        val Classic: SubtitleStyle = SubtitleStyle(
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 2f,
            backgroundEnabled = false,
            bold = true
        )

        /** Bold: large, thicker stroke. */
        val Bold: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 32f,
            bold = true,
            strokeEnabled = true,
            strokeWidthSp = 3f,
            backgroundEnabled = false
        )

        /** Clean: no stroke; subtle drop shadow. */
        val Clean: SubtitleStyle = SubtitleStyle(
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.4f,
            shadowOffsetYDp = 1.5f,
            backgroundEnabled = false
        )

        /** Box: rounded dark background, no stroke, no shadow. */
        val Box: SubtitleStyle = SubtitleStyle(
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = false,
            backgroundEnabled = true,
            backgroundColorArgb = AndroidColor.argb(255, 24, 24, 24),
            backgroundOpacity = 0.85f,
            backgroundCornerRadiusDp = 12f
        )

        /** Shorts: very large, semi-transparent background, 9:16-safe Y. */
        val Shorts: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 36f,
            bold = true,
            strokeEnabled = true,
            strokeWidthSp = 4f,
            backgroundEnabled = true,
            backgroundColorArgb = AndroidColor.BLACK,
            backgroundOpacity = 0.6f,
            backgroundCornerRadiusDp = 16f,
            positionY = 0.82f,
            maxTextWidthFraction = 0.85f
        )

        /** Minimal: not bold, subtle shadow, no stroke, no background. */
        val Minimal: SubtitleStyle = SubtitleStyle(
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.3f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = false,
            bold = false
        )

        // ---- 2026-09-06: "خفن و مینیمال" preset pack ----
        // Six modern looks for Persian AI subtitles, designed for
        // Shorts / Reels style content. All ship with Persian font
        // untouched (the font chip in the panel still wins) so only
        // color / weight / stroke / shadow / background change.

        /** Neon: bold white with an electric-cyan glow (no box). */
        val Neon: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 28f,
            bold = true,
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowColorArgb = 0xFF00E5FF.toInt(),
            shadowOpacity = 0.9f,
            shadowOffsetYDp = 0f,
            backgroundEnabled = false
        )

        /** Glass: iOS Live-Caption look — white on a soft translucent pill. */
        val Glass: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 22f,
            bold = false,
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = false,
            shadowEnabled = true,
            shadowOpacity = 0.35f,
            shadowOffsetYDp = 1f,
            backgroundEnabled = true,
            backgroundColorArgb = 0xFF14141A.toInt(),
            backgroundOpacity = 0.62f,
            backgroundCornerRadiusDp = 20f,
            positionY = 0.86f
        )

        /** Gold Lux: warm gold text, hairline black stroke, soft shadow. */
        val Gold: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 26f,
            bold = true,
            textColorArgb = 0xFFFFD54F.toInt(),
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 2f,
            shadowEnabled = true,
            shadowOpacity = 0.35f,
            shadowOffsetYDp = 2f,
            backgroundEnabled = false
        )

        /** Pop: TikTok-style — huge bold with a very thick stroke. */
        val Pop: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 34f,
            bold = true,
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.BLACK,
            strokeWidthSp = 5f,
            backgroundEnabled = false,
            positionY = 0.80f
        )

        /** Ink: for bright scenes — near-black text with a white stroke. */
        val Ink: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 26f,
            bold = true,
            textColorArgb = 0xFF111111.toInt(),
            strokeEnabled = true,
            strokeColorArgb = AndroidColor.WHITE,
            strokeWidthSp = 3f,
            backgroundEnabled = false
        )

        /** Accent: white on an iOS-blue pill — clean channel branding. */
        val Accent: SubtitleStyle = SubtitleStyle(
            fontSizeSp = 24f,
            bold = true,
            textColorArgb = AndroidColor.WHITE,
            strokeEnabled = false,
            backgroundEnabled = true,
            backgroundColorArgb = 0xFF0A84FF.toInt(),
            backgroundOpacity = 0.92f,
            backgroundCornerRadiusDp = 14f
        )

        /** All named presets for the picker rows (core + cool pack). */
        val AllPresets: List<Pair<String, SubtitleStyle>> = listOf(
            "Neon" to Neon,
            "Glass" to Glass,
            "Gold" to Gold,
            "Pop" to Pop,
            "Ink" to Ink,
            "Accent" to Accent,
            "Minimal" to Minimal,
            "Classic" to Classic,
            "Clean" to Clean,
            "Box" to Box,
            "Shorts" to Shorts,
            "Bold" to Bold
        )
    }
}

/** Horizontal alignment of the caption inside the safe-area band. */
enum class SubtitleAlignmentH { LEFT, CENTER, RIGHT }

/** Vertical alignment of the caption relative to the canvas. */
enum class SubtitleAlignmentV { TOP, CENTER, BOTTOM }
