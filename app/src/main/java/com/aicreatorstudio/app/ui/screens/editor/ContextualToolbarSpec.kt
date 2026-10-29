package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Masks
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tonality
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.ui.graphics.vector.ImageVector
import com.aicreatorstudio.app.model.EditorContext
import com.aicreatorstudio.app.model.ToolPanel

/**
 * One entry in the contextual toolbar.
 *
 * The dock renders a flat row of [ContextChip]s; tapping one either fires an
 * immediate action, opens a panel directly, or opens a [Group] sheet listing
 * sub-tools. See `docs/UX_IA_AUDIT_AND_PROPOSAL.md` §4.
 */
sealed interface ContextChip {
    /** Stable key for `LazyRow` item identity and for tests. */
    val id: String

    /** Persian label shown under the icon. */
    val label: String

    val icon: ImageVector

    /**
     * Fires immediately through the existing `onAction(actionId)` channel.
     * Destructive actions are rendered icon-only and right-aligned.
     */
    data class Action(
        override val id: String,
        override val label: String,
        override val icon: ImageVector,
        val actionId: String,
        val destructive: Boolean = false
    ) : ContextChip

    /** Opens a single panel — no intermediate sheet. */
    data class Panel(
        override val id: String,
        override val label: String,
        override val icon: ImageVector,
        val toolPanel: ToolPanel
    ) : ContextChip

    /**
     * Opens a sheet listing [members].
     *
     * Members are always [Panel] or [Action] — a [Group] inside a [Group] is
     * not rendered and is asserted against in `ContextualToolbarSpecTest`,
     * so the toolbar can never nest more than one level deep.
     */
    data class Group(
        override val id: String,
        override val label: String,
        override val icon: ImageVector,
        val sheetTitle: String,
        val members: List<ContextChip>
    ) : ContextChip
}

/**
 * Declarative description of the contextual toolbar — the single place that
 * decides which tools are visible for a given [EditorContext].
 *
 * Keeping this as pure data (no Compose, no ViewModel) means the whole IA can
 * be asserted in a JVM unit test: that every `ToolPanel` is reachable, that no
 * context renders a duplicate chip, and that no chip points at a panel the
 * `SubPanelContent` dispatcher does not handle.
 *
 * ## Deviation from the approved IA: the audio toolbar
 *
 * The approved plan specified `Beat Detection / Volume & Fade / Split &
 * Delete` for [EditorContext.AUDIO_SELECTED]. Auditing the implementation
 * showed only one of those three groups exists:
 *
 * - **Beat Detection** — real (`ToolPanel.BEATS`, `BeatPanel`, beat markers on
 *   the timeline). Shipped.
 * - **Volume & mute** — real for an audio object (`VolumeSheet` reads
 *   `selectedAudioClipId`; `updateAudioClipVolume` / `updateAudioClipMute`).
 *   Shipped.
 * - **Fade** — `AudioClip.fadeInMs` / `fadeOutMs` exist on the model and are
 *   persisted, but **nothing writes them** and `project.audioClips` never
 *   reaches `RenderGraphBuilder`, so a fade control would be write-only.
 *   Omitted rather than shipped dead (bug class 1).
 * - **Split / Delete** — both were video-only: `splitClip` / `deleteClip`
 *   operate on `project.clips` (`VideoClip`). Closed on 2026-10-19 (split) and
 *   2026-10-20 (delete).
 *
 * 2026-10-19 — `splitClip` now cuts the paired audio object together with its
 * video clip, and `EditorViewModel.splitAudioClip` splits a standalone audio
 * object, so «برش» is a real action for [EditorContext.AUDIO_SELECTED] and is
 * shipped as [SplitAudioAction].
 *
 * 2026-10-20 — **Delete** is closed as well. `EditorViewModel.deleteAudioClip`
 * removes an imported music / sfx object outright. The soundtrack of a *video*
 * clip is refused with a notice instead: that row is only a view of the clip's
 * own audio, `seedVideoAudioClipsIfNeeded` rebuilds it on the next load, and
 * the exporter re-extracts it from the video source — so muting the clip is the
 * supported way to silence it. The audio context now ships [DeleteAudioAction]
 * as a trailing action; the row is no longer empty.
 *
 * This is the same "never ship a chip that eats a tap" rule the audit applied
 * to the four `locked` AI tools. The fade control remains omitted for the same
 * reason as before (nothing writes `AudioClip.fadeInMs`/`fadeOutMs`).
 */
