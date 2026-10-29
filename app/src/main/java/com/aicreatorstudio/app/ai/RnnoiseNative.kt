package com.aicreatorstudio.app.ai

import android.util.Log

/**
 * JNI bridge to the vendored RNNoise library (`librnnoise.so`).
 *
 * RNNoise (Xiph, BSD-3-Clause) is a recurrent-neural-network
 * speech enhancer: a GRU estimates per-band suppression gains from
 * pitch + spectral features and applies them to 10 ms frames of
 * 48 kHz mono float PCM. The pre-trained 8-bit weights are baked
 * into the native library (`rnn_data.c`), so inference is fully
 * offline with no model download.
 *
 * Provenance: `app/src/main/cpp/rnnoise/` is populated by the
 * Gradle `downloadRnnoiseSource` task from the pinned
 * Yellow-Dog-Man/rnnoise snapshot (see `models.lock.json`
 * `nativeLibs.rnnoise`); the bridge itself lives in
 * `app/src/main/cpp/rnnoise-jni.cpp`.
 *
 * Threading: the native `DenoiseState` is stateful (the GRU carries
 * hidden state across frames), so one handle must be used from a
 * single thread at a time. [RnnoiseSession] enforces this by
 * owning the handle for the duration of one denoise job.
 */
internal object RnnoiseNative {

    /** Sample rate RNNoise operates at. Fixed by the model. */
    const val SAMPLE_RATE: Int = 48_000

    /** Samples per frame RNNoise processes. Fixed by the model. */
    const val FRAME_SAMPLES: Int = 480

    private const val TAG = "RnnoiseNative"
    private const val LIB_NAME = "rnnoise"

    private val loadError: Throwable? = runCatching {
        System.loadLibrary(LIB_NAME)
    }.exceptionOrNull().also {
        if (it != null) Log.w(TAG, "librnnoise.so not available", it)
    }

    /** True when the native library loaded successfully. */
    val isAvailable: Boolean get() = loadError == null

    /** Why the library failed to load, or null when loaded. */
    fun loadErrorOrNull(): Throwable? = loadError

    /**
     * End-to-end smoke test: creates a state, pushes one silent
     * frame through the real network, destroys it. Returns the
     * native status string (`"rnnoise-ok:480"`) or null on failure.
     */
    fun selfTest(): String? {
        if (!isAvailable) return null
        return runCatching { nativeSelfTest() }.getOrNull()
    }

    fun frameSize(): Int = nativeFrameSize()

    fun sampleRate(): Int = nativeSampleRate()

    fun create(): Long = nativeCreate()

    fun destroy(handle: Long) {
        if (handle != 0L) runCatching { nativeDestroy(handle) }
    }

    /**
     * Denoise [samples] (48 kHz mono float) **in place** and return
     * the per-frame voice-activity probabilities (one float per
     * 480-sample frame). [samples.size] must be a positive multiple
     * of [FRAME_SAMPLES]; returns null on any native failure.
     */
    fun denoiseInPlace(handle: Long, samples: FloatArray): FloatArray? {
        if (handle == 0L) return null
        return runCatching { nativeDenoise(handle, samples) }.getOrNull()
    }

    @JvmStatic private external fun nativeFrameSize(): Int
    @JvmStatic private external fun nativeSampleRate(): Int
    @JvmStatic private external fun nativeCreate(): Long
    @JvmStatic private external fun nativeDestroy(handle: Long)
    @JvmStatic private external fun nativeDenoise(handle: Long, samples: FloatArray): FloatArray?
    @JvmStatic private external fun nativeSelfTest(): String?
}

/**
 * One stateful RNNoise stream. Create per denoise job, use from a
 * single thread, [close] when done (idempotent).
 */
internal class RnnoiseSession private constructor(val handle: Long) : AutoCloseable {

    companion object {
        /** Open a session, or null when the native backend is down. */
        fun open(): RnnoiseSession? {
            if (!RnnoiseNative.isAvailable) return null
            val handle = runCatching { RnnoiseNative.create() }.getOrNull() ?: 0L
            if (handle == 0L) return null
            return RnnoiseSession(handle)
        }
    }

    override fun close() = RnnoiseNative.destroy(handle)
}
