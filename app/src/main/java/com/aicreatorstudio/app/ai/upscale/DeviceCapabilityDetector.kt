package com.aicreatorstudio.app.ai.upscale

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight hardware probe used by [UpscaleProfile.Auto] (and by
 * the bottom-sheet card's "Auto" toggle) to pick between the FAST
 * and HIGH_QUALITY engines at runtime.
 *
 * The detector is deliberately conservative — a wrong "fast" pick
 * on a flagship phone is a poor user experience, so the threshold
 * values are tuned to be permissive on the high-quality side. The
 * final result is a *recommendation*; the user can still override
 * it manually.
 *
 * Signals inspected:
 *  1. Total device RAM (via [ActivityManager.MemoryInfo]) — anything
 *     below ~4 GiB is "low-end".
 *  2. CPU core count — below 6 cores is "low-end" (frame decoding +
 *     super-resolution parallelism drops off fast).
 *  3. OpenGL ES major version, read from the platform's feature
 *     table (`FeatureInfo.reqGlEsVersion`). Anything below 3.1 is a
 *     strong "low-end" signal because the ONNX/Vulkan high-quality
 *     engine needs GLES 3.1+ compute.
 *  4. Vulkan / API level (Build.VERSION.SDK_INT >= 33) is a
 *     soft signal — modern flagships almost always have it, but
 *     mid-range devices can be split either way.
 *
 * The detector is `@Singleton` and side-effect free. Every signal it
 * reads is a **plain metadata query** — no GL context is created and
 * no native entry point is called, because this runs on a background
 * worker thread where a context-less GL call is a hard SIGSEGV (see
 * [queryGlesMajor]). JVM unit tests use the [recommend] companion-
 * object function with a synthetic [Probe] so they don't need a real
 * Context.
 */
@Singleton
class DeviceCapabilityDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Snapshot of the current device's hardware signals. The fields
     * here are the only ones the recommendation policy needs; the
     * detector is free to read additional Android APIs internally
     * without changing this surface.
     *
     * `gpuSupportsCompute` is the strongest single signal — when
     * false, HIGH_QUALITY is essentially guaranteed to fail or
     * run at CPU speeds, so the policy forces FAST.
     */
    data class Probe(
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val cpuCoreCount: Int,
        val gpuSupportsCompute: Boolean,
        val sdkInt: Int
    ) {
        /** True when the device has at least 4 GiB total RAM. */
        val isHighRam: Boolean get() = totalRamBytes >= HIGH_RAM_THRESHOLD

        /** True when the device has 6+ CPU cores. */
        val isManyCores: Boolean get() = cpuCoreCount >= MANY_CORES_THRESHOLD

        /** True when the device is a modern flagship-or-better. */
        val isFlagship: Boolean
            get() = isHighRam && isManyCores && gpuSupportsCompute && sdkInt >= 33
    }

    /**
     * Run the probe against the live device. Catches any unexpected
     * [GLException] / [SecurityException] (some custom ROMs lock
     * down the EGL context) and degrades to a conservative
     * `gpuSupportsCompute = false` reading so a flaky probe never
     * falsely recommends the high-quality pipeline.
     */
    fun probe(): Probe {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val gles = queryGlesMajor()
        val compute = gles >= 3
        return Probe(
            totalRamBytes = mem.totalMem,
            availableRamBytes = mem.availMem,
            cpuCoreCount = cores,
            gpuSupportsCompute = compute,
            sdkInt = Build.VERSION.SDK_INT
        )
    }

    /**
     * Recommend a profile based on a probe result. The result is the
     * **profile** (not the engine id) so the caller can use the
     * result directly to drive the UI's "selected" indicator. Pure
     * function — easy to unit test with synthetic [Probe]s.
     *
     * Mirrored as a static [Companion.recommend] so JVM unit tests
     * can call the policy without instantiating the detector (which
     * needs an Android Context).
     */
    fun recommend(probe: Probe): UpscaleProfile = Companion.recommend(probe)

    /**
     * Convenience: probe the device and return the recommended
     * profile in one call. Used by the bottom sheet to set the
     * initial Auto selection.
     */
    fun recommended(): UpscaleProfile = recommend(probe())

    /**
     * GLES **major** version (1, 2 or 3) as declared by the platform's
     * feature table.
     *
     * 2026-10-20 CRASH FIX — this used to call
     * `GLES20.glGetString(GL_VERSION)` from the upscaler's worker
     * thread. There is no current EGL context on that thread, so the
     * driver dereferenced a null dispatch table and the process died
     * with SIGSEGV inside `libGLESv1_CM.so (glGetString+52)`
     * (`ai.upscale.EsrganUpscalePipeline.run` was frame #15 of the
     * tombstone). A native fault is *not* an exception, so the
     * `runCatching` that used to wrap this call could never have
     * caught it — the app simply vanished the moment the user tapped
     * «افزایش کیفیت».
     *
     * `FeatureInfo.reqGlEsVersion` needs no GL context, makes no
     * native call, and is exactly what the platform publishes
     * (`0x00030002` = GLES 3.2). Anything unreadable degrades to 1,
     * which keeps the recommendation on the safe FAST engine.
     */
    private fun queryGlesMajor(): Int = runCatching {
        val packed = context.packageManager.systemAvailableFeatures
            ?.maxOfOrNull { it.reqGlEsVersion }
            ?: 0
        if (packed <= 0) 1 else (packed shr 16) and 0xFFFF
    }.getOrDefault(1)

    companion object {
        /** 4 GiB — comfortably above the OS + app baseline. */
        const val HIGH_RAM_THRESHOLD: Long = 4L * 1024L * 1024L * 1024L

        /** 2.5 GiB — anything below this is firmly "low-end". */
        const val LOW_RAM_THRESHOLD: Long = (2L * 1024L + 512L) * 1024L * 1024L

        /** 6 cores — minimum for stable parallel frame decode + SR. */
        const val MANY_CORES_THRESHOLD: Int = 6

        /** 4 cores — below this, HIGH_QUALITY is unsafe. */
        const val LOW_CORE_THRESHOLD: Int = 4

        /**
         * Pure-function recommendation policy. Same rules as
         * [DeviceCapabilityDetector.recommend] but usable from a JVM
         * unit test that only has a synthetic [Probe] to hand.
         */
        fun recommend(probe: Probe): UpscaleProfile = when {
            // Strong flagship: clear winner.
            probe.isFlagship -> UpscaleProfile.HighQuality
            // Clear low-end (no compute, weak CPU, or under the RAM bar):
            // even the most permissive "many cores" check can't save it.
            !probe.gpuSupportsCompute -> UpscaleProfile.Fast
            probe.totalRamBytes < LOW_RAM_THRESHOLD -> UpscaleProfile.Fast
            probe.cpuCoreCount < LOW_CORE_THRESHOLD -> UpscaleProfile.Fast
            // Everything else (the majority of mid-range phones):
            // default to Fast to avoid the throttling cliff. The user
            // can still manually pick High Quality if they want to try.
            else -> UpscaleProfile.Fast
        }
    }
}