object ContextualToolbarSpec {

    // ── Root: nothing selected ──────────────────────────────

    /**
     * «قالب‌ها» — the Persian template gallery.
     *
     * Q5: routes to the Subtitle Studio template catalog today. The chip id is
     * deliberately `"templates"` rather than `"subtitle_studio"` so a future
     * project-template gallery can take over this entry point without the
     * toolbar or its tests changing.
     */
    val Templates: ContextChip = ContextChip.Panel(
        id = "templates",
        label = "قالب‌ها",
        icon = Icons.Filled.ClosedCaption,
        toolPanel = ToolPanel.SUBTITLE_STUDIO
    )

    /**
     * 2026-10-10 SUBTITLE ROUTING FIX — «زیرنویس» is a PROJECT-level tool,
     * not a clip-scoped one, so the chip lives in every context's row.
     *
     * It previously existed only in [RootChips]. Two states then hid it:
     * `addClipFromUri` auto-selects the new clip (→ CLIP_SELECTED, whose
     * row is the general clip tools), and tapping a subtitle on the canvas
     * selects it (→ TEXT_SELECTED). Users chasing "Subtitles" saw general
     * editing tools instead and had to manually deselect on the timeline
     * first — the "works only after a manual timeline clip tap" defect.
     * One shared constant also keeps the id/label/panel mapping identical
     * across rows, so the open panel always has a matching selected tile.
     */
    val SubtitlesChip: ContextChip.Panel = ContextChip.Panel(
        id = "subs_ai",
        label = "زیرنویس",
        icon = Icons.Filled.AutoAwesome,
        toolPanel = ToolPanel.SUBTITLES
    )

    /**
     * 2026-10-13 — «پیشرفت»: the project's video progress bar (timeline
     * indicator / story timer).
     *
     * The label is shortened from the panel's full name «نوار پیشرفت»
     * because the root row's tiles fit about eight Persian characters at
     * 11sp and a longer label would ellipsize mid-word
     * (`ContextualToolbarSpecTest.top-level chip labels are short enough
     * not to be truncated`). The full name is the sub-panel's title
     * (`ToolPanel.title()`).
     *
     * Declared as a named constant rather than an inline entry so the id /
     * label / panel mapping has exactly one definition, the same reason
     * [SubtitlesChip] is a constant.
     */
    val ProgressBarChip: ContextChip.Panel = ContextChip.Panel(
        id = "progress_bar",
        label = "پیشرفت",
        icon = Icons.Filled.LinearScale,
        toolPanel = ToolPanel.PROGRESS_BAR
    )

    /**
     * 2026-10-13 — «وویس»: the offline voice changer (pitch + timbre
     * presets — chipmunk, deep, robot, telephone, echo).
     *
     * The label is «وویس» (4 chars) rather than «تغییر صدا» (9), which is
     * over the root row's ~8-Persian-character budget, and rather than
     * «صدا» — that one is already the Volume chip's label in the very same
     * audio row, and two tiles reading «صدا» with different meanings is
     * exactly the ambiguity the 2026-09-28 IA audit set out to remove. The
     * full name «تغییر صدا» is the sub-panel's title (`ToolPanel.title()`).
     *
     * Declared as a named constant, not an inline entry, because it appears
     * in **two** chip lists — the audio toolbar and the clip toolbar. One
     * definition means one id, so the selected-tile highlight (which
     * compares ids, not panels) can never desync between the two rows.
     */
    val VoiceChangerChip: ContextChip.Panel = ContextChip.Panel(
        id = "voice_changer",
        label = "وویس",
        icon = Icons.Filled.RecordVoiceOver,
        toolPanel = ToolPanel.VOICE_CHANGER
    )

