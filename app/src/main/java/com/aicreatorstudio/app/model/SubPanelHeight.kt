package com.aicreatorstudio.app.model

/**
 * Height policy for the editor sub-panel sheet (`SubPanelRow` in
 * `EditorScreen.kt`).
 *
 * ## Why this is not a single fraction any more
 *
 * Until this round the sheet took a flat
 * `EditorLayoutSpec.SubPanelHeightFraction = 0.5f` of whatever the editor
 * `Column` had left. That one number cannot satisfy both halves of the
 * requirement at once:
 *
 * - a *slider* panel (Speed, Volume, Adjust…) needs ~170–210dp — half the
 *   screen is a mostly-empty sheet, and
 * - a *gallery* panel (Templates, Fonts, Presets…) genuinely wants more room,
 * - while the video preview above it must **never** be squashed below a
 *   comfortable minimum.
 *
 * On a short screen 50% starved the preview; on a tall one it wasted space.
 * So the sheet is now sized from the panel identity *and* the space that is
 * actually available.
 *
 * ## The unit
 *
 * Everything here is a plain `Float` measured in **density-independent
 * pixels** — never `Dp`, never `Color`. `unitTests.isReturnDefaultValues`
 * makes `android.*` inert in JVM tests, so a `Dp`-typed API would silently
 * test against `0f` and pass for the wrong reason. Keeping the arithmetic in
 * plain floats is what makes [resolveHeightDp] honestly testable.
 *
 * ## What "available" means
 *
 * [resolveHeightDp]'s `availableDp` is the space left in the editor `Column`
 * *after* the top bar and the playback strip — i.e. exactly the space the
 * weighted video preview competes for. `SubPanelRow` reads it at runtime from
 * a `BoxWithConstraints`, so the sheet is measured against the real device
 * instead of a hard-coded screen height.
 */
object SubPanelHeight {

    /**
     * The video preview may never be squeezed below this. `EditorScreen`
     * gives the preview `weight(1f, fill = true)`, so every dp handed to the
     * sheet is taken straight from the preview — this constant is the floor
     * that stops the sheet from eating it.
     */
    const val PreviewMinDp: Float = 180f

    // ── Slider / control panels ────────────────────────────────
    // Target band requested: ~170dp–210dp, and no more than ~28% of the
    // screen. 0.30 of the post-chrome space lands at 26.6% of a 640dp screen
    // and 26.4% of a 900dp one — inside the band on both.
    const val CompactFraction: Float = 0.30f
    const val CompactMinDp: Float = 170f
    const val CompactMaxDp: Float = 240f

    // ── Gallery / list panels ──────────────────────────────────
    // Must stay under ~38% of screen height. 0.40 of the post-chrome space
    // lands at 33.3% of a 640dp screen and 35.2% of a 900dp one.
    const val GalleryFraction: Float = 0.40f
    const val GalleryMinDp: Float = 200f
    const val GalleryMaxDp: Float = 340f

    /** How a panel's content behaves, which is what decides its height. */
    enum class Kind {
        /** A handful of sliders / chips — wants to stay short. */
        COMPACT,

        /** A scrolling gallery of cards — needs vertical room to browse. */
        GALLERY
    }

