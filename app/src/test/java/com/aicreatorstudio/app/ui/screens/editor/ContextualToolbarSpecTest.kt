package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.model.EditorContext
import com.aicreatorstudio.app.model.ToolPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ContextualToolbarSpec] — the declarative description of the
 * editor's contextual toolbar.
 *
 * These are the structural guarantees the 2026-09-28 IA audit asked for:
 * a fixed, non-scrolling root row; no duplicate chips; one level of sheet
 * nesting; and — the headline fix — **every `ToolPanel` reachable**, where the
 * old flat dock left `ADJUST` and `CROP` with no entry point at all.
 */
class ContextualToolbarSpecTest {

    // ---- chip counts per context ----

    @Test
    fun `root renders exactly the eight flat chips`() {
        // 2026-10-05 — the root row is fully flat (no group sheets).
        // 2026-10-13 — «پیشرفت» (video progress bar / story timer) joined
        // as the eighth chip. It is a PROJECT-level setting like «نسبت» and
        // «پس‌زمینه», so the root row is its only correct home; the clip
        // toolbar would imply it applies to one clip.
        val chips = ContextualToolbarSpec.chipsFor(EditorContext.ROOT)
        assertEquals(8, chips.size)
        assertEquals(
            listOf(
                "templates", "subs_ai", "add_text", "add_music",
                "aspect", "background", "progress_bar", "overlay"
            ),
            chips.map { it.id }
        )
        // Every root chip opens its panel directly — none is a Group.
        assertTrue(
            "root should have no group chips, found: " +
                chips.filterIsInstance<ContextChip.Group>().map { it.id },
            chips.all { it is ContextChip.Panel }
        )
    }