    /**
     * 2026-10-21 — «نریشن» (voiceover): record a narration over the
     * video.
     *
     * Lives in the **audio** toolbar only. Unlike the voice changer —
     * which addresses both a video clip's own audio and a standalone
     * audio object — a voiceover is always a *new* object, never an
     * edit of an existing one, so it needs no clip-scoped entry and
     * giving it one would imply it applies to the selected clip.
     *
     * The label is five characters («نریشن»), well inside the eight
     * that fit a 60dp tile. «وویس» was the obvious candidate and is
     * already taken by the voice changer — two tiles with the same word
     * and different meanings is exactly the ambiguity the 2026-09-28 IA
     * audit removed.
     */
    val VoiceoverChip: ContextChip.Panel = ContextChip.Panel(
        id = "voiceover",
        label = "نریشن",
        icon = Icons.Filled.Mic,
        toolPanel = ToolPanel.VOICEOVER
    )

    /**
     * 2026-10-13 — «ماسک»: the shape mask for the selected picture-in-picture
     * overlay.
     *
     * Lives in the **clip** toolbar rather than the root row because a mask
     * belongs to one overlay, and overlays belong to a clip. It sits next to
     * «شفافیت» and «جابجایی» because all three shape a layer's on-canvas
     * presentation.
     *
     * The panel itself resolves its target from
     * `EditorUiState.selectedOverlayId` and shows a "select an overlay"
     * state when there is none — so the chip is always reachable, and the
     * panel explains itself rather than the chip disappearing and leaving
     * the user wondering where the tool went.
     */
    val OverlayMaskChip: ContextChip.Panel = ContextChip.Panel(
        id = "overlay_mask",
        label = "ماسک",
        icon = Icons.Filled.Masks,
        toolPanel = ToolPanel.OVERLAY_MASK
    )

    /**
     * 2026-10-13 — «پرده سبز»: chroma key (green/blue screen removal) for the
     * selected picture-in-picture overlay.
     *
     * The twin of [OverlayMaskChip] and for the same two reasons it sits in
     * the **clip** toolbar next to it: a key belongs to one overlay, and a
     * root-level entry point would imply it applies to the whole project the
     * way «نسبت» / «پس‌زمینه» do. Mask and key also compose at render time
     * (the key runs *before* the mask clip), so keeping the two chips
     * adjacent is the user's hint that they are meant to be used together.
     *
     * The label is exactly eight characters — the pinned maximum for a 60dp
     * tile at 11sp (`ContextualToolbarSpecTest` asserts `<= 8`), so it is not
     * shortened any further; the sheet title is the same words.
     *
     * The panel resolves its target from `EditorUiState.selectedOverlayId`
     * and shows a "select an overlay" state when there is none, so the chip
     * is always reachable and the panel explains itself.
     */
    val ChromaKeyChip: ContextChip.Panel = ContextChip.Panel(
        id = "chroma_key",
        label = "پرده سبز",
        icon = Icons.Filled.Colorize,
        toolPanel = ToolPanel.CHROMA_KEY
    )

    /**
     * 2026-10-13 — «استخراج»: extract the audio of any picked video into
     * an M4A and add it as an independent audio clip (استخراج صدا از
     * ویدیو).
     *
     * An [ContextChip.Action] rather than a panel chip because the tool
     * has no persistent UI of its own — tapping it opens the system
     * video picker and the whole flow finishes in a 1-2 second
     * non-blocking loading state with a confirmation banner.
     *
     * The label is «استخراج» (6 chars, inside the 8-character tile
     * budget); «استخراج صدا» (11) would ellipsize mid-word. The icon is
     * [Icons.Filled.AudioFile] — deliberately NOT `ContentCut`, which
     * already means «برش» (split) and «برش سکوت» (silence cut) in this
     * toolbar: two scissors with different meanings is exactly the
     * ambiguity the 2026-09-28 IA audit removed.
     */
    val ExtractAudioChip: ContextChip.Action = ContextChip.Action(
        id = "audio_extract",
        label = "استخراج",
        icon = Icons.Filled.AudioFile,
        actionId = "extract_audio"
    )

