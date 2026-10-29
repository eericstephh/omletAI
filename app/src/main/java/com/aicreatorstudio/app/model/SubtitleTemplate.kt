package com.aicreatorstudio.app.model

import org.json.JSONObject
import java.util.UUID

/**
 * 2026-09-11 — «زیرنویس فارسی» template system.
 *
 * A [SubtitleTemplate] is a NAMED, extensible bundle of:
 *  - an existing [SubtitleStyle] (the single source of style truth —
 *    font, size, colors, stroke, shadow, background, alignment,
 *    position; rendered identically in preview and export),
 *  - ACTIVE-WORD styling for karaoke/word-highlight
 *    ([activeWordColorArgb], [activeWordScale], [activeWordBold],
 *    [activeWordBackgroundArgb]),
 *  - motion: [animationIn] / [animationOut] (the existing
 *    [TextOverlayAnimation] enum, so preview + export stay in sync)
 *    and [animationDurationMs],
 *  - [wordRevealMode] — how words appear over time.
 *
 * Word timing reuses [com.aicreatorstudio.app.ai.TranscribedWord]
 * (word + startTimeMs + endTimeMs) — the exact WordTiming shape.
 *
 * Templates are data only: no Compose, no Android UI types. They
 * serialize to JSON so a future template-store can sync them.
 */
