package com.aicreatorstudio.app.ai.subtitle.cloud

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.AudioExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-09-13 GROQ REBUILD — extracts the AUDIO TRACK of a video
 * into a REAL, readable, standalone audio file for upload to the
 * Groq API. The MP4 container itself is NEVER uploaded.
 *
 * ## Strategy (brief priority: WAV > M4A/AAC > MP3)
 *
 * For a VIDEO source the most reliable Android-native method is
 * **remuxing the compressed audio track into an .m4a**
 * (`MediaExtractor` + `MediaMuxer`):
 *  - pure container I/O — no decode, no re-encode, runs at disk
 *    speed, works on every device that can play the video;
 *  - the extracted file is a real audio file with correct
 *    metadata (Groq's documented supported types include m4a).
 *
 * A full WAV transcode is only used as the FALLBACK when remux
 * fails (corrupt container, unsupported track write): the audio
 * is decoded via the shared [AudioExtractor] into 16 kHz mono
 * PCM and wrapped in a WAV header. This fallback holds PCM in
 * memory, so it is only taken when the cheap remux path fails.
 *
 * For an AUDIO-ONLY source (.m4a/.wav/.mp3 clip on the timeline)
 * the existing file is already a real audio file — it is
 * returned as-is (no copy, no upload of any video container).
 *
 * ## Memory
 *
 * The remux path streams through a 1 MiB direct buffer — the
 * whole audio track is never held in RAM. The WAV fallback holds
 * the decoded PCM (unavoidable for a re-encode) and is guarded by
 * the caller's length check before being attempted.
 *
 * ## Progress + cancellation
 *
 * [onProgress] is the REAL extraction fraction (sample PTS /
 * track duration). [checkCancelled] is invoked between samples
 * so a cancelled generation aborts instantly. Both are wired
 * through by the engine into the UI state machine.
 */
@Singleton
class SubtitleAudioExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioExtractor: AudioExtractor,
    private val tempFiles: TemporaryFileManager
) {

    /** What came out of an extraction. */
    data class ExtractedAudio(
        /** The real, readable audio file (caller uploads THIS). */
        val file: File,
        /** Exact MIME for the Content-Type header. */
        val mimeType: String,
        /** How the file was produced — for logs and error text. */
        val method: Method
    ) {
        enum class Method { REMUX_M4A, TRANSCODE_WAV, SOURCE_ALREADY_AUDIO }
    }

    /**
     * Extract [sourceUri]'s audio track into a temp file.
     *
     * @param sourceUri the video (or audio) Uri the user selected.
     * @param onProgress real 0..1 extraction fraction (remux path).
     * @param checkCancelled throws to abort (Job.ensureActive).
     * @throws Exception with a Persian message when no audio track
     *   exists or the extraction produced an empty/unreadable file.
     */
    suspend fun extract(
        sourceUri: Uri,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): ExtractedAudio = withContext(Dispatchers.IO) {
        val hostJob = currentCoroutineContext()[Job]

        // ---- video container: remux the compressed audio track ----
        val remuxed = runCatching {
            remuxAudioTrackToM4a(sourceUri, onProgress, checkCancelled)
        }.onFailure { e ->
            if (hostJob?.isActive != true) throw kotlinx.coroutines.CancellationException("cancelled")
            Log.w(TAG, "remux extraction failed — trying WAV transcode fallback", e)
        }.getOrNull()
        if (remuxed != null) return@withContext remuxed

        // ---- fallback: decode + transcode to 16 kHz mono WAV ----
        //
        // 2026-10-20 — two fixes:
        //  1. RESAMPLE TO 16 kHz. The fallback used to write the WAV at
        //     the SOURCE rate (48 kHz → 96 kB/s), so any video longer
        //     than ~4.3 minutes produced a file over Groq's 25 MB
        //     limit and the upload was rejected with a size error.
        //     16 kHz mono is Whisper's native rate (32 kB/s) and gives
        //     ~13 minutes of headroom.
        //  2. REPORT PROGRESS. The old path emitted no progress at
        //     all, so the card sat at "0%" for the whole decode.
        onProgress(0.05f)
        val (pcm, sampleRate, channels) = audioExtractor.decodeTo16kMonoFloat(
            sourceUri,
            onProgress = { onProgress(0.05f + it.coerceIn(0f, 1f) * 0.8f) },
            checkCancelled = checkCancelled
        )
        if (pcm.isEmpty()) {
            throw Exception("فایل صوتی از ویدیو استخراج نشد (ترک صوتی خالی است).")
        }
        checkCancelled?.invoke()
        val wav = writeMonoPcmWav(pcm, sampleRate)
        if (wav.length() <= 44L) {
            tempFiles.delete(wav)
            throw Exception("فایل صوتی استخراج‌شده خالی است.")
        }
        ExtractedAudio(
            file = wav,
            mimeType = MIME_WAV,
            method = ExtractedAudio.Method.TRANSCODE_WAV
        )
    }

    // ---- remux path (preferred for video sources) ----

    /**
     * Copy the FIRST compressed audio track of [sourceUri] into a
     * standalone .m4a. Returns null (via runCatching) when the
     * source has no audio track, is audio-only already, or the
     * muxer cannot produce a valid file.
     */
    private fun remuxAudioTrackToM4a(
        sourceUri: Uri,
        onProgress: (Float) -> Unit,
        checkCancelled: (() -> Unit)?
    ): ExtractedAudio? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var out: File? = null
        try {
            extractor.setDataSource(context, sourceUri, null)
            var audioTrackIndex = -1
            var videoTrackFound = false
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val tf = extractor.getTrackFormat(i)
                val mime = tf.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") -> videoTrackFound = true
                    mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                        audioTrackIndex = i
                        audioFormat = tf
                    }
                }
            }
            // No audio at all → hard failure with a clear message.
            if (audioTrackIndex == -1 || audioFormat == null) {
                throw Exception("هیچ ترک صوتی در ویدیو پیدا نشد.")
            }
            // Audio-only source → the file IS the audio.
            // 2026-10-20 — CRITICAL DATA-LOSS FIX.
            //
            // The old code returned the USER'S OWN file unchanged
            // (`ExtractedAudio(f, ...)`), and the engine's `finally`
            // block then deleted it. For an imported music track that
            // meant: pick the music → run cloud subtitles → the music
            // file was erased from disk and the project lane broke.
            //
            // The extractor now always hands back a file it OWNS: the
            // source is copied into the temp directory first.
            //
            // 2026-09-13 — the MIME now reflects the REAL container
            // (.wav → audio/wav, .mp3 → audio/mpeg, else audio/mp4)
            // so the upload's Content-Type is truthful. content://
            // sources (SFX resources) cannot be mapped here directly
            // and fall through to the WAV transcode path.
            if (!videoTrackFound) {
                val path = sourceUri.path
                if (path != null && sourceUri.scheme == "file") {
                    val f = File(path)
                    if (f.exists() && f.length() > 0) {
                        val mime = when {
                            path.endsWith(".wav", ignoreCase = true) ||
                                path.endsWith(".wave", ignoreCase = true) -> MIME_WAV
                            path.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                            else -> MIME_M4A
                        }
                        val ext = when {
                            path.endsWith(".wav", ignoreCase = true) ||
                                path.endsWith(".wave", ignoreCase = true) -> "wav"
                            path.endsWith(".mp3", ignoreCase = true) -> "mp3"
                            else -> "m4a"
                        }
                        val owned = tempFiles.newAudioFile(ext)
                        f.inputStream().use { input ->
                            owned.outputStream().use { output -> input.copyTo(output) }
                        }
                        onProgress(1f)
                        return ExtractedAudio(
                            owned, mime, ExtractedAudio.Method.SOURCE_ALREADY_AUDIO
                        )
                    }
                }
                // content:// audio (e.g. a picked file not yet
                // copied) — decode + re-wrap via the WAV fallback.
                return null
            }

            val format = audioFormat
            val durationUs = readDurationUs(format)
                .takeIf { it > 0 }
                ?: metadataDurationUs(sourceUri)

            extractor.selectTrack(audioTrackIndex)
            out = tempFiles.newAudioFile("m4a")
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrack = muxer.addTrack(format)
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val buffer = java.nio.ByteBuffer.allocateDirect(BUFFER_BYTES)
            var lastEmitMs = 0L
            var lastWriteWallMs = android.os.SystemClock.elapsedRealtime()
            var lastPts = -1L
            var ptsFreezeRounds = 0
            var samplesWritten = 0L
            if (durationUs > 0) onProgress(0f)

            while (true) {
                checkCancelled?.invoke()
                val wallNow = android.os.SystemClock.elapsedRealtime()
                if (wallNow - lastWriteWallMs > STALL_WALL_MS) {
                    throw Exception("استخراج صدا متوقف شد (۳۰ ثانیه پیشرفت نبود).")
                }
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                info.set(0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
                samplesWritten++
                lastWriteWallMs = wallNow
                if (extractor.sampleTime == lastPts) {
                    if (++ptsFreezeRounds > PTS_FREEZE_LIMIT) {
                        throw Exception("استخراج صدا متوقف شد (timestamp ثابت ماند).")
                    }
                } else {
                    ptsFreezeRounds = 0
                    lastPts = extractor.sampleTime
                }
                if (durationUs > 0 && wallNow - lastEmitMs >= 100L) {
                    lastEmitMs = wallNow
                    onProgress(
                        (extractor.sampleTime.coerceAtLeast(0L).toFloat() / durationUs)
                            .coerceIn(0f, 1f)
                    )
                }
            }
            runCatching { muxer.stop() }
            if (samplesWritten == 0L || out.length() == 0L) {
                tempFiles.delete(out)
                throw Exception("استخراج صدا ناقص بود (نمونه‌ای نوشته نشد).")
            }
            onProgress(1f)
            Log.i(TAG, "remux ok: ${out.length()} bytes, $samplesWritten samples")
            return ExtractedAudio(out, MIME_M4A, ExtractedAudio.Method.REMUX_M4A)
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    // ---- WAV fallback writer ----

    /** 16-bit PCM mono WAV (44-byte RIFF header + data). */
    private fun writeMonoPcmWav(pcm: FloatArray, sampleRate: Int): File {
        val out = tempFiles.newAudioFile("wav")
        java.io.FileOutputStream(out).use { fos ->
            val dataSize = pcm.size * 2
            fun le32(v: Int) {
                fos.write(v and 0xFF); fos.write((v shr 8) and 0xFF)
                fos.write((v shr 16) and 0xFF); fos.write((v shr 24) and 0xFF)
            }
            fun le16(v: Int) { fos.write(v and 0xFF); fos.write((v shr 8) and 0xFF) }
            fos.write("RIFF".toByteArray()); le32(36 + dataSize)
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray()); le32(16)
            le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2)
            le16(2); le16(16)
            fos.write("data".toByteArray()); le32(dataSize)
            val buf = ByteArray(8192)
            var bi = 0
            for (v in pcm) {
                val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
                buf[bi++] = (s and 0xFF).toByte()
                buf[bi++] = ((s shr 8) and 0xFF).toByte()
                if (bi == buf.size) { fos.write(buf); bi = 0 }
            }
            if (bi > 0) fos.write(buf, 0, bi)
        }
        return out
    }

    // ---- duration helpers (Integer/Long-safe, same as AudioExtractor) ----

    private fun readDurationUs(format: MediaFormat): Long {
        if (!format.containsKey(MediaFormat.KEY_DURATION)) return 0L
        runCatching { return format.getLong(MediaFormat.KEY_DURATION) }
        return runCatching { format.getInteger(MediaFormat.KEY_DURATION).toLong() }
            .getOrDefault(0L)
    }

    private fun metadataDurationUs(uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val ms = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            ms * 1000L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TAG = "SubtitleAudioExtractor"
        const val MIME_WAV = "audio/wav"
        const val MIME_M4A = "audio/mp4"
        private const val BUFFER_BYTES = 1 shl 20
        private const val STALL_WALL_MS = 30_000L
        private const val PTS_FREEZE_LIMIT = 200
    }
}