    /**
     * Chip labels are deliberately **short** (3–5 Persian characters). The
     * toolbar is a horizontally scrollable row of fixed-width tiles
     * (CapCut-style, 2026-10-04), so labels never collide — but a long label
     * such as «فیلتر و تنظیم» would still waste tile width in the root row.
     * The descriptive name lives in [ContextChip.Group.sheetTitle] and in
     * each sheet member's label, where there is room for it.
     */
    /**
     * 2026-10-05 — fully FLAT root toolbar.
     *
     * The 2026-10-04 relayout kept four group chips («متن / صدا / کادر /
     * لایه») that each opened a sheet of wide cards styled differently from
     * the main row. That mixed interaction (some buttons open a panel, some
     * open a second grid) is what read as "messy". The root row is now flat
     * like CapCut's: one-tap buttons, no sheets. Clip-scoped tools (crop,
     * transform, opacity…) live only in the clip toolbar; paragraph layout
     * lives only in the text toolbar.
     *
     * 2026-10-13 — «پیشرفت» joined as the eighth chip. It is a
     * **project-level** setting like «نسبت» and «پس‌زمینه», so the root row
     * is its only correct home; burying it in the clip toolbar would imply
     * it applies to one clip, which it does not.
     */
    val RootChips: List<ContextChip> = listOf(
        Templates,
        SubtitlesChip,
        ContextChip.Panel("add_text", "متن", Icons.Filled.TextFields, ToolPanel.TEXT),
        ContextChip.Panel("add_music", "موزیک", Icons.Filled.MusicNote, ToolPanel.AUDIO),
        ContextChip.Panel("aspect", "نسبت", Icons.Filled.AspectRatio, ToolPanel.ASPECT_RATIO),
        ContextChip.Panel("background", "پس‌زمینه", Icons.Filled.Wallpaper, ToolPanel.BACKGROUND),
        ProgressBarChip,
        ContextChip.Panel("overlay", "لایه", Icons.Filled.Layers, ToolPanel.OVERLAY)
    )

    // ── Clip selected ───────────────────────────────────────

