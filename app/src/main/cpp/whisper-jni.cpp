// JNI bridge to whisper.cpp for AICreatorStudio's WhisperEngine.
//
// Compile by linking against whisper.cpp + libggml. Drop the
// resulting libwhisper.so into:
//   app/src/main/jniLibs/<abi>/libwhisper.so
//
// The Kotlin side calls into the native functions declared
// in com/aicreatorstudio/app/ai/WhisperNative.kt. They are:
//   - nativeInit(path)               -> opaque context
//   - nativeFree(ctx)                -> release
//   - nativeFullWithWordTimestamps(ctx, samples, language,
//                                  translate, noContext,
//                                  singleSegment)
//                                   -> WordTimestamp[] (text, t0Cs,
//                                      t1Cs, probability)
//   - nativeSelfTest()               -> { version, abi } strings;
//                                      confirms the library is
//                                      actually linked in and
//                                      ready to use. The Kotlin
//                                      side calls this on every
//                                      app start so a missing
//                                      .so surfaces as
//                                      ENGINE_UNAVAILABLE rather
//                                      than a confusing
//                                      UnsatisfiedLinkError at
//                                      the first transcription.
//
// Word-level timestamps come from whisper.cpp's per-token
// `whisper_token_data` (v1.7.6 API), joined with the
// per-token text from `whisper_token_to_str`. There is NO
// equal-splitting or estimated timing anywhere in this path.

