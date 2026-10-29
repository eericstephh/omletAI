package com.aicreatorstudio.app.ui.screens.editor

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aicreatorstudio.app.model.AudioFade
import com.aicreatorstudio.app.model.VoiceEffect

/**
 * 2026-09-13 — parallel audio playback for AUDIO-ONLY clips (imported
 * .wav/.mp3/.m4a, built-in SFX) on the timeline.
 *
 * ## The bug this fixes
 *
 * The editor previously had exactly ONE ExoPlayer bound to the first
 * VIDEO clip. Audio-only clips rendered as tiles on the Music/SFX
 * lanes but nothing ever played them — importing a song produced a
 * silent timeline no matter where the playhead was.
 *
 * ## Design
 *
 * One small ExoPlayer per audio-only clip ("stem player"), all
 * slaved to the editor's shared clock:
 *
 *  - `syncTo(positionMs, playing)` — called by the screen's poller
 *    and scrub handlers; each stem seeks to
 *    `positionMs − clip.timelineStartMs` (clamped to its trimmed
 *    window) and starts/pauses with the master.
 *  - `setPlaying(playing)` — play/pause without re-seeking.
 *  - `setSpeed(speed)` — global playback speed applies to stems.
 *  - `update(clips)` — diff-based rebuild: players for removed
 *    clips are released, new ones are created; unchanged clips keep
 *    their player (no restart glitch when the timeline moves).
 *  - volume/mute per clip are applied from the clip model on every
 *    sync so the Volume panel changes take effect immediately.
 *
 * Stems outside the playhead window are PAUSED and left seeked to
 * the window entry point, so entry is instant when the playhead
 * reaches them.
 *
 * All operations are main-thread only (ExoPlayer requirement) and
 * must be called from the composition; the class is owned by a
 * `remember {}` block and released via [release].
 */
class AudioMixerPlayer(private val context: Context) {

    /** Minimal playback fields the mixer needs per audio-only clip. */
    data class StemClip(
        val id: String,
        val uri: String,
        /** Timeline start (ms). */
        val startMs: Long,
        /** Effective on-timeline duration after trim (ms). */
        val durationMs: Long,
        /** Where to start reading inside the source (ms). */
        val trimStartMs: Long,
        val volume: Float,
        val muted: Boolean,
        /** Audio fade-in length in clip-local ms; `0` = none. */
        val fadeInMs: Long = 0L,
        /** Audio fade-out length measured back from [durationMs]; `0` = none. */
        val fadeOutMs: Long = 0L,
        /**
         * 2026-10-13 — Voice Changer effect for this stem.
         *
         * Appended last so every existing `StemClip(...)` call site keeps
         * its meaning; a standalone song or SFX can be run through the
         * same voice DSP as a video clip's own track.
         */
        val voiceEffect: VoiceEffect = VoiceEffect.NONE
    )

    private data class Stem(
        val clip: StemClip,
        val player: ExoPlayer,
        /**
         * 2026-10-13 — this stem's voice changer. One bridge per stem,
         * not one shared: two songs on the timeline can carry different
         * effects, and a shared bridge would let the second one overwrite
         * the first's effect on every sync.
         */
        val voice: VoiceEffectPreviewBridge
    )

    private val stems = LinkedHashMap<String, Stem>()
    private var lastSyncMs = 0L
    private var playing = false
    private var speed = 1f

    /**
     * Reconcile the player set with [clips]. Creates players for new
     * audio clips, releases players whose clip disappeared, and
     * updates position/volume/mute for the rest. Cheap (a no-op when
     * nothing changed) — safe to call on every recomposition.
     */
    fun update(clips: List<StemClip>) {
        val wanted = clips.associateBy { it.id }
        // Release stems whose clip is gone.
        val toRemove = stems.keys.filter { it !in wanted }
        toRemove.forEach { id ->
            runCatching { stems.remove(id)?.player?.release() }
        }
        // Create stems for new clips.
        wanted.values.forEach { clip ->
            if (!stems.containsKey(clip.id)) {
                runCatching {
                    // 2026-10-13 — every stem gets its own voice bridge,
                    // so two songs can carry two different effects.
                    val voice = VoiceEffectPreviewBridge()
                    voice.set(clip.voiceEffect)
                    val player = ExoPlayer.Builder(
                        context,
                        VoiceEffectRenderersFactory(context, voice)
                    ).build().apply {
                        playWhenReady = false
                        volume = if (clip.muted) 0f else clip.volume.coerceIn(0f, 1f)
                        setMediaItem(MediaItem.fromUri(clip.uri))
                        prepare()
                    }
                    stems[clip.id] = Stem(clip, player, voice)
                }.onFailure { e ->
                    Log.w(TAG, "stem player create failed for ${clip.id}", e)
                }
            } else {
                // Existing stem: refresh model data (position/volume
                // may have changed) without rebuilding the player.
                val old = stems[clip.id]!!
                stems[clip.id] = old.copy(clip = clip)
                // 2026-10-13 — retarget the voice effect. Cheap and
                // idempotent: the DSP is only rebuilt when the resolved
                // profile actually differs, so a sync at 33 ms does not
                // restart the filter or the delay line.
                runCatching { old.voice.set(clip.voiceEffect) }
                runCatching {
                    old.player.volume = if (clip.muted) 0f
                    else clip.volume.coerceIn(0f, 1f)
                }
            }
        }
    }

