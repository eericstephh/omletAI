package com.aicreatorstudio.app.ai.tracking

/**
 * Chooses the appearance tracker (Tier B) available on this device.
 *
 * ## Selection order
 *
 *  1. **OpenCV CSRT** — most accurate; handles scale and partial
 *     deformation, which is what keeps a sticker locked to a hand that
 *     changes distance from the camera.
 *  2. **OpenCV KCF** — faster, fixed scale estimate. Correct for
 *     scale-stable targets (a logo on a wall); degrades on people.
 *  3. **NCC template (pure Kotlin)** — no native dependency at all.
 *     Weaker than CSRT but self-contained, so the feature works
 *     everywhere rather than only where OpenCV resolves.
 *
 * The choice is **cached** and made once per process: resolving the
 * OpenCV classes loads native libraries, which is not something to do
 * per tracking run, and a device either has them or does not.
 */
object RoiTrackerFactory {

    /** Reported when no tracker could be created at all. */
    const val BACKEND_UNAVAILABLE: String = "unavailable"

    @Volatile
    private var cachedBackend: String? = null

    @Volatile
    private var openCvAvailable: Boolean? = null

    /**
     * Stable algorithm id matching
     * [com.aicreatorstudio.app.model.MotionTrackSettings]'s
     * `ALGORITHM_*` constants, for the status line and for persisting
     * which tracker produced a trajectory.
     *
     * Safe to call from the UI: the probe result is cached after the
     * first call.
     */
    fun backendId(): String {
        cachedBackend?.let { return it }
        val id = probe()
        cachedBackend = id
        return id
    }

    /**
     * True when an OpenCV-backed tracker is usable. Cached.
     */
    fun isOpenCvAvailable(): Boolean {
        openCvAvailable?.let { return it }
        val ok = try {
            Class.forName("org.opencv.core.Mat")
            OpenCvRoiTracker.isAvailable()
        } catch (t: Throwable) {
            false
        }
        openCvAvailable = ok
        return ok
    }

    /**
     * Create a fresh tracker instance. **The caller owns it and must
     * call [RoiTracker.close]** — an OpenCV tracker holds a native
     * handle and leaks megabytes per run if it is not released.
     *
     * Returns null only when even the pure-Kotlin backend fails to
     * construct, which would indicate a far more basic problem.
     */
    fun create(): RoiTracker? {
        OpenCvRoiTracker.create()?.let { return it }
        return runCatching { NccRoiTracker() }.getOrNull()
    }

    /**
     * Human-readable backend description for the panel status line,
     * in the same spirit as the other AI tools' `modelStatusText`.
     */
    fun statusText(): String = when (backendId()) {
        com.aicreatorstudio.app.model.MotionTrackSettings.ALGORITHM_CSRT_V1 ->
            "OpenCV CSRT (دقیق‌ترین) · آفلاین"
        com.aicreatorstudio.app.model.MotionTrackSettings.ALGORITHM_KCF_V1 ->
            "OpenCV KCF (سریع) · آفلاین"
        com.aicreatorstudio.app.model.MotionTrackSettings.ALGORITHM_NCC_V1 ->
            "ردیاب NCC داخلی (بدون OpenCV) · آفلاین"
        else -> "ردیاب در دسترس نیست"
    }

    private fun probe(): String = when {
        tryCreateCsrt() -> com.aicreatorstudio.app.model.MotionTrackSettings.ALGORITHM_CSRT_V1
        tryCreateKcf() -> com.aicreatorstudio.app.model.MotionTrackSettings.ALGORITHM_KCF_V1
        else -> com.aicreatorstudio.app.model.MotionTrackSettings.ALGORITHM_NCC_V1
    }

    private fun tryCreateCsrt(): Boolean = probeBackend("org.opencv.tracking.TrackerCSRT")

    private fun tryCreateKcf(): Boolean = probeBackend("org.opencv.tracking.TrackerKCF")

    /**
     * Instantiate a tracker class and immediately close it. Wrapped in
     * `catch (Throwable)` rather than `catch (Exception)`: a missing
     * native library surfaces as `UnsatisfiedLinkError`, which is an
     * `Error` and would otherwise escape and crash the editor. This is
     * the same guard `SaliencyTrackerEngine.nativeLibAvailable` uses
     * for MediaPipe.
     */
    private fun probeBackend(className: String): Boolean = try {
        val cls = Class.forName(className)
        val instance = cls.getMethod("create").invoke(null)
        if (instance == null) {
            false
        } else {
            runCatching { cls.getMethod("clear").invoke(instance) }
            true
        }
    } catch (t: Throwable) {
        false
    }
}
