package com.aicreatorstudio.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Privacy-safe, metadata-only diagnostics for the offline Whisper path.
 *
 * ## Why this exists
 *
 * The 2026-10-11 compatibility audit established that the native side is
 * sound on `arm64-v8a` (all JNI symbols resolve, the library is a valid
 * AArch64 PIE, and the ggml CPU-feature gate can never reject a device).
 * It also established that a failing device is currently **impossible to
 * diagnose**, because every distinct failure — missing file, truncated
 * file, wrong SHA, native init returning `NULL`, native allocation
 * failure — collapses into one undifferentiated message.
 *
 * This object fixes that one gap and nothing else. It records *which
 * stage* failed and the surrounding metadata, so the next user report is
 * answerable instead of speculative.
 *
 * ## What is NEVER recorded
 *
 * - Audio content, PCM samples, or any decoded media.
 * - Absolute file paths (only the file **name** and its directory name).
 * - API keys, tokens, or any secret.
 * - Device identifiers (IMEI, serial, ANDROID_ID, advertising id).
 * - Anything unrelated to debugging the failure at hand.
 *
 * Everything below is either a public build constant, a resource
 * counter, or a file size. None of it is personal data.
 */
internal object WhisperDiagnostics {

    private const val TAG = "WhisperDiag"
    private const val COMPONENT = "whisper"

    /**
     * 2026-10-12 — optional sink into the centralized logger, wired once
     * from DI at startup. When null (JVM tests) every method still
     * writes to logcat exactly as before, so adding structured
     * persistence cannot change Whisper behaviour.
     */
    @Volatile
    private var sink: com.aicreatorstudio.app.logging.OmeletLog? = null

    fun attach(log: com.aicreatorstudio.app.logging.OmeletLog?) {
        sink = log
    }

    /**
     * The discrete stages an offline Whisper run passes through.
     *
     * This enum is the single most valuable artefact of the audit: it
     * turns "it doesn't work on my phone" into a specific, comparable
     * answer. The first failing stage *is* the diagnosis.
     */
    enum class Stage {
        /** `System.loadLibrary("whisper")` + JNI self-test. */
        NATIVE_SELFTEST,

        /** The model file is not on disk at the expected location. */
        MODEL_MISSING,

        /** The file exists but is smaller than the declared minimum. */
        MODEL_SIZE_MISMATCH,

        /** The file size is plausible but its SHA-256 does not match. */
        MODEL_SHA_MISMATCH,

        /** SHA is correct but `whisper_init_from_file_with_params` returned NULL. */
        NATIVE_INIT_NULL,

        /** The native context is live and inference may proceed. */
        NATIVE_INIT_OK,

        /** Audio extraction / resampling from the media container. */
        AUDIO_DECODE,

        /** `whisper_full` ran but produced no usable segments. */
        INFERENCE_EMPTY,

        /** `whisper_full` threw or its JNI bridge reported an error. */
        INFERENCE_ERROR,
    }

    /** Human-readable Persian explanation per stage (for the UI). */
    fun persianFor(stage: Stage): String = when (stage) {
        Stage.NATIVE_SELFTEST ->
            "کتابخانهٔ بومی Whisper روی این دستگاه بارگذاری نشد."
        Stage.MODEL_MISSING ->
            "فایل مدل Whisper روی دستگاه پیدا نشد. لطفاً مدل را دانلود کنید."
        Stage.MODEL_SIZE_MISMATCH ->
            "فایل مدل ناقص است (حجم آن کمتر از حد انتظار است). لطفاً دوباره دانلود کنید."
        Stage.MODEL_SHA_MISMATCH ->
            "فایل مدل با نسخهٔ رسمی مطابقت ندارد و ممکن است خراب باشد. لطفاً دوباره دانلود کنید."
        Stage.NATIVE_INIT_NULL ->
            "موتور Whisper نتوانست فایل مدل را بخواند. حافظهٔ آزاد دستگاه را بررسی " +
                "کنید و دوباره تلاش کنید؛ اگر تکرار شد، مدل را دوباره دانلود کنید."
        Stage.NATIVE_INIT_OK ->
            "مدل Whisper با موفقیت بارگذاری شد."
        Stage.AUDIO_DECODE ->
            "استخراج صدای این ویدیو ناموفق بود. فرمت صوتی ممکن است پشتیبانی نشود."
        Stage.INFERENCE_EMPTY ->
            "صدای قابل رونویسی در این فایل پیدا نشد."
        Stage.INFERENCE_ERROR ->
            "پردازش صوتی Whisper ناتمام ماند. دوباره تلاش کنید."
    }

    /**
     * Build-environment snapshot. Captured once per process — these
     * values cannot change while the app is running.
     */
    data class Environment(
        val apiLevel: Int,
        val release: String,
        val primaryAbi: String,
        val supportedAbis: String,
        val totalRamMb: Long,
        val availRamMb: Long,
        val lowMemory: Boolean,
        val cpuCount: Int,
        val largeHeap: Boolean
    )