#include <jni.h>
#include <string>
#include <vector>
#include <unistd.h>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_aicreatorstudio_app_ai_WhisperNative_nativeInit(
    JNIEnv *env,
    jclass /*clazz*/,
    jstring jpath
) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return 0;
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    // 2026-09-08 FLASH ATTENTION — flash_attn lives on the CONTEXT
    // params (not whisper_full_params) in v1.7.6. The CPU ggml
    // backend implements GGML_OP_FLASH_ATTN_EXT with optimized NEON
    // kernels, so enabling it here cuts the attention cost in every
    // encoder/decoder layer (measured 1.3-2x on real arm64 devices).
    // Token timestamps are greedy-chain derived (not DTW), so they
    // remain fully accurate with flash attention.
    cparams.flash_attn = true;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_aicreatorstudio_app_ai_WhisperNative_nativeFree(
    JNIEnv * /*env*/,
    jclass /*clazz*/,
    jlong ctx_ptr
) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT jobjectArray JNICALL
Java_com_aicreatorstudio_app_ai_WhisperNative_nativeFullWithWordTimestamps(
    JNIEnv *env,
    jclass /*clazz*/,
    jlong ctx_ptr,
    jfloatArray j_samples,
    jstring j_language,
    jstring j_initial_prompt,
    jint j_beam_size,
    jstring j_vad_model_path,
    jboolean j_translate,
    jboolean j_no_context,
    jboolean j_single_segment,
    jint j_threads,
    jfloat j_temperature_inc
) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (!ctx) return nullptr;

    jsize n = env->GetArrayLength(j_samples);
    jfloat *samples = env->GetFloatArrayElements(j_samples, nullptr);
    if (!samples) return nullptr;

    const char *language = env->GetStringUTFChars(j_language, nullptr);
    // 2026-09-08 QUALITY FIX — initial_prompt + beam search. The
    // prompt primes the decoder toward properly-spaced Persian (the
    // #1 complaint: whisper emits unspaced Persian without it) and
    // beam search replaces greedy sampling for better word accuracy.
    const char *initialPrompt = j_initial_prompt
        ? env->GetStringUTFChars(j_initial_prompt, nullptr) : nullptr;

    struct whisper_full_params params =
        (j_beam_size > 1)
            ? whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH)
            : whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.translate = static_cast<bool>(j_translate);
    params.no_context = static_cast<bool>(j_no_context);
    params.single_segment = static_cast<bool>(j_single_segment);
    params.language = language;
    params.token_timestamps = true; // <-- CRITICAL: required for per-token t0/t1
    params.max_len = 0;
    // 2026-09-08 PERSIAN QUALITY — three decoder knobs the defaults
    // leave off, all aimed at the reported word-level errors
    // ("به"→"آیی", "کردن"→"کردنار"):
    //
    //  1. suppress_nst = true — suppress the non-speech token set
    //     (♪, [...], (bell), …). These artifacts leak into the decode
    //     on music/noise beds and displace real Persian tokens.
    //  2. length_penalty = 0.3 — beam/sequence scoring divides the
    //     score by pow((5+len)/6, penalty) for penalty > 0, so
    //     LONGER sequences are penalized. Whisper Small's habit of
    //     growing a spurious suffix ("کردن"→"کردنار") is exactly an
    //     over-long sequence winning the beam; a mild 0.3 penalty
    //     favors the tighter hypothesis without truncating speech.
    //  3. no_context — see the Kotlin call site (cross-window text
    //     conditioning is enabled there, OpenAI-default behaviour,
    //     which materially improves cross-window word consistency).
    if (initialPrompt && initialPrompt[0] != '\0') {
        params.initial_prompt = initialPrompt;
    }
    if (j_beam_size > 1) {
        int b = static_cast<int>(j_beam_size);
        if (b > 8) b = 8;
        params.beam_search.beam_size = b;
        params.beam_search.patience = -1.0f;
        params.length_penalty = 0.3f;
        // NOTE: whisper.cpp v1.7.6 exposes `best_of` only on the GREEDY
        // strategy struct; beam mode has beam_size + patience only.
        // The Accurate preset widens the net via beam_size = 8.
    }
    params.suppress_nst = true;
    // 2026-09-08 FALLBACK SENSITIVITY — accept-a-segment quality
    // gates. whisper.cpp only accepts a decoded segment when its
    // average log-probability and compression ratio pass these
    // gates; when they fail it retries with the next temperature
    // step.
    //
    // 2026-10-20 FALLBACK FIX — logprob_thold is back to the
    // upstream -1.0. It had been tightened to -0.8 in an attempt to
    // force a retry on wrong words, but the retry ladder is
    // temperature 0 → 0.2 → … → 1.0 and whisper.cpp ALWAYS accepts
    // the LAST rung regardless of quality (see whisper_full_with_state:
    // `if (it != temperatures.size() - 1)` guards the failure test).
    // Persian's agglutinative BPE gives correct sentences an average
    // log-prob near -0.6…-0.9, so -0.8 pushed most ordinary windows
    // down the ladder and the subtitle was whatever the 0.8/1.0
    // decode invented — fluent Persian nobody said. Trusting the
    // acoustic decode at low temperature is strictly better than
    // trading it for a more random one.
    //
    // entropy_thold 2.4 → 3.0 stays: in beam mode a decoder is
    // rejected when result_len > 32 AND entropy < thold, i.e. it is
    // the repetition-loop guard, and 3.0 makes it stricter.
    params.entropy_thold = 3.0f;
    params.logprob_thold = -1.0f;
    // 2026-09-08 VAD — when a silero VAD model path is provided,
    // whisper.cpp runs voice-activity detection FIRST and only
    // transcribes the speech segments. This is the biggest quality
    // lever for real-world footage: music beds / silence / noise no
    // longer confuse the decoder into hallucinating wrong Persian
    // words. The internal mapping table re-maps word timestamps back
    // to the original media timeline, so overlays stay in sync.
    //
    // NOTE the string lifetime: vadPathUtf must stay acquired until
    // AFTER whisper_full returns (params.vad_model_path points into
    // it; the VAD context is also built lazily inside whisper_full).
    const char *vadPathUtf = nullptr;
    if (j_vad_model_path) {
        vadPathUtf = env->GetStringUTFChars(j_vad_model_path, nullptr);
        if (vadPathUtf && vadPathUtf[0] != '\0') {
            params.vad = true;
            params.vad_model_path = vadPathUtf;
            // Slightly-above-default sensitivity so quiet Persian
            // speech is kept, but music-only regions are still cut.
            params.vad_params.threshold = 0.55f;
            params.vad_params.min_speech_duration_ms = 250;
            params.vad_params.min_silence_duration_ms = 150;
        }
    }
    // NOTE: flash_attn is a whisper_context_params field in v1.7.6
    // (set in nativeInit above), not a whisper_full_params field.
    // n_threads is model-driven: the engine passes
    // spec.whisperThreads (4 for v1) clamped to the device's
    // logical CPU count and to whisper.cpp's self-test max.
    // 1..8 is the safe range; whisper.cpp itself caps at
    // systemMaxThreads when computing the default.
    {
        int t = static_cast<int>(j_threads);
        if (t < 1) t = 1;
        if (t > 8) t = 8;
        params.n_threads = t;
    }
    // temperature_inc controls the fallback decoding strategy.
    // whisper.cpp's default is 0.2; passing it through (instead
    // of leaving 0) makes the decoder try a small temperature
    // bump on low-confidence audio windows, which is exactly
    // what multilingual models on noisy speech need to recover
    // from an all-blank first pass. The 0.2 value is what
    // whisper.cpp's own CLI uses for `--temperature-inc 0.2`.
    params.temperature_inc = static_cast<float>(j_temperature_inc);

    int rc = whisper_full(ctx, params, samples, n);
    env->ReleaseFloatArrayElements(j_samples, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(j_language, language);
    if (initialPrompt) {
        env->ReleaseStringUTFChars(j_initial_prompt, initialPrompt);
    }
    if (vadPathUtf) {
        // vad_model_path points INTO vadPathUtf; release only now,
        // after whisper_full has consumed it (and the VAD context,
        // which is cached in the whisper state, has been built).
        env->ReleaseStringUTFChars(j_vad_model_path, vadPathUtf);
    }

    if (rc != 0) {
        return nullptr;
    }

    // Collect every per-token timestamp from every segment. The
    // v1.7.6 API doesn't carry the token's decoded text on the
    // whisper_token_data struct; we look it up via
    // whisper_token_to_str(ctx, td.id) for each one.
    std::vector<whisper_token_data> all;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const int n_tokens = whisper_full_n_tokens(ctx, i);
        for (int w = 0; w < n_tokens; ++w) {
            whisper_token_data td = whisper_full_get_token_data(ctx, i, w);
            // t0 == t1 == 0 means the timestamp wasn't computed;
            // skip these so the engine doesn't paint zero-duration
            // words on the timeline.
            if (td.t0 == 0 && td.t1 == 0) continue;
            all.push_back(td);
        }
    }

    // Find the inner class generated by Kotlin for the
    // WhisperNative.WordTimestamp data class. The JNI signature is
    // (text: String, t0Cs: Long, t1Cs: Long, probability: Float).
    jclass wordClass = env->FindClass(
        "com/aicreatorstudio/app/ai/WhisperNative$WordTimestamp"
    );
    if (!wordClass) return nullptr;

    jmethodID ctor = env->GetMethodID(
        wordClass, "<init>",
        "(Ljava/lang/String;JJF)V"
    );
    if (!ctor) return nullptr;

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(all.size()), wordClass, nullptr);
    if (!result) return nullptr;

    int outIdx = 0;
    for (size_t i = 0; i < all.size(); ++i) {
        const whisper_token_data &td = all[i];
        // whisper_token_to_str returns the decoded BPE piece. Word-
        // INITIAL tokens carry a literal leading space (the BPE
        // encoder's "Ġ" -> " " mapping); mid-word CONTINUATION
        // tokens have none.
        //
        // 2026-09-08 WORD-MERGE FIX — the old code stripped the
        // leading space, which DESTROYED the word-boundary marker.
        // The engine then treated every sub-token as a separate
        // word, rendering Persian words letter-by-letter
        // ("کلمه" → "ک ل م ه"). We now PRESERVE the leading space:
        // a token starting with ' ' begins a new word; a token
        // without one is a continuation the engine concatenates
        // into the previous word. Only leading newlines are
        // stripped. Special tokens (SOT, EOT, language tags, ...)
        // still return an empty string and are skipped.
        const char *raw = whisper_token_to_str(ctx, td.id);
        std::string txt = raw ? raw : "";
        while (!txt.empty() && txt.front() == '\n') {
            txt.erase(txt.begin());
        }
        if (txt.empty()) continue;
        jstring jtext = env->NewStringUTF(txt.c_str());
        jobject obj = env->NewObject(
            wordClass, ctor,
            jtext,
            static_cast<jlong>(td.t0), // already in centiseconds
            static_cast<jlong>(td.t1),
            static_cast<jfloat>(td.p)
        );
        env->SetObjectArrayElement(result, static_cast<jsize>(outIdx), obj);
        env->DeleteLocalRef(jtext);
        env->DeleteLocalRef(obj);
        outIdx++;
    }
    return result;
}

