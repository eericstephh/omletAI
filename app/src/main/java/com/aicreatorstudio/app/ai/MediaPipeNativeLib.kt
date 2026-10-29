package com.aicreatorstudio.app.ai

/**
 * Process-wide probe for the MediaPipe Tasks JNI library.
 *
 * The library is loaded once per process (`System.loadLibrary` is
 * idempotent) and every engine that talks to MediaPipe needs the
 * same answer, so the probe state lives here instead of being
 * duplicated per engine.
 *
 * 2026-10-15 — the probe is a **candidate list**, not a fixed name.
 * The project had hard-coded `mediapipe_tasks_vision_jni`, the name
 * the old tasks-vision AAR (0.10.14/0.10.21) shipped. The currently
 * resolved `tasks-vision 0.10.35` ships **no** native library at
 * all; `tasks-core 0.10.35` ships the only `.so` that lands in the
 * APK, and it is named `mediapipe_tasks_jni` (verified: the built
 * APK contains exactly `lib/arm64-v8a/libmediapipe_tasks_jni.so`
 * and no vision-named library). Probing the stale name alone
 * returned false on perfectly supported hardware, and the caller
 * then classified the failure as `LIBRARY_ABI_MISSING` — the
 * device told the user "this version of the app was not built for
 * your processor" while the arm64 library sat inside the APK.
 *
 * Order matters only for speed: the name that is actually packaged
 * is tried first, so the common case performs one successful load
 * instead of one failed load plus one success.
 */
internal object MediaPipeNativeLib {

    /**
     * Library names to try, in order. `mediapipe_tasks_jni` is what
     * `com.google.mediapipe:tasks-core` ships today; the legacy
     * `mediapipe_tasks_vision_jni` name is kept so an older
     * tasks-vision AAR still probes green instead of reporting a
     * packaging failure. `internal` so
     * [com.aicreatorstudio.app.ai.MediaPipeNativeLibWiringTest] can
     * pin the packaged name from a JVM test.
     */
    internal val CANDIDATE_LIBS = listOf(
        "mediapipe_tasks_jni",
        "mediapipe_tasks_vision_jni"
    )

    @Volatile
    private var probe: Boolean? = null

    /**
     * 2026-10-13 — the throwable from the most recent failed probe,
     * kept so [NativeEngineFailure.classify] can distinguish an
     * absent library from a symbol mismatch or a wrong-architecture
     * ELF instead of guessing from prose.
     */
    @Volatile
    var lastLoadError: Throwable? = null
        private set

    /**
     * True when at least one candidate library loaded. The result is
     * cached: a native load cannot change while the process lives,
     * and `dlopen` of an already-loaded library is a cheap no-op
     * that the cache makes unnecessary anyway.
     */
    fun isAvailable(): Boolean {
        probe?.let { return it }
        var failure: Throwable? = null
        val ok = CANDIDATE_LIBS.any { name ->
            try {
                System.loadLibrary(name)
                true
            } catch (t: Throwable) {
                failure = t
                false
            }
        }
        if (!ok) {
            lastLoadError = failure
        }
        probe = ok
        return ok
    }
}
