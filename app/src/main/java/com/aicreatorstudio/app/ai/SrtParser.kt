package com.aicreatorstudio.app.ai

/**
 * PHASE B4 — SRT (SubRip) parser.
 *
 * The dual of [SrtFormatter]. Reads a `.srt` text body and
 * produces a list of [SrtCue]s the editor can map onto
 * `TextOverlay`s and drop onto the existing text timeline.
 *
 * The parser is deliberately tolerant:
 *
 * - UTF-8 with optional BOM (the BOM is silently stripped so
 *   cue text never carries a leading U+FEFF).
 * - LF and CRLF line endings — the `--> ` and the millisecond
 *   separator `,` are honored as-is; the parser does not
 *   assume either line ending.
 * - Index numbers are treated as metadata only. Re-numbering
 *   (after a manual edit), gaps, and duplicates are all
 *   non-fatal. The **time + text** of every block is the
 *   authoritative payload.
 * - Blank lines are block separators, exactly as the
 *   SubRip convention defines.
 * - Malformed blocks (bad timestamp, missing `-->`, missing
 *   text) are reported as [SrtIssue]s on the [ParseResult]
 *   but the rest of the file is still returned. The caller
 *   can decide whether to surface a non-fatal warning or
 *   abort the import.
 *
 * What the parser does NOT do:
 *
 * - It does not sort cues; the editor's mapper handles that.
 * - It does not interpret markup (`<i>`, `{\an8}`, ...) — the
 *   SRT spec is plain text and the editor already supports
 *   newlines inside the `TextOverlay.text` field.
 * - It does not enforce uniqueness on index; it does flag
 *   duplicates on the [ParseResult.issues] list so the import
 *   UX can warn the user.
 * - It does not normalize whitespace inside cue text; only
 *   the trailing newline per line is stripped.
 */
object SrtParser {

    /**
     * Parse a SubRip text body. Always returns a [ParseResult];
     * never throws. A completely empty or whitespace-only
     * input produces `cues = []` and a single `EMPTY` issue.
     *
     * @param body the raw file contents. UTF-8 / UTF-8-BOM / UTF-16
     *   line-broken text is fine; the parser only looks at
     *   characters, not bytes.
     * @param sourceLabel short tag (e.g. "my.srt") used in
     *   issue messages so the user can tell which file
     *   produced the warning.
     */
    fun parse(body: String, sourceLabel: String = "<srt>"): ParseResult {
        // Strip a UTF-8 BOM if present. The BOM appears as
        // U+FEFF and would otherwise become the first
        // character of the first cue's text.
        val stripped = if (body.isNotEmpty() && body[0] == '﻿') {
            body.substring(1)
        } else {
            body
        }

        val issues = mutableListOf<SrtIssue>()
        val cues = mutableListOf<SrtCue>()

        if (stripped.isBlank()) {
            issues.add(SrtIssue(SrtIssueKind.EMPTY, sourceLabel, "File is empty."))
            return ParseResult(cues = emptyList(), issues = issues)
        }

        // Normalize line endings. We don't care whether the file
        // used LF or CRLF, but a mixed file shouldn't trip us up
        // either — the regex used below operates on a single
        // line at a time so either is fine; we just split on
        // either.
        val lines = stripped.split('\n').map { it.trimEnd('\r') }

        // Walk the file in "block" chunks separated by blank
        // lines, exactly as the SubRip spec defines.
        var i = 0
        var blockIndex = 0
        while (i < lines.size) {
            // Skip blank lines between blocks.
            while (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) break

            val blockStartLine = i
            val blockLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                blockLines.add(lines[i])
                i++
            }
            blockIndex++

            // 2026-09-07 CRASH FIX — the index/time lookups below
            // previously ran BEFORE the size guard, so a stray
            // 1-line block (e.g. trailing junk after the last cue)
            // threw IndexOutOfBoundsException and aborted the whole
            // import, violating the parser's never-throw contract.
            if (blockLines.size < 3) {
                issues.add(
                    SrtIssue(
                        SrtIssueKind.MALFORMED_BLOCK,
                        sourceLabel,
                        "Block #$blockIndex has only ${blockLines.size} line(s); " +
                            "expected 3+ (index, time range, text). " +
                            "First line: '${blockLines.firstOrNull().orEmpty().take(60)}'"
                    )
                )
                continue
            }
            val indexLine = blockLines[0]
            val timeLine = blockLines[1]
            // A valid SRT block has at LEAST 3 lines: index,
            // time, and at least one text line. The SubRip
            // convention is to terminate text lines with `\n`
            // and the block with a blank line, so when the
            // time line is the last line of the file (or
            // followed by a blank line) the text section is
            // missing — we report MALFORMED_BLOCK.
            //
            // If the block has 3+ lines but every text line
            // is blank/whitespace-only, the text is present
            // but empty — we report MISSING_TEXT below.
            val textLines = blockLines.drop(2)

            // Index is metadata. We don't require it to be
            // numeric, sequential, or unique; the SubRip spec
            // is permissive in practice. We do, however,
            // detect literal duplicates (same index written
            // twice) and report them as a non-fatal issue so
            // the user knows.
            val indexNumeric = indexLine.trim().toLongOrNull()

            // The time line is the authoritative payload.
            val range = parseTimeRange(timeLine)
            if (range == null) {
                issues.add(
                    SrtIssue(
                        SrtIssueKind.MALFORMED_TIMESTAMP,
                        sourceLabel,
                        "Block #$blockIndex (declared index='$indexLine') has an " +
                            "unrecognised time range line: '$timeLine'"
                    )
                )
                continue
            }
            val (startMs, endMs) = range

            if (endMs <= startMs) {
                issues.add(
                    SrtIssue(
                        SrtIssueKind.INVALID_RANGE,
                        sourceLabel,
                        "Block #$blockIndex has end <= start " +
                            "(start=${startMs}ms, end=${endMs}ms). Skipped."
                    )
                )
                continue
            }

            val rawText = textLines.joinToString("\n")
            if (rawText.isBlank()) {
                issues.add(
                    SrtIssue(
                        SrtIssueKind.MISSING_TEXT,
                        sourceLabel,
                        "Block #$blockIndex has no text. Skipped."
                    )
                )
                continue
            }

            cues.add(
                SrtCue(
                    index = indexNumeric,
                    startTimeMs = startMs,
                    endTimeMs = endMs,
                    text = rawText
                )
            )
        }