    /**
     * Classifies a panel by the shape of its content, not by its name.
     *
     * COMPACT is the panels that are essentially "a few sliders and a couple
     * of rows": trim handles, speed presets, volume, filter thumbs, the
     * colour-grading sliders, opacity, denoise strength, transform, crop
     * presets, background fill and the progress-bar controls (one toggle,
     * two position chips, three sliders, one swatch row).
     *
     * Everything else is GALLERY. That is deliberately the **default**: a
     * panel added later without being classified here gets the roomier
     * treatment, which fails safe — a too-tall slider sheet is untidy, a
     * too-short gallery is unusable because its cards cannot be reached.
     */
    fun kindFor(panel: ToolPanel): Kind = when (panel) {
        ToolPanel.TRIM,
        ToolPanel.SPEED,
        ToolPanel.VOLUME,
        ToolPanel.FILTERS,
        ToolPanel.ADJUST,
        ToolPanel.OPACITY,
        ToolPanel.TRANSFORM,
        ToolPanel.CROP,
        ToolPanel.BACKGROUND,
        ToolPanel.AI_VOICE_CLEAN,
        ToolPanel.PROGRESS_BAR -> Kind.COMPACT

        ToolPanel.TEXT,
        ToolPanel.PARAGRAPH,
        ToolPanel.SUBTITLES,
        ToolPanel.AUDIO,
        ToolPanel.EFFECTS,
        ToolPanel.OVERLAY,
        ToolPanel.ANIMATIONS,
        ToolPanel.TRANSITIONS,
        ToolPanel.ASPECT_RATIO,
        ToolPanel.UPSCALER,
        ToolPanel.AI_MATTING,
        ToolPanel.AI_AUTOFRAME,
        ToolPanel.AI_MOTION_TRACK,
        ToolPanel.AI_OCR,
        ToolPanel.AI_SILENCE_CUT,
        ToolPanel.AI_VOICE_ENHANCE,
        ToolPanel.AI_REFRAME,
        ToolPanel.AI_BEAT_SYNC,
        ToolPanel.AI_HIGHLIGHTS,
        ToolPanel.BEATS,
        ToolPanel.SUBTITLE_STUDIO,
        // 2026-10-13 — the voice changer is a two-row grid of preset cards
        // plus a reset button, i.e. a gallery of cards, not a slider sheet.
        // Classifying it here (rather than adding it to the COMPACT list)
        // is also what keeps `SubPanelHeightTest`'s exact COMPACT count at
        // 11 — the allow-list is asserted by size on purpose.
        ToolPanel.VOICE_CHANGER -> Kind.GALLERY

        // 2026-10-13 — «ماسک» is a row of five shape cards plus up to two
        // sliders and a reset button, so it is a gallery too. Same
        // reasoning as VOICE_CHANGER: GALLERY keeps the asserted COMPACT
        // count at 11.
        //
        // Deliberately its OWN branch rather than one more name in the list
        // above. `VoiceEffectWiringTest` pins the literal string
        // `ToolPanel.VOICE_CHANGER -> Kind.GALLERY`; appending here would
        // move the `->` off VOICE_CHANGER and fail a guard that is still
        // telling the truth. A separate branch keeps both assertions exact
        // and changes no behaviour.
        ToolPanel.OVERLAY_MASK -> Kind.GALLERY

        // 2026-10-13 — «پرده سبز» is a row of colour presets plus three
        // sliders and a reset button, so it is a gallery too, and for the
        // same reason: GALLERY keeps `SubPanelHeightTest`'s asserted
        // COMPACT count at 11.
        //
        // ⚠️ Its own branch, NOT appended to the VOICE_CHANGER group above.
        // `OverlayMaskWiringTest` pins the literal string
        // `ToolPanel.OVERLAY_MASK -> Kind.GALLERY`, and moving the `->`
        // would fail a guard that is still telling the truth.
        ToolPanel.CHROMA_KEY -> Kind.GALLERY

        // 2026-10-21 — «نریشن» is a hero record button with a live level
        // meter and a timer, plus the confirm/cancel row for the captured
        // take. It is a gallery, not a slider sheet — and it must NOT go
        // on the COMPACT allow-list, because that list's exact size is
        // asserted by `SubPanelHeightTest` (11 entries) and a record
        // button needs the room.
        ToolPanel.VOICEOVER -> Kind.GALLERY
    }

    /** Share of the post-chrome space the panel would like. */
    fun fractionFor(panel: ToolPanel): Float = when (kindFor(panel)) {
        Kind.COMPACT -> CompactFraction
        Kind.GALLERY -> GalleryFraction
    }

    /** Lower bound for the panel, in dp. */
    fun minHeightDpFor(panel: ToolPanel): Float = when (kindFor(panel)) {
        Kind.COMPACT -> CompactMinDp
        Kind.GALLERY -> GalleryMinDp
    }

    /** Upper bound for the panel, in dp — stops a tall screen giving it a silly size. */
    fun maxHeightDpFor(panel: ToolPanel): Float = when (kindFor(panel)) {
        Kind.COMPACT -> CompactMaxDp
        Kind.GALLERY -> GalleryMaxDp
    }

    /**
     * Resolves the sheet's height in dp.
     *
     * Three clamps, in this order:
     *
     * 1. the panel's preferred share of [availableDp],
     * 2. pulled into that kind's `[min, max]` band so a slider sheet never
     *    collapses and a gallery never grows without limit,
     * 3. capped by `availableDp - previewMinDp` so [previewMinDp] of preview
     *    is **always** left over.
     *
     * Step 3 is applied last and wins on purpose: on a very short screen the
     * panel may end up below its own minimum, which is correct — a cramped
     * panel is recoverable (it scrolls), a preview with no height is not.
     *
     * @return a non-negative height in dp. Returns `0f` for degenerate input
     *   rather than inventing a size.
     */
    fun resolveHeightDp(
        availableDp: Float,
        panel: ToolPanel,
        previewMinDp: Float = PreviewMinDp
    ): Float {
        if (!availableDp.isFinite() || availableDp <= 0f) return 0f
        if (!previewMinDp.isFinite() || previewMinDp < 0f) return 0f

        val minDp = minHeightDpFor(panel)
        val maxDp = maxHeightDpFor(panel)
        val wanted = (availableDp * fractionFor(panel)).coerceIn(minDp, maxDp)

        // Space the preview is allowed to keep. Never negative.
        val previewBudget = (availableDp - previewMinDp).coerceAtLeast(0f)

        return wanted.coerceAtMost(previewBudget).coerceAtLeast(0f)
    }
}
