package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay

/**
 * PHASE B4 — convert SRT cues into the editor's existing
 * `TextOverlay` shape.
 *
 * This is the dual of [WhisperTranscriptionToTextOverlays]
 * (PHASE B2). B2 maps Whisper's per-word timing into one
 * `TextOverlay` per segment with `words = [..]` so the
 * preview can drive karaoke highlighting. SRT carries
 * segment-level timing only — no per-word alignment — so
 * the B4 mapper produces the same `TextOverlay` shape but
 * with an empty `words` list, exactly like a user-authored
 * text overlay.
 *
 * ## Why one [TextOverlay] per SRT cue
 *
 * The SRT spec is "one cue = one caption". Mapping each cue
 * to its own overlay matches what every other NLE does
 * (Premiere, DaVinci, Final Cut) and keeps the editor
 * timeline readable. Splitting per-word would force the
 * timeline into hundreds of zero-width clips and the user
 * would have no way to drag a whole caption.
 *
 * ## Multiline preservation
 *
 * SRT cues can span multiple lines (`\n` in the cue text).
 * The mapper passes the text through verbatim — the
 * existing `TextOverlay.text` field is multiline-friendly
 * and the Preview + SRT Export both handle `\n`.
 *
 * ## Persian / RTL preservation
 *
 * The mapper never touches the bytes of `cue.text`. The
 * existing RTL / Persian rendering pipeline (PHASE BUG 2
 * fix in `EditorScreen` — `fontFamily = PersianFont.from(...).toFontFamily()`
 * + `LocalLayoutDirection provides LayoutDirection.Rtl`)
 * reads from the overlay's `fontFamily` and reads the text
 * as-is, so imported Persian renders correctly.
 *
 * ## Timeline coordinate system
 *
 * SRT times are in **source-media ms** (0 = start of the
 * audio). The editor timeline uses **timeline ms** (0 =
 * start of the entire project). For a single-clip project
 * the offset is 0; for multi-clip, the caller passes
 * [clipOffsetMs] and the mapper shifts every cue.
 *
 * ## Duplicate-import protection
 *
 * SRT cues are matched against [existing] by
 * (normalized text, startTimeMs, endTimeMs). A cue is
 * skipped if an existing overlay already covers the same
 * content at the same time, so the user can re-import the
 * same file without creating duplicates. The match is
 * deliberately strict: two cues with similar text but
 * different times are not considered duplicates.
 */
object SrtImportMapper {

    /**
     * Map [cues] into a list of [TextOverlay]s ready to be
     * appended to the editor's `textOverlays` list.
     *
     * - [clipOffsetMs] is added to every cue's start/end
     *   (use 0 for single-clip projects).
     * - [font] is the user-picked Persian font; the
     *   overlay's `fontFamily` field carries its enum name
     *   so the render layer can resolve it.
     * - [existing] is the editor's current overlay list; any
     *   cue that is a (text, start, end)-triple duplicate of
     *   an existing overlay is dropped. Pass `emptyList()`
     *   to disable the dedup pass.
     *
     * Returns the new overlays (in start-time order). The
     * caller is responsible for appending them to the
     * editor's `textOverlays` and for tracking their ids in
     * the "this is a subtitle" set so the export path picks
     * them up.
     */
    fun toTextOverlays(
        cues: List<SrtCue>,
        clipOffsetMs: Long = 0L,
        font: PersianFont = PersianFont.DEFAULT,
        existing: List<TextOverlay> = emptyList()
    ): List<TextOverlay> {
        if (cues.isEmpty()) return emptyList()
        val offset = clipOffsetMs.coerceAtLeast(0L)

        // Build a fingerprint set from the existing overlays
        // for O(1) duplicate lookup. SRT-imported cues are
        // matched against ALL existing overlays — both
        // user-authored text and prior AI / SRT captions —
        // so re-importing the same SRT after editing a few
        // cues still surfaces the unchanged ones as dupes.
        // 2026-09-07 DEDUP FIX — existing overlays are
        // fingerprinted with the SAME normalization as the
        // incoming cue. Previously the stored raw text
        // ("Hello\nworld") was hashed while the cue side was
        // normalized ("Hello world"), so multi-line cues never
        // matched and re-import duplicated them.
        val existingFingerprints: Set<Long> = existing.mapTo(HashSet()) {
            fingerprintOf(normalizeForCompare(it.text), it.startTimeMs, it.endTimeMs)
        }

        val out = ArrayList<TextOverlay>(cues.size)
        for (cue in cues) {
            val segStart = offset + cue.startTimeMs
            val segEnd = offset + cue.endTimeMs
            if (segEnd <= segStart) continue
            val normalizedText = normalizeForCompare(cue.text)
            val fp = fingerprintOf(normalizedText, segStart, segEnd)
            if (fp in existingFingerprints) continue

            out.add(
                TextOverlay(
                    id = java.util.UUID.randomUUID().toString(),
                    text = cue.text,
                    startTimeMs = segStart,
                    endTimeMs = segEnd,
                    fontFamily = font.name,
                    // SRT carries segment-level timing only;
                    // per-word timing is empty. The preview
                    // falls back to block rendering in that
                    // case (same as a user-authored overlay).
                    words = emptyList(),
                    karaokeEnabled = true,
                    // PHASE B4: tag the overlay so the export
                    // filter picks it up across project save/
                    // reload without relying on the in-memory
                    // `srtImportedOverlayIds` set.
                    subtitleSource = "srt",
                    // 2026-09-07 — the import offset, so exporting
                    // this SRT back out restores the original cue
                    // times exactly (per-overlay timebase).
                    subtitleAnchorMs = offset
                )
            )
        }
        return out.sortedBy { it.startTimeMs }
    }

    /**
     * Normalize cue text for duplicate detection: collapse
     * runs of whitespace (including the newlines the
     * formatter would have added) into a single space and
     * trim. Two cues that differ only in line wrapping
     * are still considered duplicates.
     */
    private fun normalizeForCompare(s: String): String =
        s.replace(Regex("\\s+"), " ").trim()

    /**
     * Compose a 64-bit fingerprint from (text, start, end)
     * for the dedup HashSet. We do not need a
     * cryptographic hash; this is just an O(1) lookup key.
     */
    private fun fingerprintOf(text: String, start: Long, end: Long): Long {
        var h = 1125899906842597L // a large prime
        for (c in text) {
            h = 31 * h + c.code
        }
        h = 31 * h + start.hashCode()
        h = 31 * h + end.hashCode()
        return h
    }
}
