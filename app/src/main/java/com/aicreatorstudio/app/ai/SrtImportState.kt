package com.aicreatorstudio.app.ai

/**
 * PHASE B4 — sealed import state machine for SRT import.
 *
 * The brief forbids fake progress. Every state below
 * reports a real, observable phase of the import pipeline:
 *
 *   Idle          - no import in progress
 *   Reading       - the picker returned a Uri; the engine
 *                   is reading bytes from contentResolver
 *   Parsing       - the bytes have been read; the parser
 *                   is running
 *   Validating    - the parser produced cues + issues;
 *                   the engine is checking duplicates +
 *                   time ranges
 *   Importing     - the cues are being converted into
 *                   TextOverlays and appended to the
 *                   project state
 *   Completed     - the import succeeded; n new overlays
 *                   were added
 *   Error         - the import failed; the message is
 *                   the user-visible reason
 *
 * The Cancel state is intentionally absent — SRT import is
 * fast (typical SRT files are <100 KB) and reading them
 * synchronously off the main thread is sufficient.
 */
sealed class SrtImportState {
    /** Terminal idle state. */
    data object Idle : SrtImportState()

    /** Reading bytes from the contentResolver off the main thread. */
    data class Reading(val progressFraction: Float = 0f) : SrtImportState()

    /** Parsing the bytes into SrtCues. */
    data class Parsing(val progressFraction: Float = 0.3f) : SrtImportState()

    /** Validating cues + checking duplicates. */
    data class Validating(
        val progressFraction: Float = 0.6f,
        val issues: List<SrtIssue> = emptyList()
    ) : SrtImportState()

    /** Converting cues into TextOverlays and appending to project state. */
    data class Importing(val progressFraction: Float = 0.85f) : SrtImportState()

    /** Import finished. Carries the new overlay count + any parser issues. */
    data class Completed(
        val overlayCount: Int,
        val skippedDuplicates: Int,
        val issues: List<SrtIssue> = emptyList()
    ) : SrtImportState()

    /** Import failed. The [message] is user-visible. */
    data class Error(val message: String) : SrtImportState()
}