    @Test
    fun `the progress bar chip is a project-level root panel`() {
        // 2026-10-13 — regression guard for the wiring the feature needs:
        // the chip must route to ToolPanel.PROGRESS_BAR (not to the
        // subtitle studio's per-word `progressBar` style, which shares the
        // Persian name «نوار پیشرفت» but is an unrelated setting), and it
        // must live in the root row where the other canvas-wide settings
        // are.
        val chip = ContextualToolbarSpec.chipsFor(EditorContext.ROOT)
            .single { it.id == "progress_bar" } as ContextChip.Panel
        assertEquals(ToolPanel.PROGRESS_BAR, chip.toolPanel)
        assertTrue(
            "the progress-bar chip must not leak into the clip toolbar — it is not clip-scoped",
            ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED, hasAdjacentClip = true)
                .none { it.id == "progress_bar" }
        )
    }

    @Test
    fun `clip renders sixteen flat chips and no transitions without an adjacent clip`() {
        // 2026-10-05 flatten — every frequent edit is a direct one-tap chip;
        // the ONLY remaining group is the AI collection. 2026-10-10 — the
        // project-level Subtitles chip joined this row (SUBTITLE ROUTING
        // FIX) so a selected clip no longer hides the entry point.
        // 2026-10-13 — the voice changer joined, next to Volume. A video
        // clip's own soundtrack is edited from THIS row (the audio toolbar
        // only appears for a standalone audio object), so without it the
        // effect would be unreachable for video clips.
        // 2026-10-13 — «ماسک» (PiP shape masks) joined, next to Opacity:
        // all three of Opacity / Transform / Mask describe how a layer is
        // presented rather than what its pixels are.
        // 2026-10-13 — «پرده سبز» (chroma key) joined after «ماسک», sharing
        // its target and composing with it at render time.
        val chips = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED, hasAdjacentClip = false)
        assertEquals(17, chips.size)
        assertFalse(chips.any { it.id == "clip_transitions" })
        assertEquals(
            listOf("clip_ai"),
            chips.filterIsInstance<ContextChip.Group>().map { it.id }
        )
        // The frequent tools must NOT be buried in a group sheet.
        val flatPanels = chips.filterIsInstance<ContextChip.Panel>().map { it.toolPanel }
        listOf(
            ToolPanel.SPEED, ToolPanel.VOLUME, ToolPanel.FILTERS, ToolPanel.ADJUST,
            ToolPanel.EFFECTS, ToolPanel.OPACITY, ToolPanel.ANIMATIONS, ToolPanel.TRIM,
            ToolPanel.CROP, ToolPanel.TRANSFORM, ToolPanel.AUDIO, ToolPanel.SUBTITLES,
            ToolPanel.VOICE_CHANGER, ToolPanel.OVERLAY_MASK, ToolPanel.CHROMA_KEY
        ).forEach { panel ->
            assertTrue("$panel should be a flat chip in the clip toolbar", panel in flatPanels)
        }
    }

    @Test
    fun `clip renders transitions as the last chip when a neighbour exists`() {
        // Q7 — a transition needs two clips to join, so the chip only appears
        // when one exists rather than being a permanent dead tap. The
        // Transitions chip is appended AFTER the row (which now ends with
        // Subtitles), so it remains last.
        val chips = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED, hasAdjacentClip = true)
        assertEquals(18, chips.size)
        assertEquals("clip_transitions", chips.last().id)
    }

    @Test
    fun `the overlay mask chip is clip-scoped and never in the root row`() {
        // 2026-10-13 — the mask panel is reachable from the CLIP toolbar
        // only. It must NOT join the root row: a mask belongs to one
        // overlay, and a root-level entry point would imply it applies to
        // the whole project the way «نسبت» / «پس‌زمینه» do.
        val fromClip = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED)
            .single { it.id == "overlay_mask" } as ContextChip.Panel
        assertEquals(ToolPanel.OVERLAY_MASK, fromClip.toolPanel)
        assertFalse(
            "the mask chip must not leak into the root row",
            ContextualToolbarSpec.chipsFor(EditorContext.ROOT).any { it.id == "overlay_mask" }
        )
        assertFalse(
            "the mask chip is not an audio tool",
            ContextualToolbarSpec.chipsFor(EditorContext.AUDIO_SELECTED)
                .any { it.id == "overlay_mask" }
        )
    }

    @Test
    fun `audio renders seven working chips`() {
        // 2026-10-10 — Subtitles joined: generation can transcribe the
        // selected audio object directly.
        // 2026-10-13 — the voice changer joined: this row IS the audio
        // tools dock, which is where a user looks for anything that changes
        // how a track sounds.
        // 2026-10-13 — «استخراج» (extract audio from a picked video)
        // joined. It is the only Action chip in this row: the tool has no
        // panel of its own (picker → 1-2 s extraction → banner).
        // 2026-10-19 — «برش» joined FIRST, mirroring the clip row. Until
        // then the cut was a video-only action: selecting the audio tile
        // and tapping Split did nothing because the dock fell back to
        // `selectedClipId`, which is null by construction in this context.
        // 2026-10-21 — «نریشن» (voiceover) joined LAST: it is the only
        // chip in this row that CREATES an object rather than editing the
        // selected one.
        val chips = ContextualToolbarSpec.chipsFor(EditorContext.AUDIO_SELECTED)
        assertEquals(
            listOf(
                "audio_split", "audio_beats", "audio_volume", "subs_ai",
                "voice_changer", "audio_extract", "voiceover"
            ),
            chips.map { it.id }
        )
        val extract = chips.single { it.id == "audio_extract" } as ContextChip.Action
        assertEquals("extract_audio", extract.actionId)
        // The split chip must dispatch the SAME actionId the dock already
        // handles, otherwise the new tile would eat the tap.
        val split = chips.single { it.id == "audio_split" } as ContextChip.Action
        assertEquals("split", split.actionId)
        // The voiceover chip opens a panel of its own.
        val voiceover = chips.single { it.id == "voiceover" } as ContextChip.Panel
        assertEquals(ToolPanel.VOICEOVER, voiceover.toolPanel)
    }

    @Test
    fun `the voice changer chip shares one id and panel across both rows`() {
        // 2026-10-13 — the chip is placed in the audio toolbar AND the clip
        // toolbar. The selected-tile highlight compares chip IDS, so a
        // per-context id drift would leave the open panel with no
        // highlighted tile in one of the two rows. One constant, one id.
        val fromAudio = ContextualToolbarSpec.chipsFor(EditorContext.AUDIO_SELECTED)
            .single { it.id == "voice_changer" } as ContextChip.Panel
        val fromClip = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED)
            .single { it.id == "voice_changer" } as ContextChip.Panel
        assertEquals(ToolPanel.VOICE_CHANGER, fromAudio.toolPanel)
        assertEquals(fromAudio.toolPanel, fromClip.toolPanel)
        assertEquals(fromAudio.label, fromClip.label)
        assertEquals("وویس", fromAudio.label)
    }

    @Test
    fun `text renders four flat chips`() {
        // 2026-10-05 — the «استایل» group is gone; a selected text object
        // edits through three direct taps. 2026-10-10 — Subtitles joined so
        // tapping a subtitle on the canvas never hides the open panel's tile.
        val chips = ContextualToolbarSpec.chipsFor(EditorContext.TEXT_SELECTED)
        assertEquals(listOf("text_font", "paragraph", "style_gallery", "subs_ai"), chips.map { it.id })
        assertTrue(chips.all { it is ContextChip.Panel })
    }

    @Test
    fun `every context renders a non-empty toolbar`() {
        EditorContext.entries.forEach { context ->
            assertTrue(
                "context $context rendered no chips",
                ContextualToolbarSpec.chipsFor(context).isNotEmpty()
            )
        }
    }

    // ---- the headline guarantee: no unreachable panels ----

    @Test
    fun `every ToolPanel is reachable from some context`() {
        // Before this change ADJUST and CROP had SubPanelContent branches but
        // no dock entry, so they could not be opened at all.
        val unreachable = ToolPanel.entries.toSet() - ContextualToolbarSpec.reachableToolPanels()
        assertTrue("unreachable panels: $unreachable", unreachable.isEmpty())
    }

    @Test
    fun `the two formerly orphaned panels are reachable`() {
        val reachable = ContextualToolbarSpec.reachableToolPanels()
        assertTrue(ToolPanel.ADJUST in reachable)
        assertTrue(ToolPanel.CROP in reachable)
    }

    @Test
    fun `crop is reachable as a flat chip in the clip toolbar`() {
        // Was root → canvas group; the 2026-10-05 flatten moved it next to
        // the other clip-scoped appearance tools where it belongs.
        val flat = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED)
            .filterIsInstance<ContextChip.Panel>()
        assertTrue(flat.any { it.toolPanel == ToolPanel.CROP })
    }

    @Test
    fun `adjust is reachable as a flat chip in the clip toolbar`() {
        val flat = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED)
            .filterIsInstance<ContextChip.Panel>()
        assertTrue(flat.any { it.toolPanel == ToolPanel.ADJUST })
    }

    @Test
    fun `no panel is opened twice within a single context`() {
        // The audit's duplicate-entry-point defect was Split living in three
        // places *on screen at once*. A panel may legitimately be reachable
        // from several contexts (Volume belongs to both a clip and an audio
        // object), but never twice within one context.
        EditorContext.entries.forEach { context ->
            val placements = mutableMapOf<ToolPanel, MutableList<String>>()
            fun walk(chips: List<ContextChip>) {
                chips.forEach { chip ->
                    when (chip) {
                        is ContextChip.Panel ->
                            placements.getOrPut(chip.toolPanel) { mutableListOf() }.add(chip.id)
                        is ContextChip.Group -> walk(chip.members)
                        is ContextChip.Action -> Unit
                    }
                }
            }
            walk(ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true))
            val duplicated = placements.filterValues { it.size > 1 }
            assertEquals("$context opens the same panel twice: $duplicated", emptyMap<ToolPanel, List<String>>(), duplicated)
        }
    }

    @Test
    fun `transitions is the only panel reachable from two different contexts`() {
        // Root → Overlay keeps it discoverable with nothing selected; the clip
        // chip is the conditional shortcut. Everything else has one home.
        val placements = mutableMapOf<ToolPanel, MutableSet<EditorContext>>()
        EditorContext.entries.forEach { context ->
            fun walk(chips: List<ContextChip>) {
                chips.forEach { chip ->
                    when (chip) {
                        is ContextChip.Panel ->
                            placements.getOrPut(chip.toolPanel) { mutableSetOf() }.add(context)
                        is ContextChip.Group -> walk(chip.members)
                        is ContextChip.Action -> Unit
                    }
                }
            }
            walk(ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true))
        }
        val multiContext = placements.filterValues { it.size > 1 }.keys
        // These legitimately serve more than one object type: an audio tool
        // belongs to both a video clip's audio and a standalone audio object.
        val allowed = setOf(
            ToolPanel.TRANSITIONS,   // Root → Overlay + conditional clip chip
            ToolPanel.VOLUME,        // clip audio group + audio-object toolbar
            ToolPanel.AUDIO,         // Root → Audio + clip audio group
            ToolPanel.AI_VOICE_CLEAN,
            ToolPanel.AI_SILENCE_CUT,
            ToolPanel.OPACITY,       // clip appearance + overlay
            ToolPanel.TEXT,          // Root → Text + text-object toolbar
            ToolPanel.PARAGRAPH,
            ToolPanel.SUBTITLE_STUDIO, // Root → Templates + text style gallery
            // 2026-10-10 — project-level subtitle tool: reachable from every
            // context so a selection change never hides the entry point.
            ToolPanel.SUBTITLES,
            // 2026-10-13 — the voice changer addresses both a video clip's
            // own audio (clip toolbar) and a standalone audio object (audio
            // toolbar). Same reason VOLUME is on this list.
            ToolPanel.VOICE_CHANGER
        )
        assertEquals(emptySet<ToolPanel>(), multiContext - allowed)
    }

    // ---- structural invariants ----

    @Test
    fun `chip ids are unique within every context`() {
        EditorContext.entries.forEach { context ->
            val ids = ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true).map { it.id }
            assertEquals("duplicate chip id in $context: $ids", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `groups are never nested more than one level deep`() {
        fun walk(chips: List<ContextChip>, depth: Int) {
            chips.forEach { chip ->
                if (chip is ContextChip.Group) {
                    assertTrue("group ${chip.id} nested at depth $depth", depth <= 0)
                    walk(chip.members, depth + 1)
                }
            }
        }
        EditorContext.entries.forEach { context ->
            walk(ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true), 0)
        }
    }

    @Test
    fun `groups always have at least two members`() {
        // A one-member group is a needless extra tap — it should be a Panel.
        fun walk(chips: List<ContextChip>) {
            chips.forEach { chip ->
                if (chip is ContextChip.Group) {
                    assertTrue(
                        "group ${chip.id} has ${chip.members.size} member(s)",
                        chip.members.size >= 2
                    )
                    walk(chip.members)
                }
            }
        }
        EditorContext.entries.forEach { context ->
            walk(ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true))
        }
    }

    @Test
    fun `group members are only panels or actions`() {
        fun walk(chips: List<ContextChip>) {
            chips.forEach { chip ->
                if (chip is ContextChip.Group) {
                    chip.members.forEach { member ->
                        assertFalse(
                            "group ${chip.id} contains a nested group ${member.id}",
                            member is ContextChip.Group
                        )
                    }
                }
            }
        }
        EditorContext.entries.forEach { context ->
            walk(ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true))
        }
    }

    @Test
    fun `every chip has a non-blank id and label`() {
        fun walk(chips: List<ContextChip>) {
            chips.forEach { chip ->
                assertTrue("blank chip id", chip.id.isNotBlank())
                assertTrue("blank label on ${chip.id}", chip.label.isNotBlank())
                if (chip is ContextChip.Group) {
                    assertTrue("blank sheet title on ${chip.id}", chip.sheetTitle.isNotBlank())
                    walk(chip.members)
                }
            }
        }
        EditorContext.entries.forEach { context ->
            walk(ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true))
        }
    }

    @Test
    fun `actions carry a non-blank actionId`() {
        fun walk(chips: List<ContextChip>) {
            chips.forEach { chip ->
                when (chip) {
                    is ContextChip.Action -> assertTrue("blank actionId on ${chip.id}", chip.actionId.isNotBlank())
                    is ContextChip.Group -> walk(chip.members)
                    is ContextChip.Panel -> Unit
                }
            }
        }
        EditorContext.entries.forEach { context ->
            walk(ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true))
            walk(ContextualToolbarSpec.trailingActionsFor(context))
        }
    }

    // ---- trailing icon-only actions ----

    @Test
    fun `clip context has duplicate and delete trailing actions`() {
        val trailing = ContextualToolbarSpec.trailingActionsFor(EditorContext.CLIP_SELECTED)
        assertEquals(listOf("dup", "del_clip"), trailing.map { it.id })
        assertTrue((trailing.last() as ContextChip.Action).destructive)
    }

    @Test
    fun `text context has a trailing delete action`() {
        val trailing = ContextualToolbarSpec.trailingActionsFor(EditorContext.TEXT_SELECTED)
        assertEquals(listOf("del_text"), trailing.map { it.id })
        assertTrue((trailing.first() as ContextChip.Action).destructive)
    }

    @Test
    fun `audio context has a trailing delete action`() {
        // 2026-10-20 — this used to assert an EMPTY list ("audio delete does
        // not exist in the codebase"), which pinned the defect instead of the
        // contract: an audio object could be split but never removed, so the
        // delete button only ever acted on the video timeline.
        val trailing = ContextualToolbarSpec.trailingActionsFor(EditorContext.AUDIO_SELECTED)
        assertEquals(listOf("del_audio"), trailing.map { it.id })
        assertTrue((trailing.first() as ContextChip.Action).destructive)
    }

    @Test
    fun `root has no trailing actions`() {
        assertTrue(ContextualToolbarSpec.trailingActionsFor(EditorContext.ROOT).isEmpty())
    }

    @Test
    fun `audio delete dispatches through the shared delete actionId`() {
        // Same rule as `audio_split`: a distinct chip id for the highlight, but
        // the SAME actionId, because the dock's `when` branches on which
        // object is selected rather than on the id.
        assertEquals(
            "delete",
            (ContextualToolbarSpec.DeleteAudioAction as ContextChip.Action).actionId
        )
        assertEquals(
            "delete",
            (ContextualToolbarSpec.DeleteClipAction as ContextChip.Action).actionId
        )
        // ...and the ids must differ, or the two tiles would share a highlight.
        assertNotEquals(
            ContextualToolbarSpec.DeleteAudioAction.id,
            ContextualToolbarSpec.DeleteClipAction.id
        )
    }

    @Test
    fun `the split action keeps the actionId the dock already dispatches`() {
        // The dock's onAction handler keys off these strings; renaming one
        // without the other silently breaks the button.
        val split = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED)
            .first { it.id == "clip_split" } as ContextChip.Action
        assertEquals("split", split.actionId)

        assertEquals("duplicate", (ContextualToolbarSpec.DuplicateAction as ContextChip.Action).actionId)
        assertEquals("delete", (ContextualToolbarSpec.DeleteClipAction as ContextChip.Action).actionId)
        assertEquals("delete_text", (ContextualToolbarSpec.DeleteTextAction as ContextChip.Action).actionId)
    }

    // ---- the templates entry point (Q5) ----

    @Test
    fun `templates is the first root chip and routes to the subtitle studio today`() {
        val templates = ContextualToolbarSpec.chipsFor(EditorContext.ROOT).first() as ContextChip.Panel
        assertEquals("templates", templates.id)
        assertEquals(ToolPanel.SUBTITLE_STUDIO, templates.toolPanel)
    }

    // ---- the scroll budget (2026-10-04 CapCut relayout) ----

    @Test
    fun `no context renders more chips than a short scroll`() {
        // 2026-10-05 — the toolbar is a horizontally SCROLLABLE row of
        // fixed-width tiles, so chips no longer compete for width. The
        // flatten traded shortness for CONSISTENCY: the worst case
        // (CLIP + transitions) is 18 tiles after the 2026-10-13 voice
        // changer, the PiP shape-mask chip and the chroma-key chip joined
        // the clip row. The budget keeps a future edit from doubling that.
        val maxChips = 18
        EditorContext.entries.forEach { context ->
            val chips = ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true)
            assertTrue(
                "$context renders ${chips.size} chips, scroll budget is $maxChips",
                chips.size <= maxChips
            )
        }
    }

    @Test
    fun `top-level chip labels are short enough not to be truncated`() {
        // A 60dp tile fits about eight Persian characters at 11sp. Labels
        // are deliberately terse; a long label here would ellipsize
        // mid-word, so this guards the rule.
        val maxLabelLength = 8
        EditorContext.entries.forEach { context ->
            ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true).forEach { chip ->
                assertTrue(
                    "label '${chip.label}' on ${chip.id} is ${chip.label.length} chars",
                    chip.label.length <= maxLabelLength
                )
            }
        }
    }

    @Test
    fun `the split action is offered by exactly the two selection contexts`() {
        // §5.4 — Split used to be reachable from both the transport row and
        // the dock, so selecting a clip put the same button on screen twice.
        // The transport copy is gone; this keeps the spec the only home.
        //
        // 2026-10-19 — AUDIO_SELECTED joined: the cut now applies to the
        // selected audio object as well as to a video clip. The two chips
        // share one actionId but never render at the same time — the
        // selection ids are mutually exclusive.
        val placements = EditorContext.entries.filter { context ->
            val chips = ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true)
            val trailing = ContextualToolbarSpec.trailingActionsFor(context)
            (chips + trailing).any { it is ContextChip.Action && it.actionId == "split" }
        }
        assertEquals(
            listOf(EditorContext.CLIP_SELECTED, EditorContext.AUDIO_SELECTED),
            placements
        )
    }

    @Test
    fun `the destructive cluster never exceeds two icons`() {
        // Delete sits right of Split; every extra icon in that fixed slice
        // steals width from all the chips.
        EditorContext.entries.forEach { context ->
            val trailing = ContextualToolbarSpec.trailingActionsFor(context)
            assertTrue("$context has ${trailing.size} trailing actions", trailing.size <= 2)
        }
    }

    // ---- 2026-10-10 SUBTITLE ROUTING FIX ----

    @Test
    fun `the subtitles chip is present in every context and always routes to SUBTITLES`() {
        // Regression guard for the "Subtitles opens general tools" defect:
        // the chip existed only in ROOT, so CLIP_SELECTED (auto-selected
        // after addClipFromUri), AUDIO_SELECTED and TEXT_SELECTED all hid
        // the entry point. Tapping it must activate ToolPanel.SUBTITLES
        // regardless of the current selection.
        EditorContext.entries.forEach { context ->
            val subtitlesChips = ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true)
                .filter { it.id == "subs_ai" }
            assertEquals(
                "context $context should render exactly one subs_ai chip, " +
                    "found ${subtitlesChips.size}",
                1,
                subtitlesChips.size
            )
            val chip = subtitlesChips.single() as ContextChip.Panel
            assertEquals(
                "subs_ai in $context must route to ToolPanel.SUBTITLES",
                ToolPanel.SUBTITLES,
                chip.toolPanel
            )
        }
    }

    @Test
    fun `the subtitles chip keeps one shared id and label across every context`() {
        // The LazyRow item key and the selected-tile comparison both key off
        // the chip id; a per-context id drift would desync the highlight.
        EditorContext.entries.forEach { context ->
            val chip = ContextualToolbarSpec.chipsFor(context, hasAdjacentClip = true)
                .single { it.id == "subs_ai" }
            assertEquals("زیرنویس", chip.label)
        }
    }

    @Test
    fun `subtitles can be opened and closed from both root and clip contexts`() {
        // Bidirectional switching: the user can open SUBTITLES from ROOT,
        // select a clip (toolbar → CLIP_SELECTED) and still see the open
        // tool highlighted, then toggle it off from that row — no manual
        // timeline deselect required in between.
        val rootChip = ContextualToolbarSpec.chipsFor(EditorContext.ROOT)
            .single { it.id == "subs_ai" } as ContextChip.Panel
        val clipChip = ContextualToolbarSpec.chipsFor(EditorContext.CLIP_SELECTED)
            .single { it.id == "subs_ai" } as ContextChip.Panel
        assertEquals(ToolPanel.SUBTITLES, rootChip.toolPanel)
        assertEquals(rootChip.toolPanel, clipChip.toolPanel)
    }
}
