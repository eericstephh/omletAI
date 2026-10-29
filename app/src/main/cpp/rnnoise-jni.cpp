// JNI bridge to RNNoise (Xiph, BSD-3-Clause) for AICreatorStudio's
// Voice Clean engine.
//
// The Kotlin side calls the native functions declared in
// com/aicreatorstudio/app/ai/RnnoiseNative.kt:
//   - nativeFrameSize()            -> 480 (samples per frame)
//   - nativeSampleRate()           -> 48000 (Hz, mono)
//   - nativeCreate()               -> opaque DenoiseState handle
//                                      (default baked-in model)
//   - nativeDestroy(handle)        -> release
//   - nativeDenoise(handle, float[] samples)
//                                  -> float[] per-frame voice-activity
//                                     probabilities; `samples` is
//                                     denoised in place. Length must
//                                     be a multiple of 480.
//   - nativeSelfTest()             -> "rnnoise-ok:<frameSize>"
//                                      confirms the library is linked
//                                      and the default model works.
//
// Provenance: sources under app/src/main/cpp/rnnoise/ are vendored
// from Yellow-Dog-Man/rnnoise @ f0b73e3 (BSD, attribution in
// rnnoise/COPYING), itself the unmodified upstream Xiph DSP + GRU
// code with an added CMake/Android.mk build. The pre-trained
// 8-bit GRU weights are baked into rnn_data.c, so inference is
// fully offline with no model download.

#include <jni.h>

#include <cstdio>
#include <cstring>
#include <new>

#include "rnnoise.h"

namespace {

const char *kClass = "com/aicreatorstudio/app/ai/RnnoiseNative";

jlong toHandle(DenoiseState *st) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(st));
}

DenoiseState *fromHandle(jlong h) {
    return reinterpret_cast<DenoiseState *>(static_cast<intptr_t>(h));
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_aicreatorstudio_app_ai_RnnoiseNative_nativeFrameSize(JNIEnv *, jclass) {
    return rnnoise_get_frame_size();
}

JNIEXPORT jint JNICALL
Java_com_aicreatorstudio_app_ai_RnnoiseNative_nativeSampleRate(JNIEnv *, jclass) {
    return 48000;
}

JNIEXPORT jlong JNICALL
Java_com_aicreatorstudio_app_ai_RnnoiseNative_nativeCreate(JNIEnv *, jclass) {
    // NULL model = the baked-in default (rnn_data.c weights).
    DenoiseState *st = rnnoise_create(nullptr);
    return toHandle(st);
}

JNIEXPORT void JNICALL
Java_com_aicreatorstudio_app_ai_RnnoiseNative_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    DenoiseState *st = fromHandle(handle);
    if (st != nullptr) {
        rnnoise_destroy(st);
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_aicreatorstudio_app_ai_RnnoiseNative_nativeDenoise(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples) {
    DenoiseState *st = fromHandle(handle);
    if (st == nullptr || samples == nullptr) {
        return nullptr;
    }
    const jsize n = env->GetArrayLength(samples);
    const int frame = rnnoise_get_frame_size();
    if (n <= 0 || (n % frame) != 0) {
        return nullptr;
    }
    const jsize frames = n / frame;
    jfloat *buf = env->GetFloatArrayElements(samples, nullptr);
    if (buf == nullptr) {
        return nullptr;
    }
    jfloatArray vadOut = env->NewFloatArray(frames);
    if (vadOut == nullptr) {
        env->ReleaseFloatArrayElements(samples, buf, 0);
        return nullptr;
    }
    // Denoise frame-by-frame in place. RNNoise keeps its recurrent
    // state inside `st`, so consecutive calls continue the stream.
    // We read each frame into a small copy first so in/out aliasing
    // is impossible even if the library ever reads `in` after
    // writing `out`.
    float *in = new (std::nothrow) float[frame];
    float *out = new (std::nothrow) float[frame];
    jfloat *vad = new (std::nothrow) jfloat[frames];
    if (in == nullptr || out == nullptr || vad == nullptr) {
        delete[] in;
        delete[] out;
        delete[] vad;
        env->ReleaseFloatArrayElements(samples, buf, JNI_ABORT);
        return nullptr;
    }
    for (jsize f = 0; f < frames; ++f) {
        for (int i = 0; i < frame; ++i) {
            in[i] = buf[f * frame + i];
        }
        const float vadProb = rnnoise_process_frame(st, out, in);
        vad[f] = vadProb;
        for (int i = 0; i < frame; ++i) {
            buf[f * frame + i] = out[i];
        }
    }
    env->ReleaseFloatArrayElements(samples, buf, 0);
    env->SetFloatArrayRegion(vadOut, 0, frames, vad);
    delete[] in;
    delete[] out;
    delete[] vad;
    return vadOut;
}

JNIEXPORT jstring JNICALL
Java_com_aicreatorstudio_app_ai_RnnoiseNative_nativeSelfTest(JNIEnv *env, jclass) {
    DenoiseState *st = rnnoise_create(nullptr);
    if (st == nullptr) {
        return env->NewStringUTF("rnnoise-create-failed");
    }
    // Push one silent frame through the real network to prove the
    // weights + code path work end to end.
    const int frame = rnnoise_get_frame_size();
    float *in = new (std::nothrow) float[frame];
    float *out = new (std::nothrow) float[frame];
    if (in == nullptr || out == nullptr) {
        delete[] in;
        delete[] out;
        rnnoise_destroy(st);
        return env->NewStringUTF("rnnoise-oom");
    }
    for (int i = 0; i < frame; ++i) {
        in[i] = 0.0f;
        out[i] = 0.0f;
    }
    rnnoise_process_frame(st, out, in);
    delete[] in;
    delete[] out;
    rnnoise_destroy(st);
    char msg[64];
    snprintf(msg, sizeof(msg), "rnnoise-ok:%d", frame);
    return env->NewStringUTF(msg);
}

}  // extern "C"
