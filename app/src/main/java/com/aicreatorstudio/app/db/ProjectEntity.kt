package com.aicreatorstudio.app.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class VideoProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    // ---- Project restoration (v5) ----
    // Everything the editor needs to look exactly like the user left it.
    // Canvas + captions + media overlays + playhead used to be lost on
    // reopen because they were never written down at all.
    /**
     * Name of a `model.CanvasPreset` entry, e.g. "WIDE_16_9".
     *
     * 2026-09-30 — defaults to `"FREE"` ("track the source"), not
     * `"WIDE_16_9"`. A row inserted without an explicit preset has no
     * known orientation, and asserting 16:9 on it forced a landscape
     * canvas onto portrait footage. `MIGRATION_4_5`'s historical
     * `DEFAULT 'WIDE_16_9'` is left as-is: it is the immutable record of a
     * migration that already ran on installed databases, and rewriting it
     * would only diverge from those devices.
     */
    val canvasPreset: String = "FREE",
    /** Encoded canvas background: "BLACK", "BLUR" or "COLOR:<argb>". */
    val canvasBackground: String = "BLACK",
    /** [EditorStateJson.textOverlaysToJson] blob for captions/subtitles. */
    val textOverlaysJson: String = "[]",
    /** [EditorStateJson.mediaOverlaysToJson] blob for media overlays. */
    val mediaOverlaysJson: String = "[]",
    /** Last playhead position (ms) to restore on reopen. */
    val lastPositionMs: Long = 0L,
    // ---- AI tools (v6) ----
    /**
     * [EditorStateJson.aiToolsToJson] blob keyed by `clipId`. Each
     * entry is a small object with the four per-clip AI tool
     * settings: matting, autoFrame, cropKeyframes, voiceClean. The
     * `VideoClip` model itself carries the same fields in memory;
     * this blob is the persistence shim that survives project
     * save/reopen.
     */
    val aiToolsJson: String = "{}",
    // ---- Audio clips (v7) ----
    /**
     * [EditorStateJson.audioClipsToJson] blob for the synchronized
     * video-original audio timeline (PART 13-19 of the brief). Saved
     * on the project row so the timeline audio survives
     * SAVE -> EXIT -> REOPEN, including the [AudioClip.originVideoClipId]
     * link that pairs each audio clip back to its source video.
     */
    val audioClipsJson: String = "[]",
    // ---- Video progress bar (v8) ----
    /**
     * [EditorStateJson.progressBarSettingsToJson] blob for the project's
     * video progress bar (timeline indicator / story timer). Defaults to
     * `"{}"`, which
     * [EditorStateJson.progressBarSettingsFromJson] decodes to
     * [com.aicreatorstudio.app.model.ProgressBarSettings.Disabled] — so a
     * v7 row migrates in place and the feature stays off until the user
     * turns it on.
     */
    val progressBarSettingsJson: String = "{}"
)

@Entity(tableName = "clips")
data class VideoClipEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val uri: String,
    val fileName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fileSizeBytes: Long,
    val importedAtEpochMillis: Long,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val timelineStartMs: Long = -1L,
    val isAudioOnly: Boolean = false,
    val audioCategory: String = "music",
    val transitionIn: String? = null,
    val transitionOut: String? = null,
    val animation: String = "NONE",
    val cropPreset: String = "FREE",
    val cropAspectRatio: Float = 0f,
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val rotation: Int = 0
)