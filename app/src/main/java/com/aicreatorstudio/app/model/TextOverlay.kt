package com.aicreatorstudio.app.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.aicreatorstudio.app.ai.TranscribedWord

import java.util.UUID

/**
 * A text overlay on the video timeline.
 *
 * Represents text that appears on screen at a specific time range
 * with customizable styling and position.
 *
 * When [words] is non-empty (the AI Auto-Subtitle path), the overlay
 * also carries word-level timing so the preview can drive
 * karaoke-style word highlighting synchronized to the playhead. When
 * [words] is empty the overlay renders as a single block of text
 * (the legacy behavior, preserved).
 */
data class TextOverlay(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val position: Offset = Offset(0.5f, 0.5f), // Normalized 0-1 coordinates
    val color: Color = Color.White,
    val fontSizeSp: Float = 24f,
    val fontWeight: FontWeight = FontWeight.Normal,
    val backgroundColor: Color? = null,
    val backgroundAlpha: Float = 0.5f,
    val rotation: Float = 0f,
    // Name of a [PersianFont] entry, e.g. "VAZIR". Unknown names fall back to default.
    val fontFamily: String = PersianFont.DEFAULT.name,
    /**
     * Word-level timing inside this overlay, if any. Populated by the
     * AI Auto-Subtitle generator. Empty for user-authored text
     * overlays.
     */
    val words: List<TranscribedWord> = emptyList(),
    /**
     * When true (and [words] is non-empty) the preview will highlight
     * the currently-spoken word. Defaults to true; the user can
     * disable karaoke highlighting per overlay.
     */
    val karaokeEnabled: Boolean = true,
    /**
     * Subtitle source tag — survives project save/reopen. The
     * editor uses this to decide which TextOverlays are
     * "subtitles" (exportable as SRT) vs decorative text
     * (titles, watermarks).
     *
     *   "ai"  — produced by the Whisper auto-subtitle path
     *           (also implicit: words.isNotEmpty())
     *   "srt" — produced by the SRT import path
     *           (PHASE B4)
     *   null  — user-authored decorative text (titles,
     *           watermarks, etc.)
     *
     * Defaults to null. The export filter uses this tag in
     * preference to the [aiSubtitleOverlayIds] in-memory set so
     * subtitles survive a project close + reopen.
     */
    val subtitleSource: String? = null,
    /**
     * PHASE B5 — production subtitle styling. Optional
     * subtitle-only style bundle (font, color, stroke, shadow,
     * background, alignment, position). When null, the renderer
     * uses [SubtitleStyle.Default] so existing decorative
     * overlays (titles, watermarks) and pre-B5 subtitles keep
     * rendering unchanged. Decorative overlays never read this
     * field.
     */
    val subtitleStyle: SubtitleStyle? = null,
    /**
     * 2026-09-07 — timeline offset that was ADDED to the source
     * timestamps when this subtitle was created (the generating
     * clip's timeline position for AI subtitles, the import offset
     * for SRT cues). Subtracting it converts the overlay's timeline
     * times back to media-local times — the SRT export timebase.
     * 0 for decorative text overlays.
     */
    val subtitleAnchorMs: Long = 0L,
    /**
     * 2026-09-06 — entrance/exit animation for this text overlay,
     * applied by the preview renderer (time-based, deterministic).
     * NONE keeps the legacy static behaviour.
     */
    val textAnimation: TextOverlayAnimation = TextOverlayAnimation.NONE,
    // ---- 2026-09-11 «زیرنویس فارسی» template layer ----
    /** The [SubtitleTemplate] this overlay was created/styled with. */
    val templateId: String? = null,
    /** Active-word highlight color (null = template/default karaoke). */
    val activeWordColorArgb: Int? = null,
    /** Active-word scale multiplier (1f = off). */
    val activeWordScale: Float = 1f,
    /** Bold the active (spoken) word — from the template's flag. */
    val activeWordBold: Boolean = false,
    /** Pill background behind the active word (null = off). */
    val activeWordBackgroundArgb: Int? = null,
    /**
     * 2026-09-19 PHASE 6 — direction-aware progress bar under the
     * caption, synced to the real word timings (null = off).
     * Copied by [withTemplate]; honored by the shared renderer in
     * preview and export alike.
     */
    val progressBar: ProgressBarSpec? = null,
    /**
     * 2026-09-19 PHASE 7 — gradient angle in CSS degrees (null =
     * legacy horizontal). Copied by [withTemplate].
     */
    val gradientAngleDeg: Float? = null,
    /** Exit animation (entry stays in [textAnimation]). */
    val textAnimationOut: TextOverlayAnimation = TextOverlayAnimation.NONE,
    /** How words reveal across the window (see [WordRevealMode]). */
    val wordRevealMode: WordRevealMode = WordRevealMode.ALL_AT_ONCE,
    // ---- 2026-09-14 viral motion + background layer ----
    /** Word-by-word motion engine (NONE = legacy). */
    val motionKind: WordMotionKind = WordMotionKind.NONE,
    /** Caption background renderer (NONE = style's own setting). */
    val backgroundKind: CaptionBackgroundKind = CaptionBackgroundKind.NONE,
    /** Neon glow radius behind the active word (0 = off). */
    val activeWordGlowRadiusDp: Float = 0f,
    /** Underline the spoken word. */
    val activeWordUnderline: Boolean = false,
    /** Secondary accent color (gradient end / Karaoke fill). */
    val accentColorArgb: Int = 0xFF00E5FF.toInt(),
    /** Word-motion / entry-exit effect length in ms (60..1500). */
    val animationDurationMs: Int = 260,
    /**
     * PHASE 8 — easing preset for entry animation (null = linear).
     * Copied by [withTemplate]; honored by [WordMotionEngine].
     */
    val easingIn: String? = null,
    /**
     * PHASE 8 — easing preset for exit animation (null = linear).
     * Copied by [withTemplate]; honored by [WordMotionEngine].
     */
    val easingOut: String? = null,
    /**
     * PHASE 10E — exit animation duration in ms (null = uses
     * [animationDurationMs] for both entry and exit).
     */
    val animationDurationMsOut: Int? = null
) {
    /** Duration of the text overlay in milliseconds */
    val durationMs: Long
        get() = endTimeMs - startTimeMs

    /** Check if text is visible at given playback position */
    fun isVisibleAt(positionMs: Long): Boolean {
        return positionMs in startTimeMs..endTimeMs
    }

    /**
     * Returns the [TranscribedWord] that is currently being spoken at
     * [positionMs], or null if the position is between two words or
     * if no word timing is attached.
     */
    fun activeWordAt(positionMs: Long): TranscribedWord? =
        words.firstOrNull { it.isActiveAt(positionMs) }

    companion object {
        /** Default text overlay preset */
        fun default(
            text: String = "Sample Text",
            startTimeMs: Long = 0L,
            endTimeMs: Long = 5000L
        ) = TextOverlay(
            text = text,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs
        )
    }
}

