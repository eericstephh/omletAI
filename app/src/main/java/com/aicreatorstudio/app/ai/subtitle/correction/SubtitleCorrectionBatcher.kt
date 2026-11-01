package com.aicreatorstudio.app.ai.subtitle.correction

/**
 * Splits a subtitle track into request-sized batches.
 *
 * ## Why batch at all
 *
 * A 40-minute video is 600+ segments. Sending them in one request
 * blows the model's context window, makes a single malformed reply
 * lose everything, and gives the user no progress to watch. Sending
 * one segment per request would multiply the quota cost by 600 and
 * strip the model of exactly the surrounding text that Persian
 * homophone resolution needs.
 *
 * ## Split points
 *
 * Batches break ONLY on segment boundaries. That is the property the
 * brief demands ("do not split Persian Unicode characters or
 * malformed text") — a segment's text is already a complete run of
 * whole words, so a boundary between segments can never bisect a
 * character, a ZWNJ compound, or a word.
 *
 * ## Context
 *
 * Each batch carries the tail of the preceding segments and the head
 * of the following ones as read-only context. Those segments are NOT
 * in the request's id list, so the parser's "no unknown ids" rule
 * rejects any attempt by the model to correct them.
 *
 * Pure + JVM-testable: no I/O, no Android.
 */
object SubtitleCorrectionBatcher {

    /** Segments per request. Small enough to stay well inside any context window. */
    const val DEFAULT_MAX_SEGMENTS = 12

    /** Characters per request (ids + texts). ~1800 chars ≈ 600 Persian tokens. */
    const val DEFAULT_MAX_CHARS = 1_800

    /** How many neighbour segments of context to attach on each side. */
    const val CONTEXT_SEGMENTS = 2

    /** Hard cap on each context string, so context can never dominate the request. */
    const val CONTEXT_MAX_CHARS = 220

    /**
     * Plan the batches for [segments].
     *
     * Blank segments are dropped (there is nothing to correct, and an
     * empty `text` would be rejected by the parser's own validation).
     * Order is preserved exactly.
     *
     * @param maxSegments upper bound on segments per batch.
     * @param maxChars upper bound on the summed text length per batch;
     *   a single segment longer than this still gets its own batch
     *   rather than being split (splitting would violate the
     *   no-partial-text rule).
     */
    fun plan(
        segments: List<CorrectionInputSegment>,
        maxSegments: Int = DEFAULT_MAX_SEGMENTS,
        maxChars: Int = DEFAULT_MAX_CHARS,
        contextSegments: Int = CONTEXT_SEGMENTS
    ): List<CorrectionBatch> {
        val usable = segments.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return emptyList()

        val perBatch = maxSegments.coerceAtLeast(1)
        val charCap = maxChars.coerceAtLeast(1)

        // 1. Group indices into batches by both caps.
        val groups = ArrayList<IntRange>()
        var start = 0
        var chars = 0
        var count = 0
        for (i in usable.indices) {
            val len = usable[i].text.length + usable[i].id.length
            val overCount = count >= perBatch
            val overChars = count > 0 && chars + len > charCap
            if (overCount || overChars) {
                groups.add(start until i)
                start = i
                chars = 0
                count = 0
            }
            chars += len
            count++
        }
        if (start < usable.size) groups.add(start until usable.size)

        // 2. Attach neighbour context to each group.
        return groups.mapIndexed { batchIndex, range ->
            val contextBefore = if (range.first <= 0 || contextSegments <= 0) {
                null
            } else {
                tailContext(usable, range.first, contextSegments)
            }
            val contextAfter = if (range.last >= usable.size - 1 || contextSegments <= 0) {
                null
            } else {
                headContext(usable, range.last + 1, contextSegments)
            }
            CorrectionBatch(
                index = batchIndex,
                segments = range.map { usable[it] },
                contextBefore = contextBefore,
                contextAfter = contextAfter
            )
        }
    }

    /** Last [n] segments before [endExclusive], oldest-first, truncated from the left. */
    private fun tailContext(
        segments: List<CorrectionInputSegment>,
        endExclusive: Int,
        n: Int
    ): String? {
        val from = (endExclusive - n).coerceAtLeast(0)
        val joined = segments.subList(from, endExclusive).joinToString(" ") { it.text }.trim()
        if (joined.isEmpty()) return null
        return if (joined.length <= CONTEXT_MAX_CHARS) {
            joined
        } else {
            // Keep the END of the preceding text: it is the part that
            // touches the batch. Cutting mid-word is avoided by
            // starting at the first space after the cut.
            val cut = joined.substring(joined.length - CONTEXT_MAX_CHARS)
            val firstSpace = cut.indexOf(' ')
            if (firstSpace in 1 until cut.length - 1) cut.substring(firstSpace + 1) else cut
        }
    }

    /** First [n] segments from [startInclusive], in order, truncated from the right. */
    private fun headContext(
        segments: List<CorrectionInputSegment>,
        startInclusive: Int,
        n: Int
    ): String? {
        val to = (startInclusive + n).coerceAtMost(segments.size)
        val joined = segments.subList(startInclusive, to).joinToString(" ") { it.text }.trim()
        if (joined.isEmpty()) return null
        if (joined.length <= CONTEXT_MAX_CHARS) return joined
        // Keep the START of the following text: it continues the batch.
        val cut = joined.substring(0, CONTEXT_MAX_CHARS)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > 0) cut.substring(0, lastSpace) else cut
    }
}
