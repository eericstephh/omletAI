package com.aicreatorstudio.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 2026-10-21 — the «نریشن» (voiceover) recorder.
 *
 * CapCut's voiceover records a narration over the video and drops it
 * on the timeline as its own audio object. Everything downstream of
 * that already existed in this project (the audio lane, audio clips
 * with volume and fades, the mixer, the offline voice changer); what
 * was missing was the capture itself and the `RECORD_AUDIO` grant.
 *
 * ## Why `MediaRecorder` and not `AudioRecord`
 *
 * `AudioRecord` gives raw PCM, which would mean writing a WAV
 * container by hand and then encoding AAC before the export could mix
 * it. `MediaRecorder` writes AAC in an MPEG-4 container directly, and
 * that file is exactly what the export's audio mixer wants: it is a
 * normal media file with a normal duration, so it goes through the
 * same path as an imported music file — no special case downstream.
 *
 * ## The output file
 *
 * The take is written into the app's own `filesDir/voiceovers/`, so
 * it survives the app being killed and it needs no storage permission
 * (the project's rule for imported media is the same: copy into
 * app-owned storage rather than rely on a transient content grant).
 *
 * ## Failure handling
 *
 * Every entry point returns a [Result] rather than throwing:
 * `MediaRecorder.stop()` in particular throws when the recording was
 * too short to produce a valid file, and that is a *user* outcome
 * ("held the button for a fraction of a second"), not a crash.
 */
class VoiceoverRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtEpochMs: Long = 0L

    /** True while a recording is running. */
    val isRecording: Boolean get() = recorder != null

    /**
     * The live input level, 0..1.
     *
     * `MediaRecorder.maxAmplitude` returns the peak of the last
     * recorded buffer (0..32767). It is normalised against a
     * reference of 16000 rather than 32767 so that ordinary speech
     * moves the meter visibly — the raw scale is very compressed at
     * the bottom and a meter built on it looks dead.
     */
    fun level(): Float {
        val active = recorder ?: return 0f
        return runCatching {
            val raw = active.maxAmplitude
            if (raw <= 0) 0f else (raw / 16000f).coerceIn(0f, 1f)
        }.getOrDefault(0f)
    }

    /**
     * Begin recording into [output].
     *
     * Any previous recording is released first, so a failed start
     * never leaves a dangling recorder holding the microphone.
     */
    fun start(output: File): Result<Unit> = runCatching {
        release()
        output.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ takes the context, so the recorder is bound to
            // this app's process rather than to a leaked service.
            MediaRecorder(context)
        } else {
            // Below API 31 the no-argument constructor is the only one
            // that exists. It is deprecated but not removed, and the
            // project supports API 24+.
            MediaRecorder()
        }
        created.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(output.absolutePath)
            prepare()
            start()
        }
        recorder = created
        outputFile = output
        startedAtEpochMs = System.currentTimeMillis()
    }.onFailure {
        release()
        outputFile = null
    }

    /**
     * Stop the recording and return the captured file.
     *
     * Fails when the take was too short to produce a usable file —
     * `MediaRecorder.stop()` throws in that case — or when the file
     * is empty. The partial file is deleted so a failed take leaves
     * nothing behind.
     */
    fun stop(): Result<File> {
        val active = recorder ?: return Result.failure(
            IllegalStateException("no recording in progress")
        )
        val file = outputFile
        return runCatching {
            // `stop()` throws when nothing usable was captured. That
            // is a user outcome, not a bug, so it becomes a failed
            // Result rather than an exception.
            active.stop()
            val captured = file
                ?: throw IllegalStateException("no output file")
            if (!captured.exists() || captured.length() == 0L) {
                throw IllegalStateException("the take is empty")
            }
            captured
        }.onFailure {
            runCatching { file?.delete() }
        }.also {
            release()
            outputFile = null
        }
    }

    /** Stop and throw the take away. */
    fun cancel() {
        val active = recorder
        if (active != null) {
            // `stop()` is the only way to end a recording cleanly;
            // `reset()` alone can leave the encoder in a bad state.
            runCatching { active.stop() }
        }
        release()
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
    }

    /** When the current take started, in wall-clock millis. */
    fun startedAtEpochMs(): Long = startedAtEpochMs

    private fun release() {
        recorder?.let { active ->
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        recorder = null
    }

    companion object {
        /** The directory the takes are written to, under the app's files dir. */
        fun outputDir(context: Context): File =
            File(context.filesDir, "voiceovers").apply { mkdirs() }

        /**
         * The file name for a new take.
         *
         * @param nowEpochMs the capture's start time — it makes the
         *   name unique without a random source, so two takes in the
         *   same second still get different files.
         */
        fun outputFile(context: Context, nowEpochMs: Long): File =
            File(outputDir(context), "voice_$nowEpochMs.m4a")
    }
}
