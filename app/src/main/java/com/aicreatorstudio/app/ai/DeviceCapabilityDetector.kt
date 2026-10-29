package com.aicreatorstudio.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PHASE B1.6 — decides whether the current device is suitable
 * for a given [WhisperModelSpec]. The check is layered
 * (ABI + RAM + CPU cores + storage) because Large-v3 in
 * particular is known to OOM or crash on phones that pass
 * the bare-RAM gate but lack CPU/storage headroom.
 *
 * Why a separate class from [WhisperEngine]:
 *
 *   1. The decision needs to be reachable from the ViewModel
 *      (so the model picker can disable Large-v3 with a clear
 *      "not enough resources" caption) and from the
 *      [WhisperEngine] load path (so a 4 GB phone that does
 *      try to load Large-v3 fails fast with a precise
 *      message instead of waiting for whisper.cpp to OOM).
 *      Both call sites need the same answer, so the policy
 *      lives in one place.
 *   2. The check is `suspend` (StatFs is documented as
 *      potentially slow) and the result is cached per-spec;
 *      the engine doesn't have a coroutine scope of its own
 *      so the suspend implementation belongs to its own
 *      file.
 *   3. The class is intentionally small (no DI, no
 *      `ApplicationContext`-annotated constructor) so the
 *      JVM unit tests can construct it directly with a
 *      mocked `Context`.
 */
class DeviceCapabilityDetector(private val context: Context) {

    /**
     * Cached answers keyed by the spec. The cache is
     * process-scoped — a fresh detector instance starts
     * empty. Callers (the ViewModel in particular) hold
     * one detector per screen so re-querying is cheap.
     */
    private val cache: MutableMap<WhisperModelSpec, DeviceCapability> = HashMap()

    /**
     * Run the layered capability gate for [spec]. The four
     * layers are evaluated independently and a single failing
     * layer produces a [DeviceCapability.Unsupported] with a
     * precise [DeviceCapability.Reason] the UI can show.
     */
    suspend fun checkFor(spec: WhisperModelSpec): DeviceCapability {
        cache[spec]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            // Layer 1: ABI. whisper.cpp v1.7.6 ships arm64-v8a
            // + armeabi-v7a + x86_64. The 32-bit arm build has
            // a 4 GB VA ceiling which the 3.1 GB Large-v3
            // working set brushes against once the KV cache
            // is allocated; large-v3 on a 32-bit phone is not
            // safe. We let Medium through on armeabi-v7a
            // because Medium's 1.5 GB working set fits in 2 GB
            // of free RAM and the 32-bit KV cache.
            val abi = primaryAbi()
            // Layer 1 ABI gate. whisper.cpp v1.7.6 ships arm64-v8a +
            // armeabi-v7a + x86_64. The 32-bit arm build has a 4 GB VA
            // ceiling which the 3.1 GB Large-v3 working set brushes
            // against once the KV cache is allocated; large-v3 on a
            // 32-bit phone is not safe.
            val abiOk = when (spec) {
                // 2026-09-08 — Small f16 (488 MB working set) fits
                // comfortably in any architecture's address space.
                WhisperModelSpec.SMALL -> abi in SUPPORTED_ABIS
                WhisperModelSpec.CLOUD_API -> true
            }
            if (!abiOk) {
                return@withContext DeviceCapability.Unsupported(
                    spec = spec,
                    reason = DeviceCapability.Reason.UNSUPPORTED_ABI,
                    detail = "ABI $abi is not supported for ${spec.displayName}"
                )
            }

            // Layer 2: free RAM.
            val freeMb = readFreeRamMb()
            if (freeMb < spec.minRamMb) {
                return@withContext DeviceCapability.Unsupported(
                    spec = spec,
                    reason = DeviceCapability.Reason.INSUFFICIENT_RAM,
                    detail = "free=${freeMb}MB required=${spec.minRamMb}MB"
                )
            }

            // Layer 3: CPU cores. whisper.cpp uses n_threads
            // (currently 4 for every catalog entry) to drive
            // the encoder + decoder in parallel. 2-core phones
            // hang for 30+ seconds per audio window.
            val cores = Runtime.getRuntime().availableProcessors()
            if (cores < MIN_CORES) {
                return@withContext DeviceCapability.Unsupported(
                    spec = spec,
                    reason = DeviceCapability.Reason.INSUFFICIENT_CPU,
                    detail = "cores=$cores required=$MIN_CORES"
                )
            }

            // Layer 4: storage. The 3.1 GB Large-v3 file
            // needs a safety margin for journal + atomic
            // rename + cacheDir partial. The medium model
            // needs a smaller margin but we use the same
            // floor for both so the code path is uniform.
            val freeStorageMb = readFreeStorageMb()
            if (freeStorageMb < MIN_STORAGE_MB) {
                return@withContext DeviceCapability.Unsupported(
                    spec = spec,
                    reason = DeviceCapability.Reason.INSUFFICIENT_STORAGE,
                    detail = "free=${freeStorageMb}MB required=$MIN_STORAGE_MB"
                )
            }

            DeviceCapability.Supported(
                spec = spec,
                freeRamMb = freeMb,
                cores = cores,
                abi = abi,
                freeStorageMb = freeStorageMb
            )
        }
        cache[spec] = result
        Log.d(
            TAG,
            "device capability for ${spec.name}: ${result::class.java.simpleName} " +
                if (result is DeviceCapability.Unsupported) "(${result.reason})"
                else "(ok)"
        )
        return result
    }

    /** Read-only view of the cached result, or null if not yet probed. */
    fun lastFor(spec: WhisperModelSpec): DeviceCapability? = cache[spec]

    // ---- internals ----

    private fun primaryAbi(): String =
        if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown"

    private fun readFreeRamMb(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    private fun readFreeStorageMb(): Long {
        return runCatching {
            val path = context.filesDir.absolutePath
            val stat = StatFs(path)
            stat.availableBytes / (1024L * 1024L)
        }.getOrDefault(Long.MAX_VALUE)
    }

    companion object {
        private const val TAG = "DeviceCapability"

        /**
         * ABIs `libwhisper.so` is actually packaged for.
         *
         * ## 2026-10-11 ABI AUDIT CORRECTION
         *
         * This set previously listed `armeabi-v7a` and `x86_64`
         * alongside `arm64-v8a`. Neither is packaged: `app/build.gradle.kts`
         * sets `abiFilters += listOf("arm64-v8a")`, and the built APK
         * was verified to contain native libraries **only** under
         * `lib/arm64-v8a/`. An `armeabi-v7a` device would therefore
         * pass this advisory check and then fail at
         * `System.loadLibrary("whisper")` — the check was telling
         * users their device was supported when it was not.
         *
         * The set is now the truth: exactly the ABI that ships.
         * This affects only this advisory probe; the real gate is
         * always the native load itself (see
         * `WhisperEngine.runNativeSelfTest`), which surfaces
         * [WhisperDiagnostics.Stage.NATIVE_SELFTEST] on failure.
         */
        val SUPPORTED_ABIS: Set<String> = setOf("arm64-v8a")

        /**
         * Minimum number of logical cores to safely run any
         * Whisper checkpoint. whisper.cpp uses n_threads
         * (currently 4) to drive the encoder + decoder in
         * parallel; 2-core phones hang for 30+ seconds per
         * audio window.
         */
        const val MIN_CORES: Int = 4

        /**
         * Minimum free storage (MB) on the data partition.
         * 4 GB safety margin above the file size itself
         * covers journal + atomic-rename + cacheDir partial
         * overhead.
         */
        const val MIN_STORAGE_MB: Long = 4_096L
    }
}