data class SubtitleTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** True for the 8 shipped templates — user templates are false. */
    val builtIn: Boolean = false,
    val style: SubtitleStyle = SubtitleStyle.Default,
    /** Highlight color for the currently-spoken word (karaoke). */
    val activeWordColorArgb: Int = 0xFFFFD60A.toInt(),
    /** Scale multiplier applied to the active word (1f = off). */
    val activeWordScale: Float = 1f,
    /** Bold the active word. */
    val activeWordBold: Boolean = false,
    /** Optional pill background behind the active word (null = off). */
    val activeWordBackgroundArgb: Int? = null,
    /** Entry animation. */
    val animationIn: TextOverlayAnimation = TextOverlayAnimation.FADE_IN,
    /** Exit animation. */
    val animationOut: TextOverlayAnimation = TextOverlayAnimation.NONE,
    /** Entry/exit animation length in ms. */
    val animationDurationMs: Int = 260,
    /**
     * PHASE 10E — exit animation length in ms (null = uses
     * [animationDurationMs] for both entry and exit).
     */
    val animationDurationMsOut: Int? = null,
    /**
     * PHASE 8 — easing preset for entry animation (null = linear).
     * String identifier matching [com.aicreatorstudio.app.ai.EasingFunctions.Easing].
     */
    val easingIn: String? = null,
    /**
     * PHASE 8 — easing preset for exit animation (null = linear).
     * String identifier matching [com.aicreatorstudio.app.ai.EasingFunctions.Easing].
     */
    val easingOut: String? = null,
    /** How words reveal across the caption window. */
    val wordRevealMode: WordRevealMode = WordRevealMode.ALL_AT_ONCE,
    // ---- 2026-09-14 viral motion + background layer ----
    /** Word-by-word motion engine (NONE = legacy behaviour). */
    val motionKind: WordMotionKind = WordMotionKind.NONE,
    /** Caption background renderer (NONE = style's own setting). */
    val backgroundKind: CaptionBackgroundKind = CaptionBackgroundKind.NONE,
    /** Neon glow radius behind the active word (0 = off). */
    val activeWordGlowRadiusDp: Float = 0f,
    /** Underline the spoken word. */
    val activeWordUnderline: Boolean = false,
    /** Secondary accent color (gradient end / Karaoke fill). */
    val accentColorArgb: Int = 0xFF00E5FF.toInt(),
    /**
     * 2026-09-19 — picker grouping (e.g. «وایرال», «سینمایی»).
     * Display-only: never affects rendering. Defaults to «عمومی»
     * so legacy/user JSON without the key still decodes.
     */
    /**
     * 2026-09-19 — picker grouping (e.g. «وایرال», «سینمایی»).
     * Display-only: never affects rendering. Defaults to «عمومی»
     * so legacy/user JSON without the key still decodes.
     */
    val category: String = "عمومی",
    /**
     * 2026-09-19 PHASE 4 — English display name for JSON-driven
     * templates (shown under the Persian name in the picker).
     * Display-only; defaults to "" so every existing construction
     * and saved blob keeps compiling and decoding.
     */
    val nameEn: String = "",
    /**
     * 2026-09-19 PHASE 6 — progress-bar spec (null = off). Copied
     * onto overlays by [withTemplate] and honored by the shared
     * renderer in preview and export alike.
     */
    val progressBar: ProgressBarSpec? = null,
    /**
     * 2026-09-19 PHASE 7 — gradient angle in CSS degrees (0 = to
     * top, 90 = to right). Null = legacy horizontal (90°).
     * Copied onto overlays by [withTemplate].
     */
    val gradientAngleDeg: Float? = null
) {

    init {
        require(name.isNotBlank()) { "template name must not be blank" }
        require(activeWordScale in 0.8f..2.5f) {
            "activeWordScale must be in 0.8..2.5 (got $activeWordScale)"
        }
        require(animationDurationMs in 60..1500) {
            "animationDurationMs must be in 60..1500 (got $animationDurationMs)"
        }
    }

    // ---- JSON ----

    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("builtIn", builtIn)
        o.put("style", com.aicreatorstudio.app.db.EditorStateJson.subtitleStyleToJson(style))
        o.put("activeWordColorArgb", activeWordColorArgb)
        o.put("activeWordScale", activeWordScale.toDouble())
        o.put("activeWordBold", activeWordBold)
        o.put("activeWordBackgroundArgb", activeWordBackgroundArgb ?: JSONObject.NULL)
        o.put("animationIn", animationIn.name)
        o.put("animationOut", animationOut.name)
        o.put("animationDurationMs", animationDurationMs)
        o.put("wordRevealMode", wordRevealMode.name)
        o.put("motionKind", motionKind.name)
        o.put("backgroundKind", backgroundKind.name)
        o.put("activeWordGlowRadiusDp", activeWordGlowRadiusDp.toDouble())
        o.put("activeWordUnderline", activeWordUnderline)
        o.put("accentColorArgb", accentColorArgb)
        o.put("gradientAngleDeg", gradientAngleDeg?.toDouble() ?: JSONObject.NULL)
        o.put("category", category)
        o.put("nameEn", nameEn)
        o.put("progressBar", progressBar?.let { bar ->
            JSONObject().apply {
                put("colorArgb", bar.colorArgb)
                put("trackArgb", bar.trackArgb)
                put("heightRatio", bar.heightRatio.toDouble())
                put("directionAware", bar.directionAware)
                put("shape", bar.shape.name)
            }
        } ?: JSONObject.NULL)
        o.put("easingIn", easingIn ?: JSONObject.NULL)
        o.put("easingOut", easingOut ?: JSONObject.NULL)
        o.put("animationDurationMsOut", animationDurationMsOut?.let { it.toDouble() } ?: JSONObject.NULL)
        return o.toString()
    }

    companion object {
        fun fromJson(json: String): SubtitleTemplate? = runCatching {
            val o = JSONObject(json)
            SubtitleTemplate(
                id = o.optString("id") ?: return@runCatching null,
                name = o.getString("name"),
                builtIn = o.optBoolean("builtIn", false),
                style = com.aicreatorstudio.app.db.EditorStateJson.subtitleStyleFromJson(
                    o.optJSONObject("style")
                ) ?: SubtitleStyle.Default,
                activeWordColorArgb = o.optInt(
                    "activeWordColorArgb", 0xFFFFD60A.toInt()
                ),
                activeWordScale = o.optDouble("activeWordScale", 1.0).toFloat(),
                activeWordBold = o.optBoolean("activeWordBold", false),
                activeWordBackgroundArgb =
                    if (o.isNull("activeWordBackgroundArgb")) null
                    else o.optInt("activeWordBackgroundArgb"),
                animationIn = parseAnim(o.optString("animationIn", "FADE_IN")),
                animationOut = parseAnim(o.optString("animationOut", "NONE")),
                animationDurationMs = o.optInt("animationDurationMs", 260),
                wordRevealMode = runCatching {
                    WordRevealMode.valueOf(o.getString("wordRevealMode"))
                }.getOrDefault(WordRevealMode.ALL_AT_ONCE),
                motionKind = runCatching {
                    WordMotionKind.valueOf(o.getString("motionKind"))
                }.getOrDefault(WordMotionKind.NONE),
                backgroundKind = runCatching {
                    CaptionBackgroundKind.valueOf(o.getString("backgroundKind"))
                }.getOrDefault(CaptionBackgroundKind.NONE),
                activeWordGlowRadiusDp = o.optDouble("activeWordGlowRadiusDp", 0.0).toFloat(),
                activeWordUnderline = o.optBoolean("activeWordUnderline", false),
                accentColorArgb = o.optInt("accentColorArgb", 0xFF00E5FF.toInt()),
                gradientAngleDeg = if (o.isNull("gradientAngleDeg")) null
                else o.optDouble("gradientAngleDeg").toFloat(),
                category = o.optString("category", "عمومی").takeIf { it.isNotBlank() } ?: "عمومی",
                nameEn = o.optString("nameEn", ""),
                progressBar = o.optJSONObject("progressBar")?.let { b ->
                    ProgressBarSpec(
                        colorArgb = b.optInt("colorArgb", 0xFFFF7A00.toInt()),
                        trackArgb = b.optInt("trackArgb", 0x33FFFFFF),
                        heightRatio = b.optDouble("heightRatio", 0.12).toFloat(),
                        directionAware = b.optBoolean("directionAware", true),
                        // 2026-09-24 — absent in legacy JSON → STRAIGHT.
                        shape = runCatching {
                            ProgressBarShape.valueOf(
                                b.optString("shape", ProgressBarShape.STRAIGHT.name)
                            )
                        }.getOrDefault(ProgressBarShape.STRAIGHT)
                    )
                },
                easingIn = if (o.isNull("easingIn")) null
                else o.optString("easingIn").takeIf { it.isNotBlank() },
                easingOut = if (o.isNull("easingOut")) null
                else o.optString("easingOut").takeIf { it.isNotBlank() },
                animationDurationMsOut = if (o.isNull("animationDurationMsOut")) null
                else o.optInt("animationDurationMsOut", 260)
            )
        }.getOrNull()

        private fun parseAnim(name: String): TextOverlayAnimation =
            runCatching { TextOverlayAnimation.valueOf(name) }
                .getOrDefault(TextOverlayAnimation.NONE)
    }
}