    /**
     * Capture the build + memory environment.
     *
     * [context] is used **only** for `ActivityManager` memory readings
     * and the `largeHeap` flag. No identifier is read.
     */
    fun environment(context: Context): Environment {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        var lowMemory = false
        var availMb = -1L
        var totalMb = -1L
        if (am != null) {
            runCatching {
                am.getMemoryInfo(info)
                lowMemory = info.lowMemory
                availMb = info.availMem / (1024L * 1024L)
                totalMb = info.totalMem / (1024L * 1024L)
            }
        }
        val largeHeap = runCatching {
            (context.applicationInfo.flags and
                android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0
        }.getOrDefault(false)

        val abis = Build.SUPPORTED_ABIS
        return Environment(
            apiLevel = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE ?: "?",
            primaryAbi = abis.firstOrNull() ?: "unknown",
            supportedAbis = abis.joinToString(","),
            totalRamMb = totalMb,
            availRamMb = availMb,
            lowMemory = lowMemory,
            cpuCount = Runtime.getRuntime().availableProcessors(),
            largeHeap = largeHeap
        )
    }

    /**
     * Snapshot of a model file's presence and size — **no path**, only
     * the file name, its directory name, and byte counts.
     */
    data class ModelArtifact(
        val fileName: String,
        val dirName: String,
        val exists: Boolean,
        val actualBytes: Long,
        val expectedMinBytes: Long,
        val expectedBytes: Long?
    ) {
        /** True when the file is smaller than the declared floor. */
        val isTruncated: Boolean
            get() = exists && actualBytes < expectedMinBytes

        /**
         * True when the manifest declares an exact size and the file
         * differs from it. This catches a file that clears the floor
         * but is still not the real artefact.
         */
        val isWrongSize: Boolean
            get() = expectedBytes != null && exists && actualBytes != expectedBytes

        /** One-line summary for logcat — no path, no content. */
        fun summary(): String =
            "file=$fileName dir=$dirName exists=$exists " +
                "actual=$actualBytes expectedMin=$expectedMinBytes " +
                "expected=${expectedBytes ?: "?"} " +
                "truncated=$isTruncated wrongSize=$isWrongSize"
    }

    /**
     * Read the on-disk state of a model. Never throws; a read failure
     * yields `exists = false`.
     */
    fun inspectModel(
        file: File,
        expectedMinBytes: Long,
        expectedBytes: Long? = null
    ): ModelArtifact = ModelArtifact(
        fileName = file.name,
        dirName = file.parentFile?.name ?: "?",
        exists = file.exists(),
        actualBytes = runCatching { if (file.exists()) file.length() else 0L }.getOrDefault(0L),
        expectedMinBytes = expectedMinBytes,
        expectedBytes = expectedBytes
    )

    // ---- Logging ----

    /** Log a completed stage. Called on success paths too. */
    fun stage(stage: Stage, elapsedMs: Long, detail: String = "") {
        Log.i(TAG, "stage=$stage ok elapsed=${elapsedMs}ms ${sanitize(detail)}")
        sink?.info(
            COMPONENT, "whisper_stage_ok",
            detail,
            fields = mapOf("stage" to stage.name, "elapsedMs" to elapsedMs.toString())
        )
    }

    /** Log a failed stage together with the environment and the model state. */
    fun failure(
        stage: Stage,
        env: Environment?,
        model: ModelArtifact?,
        error: Throwable? = null,
        detail: String = ""
    ) {
        val sb = StringBuilder()
        sb.append("stage=").append(stage).append(" FAILED")
        if (detail.isNotBlank()) sb.append(" detail=").append(sanitize(detail))
        if (error != null) {
            sb.append(" error=").append(error.javaClass.simpleName)
            error.message?.let { sb.append(" msg=").append(sanitize(it)) }
        }
        if (env != null) {
            sb.append(" | api=").append(env.apiLevel)
                .append(" rel=").append(env.release)
                .append(" abi=").append(env.primaryAbi)
                .append(" abis=").append(env.supportedAbis)
                .append(" cpu=").append(env.cpuCount)
                .append(" totalMb=").append(env.totalRamMb)
                .append(" availMb=").append(env.availRamMb)
                .append(" lowMemory=").append(env.lowMemory)
                .append(" largeHeap=").append(env.largeHeap)
        }
        if (model != null) {
            sb.append(" | model=").append(model.summary())
        }
        Log.w(TAG, sb.toString(), error)

        val fields = LinkedHashMap<String, String>()
        fields["stage"] = stage.name
        if (env != null) {
            fields["api"] = env.apiLevel.toString()
            fields["abi"] = env.primaryAbi
            fields["cpu"] = env.cpuCount.toString()
            fields["totalMb"] = env.totalRamMb.toString()
            fields["availMb"] = env.availRamMb.toString()
            fields["lowMemory"] = env.lowMemory.toString()
        }
        if (model != null) {
            fields["model"] = model.fileName
            fields["exists"] = model.exists.toString()
            fields["actualBytes"] = model.actualBytes.toString()
            fields["truncated"] = model.isTruncated.toString()
            fields["wrongSize"] = model.isWrongSize.toString()
        }
        sink?.error(COMPONENT, "whisper_stage_failed", detail, fields = fields, throwable = error)
    }

    /** Record native-library load outcome. */
    fun nativeLoad(
        ok: Boolean,
        abi: String,
        version: String?,
        elapsedMs: Long,
        /**
         * 2026-10-13 — the classified cause when [ok] is false. Recorded
         * so a bug report names the *stage* rather than repeating the
         * old catch-all "CPU unsupported" sentence.
         */
        failure: NativeEngineFailure? = null,
        error: Throwable? = null
    ) {
        if (ok) {
            Log.i(TAG, "native load ok abi=$abi version=$version elapsed=${elapsedMs}ms")
            sink?.info(
                COMPONENT, "whisper_native_load_ok", "",
                fields = mapOf(
                    "abi" to abi,
                    "version" to (version ?: "?"),
                    "elapsedMs" to elapsedMs.toString()
                )
            )
        } else {
            Log.w(
                TAG,
                "native load FAILED abi=$abi cause=${failure ?: "?"} " +
                    "elapsed=${elapsedMs}ms" +
                    (error?.let { " err=${it.javaClass.simpleName}" } ?: "")
            )
            val fields = LinkedHashMap<String, String>()
            fields["abi"] = abi
            fields["elapsedMs"] = elapsedMs.toString()
            failure?.let {
                fields["cause"] = it.name
                fields["recovery"] = it.recoveryHint() ?: "none"
            }
            error?.let { fields["error"] = it.javaClass.simpleName }
            sink?.error(
                COMPONENT, "whisper_native_load_failed", "",
                fields = fields, throwable = error
            )
        }
    }

    /** Record inference parameters and duration — counts only, never audio. */
    fun inference(
        windowIndex: Int,
        totalWindows: Int,
        samples: Int,
        threads: Int,
        elapsedMs: Long,
        wordEntries: Int
    ) {
        Log.i(
            TAG,
            "inference window=${windowIndex + 1}/$totalWindows " +
                "samples=$samples threads=$threads elapsed=${elapsedMs}ms words=$wordEntries"
        )
        sink?.info(
            COMPONENT, "whisper_inference_window", "",
            fields = mapOf(
                "window" to "${windowIndex + 1}/$totalWindows",
                "samples" to samples.toString(),
                "threads" to threads.toString(),
                "elapsedMs" to elapsedMs.toString(),
                "words" to wordEntries.toString()
            )
        )
    }

    /** Record resource cleanup / cancellation of a native context. */
    fun released(detail: String = "") {
        Log.i(TAG, "native context released ${sanitize(detail)}")
        sink?.info(COMPONENT, "whisper_released", detail)
    }

    /**
     * Record the selected inference configuration once per run — the
     * knobs a report needs to tell "wrong settings" from "wrong model".
     * Counts and enum values only.
     */
    fun inferenceConfig(
        modelId: String,
        language: String,
        beamSize: Int,
        threads: Int,
        temperatureInc: Float,
        vad: Boolean
    ) {
        Log.i(
            TAG,
            "inference config model=$modelId lang=$language beam=$beamSize " +
                "threads=$threads tempInc=$temperatureInc vad=$vad"
        )
        sink?.info(
            COMPONENT, "whisper_inference_config", "",
            fields = mapOf(
                "model" to modelId,
                "language" to language,
                "beamSize" to beamSize.toString(),
                "threads" to threads.toString(),
                "temperatureInc" to temperatureInc.toString(),
                "vad" to vad.toString()
            )
        )
    }

    /**
     * Record memory-pressure indicators at the moment of an
     * initialization or inference failure — the single most useful
     * number for telling "device was tight" from "device was fine".
     */
    fun memoryPressure(context: Context, phase: String) {
        val env = runCatching { environment(context) }.getOrNull() ?: return
        Log.w(
            TAG,
            "memory pressure at $phase: availMb=${env.availRamMb} totalMb=${env.totalRamMb} " +
                "lowMemory=${env.lowMemory} largeHeap=${env.largeHeap}"
        )
        sink?.warn(
            COMPONENT, "whisper_memory_pressure", phase,
            fields = mapOf(
                "availMb" to env.availRamMb.toString(),
                "totalMb" to env.totalRamMb.toString(),
                "lowMemory" to env.lowMemory.toString()
            )
        )
    }

    /**
     * Strip anything that could be a filesystem path out of a free-form
     * string, so a careless caller cannot leak a path into logcat.
     * Keeps only the basename of any path-looking token.
     */
    private fun sanitize(s: String): String {
        if (s.isEmpty()) return s
        // Collapse absolute unix or windows paths down to their leaf.
        val parts = s.split(Regex("""[\s,;]+"""))
        return parts.joinToString(" ") { token ->
            if (token.startsWith("/") || Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(token)) {
                token.replace('\\', '/').trimEnd('/').substringAfterLast('/')
            } else {
                token
            }
        }
    }
}
