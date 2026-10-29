package com.aicreatorstudio.app.ui.screens.editor

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/**
 * 2026-10-13 — preview playback for picture-in-picture **video**
 * overlays.
 *
 * ## Why a second player pool exists
 *
 * The editor's main `ExoPlayer` holds exactly one media item (the first
 * video clip), so it cannot also show a PiP. Overlays therefore get their
 * own small player each, slaved to the same clock the audio stems use —
 * the same architecture as [AudioMixerPlayer], for the same reason.
 *
 * ## Why the players are MUTED
 *
 * The export composites the overlay's **video frames only**: the PiP
 * pre-pass in `AiExportHooks` copies the segment's audio through
 * sample-for-sample and never touches the overlay's soundtrack. Playing
 * the overlay's audio in the preview would therefore promise something
 * the exported file does not deliver — a preview/export mismatch, which
 * is the exact class of bug this project keeps fixing. Silence in both
 * places is the honest pairing.
 *
 * ## Sync model
 *
 * [syncTo] is called from the screen's existing playhead poller with the
 * **editor-timeline** position. An overlay inside its window seeks to
 * `editorMs − startMs` and follows the master's play/pause; one outside
 * its window is paused and parked at `0` so entering the window is
 * instant. Drift correction only kicks in past a tolerance, because while
 * playing the player's own clock is the more accurate position.
 *
 * Main-thread only (an `ExoPlayer` requirement); owned by a `remember {}`
 * and released in `onDispose`.
 */
class OverlayPipPlayer(private val context: Context) {

    /** The minimum an overlay must expose to be played. */
    data class PipClip(
        val id: String,
        val uri: String,
        val startMs: Long,
        val endMs: Long
    )

    private data class Pip(val clip: PipClip, val player: ExoPlayer)

    private val pips = LinkedHashMap<String, Pip>()
    private var playing = false

    /**
     * Reconcile the player set with [clips]. Creates players for new
     * overlays, releases players whose overlay disappeared, and refreshes
     * the rest in place — so moving the playhead never restarts a player
     * that did not change. Cheap when nothing changed, so it is safe to
     * call on every recomposition.
     */
    fun update(clips: List<PipClip>) {
        val wanted = clips.associateBy { it.id }
        val stale = pips.keys.filter { it !in wanted }
        stale.forEach { id ->
            runCatching { pips.remove(id)?.player?.release() }
        }
        wanted.values.forEach { clip ->
            val existing = pips[clip.id]
            if (existing == null) {
                runCatching {
                    val player = ExoPlayer.Builder(context).build().apply {
                        playWhenReady = false
                        // See the class KDoc: the export does not carry the
                        // overlay's audio, so the preview must not either.
                        volume = 0f
                        setMediaItem(MediaItem.fromUri(clip.uri))
                        prepare()
                    }
                    pips[clip.id] = Pip(clip, player)
                }.onFailure { e ->
                    Log.w(TAG, "overlay player create failed for ${clip.id}", e)
                }
            } else {
                // The media URI changed (the user re-picked the overlay) —
                // swap the item rather than leaking the old one.
                if (existing.clip.uri != clip.uri) {
                    runCatching {
                        existing.player.setMediaItem(MediaItem.fromUri(clip.uri))
                        existing.player.prepare()
                    }
                }
                pips[clip.id] = existing.copy(clip = clip)
            }
        }
    }

    /**
     * Drive every overlay player to the shared clock. [editorMs] is the
     * **editor timeline** position — the same value the playhead and the
     * audio stems use, so an overlay's `startTimeMs` needs no rebasing.
     */
    fun syncTo(editorMs: Long) {
        pips.values.forEach { pip ->
            runCatching {
                val clip = pip.clip
                val player = pip.player
                val localMs = editorMs - clip.startMs
                val inWindow = clip.endMs > clip.startMs &&
                    localMs in 0 until (clip.endMs - clip.startMs)
                if (inWindow) {
                    val tolerance = if (playing) SEEK_TOLERANCE_PLAY_MS else SEEK_TOLERANCE_PAUSE_MS
                    if (kotlin.math.abs(player.currentPosition - localMs) > tolerance) {
                        player.seekTo(localMs.coerceAtLeast(0L))
                    }
                    player.playWhenReady = playing
                } else {
                    player.playWhenReady = false
                    // Park at the head of the window so entering it is
                    // instant and the first frame is already decoded.
                    if (localMs < 0 && player.currentPosition > PARK_TOLERANCE_MS) {
                        player.seekTo(0L)
                    }
                }
            }.onFailure { e ->
                Log.w(TAG, "overlay sync failed for ${pip.clip.id}", e)
            }
        }
    }

    /** Play/pause every overlay without re-seeking. */
    fun setPlaying(playing: Boolean) {
        this.playing = playing
        pips.values.forEach { pip ->
            runCatching { pip.player.playWhenReady = playing }
        }
    }

    /**
     * The player backing [overlayId], for the preview to bind to a
     * `PlayerView`. Null when the overlay is an image, unreadable, or was
     * never added.
     */
    fun playerFor(overlayId: String): ExoPlayer? = pips[overlayId]?.player

    /** True when [overlayId] has a live player. */
    fun isPlaying(overlayId: String): Boolean = pips.containsKey(overlayId)

    /** Release every overlay player. Call from `onDispose`. */
    fun release() {
        pips.values.forEach { pip ->
            runCatching { pip.player.release() }
        }
        pips.clear()
    }

    private companion object {
        const val TAG = "OverlayPipPlayer"
        // While playing, the player's own clock is the source of truth;
        // only a real jump re-seeks.
        const val SEEK_TOLERANCE_PLAY_MS = 250L
        // A paused player re-seeks precisely — it is not decoding.
        const val SEEK_TOLERANCE_PAUSE_MS = 40L
        // Parked players only need a coarse reset.
        const val PARK_TOLERANCE_MS = 60L
    }
}