    /**
     * 2026-10-05 — fully FLAT clip toolbar, CapCut-style.
     *
     * Every frequent edit action is its own one-tap button: Split, Speed,
     * Volume, Filter, Adjust, Effect, Opacity, Animation, Trim, Crop,
     * Transform, Music. The former «صدا / فیلتر / تریم» group sheets are
     * gone — mixed tap behaviours were the "messy" the users reported. The
     * single remaining group is «هوشمند» (the on-device AI collection),
     * which genuinely is a sub-gallery; Voice Clean and Silence Cut moved
     * into it because they ARE AI tools.
     */
    val ClipChips: List<ContextChip> = listOf(
        ContextChip.Action(
            id = "clip_split",
            label = "برش",
            icon = Icons.Filled.Splitscreen,
            actionId = "split"
        ),
        ContextChip.Panel(
            id = "clip_speed",
            label = "سرعت",
            icon = Icons.Filled.Speed,
            toolPanel = ToolPanel.SPEED
        ),
        ContextChip.Panel(
            id = "clip_volume",
            label = "ولوم",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            toolPanel = ToolPanel.VOLUME
        ),
        // 2026-10-13 — the voice changer sits next to Volume because both
        // act on *this clip's own audio*. A video clip's soundtrack is
        // edited from this row (there is no separate audio-object selection
        // for it), so without this chip the effect would be unreachable for
        // video clips — the audio toolbar only appears for a standalone
        // audio object (`EditorContext.AUDIO_SELECTED`).
        VoiceChangerChip,
        ContextChip.Panel(
            id = "clip_filters",
            label = "فیلتر",
            icon = Icons.Filled.MovieFilter,
            toolPanel = ToolPanel.FILTERS
        ),
        ContextChip.Panel(
            id = "clip_adjust",
            label = "تنظیم",
            icon = Icons.Filled.Tune,
            toolPanel = ToolPanel.ADJUST
        ),
        ContextChip.Panel(
            id = "clip_effects",
            label = "افکت",
            icon = Icons.Filled.AutoFixHigh,
            toolPanel = ToolPanel.EFFECTS
        ),
        ContextChip.Panel(
            id = "clip_opacity",
            label = "شفافیت",
            icon = Icons.Filled.Tonality,
            toolPanel = ToolPanel.OPACITY
        ),
        // 2026-10-13 — the PiP shape mask. Sits with Opacity and Transform
        // because all three describe how a layer is presented on the canvas
        // rather than what its pixels are.
        OverlayMaskChip,
        // 2026-10-13 — «پرده سبز» (chroma key) sits immediately after «ماسک»
        // because the two compose at render time (the key runs before the
        // mask clip) and share the same target. Both act on what a layer's
        // pixels are — the mask carves the shape around them, the key
        // removes the colour behind them.
        ChromaKeyChip,
        ContextChip.Panel(
            id = "clip_animations",
            label = "انیمیشن",
            icon = Icons.Filled.Animation,
            toolPanel = ToolPanel.ANIMATIONS
        ),
        ContextChip.Panel(
            id = "clip_trim",
            label = "تریم",
            icon = Icons.Filled.ContentCut,
            toolPanel = ToolPanel.TRIM
        ),
        ContextChip.Panel(
            id = "clip_crop",
            label = "کراپ",
            icon = Icons.Filled.Crop,
            toolPanel = ToolPanel.CROP
        ),
        ContextChip.Panel(
            id = "clip_transform",
            label = "جابجایی",
            icon = Icons.Filled.OpenWith,
            toolPanel = ToolPanel.TRANSFORM
        ),
        ContextChip.Panel(
            id = "clip_audio",
            label = "موزیک",
            icon = Icons.Filled.MusicNote,
            toolPanel = ToolPanel.AUDIO
        ),
        // The ONE remaining group: the on-device AI tools are a genuine
        // sub-gallery (7 engines), and flattening them would bury the
        // frequent edits under a wall of AI tiles.
        ContextChip.Group(
            id = "clip_ai",
            label = "هوشمند",
            icon = Icons.Filled.AutoAwesome,
            sheetTitle = "ابزارهای هوشمند",
            members = listOf(
                ContextChip.Panel("autoframe", "اتوفریم", Icons.Filled.CropFree, ToolPanel.AI_AUTOFRAME),
                ContextChip.Panel("matting", "حذف پس‌زمینه", Icons.Filled.AutoFixHigh, ToolPanel.AI_MATTING),
                ContextChip.Panel("motion_track", "ردیابی حرکت", Icons.Filled.CenterFocusStrong, ToolPanel.AI_MOTION_TRACK),
                ContextChip.Panel("ocr", "متن‌یابی", Icons.Filled.TextFields, ToolPanel.AI_OCR),
                ContextChip.Panel("voice_clean", "پاک‌سازی صدا", Icons.Filled.GraphicEq, ToolPanel.AI_VOICE_CLEAN),
                ContextChip.Panel("voice_enhance", "بهبود صدا", Icons.Filled.GraphicEq, ToolPanel.AI_VOICE_ENHANCE),
                ContextChip.Panel("silence_cut", "برش سکوت", Icons.Filled.ContentCut, ToolPanel.AI_SILENCE_CUT),
                ContextChip.Panel("reframe", "ری‌فریم هوشمند", Icons.Filled.CropFree, ToolPanel.AI_REFRAME),
                ContextChip.Panel("beat_sync", "سینک ضرب", Icons.Filled.MusicNote, ToolPanel.AI_BEAT_SYNC),
                ContextChip.Panel("highlights", "هایلایت هوشمند", Icons.Filled.AutoAwesome, ToolPanel.AI_HIGHLIGHTS),
                // Upscaler was not named as ready in Q4, so it keeps a
                // "به‌زودی" badge inside the sheet rather than a dead chip.
                ContextChip.Panel("upscale", "افزایش کیفیت", Icons.Filled.AutoAwesome, ToolPanel.UPSCALER)
            )
        ),
        // Project-level subtitle tool — reachable with a clip selected so
        // the auto-selected clip after import never hides the entry point.
        // The conditional Transitions chip is still appended LAST in
        // `chipsFor`, after this row.
        SubtitlesChip
    )