        // Detect literal duplicate indexes (non-fatal, but
        // worth surfacing because some tools generate them
        // and they confuse downstream consumers).
        val seen = HashMap<Long, Int>()
        for (cue in cues) {
            val idx = cue.index ?: continue
            seen[idx] = (seen[idx] ?: 0) + 1
        }
        for ((idx, count) in seen) {
            if (count > 1) {
                issues.add(
                    SrtIssue(
                        SrtIssueKind.DUPLICATE_INDEX,
                        sourceLabel,
                        "Index $idx appears $count times; indexes are " +
                            "metadata but this may indicate a malformed export."
                    )
                )
            }
        }

        return ParseResult(cues = cues, issues = issues)
    }

    /**
     * Parse the time-range line `HH:MM:SS,mmm --> HH:MM:SS,mmm`
     * into a pair of milliseconds. Returns null on any
     * structural problem.
     *
     * Defensive against:
     *   - missing `-->`
     *   - non-numeric components
     *   - extra whitespace
     *   - period decimal separator (some non-strict tools
     *     emit `00:00:01.000` instead of `00:00:01,000`).
     *   - any sign on the components (negative is rejected
     *     at the caller by the start >= 0 gate).
     */
    internal fun parseTimeRange(line: String): Pair<Long, Long>? {
        // Find the arrow; tolerate whitespace around it.
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null
        val before = line.substring(0, arrow).trim()
        val after = line.substring(arrow + 3).trim()
        val start = parseTimestamp(before) ?: return null
        val end = parseTimestamp(after) ?: return null
        return start to end
    }

    /**
     * Parse `HH:MM:SS,mmm` (or `HH:MM:SS.mmm`) into a
     * millisecond count. Returns null on any structural
     * problem.
     *
     * All arithmetic is integer. The millisecond component
     * is bound to 0..999; minutes and seconds to 0..59 (the
     * SRT spec is permissive on those upper bounds; we
     * accept the full integer range because some authoring
     * tools emit values like `01:65:00,000` for short
     * pre-roll and consumers round up). Hours are unsigned.
     */
    internal fun parseTimestamp(s: String): Long? {
        // Split on `:` first, then on `,` or `.` for the
        // millisecond component.
        val mainParts = s.split(':')
        if (mainParts.size != 3) return null
        val hours = mainParts[0].toLongOrNull() ?: return null
        if (hours < 0) return null
        val minutes = mainParts[1].toLongOrNull() ?: return null
        if (minutes < 0) return null
        val secondsAndMillis = mainParts[2]
        // Millisecond separator: prefer `,` (spec), accept `.` (lenient).
        val sepIndex = when {
            secondsAndMillis.contains(',') -> secondsAndMillis.indexOf(',')
            secondsAndMillis.contains('.') -> secondsAndMillis.indexOf('.')
            else -> -1
        }
        val seconds: Long
        val millis: Long
        if (sepIndex < 0) {
            // No millisecond component; accept "HH:MM:SS" as exact seconds.
            seconds = secondsAndMillis.toLongOrNull() ?: return null
            if (seconds < 0) return null
            millis = 0L
        } else {
            val secondsStr = secondsAndMillis.substring(0, sepIndex)
            val millisStr = secondsAndMillis.substring(sepIndex + 1)
            seconds = secondsStr.toLongOrNull() ?: return null
            if (seconds < 0) return null
            // Tolerate "1" or "01" or "001" for the millisecond
            // component. Pad/truncate to exactly 3 digits.
            millis = when (millisStr.length) {
                0 -> 0L
                1 -> (millisStr.toLongOrNull() ?: return null) * 100L
                2 -> (millisStr.toLongOrNull() ?: return null) * 10L
                else -> {
                    // Take the first 3 digits; ignore the rest
                    // (some encoders emit "1234" instead of
                    // truncating to "123").
                    val head = millisStr.substring(0, 3)
                    head.toLongOrNull() ?: return null
                }
            }
        }
        return hours * 3_600_000L +
            minutes * 60_000L +
            seconds * 1_000L +
            millis
    }
}

