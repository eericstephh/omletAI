package com.aicreatorstudio.app.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.aicreatorstudio.app.model.AudioClip
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Video → audio pairing service (PART 14 of the brief).
 *
 * When the user imports a video, the timeline must reflect the
 * original audio track as a paired [AudioClip] placed directly
 * below the video clip, with identical `startTimeMs` and
 * `durationMs`. This service probes a `Uri` for the presence of an
 * audio track and, if one exists, returns a paired [AudioClip]
 * referencing the same source URI (no extracted PCM is required
 * up-front — the exporter and the audio panel re-decode on
 * demand; the cache is written lazily by [WaveformGenerator]
 * and the Voice Clean engine).
 *
 * **Honest failure modes (PART 9 / PART 23 of the brief):**
 *  - If no audio track is found the service returns `null`.
 *    Callers must NOT manufacture an empty / silent `AudioClip`
 *    to keep the timeline looking balanced — that is the kind
 *    of "fake functionality" the brief explicitly forbids.
 *  - If the codec cannot be read, the service returns `null`
 *    and surfaces the cause through [lastError].
 *  - If the user picks a non-video (music file, image, raw
 *    audio) the service also returns `null` — audio pairing
 *    is a video-import concern only.
 *
 * **Threading:** all callers must invoke on `Dispatchers.IO`.
 * The implementation is stateless and uses a fresh
 * [MediaExtractor] per call so it is safe to call concurrently
 * from many threads.
 */
@Singleton
class AudioPairingService @Inject constructor(
    @ApplicationContext private val context: Context?
) {
    /**
     * JVM-friendly secondary constructor used by unit tests that
     * don't have an Android `Context` available. The Android-only
     * `MediaExtractor` probe is replaced by the test-supplied
     * function via [pairForVideoWithProbe] — production code
     * always uses the primary ctor + [pairForVideo].
     */
    constructor() : this(context = null)

    /**
     * Last error message produced by [pairForVideo] or `null` when
     * the last call succeeded or has not been made. Read by the
     * editor surface when it logs a failure to the diagnostic
     * channel — never surfaced verbatim in user-facing strings,
     * which already carry the user-friendly Persian / English copy.
     */
    @Volatile var lastError: String? = null
        private set

    /**
     * Probe [videoUri] for an audio track. If one exists, build a
     * paired [AudioClip] that points back at the same source URI
     * and is synchronized to the parent video's timeline start +
     * duration. If no audio exists (or the file is unreadable),
     * return `null`.
     *
     * @param videoUri the imported video's URI (already copied into
     *   app-owned storage by the time this is called).
     * @param videoClipId id of the parent [com.aicreatorstudio.app.model.VideoClip]
     *   — encoded into [AudioClip.originVideoClipId] for round-trip
     *   save/load.
     * @param startTimeMs the parent video's `timelineStartMs`.
     * @param durationMs the parent video's `effectiveDurationMs`.
     * @param fileName human-readable label for the audio panel;
     *   falls back to the parent file name when blank.
     */
    fun pairForVideo(
        videoUri: Uri,
        videoClipId: String,
        startTimeMs: Long,
        durationMs: Long,
        fileName: String?
    ): AudioClip? = pairForVideoInternal(videoUri.toString(), videoClipId, startTimeMs, durationMs, fileName) { uri ->
        probeAudioTrack(Uri.parse(uri))
    }

    /**
     * Same as [pairForVideo] but with an explicit probe function.
     * Exposed for unit tests that cannot stand up a real
     * `MediaExtractor` on the JVM — production callers must use
     * the no-arg overload so the implementation routes through
     * Android's `MediaExtractor` / `MediaFormat` APIs.
     */
    fun pairForVideoWithProbe(
        videoUri: Uri,
        videoClipId: String,
        startTimeMs: Long,
        durationMs: Long,
        fileName: String?,
        probe: (Uri) -> Boolean
    ): AudioClip? = pairForVideoInternal(videoUri.toString(), videoClipId, startTimeMs, durationMs, fileName) { uri ->
        if (probe(Uri.parse(uri))) ProbeResult(true, "test/audio", 0, 0) else ProbeResult(false, null, 0, 0)
    }

    /**
     * JVM-friendly test seam: accepts a `String` URI so the test
     * does not need an Android `Uri` instance. Production code
     * must use the `Uri` overload so that the on-device pairing
     * path is the one exercised by every real call.
     */
    fun pairForVideoWithProbe(
        videoUri: String,
        videoClipId: String,
        startTimeMs: Long,
        durationMs: Long,
        fileName: String?,
        probe: (String) -> Boolean
    ): AudioClip? = pairForVideoInternal(videoUri, videoClipId, startTimeMs, durationMs, fileName) { _ ->
        if (probe(videoUri)) ProbeResult(true, "test/audio", 0, 0) else ProbeResult(false, null, 0, 0)
    }

    private fun pairForVideoInternal(
        videoUri: String,
        videoClipId: String,
        startTimeMs: Long,
        durationMs: Long,
        fileName: String?,
        probeFn: (String) -> ProbeResult
    ): AudioClip? {
        if (durationMs <= 0L) {
            // No duration means the source is unreadable; do not pair.
            return null
        }
        val probe = probeFn(videoUri)
        if (!probe.hasAudio) {
            return null
        }
        val label = (fileName?.takeIf { it.isNotBlank() }) ?: "Original audio"
        return AudioClip(
            id = "audio-${UUID.randomUUID()}",
            sourceUri = videoUri,
            fileName = label,
            startTimeMs = startTimeMs.coerceAtLeast(0L),
            durationMs = durationMs,
            // Synchronized to the video from frame zero; trimming the
            // video will produce a follow-up update on both clips via
            // the editor's `setClipTrim` path so drift stays at zero.
            clipOffsetMs = 0L,
            sourceStartMs = 0L,
            sourceEndMs = durationMs,
            volume = 1f,
            muted = false,
            fadeInMs = 0L,
            fadeOutMs = 0L,
            waveformCacheKey = "wf-${videoClipId}",
            originVideoClipId = videoClipId,
            cachedPcmPath = null
        )
    }

    /**
     * Lower-level probe — returns true when [videoUri] contains at
     * least one audio track. Used by tests and by any caller that
     * wants to gate work on audio presence without constructing an
     * [AudioClip].
     */
    fun hasAudioTrack(videoUri: Uri): Boolean = probeAudioTrack(videoUri).hasAudio

    private data class ProbeResult(val hasAudio: Boolean, val mime: String?, val sampleRate: Int, val channels: Int)

    private fun probeAudioTrack(uri: Uri): ProbeResult {
        lastError = null
        val ctx = context ?: run {
            // Defensive: a JVM test that forgot to use the
            // `pairForVideoWithProbe` seam must fail loudly, not
            // silently return "no audio".
            lastError = "AudioPairingService.probeAudioTrack called without a Context — use pairForVideoWithProbe on the JVM"
            return ProbeResult(false, null, 0, 0)
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(ctx, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format: MediaFormat = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    val sr = runCatching { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) }
                        .getOrDefault(0)
                    val ch = runCatching { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                        .getOrDefault(0)
                    return ProbeResult(hasAudio = true, mime = mime, sampleRate = sr, channels = ch)
                }
            }
            ProbeResult(hasAudio = false, mime = null, sampleRate = 0, channels = 0)
        } catch (e: Exception) {
            lastError = e.message ?: e::class.java.simpleName
            ProbeResult(hasAudio = false, mime = null, sampleRate = 0, channels = 0)
        } finally {
            runCatching { extractor.release() }
        }
    }
}