    /**
     * Q7 — Transitions needs a neighbour to join two clips, so it is appended
     * to the clip toolbar only when one exists. Otherwise it stays reachable
     * from Root → Overlay.
     */
    val ConditionalTransitionsChip: ContextChip = ContextChip.Panel(
        id = "clip_transitions",
        label = "ترنزیشن",
        icon = Icons.Filled.SwapHoriz,
        toolPanel = ToolPanel.TRANSITIONS
    )

    // ── Audio object selected ───────────────────────────────

    /**
     * 2026-10-19 — «برش» for the selected audio object.
     *
     * Same actionId (`split`) as the clip chip because the dock's handler is
     * one `when` over action ids; the handler branches on whether an audio
     * object or a clip is selected. A distinct **id** is required: the
     * selected-tile highlight compares chip ids, and reusing `clip_split`
     * across two contexts would tie the audio tile's highlight to the clip
     * row.
     *
     * A paired audio object is cut together with its video clip (see
     * `EditorViewModel.splitAudioClip`), which is why this one button keeps
     * the two lanes in agreement instead of editing them independently.
     */
    val SplitAudioAction: ContextChip.Action = ContextChip.Action(
        id = "audio_split",
        label = "برش",
        icon = Icons.Filled.Splitscreen,
        actionId = "split"
    )

    val AudioChips: List<ContextChip> = listOf(
        // Split leads the row, exactly like the clip toolbar: it is the one
        // destructive-geometry action and users reach for it first.
        SplitAudioAction,
        ContextChip.Panel(
            id = "audio_beats",
            label = "بیت",
            icon = Icons.Filled.GraphicEq,
            toolPanel = ToolPanel.BEATS
        ),
        ContextChip.Panel(
            id = "audio_volume",
            label = "صدا",
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            toolPanel = ToolPanel.VOLUME
        ),
        // Generation can transcribe the selected audio object directly
        // (resolveGenerationTarget prefers it), so the subtitle tool stays
        // one tap away in this context too.
        SubtitlesChip,
        // 2026-10-13 — the voice changer. This row IS the audio tools dock,
        // which is where a user looks for anything that changes how a track
        // sounds.
        VoiceChangerChip,
        // 2026-10-13 — extract audio from a picked video. Also lives in the
        // root Audio panel («موزیک») as a row, because this toolbar is only
        // reachable once an audio object is selected — and a project with no
        // audio yet has nothing to select. The panel row is the entry point
        // that works in both states.
        ExtractAudioChip,
        // 2026-10-21 — «نریشن» (voiceover). Last in the row because it is
        // the only chip that *creates* something rather than editing the
        // selected object.
        VoiceoverChip
    )

    // ── Text / subtitle selected ────────────────────────────

    /**
     * 2026-10-05 — flat. The former «استایل» group sheet is gone; editing a
     * selected text object is three direct taps like CapCut's text sheet.
     */
    val TextChips: List<ContextChip> = listOf(
        ContextChip.Panel(
            id = "text_font",
            label = "ویرایش",
            icon = Icons.Filled.TextFields,
            toolPanel = ToolPanel.TEXT
        ),
        ContextChip.Panel(
            id = "paragraph",
            label = "پاراگراف",
            icon = Icons.AutoMirrored.Filled.Notes,
            toolPanel = ToolPanel.PARAGRAPH
        ),
        ContextChip.Panel(
            id = "style_gallery",
            label = "استایل",
            icon = Icons.Filled.ClosedCaption,
            toolPanel = ToolPanel.SUBTITLE_STUDIO
        ),
        // Tapping a subtitle on the canvas selects it (→ TEXT_SELECTED);
        // without this chip the open AI-Subtitles panel lost its selected
        // tile and the row read as "general text tools replaced my
        // subtitle tool".
        SubtitlesChip
    )