/**
 * How the caption's words appear across its time window.
 *
 *  - ALL_AT_ONCE — the whole caption shows immediately (classic).
 *  - WORD_BY_WORD — words appear one by one, evenly spread across
 *    the window (Reels/TikTok style) when no per-word timings exist;
 *    with real whisper timings each word appears at its own time.
 *  - HIGHLIGHT — all words visible, the spoken one is highlighted.
 *  - KARAOKE — all words visible, spoken words progressively change
 *    color (smooth, timing-exact when timings exist).
 */
enum class WordRevealMode(val displayName: String) {
    ALL_AT_ONCE("یکجا"),
    WORD_BY_WORD("کلمه به کلمه"),
    HIGHLIGHT("هایلایت"),
    KARAOKE("کارائوکه")
}

/**
 * 2026-09-24 — how the accent line under the caption is drawn.
 *
 *  - STRAIGHT — the original thin rounded bar.
 *  - WAVE — the same bar geometry and the same timing/RTL
 *    behaviour, drawn as a travelling sine so the accent reads as a
 *    soft wave instead of a ruler. Only the path differs; fill
 *    fraction, direction awareness and placement are shared.
 */
enum class ProgressBarShape(val displayName: String) {
    STRAIGHT("خط ساده"),
    WAVE("موج")
}

/**
 * 2026-09-19 PHASE 6 — direction-aware progress bar spec: a thin
 * rounded bar growing UNDER the whole caption, synced to the real
 * word timings (first word start → last word end). RTL captions
 * fill right-to-left, LTR left-to-right ([directionAware]).
 * Null = off. Survives JSON + project save/load.
 *
 * 2026-09-24 — [shape] selects a straight bar or a travelling wave;
 * absent in legacy JSON/blobs → [ProgressBarShape.STRAIGHT], so every
 * saved project keeps its exact previous look.
 */
data class ProgressBarSpec(
    /** Fill color (already includes its alpha). */
    val colorArgb: Int,
    /** Track color underneath (already includes its alpha). */
    val trackArgb: Int,
    /** Bar height as a fraction of the resolved font size. */
    val heightRatio: Float = 0.12f,
    /** Fill from the reading edge (RTL-aware) when true. */
    val directionAware: Boolean = true,
    /** Straight ruler or travelling wave. */
    val shape: ProgressBarShape = ProgressBarShape.STRAIGHT
)