/**
 * Result of a [DeviceCapabilityDetector] probe.
 *
 * Sealed so the model picker can exhaustively branch on
 * `is Supported` / `is Unsupported` without an extra
 * "what was the detail string again?" lookup.
 */
sealed class DeviceCapability {
    abstract val spec: WhisperModelSpec

    /**
     * The device can safely load [spec]. The fields are
     * diagnostic — callers usually only need the
     * success/failure branch.
     */
    data class Supported(
        override val spec: WhisperModelSpec,
        val freeRamMb: Long,
        val cores: Int,
        val abi: String,
        val freeStorageMb: Long
    ) : DeviceCapability()

    /**
     * The device cannot safely load [spec]. [reason] is the
     * machine-readable cause; [detail] is a free-form
     * human-readable string the UI can append after the
     * localised caption.
     */
    data class Unsupported(
        override val spec: WhisperModelSpec,
        val reason: Reason,
        val detail: String
    ) : DeviceCapability() {
        /** Persian caption the model picker renders on the row. */
        val persianCaption: String
            get() = when (reason) {
                Reason.INSUFFICIENT_RAM ->
                    "حافظه‌ی آزاد کافی نیست. این مدل به ${spec.minRamMb} مگابایت RAM نیاز دارد."
                Reason.INSUFFICIENT_CPU ->
                    "تعداد هسته‌های CPU کافی نیست."
                Reason.INSUFFICIENT_STORAGE ->
                    "فضای ذخیره‌سازی کافی نیست."
                Reason.UNSUPPORTED_ABI ->
                    "این مدل روی معماری پردازنده‌ی فعلی پشتیبانی نمی‌شود."
            }
    }

    /**
     * Machine-readable cause of an [Unsupported] result.
     */
    enum class Reason {
        INSUFFICIENT_RAM,
        INSUFFICIENT_CPU,
        INSUFFICIENT_STORAGE,
        UNSUPPORTED_ABI
    }
}