/**
 * A single decoded SubRip cue.
 *
 * @property index the original index number, or null if the
 *   source block did not carry one (some SRT variants omit
 *   the index entirely).
 * @property startTimeMs start time in milliseconds, relative
 *   to the start of the media.
 * @property endTimeMs end time in milliseconds, strictly
 *   greater than [startTimeMs].
 * @property text the cue's text. Newlines are preserved
 *   verbatim — the editor's [com.aicreatorstudio.app.model.TextOverlay.text]
 *   is multiline-friendly.
 */
data class SrtCue(
    val index: Long?,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

/**
 * The full result of parsing an SRT body.
 *
 * @property cues successfully decoded cues, in source order
 *   (NOT sorted by time — the editor's mapper sorts before
 *   producing `TextOverlay`s).
 * @property issues non-fatal problems the parser noticed
 *   (malformed blocks, empty text, duplicate indexes, etc.).
 *   The caller can decide to surface them to the user or
 *   ignore them.
 */
data class ParseResult(
    val cues: List<SrtCue>,
    val issues: List<SrtIssue>
) {
    /** True when at least one cue was produced. */
    val hasCues: Boolean get() = cues.isNotEmpty()
}

/**
 * A non-fatal problem the parser noticed. The import
 * pipeline can collect these and show a short summary to the
 * user without aborting the entire import.
 */
data class SrtIssue(
    val kind: SrtIssueKind,
    val sourceLabel: String,
    val message: String
)

/** Categories of [SrtIssue]. */
enum class SrtIssueKind {
    /** File body was empty or whitespace-only. */
    EMPTY,
    /** A time-range line could not be parsed. */
    MALFORMED_TIMESTAMP,
    /** A block had fewer than 3 lines (index + time + text). */
    MALFORMED_BLOCK,
    /** Time range had `end <= start`. */
    INVALID_RANGE,
    /** Block had no text after the time line. */
    MISSING_TEXT,
    /** Two blocks carried the same declared index. */
    DUPLICATE_INDEX
}