    /**
     * Drive every stem to the shared clock. `timelineMs` is the
     * CURRENT playhead position; stems whose window contains it play
     * from the right source offset, others pause at their entry
     * point.
     */
    fun syncTo(timelineMs: Long) {
        lastSyncMs = timelineMs
        stems.values.forEach { stem ->
            runCatching {
                val clip = stem.clip
                val player = stem.player
                val localMs = timelineMs - clip.startMs
                val inWindow = localMs in 0 until clip.durationMs
                if (inWindow) {
                    val sourceMs = clip.trimStartMs + localMs
                    // Re-seek only when we're off by more than the
                    // tolerance — otherwise the natural clock advance
                    // of the stem player IS the correct position.
                    val toleranceMs = if (playing) SEEK_TOLERANCE_PLAY_MS
                    else SEEK_TOLERANCE_PAUSE_MS
                    if (kotlin.math.abs(player.currentPosition - sourceMs) > toleranceMs) {
                        player.seekTo(sourceMs.coerceAtLeast(0L))
                    }
                    player.playWhenReady = playing
                    // 2026-09-16 — per-clip audio fade. The gain curve is
                    // shared with the export via `AudioFade`, so what the
                    // user hears here is what gets baked into the MP4.
                    // Applied on every sync (33 ms), which is finer than
                    // the ear can resolve as steps.
                    val base = if (clip.muted) 0f else clip.volume.coerceIn(0f, 1f)
                    val gain = AudioFade.gainAt(
                        clipLocalMs = localMs.toDouble(),
                        clipDurationMs = clip.durationMs,
                        fadeInMs = clip.fadeInMs,
                        fadeOutMs = clip.fadeOutMs
                    )
                    player.volume = base * gain
                } else {
                    player.playWhenReady = false
                    // Park paused stems at their entry point so the
                    // next window entry is instant.
                    val entry = if (localMs < 0) clip.trimStartMs else -1L
                    if (entry >= 0 &&
                        kotlin.math.abs(player.currentPosition - entry) > PARK_TOLERANCE_MS
                    ) {
                        player.seekTo(entry)
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "stem sync failed for ${stem.clip.id}", e)
            }
        }
    }

    /** Play/pause every stem without re-seeking. */
    fun setPlaying(playing: Boolean) {
        this.playing = playing
        stems.values.forEach { stem ->
            runCatching { stem.player.playWhenReady = playing }
        }
    }

    /** Apply the global playback speed to every stem. */
    fun setSpeed(speed: Float) {
        this.speed = speed
        val params = androidx.media3.common.PlaybackParameters(speed)
        stems.values.forEach { stem ->
            runCatching { stem.player.playbackParameters = params }
        }
    }

    /**
     * Pause everything (e.g. ON_STOP / a tool panel opening). Does
     * NOT change the user-facing `playing` state — [syncTo] with
     * `playing=false` does that; this is a hard external pause.
     */
    fun pauseAll() {
        stems.values.forEach { stem ->
            runCatching { stem.player.playWhenReady = false }
        }
    }

    /** Release every stem player. Call from onDispose. */
    fun release() {
        stems.values.forEach { stem ->
            runCatching { stem.player.release() }
        }
        stems.clear()
    }

    companion object {
        private const val TAG = "AudioMixerPlayer"
        // While playing, the stem's own clock is the source of truth;
        // only a real drift (user jump/undo) re-seeks.
        private const val SEEK_TOLERANCE_PLAY_MS = 350L
        // Paused stems re-seek precisely (cheap — nothing is decoding).
        private const val SEEK_TOLERANCE_PAUSE_MS = 40L
        // Parked (out-of-window) stems only need a coarse entry park.
        private const val PARK_TOLERANCE_MS = 250L
    }
}