/**
 * 2026-09-11 — apply [template] to a [TextOverlay] in one shot:
 * swaps the style bundle, active-word fields, animations and reveal
 * mode. Used by the Subtitle Studio for "apply to ALL generated
 * subtitles" and by the auto-generation commit path.
 *
 * 2026-09-14 — also carries the viral motion layer (motionKind,
 * backgroundKind, glow, underline, accent) so preview + export
 * honor the full preset.
 *
 * 2026-09-15 POSITION FIX — the template's positionX/Y now flows
 * through too, converted to the renderer's drag-delta contract
 * (see [templatePositionAsAnchorDelta]): `position` is an OFFSET
 * FROM THE STYLE ANCHOR where (0.5, 0.5) means "no offset".
 * Previously `withTemplate` never touched `position`, so the
 * Studio's X/Y only worked for the manual-add path.
 */
fun TextOverlay.withTemplate(template: SubtitleTemplate): TextOverlay = copy(
    subtitleStyle = template.style,
    activeWordColorArgb = template.activeWordColorArgb,
    activeWordScale = template.activeWordScale,
    // 2026-09-19 APPLY FIX — these two template flags were silently
    // dropped here, so the active-word pill ("نوار"/REELS) and the
    // template's bold choice never reached the segments (and neither
    // renderer could honor them).
    activeWordBold = template.activeWordBold,
    activeWordBackgroundArgb = template.activeWordBackgroundArgb,
    // 2026-09-19 PHASE 6 — progress-bar spec rides along.
    progressBar = template.progressBar,
    templateId = template.id,
    textAnimation = template.animationIn,
    textAnimationOut = template.animationOut,
    wordRevealMode = template.wordRevealMode,
    karaokeEnabled = template.wordRevealMode != WordRevealMode.ALL_AT_ONCE,
    motionKind = template.motionKind,
    backgroundKind = template.backgroundKind,
    activeWordGlowRadiusDp = template.activeWordGlowRadiusDp,
    activeWordUnderline = template.activeWordUnderline,
    accentColorArgb = template.accentColorArgb,
    // 2026-09-19 PHASE 7 — gradient angle rides along.
    gradientAngleDeg = template.gradientAngleDeg,
    // PHASE 8 — easing presets ride along.
    easingIn = template.easingIn,
    easingOut = template.easingOut,
    animationDurationMsOut = template.animationDurationMsOut,
    // Style-driven size keeps the selection chrome in lock-step
    // (boundsNormalized reads fontSizeSp).
    fontSizeSp = template.style.fontSizeSp,
    fontFamily = template.style.fontFamily,
    // Absolute template position re-expressed as the anchor delta.
    position = templatePositionAsAnchorDelta(template.style)
)

/**
 * 2026-09-15 POSITION UNIFICATION — convert a [SubtitleStyle]'s
 * ABSOLUTE normalized position (positionX/Y against the canvas,
 * 0..1) into the renderer's ANCHOR-DELTA contract (Offset where
 * 0.5 = "no offset from the style anchor"). The preview
 * (EditorScreen, 2026-09-14 POSITION UNIFICATION) and the export
 * (SubtitleBurnIn) both compute:
 *
 *   deltaFraction = (position.x - 0.5f) * 2f * size * 0.5f
 *
 * so a position.x of 0.5 + d moves the caption d of the full
 * canvas dimension away from the anchor. Mapping an absolute
 * target t against an anchor a therefore needs:
 *
 *   position = 0.5 + (t - a)
 *
 * The vertical anchors below match SubtitleBurnIn's exported
 * fractions (TOP 0.12 / CENTER 0.5 / BOTTOM 0.88), which is the
 * authoritative placement the burned-in export shows; the
 * preview's Alignment-based band visually matches on standard
 * aspect ratios.
 */
fun templatePositionAsAnchorDelta(style: SubtitleStyle): androidx.compose.ui.geometry.Offset {
    val anchorY = when (style.verticalAlignment) {
        SubtitleAlignmentV.TOP -> 0.12f
        SubtitleAlignmentV.CENTER -> 0.5f
        SubtitleAlignmentV.BOTTOM -> 0.88f
    }
    val anchorX = when (style.horizontalAlignment) {
        SubtitleAlignmentH.LEFT -> 0.12f
        SubtitleAlignmentH.CENTER -> 0.5f
        SubtitleAlignmentH.RIGHT -> 0.88f
    }
    return androidx.compose.ui.geometry.Offset(
        (0.5f + (style.positionX - anchorX)).coerceIn(0f, 1f),
        (0.5f + (style.positionY - anchorY)).coerceIn(0f, 1f)
    )
}