/**
 * Text style preset options.
 */
enum class TextStylePreset(val displayName: String) {
    DEFAULT("Default"),
    TITLE("Title"),
    SUBTITLE("Subtitle"),
    CAPTION("Caption"),
    WATERMARK("Watermark")
}

/**
 * 2026-09-06 — entrance/exit animations for text overlays.
 * All are time-based (computed from the overlay's own start/end),
 * deterministic, and preview-only until a future export phase
 * bakes them.
 */
enum class TextOverlayAnimation(val displayName: String) {
    NONE("None"),
    FADE_IN("Fade In"),
    FADE_OUT("Fade Out"),
    FADE_IN_OUT("Fade In & Out"),
    POP("Pop"),
    SLIDE_UP("Slide Up"),
    /** PHASE 8 — digital glitch entrance (deterministic distortion). */
    GLITCH_IN("Glitch In"),
    /** PHASE 8 — digital glitch exit (deterministic distortion). */
    GLITCH_OUT("Glitch Out")
}

/**
 * Bundled fonts — 14 Persian families (37+ weights) + 10 English.
 *
 * 2026-09-14 FONT SYSTEM UPGRADE — every rastikerdar/aminabedi68
 * OFL Persian family available, bundled locally (fully offline).
 * Weight-aware families live in res/font (font-family XML) so
 * FontWeight.Bold/Medium/Black resolve to REAL TTF weights, not
 * fake synthetic bolding:
 *
 *   Persian (OFL, RTL-shaped):
 *   Vazirmatn (L/R/M/B/Black) · Vazir Code (mono) · Sahel (L/R/B/Black)
 *   Shabnam (L/R/M/B/Black) · Samim (R/M/B) · Estedad (R/M/B/Black)
 *   Gandom · Nahid · Tanha · Parastoo (R/B) · Lalezar (display)
 *
 *   English / Latin (SIL-OFL / Apache, Google Fonts):
 *   Roboto · Open Sans · Montserrat · Poppins · Inter · Oswald ·
 *   Bebas Neue · Anton · Lobster · Playfair Display
 *
 * NOTE: IRANSans, Yekan, Dana, Kalameh, Morabba, Peyda are
 * commercial fonts WITHOUT a redistribution license — they cannot
 * legally be bundled. The picker's family list is the complete
 * free OFL set.
 */
enum class PersianFont(val displayName: String, val resName: String) {
    DEFAULT("Default", ""),
    // ---- Persian (weight-aware family resources) ----
    VAZIR("Vazirmatn", "vazirmatn_family"),
    VAZIR_CODE("Vazir Code", "vazir_code"),
    SAHEL("Sahel", "sahel_family"),
    SHABNAM("Shabnam", "shabnam_family"),
    SAMIM("Samim", "samim_family"),
    ESTEDAD("Estedad", "estedad_family"),
    PARASTOO("Parastoo", "parastoo_family"),
    GANDOM("Gandom", "gandom"),
    NAHID("Nahid", "nahid"),
    TANHA("Tanha", "tanha"),
    LALEZAR("Lalezar", "lalezar"),
    // ---- English / Latin (2026-09-06) ----
    ROBOTO("Roboto", "roboto"),
    OPEN_SANS("Open Sans", "open_sans"),
    MONTSERRAT("Montserrat", "montserrat"),
    POPPINS("Poppins", "poppins"),
    INTER("Inter", "inter"),
    OSWALD("Oswald", "oswald"),
    BEBAS_NEUE("Bebas Neue", "bebas_neue"),
    ANTON("Anton", "anton"),
    LOBSTER("Lobster", "lobster"),
    PLAYFAIR_DISPLAY("Playfair Display", "playfair_display");

    companion object {
        fun from(name: String?): PersianFont =
            entries.firstOrNull { it.name == name } ?: DEFAULT

        /** All Persian-script families for the font picker's RTL tab. */
        val PersianFamilies: List<PersianFont> = listOf(
            VAZIR, VAZIR_CODE, SAHEL, SHABNAM, SAMIM, ESTEDAD,
            PARASTOO, GANDOM, NAHID, TANHA, LALEZAR
        )
    }
}