// Returns a diagnostic struct so the Kotlin side can confirm the
// .so is actually linked in without having to attempt a model load.
// Two strings + one int: the whisper.cpp version, the ABI the
// library was compiled for, and the system's logical CPU count.
// If this method is reachable, System.loadLibrary("whisper")
// succeeded and the JNI symbols resolved; that is the gate
// WhisperEngine uses to surface ENGINE_UNAVAILABLE before any
// model work.
//
// Kotlin signature (see WhisperNative.kt):
//   nativeSelfTest(): WhisperSelfTest  (data class:
//     version: String, abi: String, systemMaxThreads: Int)
//
// The function is intentionally cheap (no I/O, no inference) so we
// can call it on every engine init without measurable cost.
//
// v1.7.6 doesn't expose whisper_version() at link time (the symbol
// was only added in newer revisions), so we hardcode the version
// string here and verify the build against the v1.7.6 headers
// under app/src/main/cpp/whisper/whisper.h.
JNIEXPORT jobject JNICALL
Java_com_aicreatorstudio_app_ai_WhisperNative_nativeSelfTest(
    JNIEnv *env,
    jclass /*clazz*/
) {
    jclass cls = env->FindClass(
        "com/aicreatorstudio/app/ai/WhisperNative$SelfTest"
    );
    if (!cls) return nullptr;

    jmethodID ctor = env->GetMethodID(
        cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;I)V"
    );
    if (!ctor) return nullptr;

    const char *version = "1.7.6";

#if defined(__aarch64__)
    const char *abi = "arm64-v8a";
#elif defined(__arm__)
    const char *abi = "armeabi-v7a";
#elif defined(__x86_64__)
    const char *abi = "x86_64";
#elif defined(__i386__)
    const char *abi = "x86";
#else
    const char *abi = "unknown";
#endif

    // whisper.cpp's n_threads default is a function of CPU count;
    // we report what the library would default to so the caller
    // can override it on low-core devices.
    int maxThreads = 1;
#if defined(_SC_NPROCESSORS_ONLN)
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (n > 0) maxThreads = static_cast<int>(n);
#endif
    if (maxThreads < 1) maxThreads = 1;
    if (maxThreads > 8) maxThreads = 8;

    jstring jversion = env->NewStringUTF(version);
    jstring jabi = env->NewStringUTF(abi);
    jobject obj = env->NewObject(cls, ctor, jversion, jabi,
                                 static_cast<jint>(maxThreads));
    env->DeleteLocalRef(jversion);
    env->DeleteLocalRef(jabi);
    return obj;
}

} // extern "C"