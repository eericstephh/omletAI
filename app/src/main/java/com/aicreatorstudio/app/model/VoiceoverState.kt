package com.aicreatorstudio.app.model

/**
 * 2026-10-21 — the state of the «نریشن» (voiceover) recorder.
 *
 * CapCut's voiceover is one of its signature capabilities: the user
 * records a narration over the video and it lands on the timeline as
 * its own audio object. This project had every *piece* of that except
 * the recorder — an audio lane, audio clips with volume and fades, an
 * audio mixer, and an offline voice-changer — but no way to capture
 * anything, and no `RECORD_AUDIO` permission in the manifest.
 *
 * The state is deliberately small and explicit so the panel can never
 * show a record button while a recording is running, and so a failed
 * capture is a *visible* state rather than a silent no-op.
 */
sealed interface VoiceoverState {

    /** Nothing is being recorded and there is no pending take. */
    data object Idle : VoiceoverState

    /**
     * A recording is running.
     *
     * @property startedAtEpochMs wall-clock start, so the panel can
     *   show an elapsed timer without the ViewModel having to tick a
     *   second clock.
     * @property level 0..1 — the live input level, for the meter.
     * @property elapsedMs how long the take has been running.
     */
    data class Recording(
        val startedAtEpochMs: Long,
        val level: Float,
        val elapsedMs: Long
    ) : VoiceoverState

    /**
     * A take was captured and is waiting to be placed on the timeline.
     *
     * @property uri the app-owned file the take was written to.
     * @property durationMs the take's length.
     */
    data class Captured(
        val uri: String,
        val durationMs: Long
    ) : VoiceoverState

    /**
     * The capture failed. The message is user-facing Persian text.
     *
     * @property permissionDenied true when the cause was a missing
     *   `RECORD_AUDIO` grant — the panel then shows the "grant
     *   permission" affordance instead of a plain error.
     */
    data class Failed(
        val message: String,
        val permissionDenied: Boolean = false
    ) : VoiceoverState
}