    // ── Trailing icon-only actions ──────────────────────────

    val DuplicateAction: ContextChip = ContextChip.Action(
        id = "dup",
        label = "تکثیر",
        icon = Icons.Filled.ContentCopy,
        actionId = "duplicate"
    )

    val DeleteClipAction: ContextChip = ContextChip.Action(
        id = "del_clip",
        label = "حذف",
        icon = Icons.Filled.Delete,
        actionId = "delete",
        destructive = true
    )

    val DeleteTextAction: ContextChip = ContextChip.Action(
        id = "del_text",
        label = "حذف",
        icon = Icons.Filled.Delete,
        actionId = "delete_text",
        destructive = true
    )

    /**
     * 2026-10-20 — «حذف» for the selected audio object.
     *
     * The audio row could split an object but never remove one:
     * `trailingActionsFor(AUDIO_SELECTED)` returned an empty list, so an
     * imported music track or an extracted soundtrack was undeletable — the
     * only destructive action in the editor that existed for video and not for
     * audio.
     *
     * The **actionId is shared with [DeleteClipAction]** (`"delete"`) because
     * the dock's handler is one `when` over action ids and branches on which
     * object is selected — exactly how [SplitAudioAction] reuses `"split"`.
     * The **id** is distinct (`del_audio`), because the selected-tile highlight
     * compares chip ids.
     */
    val DeleteAudioAction: ContextChip = ContextChip.Action(
        id = "del_audio",
        label = "حذف",
        icon = Icons.Filled.Delete,
        actionId = "delete",
        destructive = true
    )

    // ── Resolution ──────────────────────────────────────────

    /**
     * The chips for [context].
     *
     * @param hasAdjacentClip true when the selected clip is adjacent to
     *   another clip on the timeline, which is the only situation in which a
     *   transition can be applied (Q7).
     */
    fun chipsFor(context: EditorContext, hasAdjacentClip: Boolean = false): List<ContextChip> =
        when (context) {
            EditorContext.ROOT -> RootChips
            EditorContext.CLIP_SELECTED -> buildList {
                addAll(ClipChips)
                if (hasAdjacentClip) add(ConditionalTransitionsChip)
            }
            EditorContext.AUDIO_SELECTED -> AudioChips
            EditorContext.TEXT_SELECTED -> TextChips
        }

    /**
     * Icon-only actions rendered to the right of a divider, after the chips.
     * Only contexts with a working action return anything — an empty list
     * means no divider is drawn.
     */
    fun trailingActionsFor(context: EditorContext): List<ContextChip> =
        when (context) {
            EditorContext.CLIP_SELECTED -> listOf(DuplicateAction, DeleteClipAction)
            EditorContext.TEXT_SELECTED -> listOf(DeleteTextAction)
            // 2026-10-20 — was emptyList(): delete was video-only, so a split
            // audio object could never be removed. [DeleteAudioAction] reuses
            // the `"delete"` actionId and the dock branches on the selection.
            EditorContext.AUDIO_SELECTED -> listOf(DeleteAudioAction)
            EditorContext.ROOT -> emptyList()
        }

    /** Every `ToolPanel` the spec can reach, across all contexts. */
    fun reachableToolPanels(): Set<ToolPanel> = buildSet {
        fun collect(chips: List<ContextChip>) {
            chips.forEach { chip ->
                when (chip) {
                    is ContextChip.Panel -> add(chip.toolPanel)
                    is ContextChip.Group -> collect(chip.members)
                    is ContextChip.Action -> Unit
                }
            }
        }
        EditorContext.entries.forEach { collect(chipsFor(it, hasAdjacentClip = true)) }
    }
}
