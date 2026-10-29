package com.aicreatorstudio.app.ai

import android.util.Log

/**
 * Thin JNI surface around whisper.cpp's C API. Resolves the native
 * library and exposes only the symbols we need:
 *
 * - `whisper_init_from_file_with_params(path, params)` → opaque
 *   context pointer
 * - `whisper_full(ctx, params, samples, nSamples, ...)` → fills
 *   the context with token-level results
 * - `whisper_full_get_word_t(ctx, segment, word)` → returns the
 *   centisecond-level timing + probability for a token. This is
 *   where the **real** word-level timestamps come from.
 * - `whisper_free(ctx)` → release native resources
 * - `whisper_version()` → "1.7.6" string used by [nativeSelfTest]
 *
 * The Kotlin entry points are intentionally narrow so the file
 * stays trivially auditable. The native implementation lives in
 * `app/src/main/cpp/whisper-jni.cpp` (linked into the same
 * libwhisper.so that whisper.cpp + ggml compile into).
 *
 * ## Loading order
 *
 * The first call to any function in this object triggers
 * `System.loadLibrary("whisper")` via the standard JNI
 * resolution. Use [nativeSelfTest] as the *first* call to confirm
 * the library is loadable — it returns a populated [SelfTest]
 * when the .so is present and a thrown [UnsatisfiedLinkError]
 * when it isn't, which the engine translates to
 * [AiFailure.ENGINE_UNAVAILABLE].
 */
internal object WhisperNative {

    data class WordTimestamp(
        val text: String,
        val t0Cs: Long,
        val t1Cs: Long,
        val probability: Float
    )

    /**
     * Diagnostic info returned by [nativeSelfTest]. Populated by the
     * JNI bridge at the top of every engine init so the UI can show
     * the user exactly which whisper.cpp version is loaded and on
     * which ABI.
     *
     * @property version whisper.cpp's reported version, e.g. "1.7.6"
     * @property abi the architecture this .so was compiled for
     *   (e.g. "arm64-v8a"). Useful for diagnosing the case where
     *   the user has an arm64 phone but the APK only bundled
     *   armeabi-v7a.
     * @property systemMaxThreads CPU count the library would default
     *   to if asked; callers may choose a lower value to stay
     *   responsive on low-core devices.
     */
    data class SelfTest(
        val version: String,
        val abi: String,
        val systemMaxThreads: Int
    )

    /** Outcome of [initFromFileDetailed] — distinguishes the failure kinds. */
    sealed interface InitResult {
        /** The native context is live. */
        data class Ok(val ctx: Long) : InitResult

        /**
         * `System.loadLibrary` or symbol resolution failed. A device /
         * packaging problem, not a model problem.
         */
        data class LibraryUnavailable(val cause: Throwable) : InitResult

        /**
         * The library loaded and was called, but
         * `whisper_init_from_file_with_params` returned `NULL`.
         * This is the ambiguous case: a corrupt file **or** a native
         * allocation failure both land here. The caller disambiguates
         * by checking the on-disk file state first.
         */
        data object NativeReturnedNull : InitResult

        /** `nativeInit` threw something other than an `UnsatisfiedLinkError`. */
        data class Threw(val cause: Throwable) : InitResult
    }

    /**
     * Like [initFromFile] but reports **which** failure occurred.
     *
     * The old [initFromFile] collapsed all four outcomes to `null`, which
     * is why a corrupt model and an out-of-native-memory phone produced
     * an identical message. This variant keeps them apart so the engine
     * can name the real stage.
     */
    fun initFromFileDetailed(path: String): InitResult = try {
        val ctx = nativeInit(path)
        if (ctx == 0L) InitResult.NativeReturnedNull else InitResult.Ok(ctx)
    } catch (e: UnsatisfiedLinkError) {
        InitResult.LibraryUnavailable(e)
    } catch (e: Exception) {
        InitResult.Threw(e)
    }

    /**
     * Kept for callers that only need a yes/no answer
     * (see [com.aicreatorstudio.app.ai.WhisperEngine.benchmarkTranscribe]).
     * Prefer [initFromFileDetailed] on the production path so the failure
     * stage is not lost.
     */
    fun initFromFile(path: String): Long? = when (val r = initFromFileDetailed(path)) {
        is InitResult.Ok -> r.ctx
        else -> null
    }

    fun free(ctx: Long) {
        if (ctx != 0L) runCatching { nativeFree(ctx) }
    }

