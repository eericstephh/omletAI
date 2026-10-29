package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.util.BeatDetector

/**
 * A single audio clip on the project's timeline. Distinct from [VideoClip]:
 * audio clips carry PCM-source metadata (or an embedded-audio extraction
 * marker) instead of frame / duration / resolution. Every imported video
 * clip that has an audio track is paired with an [AudioClip] placed directly
 * underneath it at the same `startTimeMs` and `durationMs` so the playhead
 * drives video and audio through one shared clock.
 *
 * Audio-clip identity is independent of its parent [VideoClip] so that
 * future flows (replace-with-music, detach-audio, voice-clean-without-
 * video, etc.) can operate on audio without having to mutate the
 * owning video clip. The pairing is encoded in [originVideoClipId] when
 * the audio was extracted from a video — null when the clip was
 * imported standalone (music file, sfx).
 *
 * @property id stable id; unique across the project.
 * @property sourceUri Content URI / file path of the source PCM. For an
 *   extracted-from-video clip this is the same Uri as the video so the
 *   exporter can re-extract on demand; the cache file (when present)
 *   is a denoised copy at [cachedPcmPath].
 * @property fileName human-readable name for the project panel.
 * @property startTimeMs timeline position (matches `VideoClip.startTimeMs`
 *   for paired clips).
 * @property durationMs effective length on the timeline (after
 *   trim/clipOffset). Synchronized with the video: drift must be zero.
 * @property clipOffsetMs where to start reading inside the source —
 *   analogous to [VideoClip.trimStartMs].
 * @property sourceStartMs optional lower bound inside the source.
 * @property sourceEndMs optional upper bound inside the source.
 * @property volume 0..1 linear gain. The preview & export multipliers
 *   use this directly; `muted=true` short-circuits to silence.
 * @property muted true = never output audio for this clip.
 * @property fadeInMs linear fade-in duration in clip-local ms.
 * @property fadeOutMs linear fade-out duration in clip-local ms.
 * @property waveformCacheKey stable key under which the waveform cache
 *   stores the down-sampled peaks for this source. The audio panel
 *   reads this; when null the panel computes the key on demand.
 * @property originVideoClipId id of the [VideoClip] that produced this
 *   audio clip when it was extracted from a video import. Null for
 *   standalone audio (music imports, sfx). Drives the "video → audio"
 *   pairing invariants.
 * @property cachedPcmPath absolute path of an on-disk PCM cache the
 *   Voice Clean engine writes when the user denoises the clip. Null
 *   when no cache exists yet.
 * @property beatData detected beat markers for this clip's audio, or
 *   null when Beat Detection has not been run. Null and an empty
 *   marker list are deliberately distinct: null means "never
 *   analysed" (so the UI offers the Detect action) while a non-null
 *   empty list means "analysed and genuinely found no beats" (so the
 *   UI reports that honestly instead of re-offering). Markers are
 *   stored in **source** time, not timeline time — see
 *   [com.aicreatorstudio.app.util.BeatDetector.toTimelineMarkers].
 */
data class AudioClip(
    val id: String,
    val sourceUri: String,
    val fileName: String,
    val startTimeMs: Long,
    val durationMs: Long,
    val clipOffsetMs: Long = 0L,
    val sourceStartMs: Long = 0L,
    val sourceEndMs: Long = -1L,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val waveformCacheKey: String? = null,
    val originVideoClipId: String? = null,
    val cachedPcmPath: String? = null,
    val beatData: BeatDetector.AudioBeatData? = null,
    // ---- 2026-10-13 Voice Changer ----
    /**
     * The offline voice transformation applied to this audio clip, or
     * [VoiceEffect.NONE] for none.
     *
     * Appended last so every existing named- and positional-argument call
     * site keeps its meaning — see the note on
     * [VideoClip.voiceEffect].
     *
     * Standalone audio (an imported song, a built-in SFX) can carry a
     * voice effect just as a video clip's track can: the mixer routes
     * every stem through the same DSP, so "robot" on a music bed is a
     * supported — if unusual — thing to ask for.
     */
    val voiceEffect: VoiceEffect = VoiceEffect.NONE
) {
    init {
        require(id.isNotBlank()) { "AudioClip.id must be non-blank" }
        require(sourceUri.isNotBlank()) { "AudioClip.sourceUri must be non-blank" }
        require(durationMs >= 0L) { "AudioClip.durationMs must be non-negative (was $durationMs)" }
        require(clipOffsetMs >= 0L) { "AudioClip.clipOffsetMs must be non-negative" }
        require(volume in 0f..2f) { "AudioClip.volume must be in 0..2 (was $volume)" }
        require(fadeInMs >= 0L && fadeOutMs >= 0L) { "AudioClip fade durations must be non-negative" }
    }

    /**
     * Effective end position on the project timeline, in ms. Returns
     * `startTimeMs + durationMs`. Used by the playhead scrubber and
     * by the export composer to decide when to stop reading this clip.
     */
    val endTimeMs: Long get() = startTimeMs + durationMs

    /**
     * True when this audio clip was extracted from a video's original
     * audio track and must remain synchronized to it. Paired audio
     * clips never appear without their parent video clip; saving/
     * loading the project preserves the link via [originVideoClipId].
     */
    val isPairedToVideo: Boolean get() = originVideoClipId != null
}

/**
 * One down-sampled audio peak used by the timeline waveform. The
 * audio panel stores a list of these per [AudioClip.waveformCacheKey]
 * in its on-disk cache (memory + disk). Real PCM bytes from the
 * extractor are required to populate this — never random / static
 * data.
 *
 * @property min lowest |sample| in this bucket (0..1 normalized).
 * @property max highest |sample| in this bucket (0..1 normalized).
 * @property bucketStartMs start of this bucket in clip-local ms.
 * @property bucketEndMs end of this bucket in clip-local ms.
 */
data class AudioWaveformPeak(
    val min: Float,
    val max: Float,
    val bucketStartMs: Long,
    val bucketEndMs: Long
)

/**
 * Track lane a clip belongs to. The timeline renders video + audio
 * on separate lanes (see PART 13 of the brief) and each clip has a
 * declared [TrackType] (defined in [EditorUiState]) so the playhead
 * can drive both clocks through the same loop.
 */