    /**
     * Runs whisper.cpp with word-timestamp decoding enabled and
     * pulls the [WordTimestamp]s out. Centisecond values come back
     * from `whisper_full_get_word_t` directly — the caller (the
     * engine) multiplies by 10 to get milliseconds.
     *
     * [threads] is the value passed to `whisper_full_params.n_threads`.
     * The model spec's `whisperThreads` is the source of truth; the
     * engine clamps it to the device's logical CPU count and to
     * `selfTest.systemMaxThreads` to avoid a runtime refusal on
     * weak phones.
     *
     * [temperatureInc] is the value passed to
     * `whisper_full_params.temperature_inc` (fallback temperature
     * bump when greedy decoding produces no speech). Set to 0 to
     * disable fallback.
     *
     * 2026-09-08 QUALITY FIX — [initialPrompt] primes the decoder
     * with Persian text so the model emits properly-SPACED Persian
     * (without it, whisper.cpp commonly emits unspaced Persian for
     * the Small/Medium checkpoints). [beamSize] > 1 switches the
     * sampler from GREEDY to BEAM_SEARCH for better word accuracy.
     */
    fun fullWithWordTimestamps(
        ctx: Long,
        samples: FloatArray,
        language: String,
        initialPrompt: String = "",
        beamSize: Int = 1,
        vadModelPath: String = "",
        translate: Boolean,
        noContext: Boolean,
        singleSegment: Boolean,
        threads: Int = 4,
        temperatureInc: Float = 0.1f
    ): List<WordTimestamp> {
        if (ctx == 0L) return emptyList()
        return try {
            // Explicitly typed local: keeps the JNI bridge's declared
            // return (Array<WordTimestamp>) pinned at the call site so
            // the try-expression's static type is List<WordTimestamp>.
            val words: Array<WordTimestamp> = nativeFullWithWordTimestamps(
                ctx = ctx,
                samples = samples,
                language = language,
                initialPrompt = initialPrompt,
                beamSize = beamSize,
                vadModelPath = vadModelPath,
                translate = translate,
                noContext = noContext,
                singleSegment = singleSegment,
                threads = threads,
                temperatureInc = temperatureInc
            )
            // Surface the JNI call's actual result so the engine /
            // logcat can confirm word extraction succeeded. Empty
            // result is a real outcome (silence in the audio) and
            // must NOT be conflated with a JNI failure.
            Log.d(
                "WhisperNative",
                "fullWithWordTimestamps: samples=${samples.size} " +
                    "lang=$language threads=$threads tempInc=$temperatureInc " +
                    "beam=$beamSize vad=${vadModelPath.isNotBlank()} " +
                    "prompt=${initialPrompt.take(24)} " +
                    "wordEntries=${words.size}"
            )
            words.toList()
        } catch (e: UnsatisfiedLinkError) {
            // The native library was not loadable. This is a real
            // failure mode the user must see — do NOT swallow it
            // and return an empty list, which would look identical
            // to "no speech in video" from the engine's perspective.
            Log.w(
                "WhisperNative",
                "fullWithWordTimestamps UnsatisfiedLinkError",
                e
            )
            throw e
        } catch (e: Throwable) {
            Log.w(
                "WhisperNative",
                "fullWithWordTimestamps failed: " +
                    "${e.javaClass.simpleName}: ${e.message}",
                e
            )
            throw e
        }
    }

    /**
     * Native self-test. Returns a populated [SelfTest] when the
     * library is loadable; throws [UnsatisfiedLinkError] (or any
     * RuntimeException if FindClass returns null) when it isn't.
     * The engine wraps this in a try/catch and translates a thrown
     * exception into [AiFailure.ENGINE_UNAVAILABLE].
     */
    fun selfTest(): SelfTest = nativeSelfTest()

    @JvmStatic private external fun nativeInit(path: String): Long
    @JvmStatic private external fun nativeFree(ctx: Long)
    @JvmStatic private external fun nativeFullWithWordTimestamps(
        ctx: Long,
        samples: FloatArray,
        language: String,
        initialPrompt: String,
        beamSize: Int,
        vadModelPath: String,
        translate: Boolean,
        noContext: Boolean,
        singleSegment: Boolean,
        threads: Int,
        temperatureInc: Float
    ): Array<WordTimestamp>
    @JvmStatic private external fun nativeSelfTest(): SelfTest
}