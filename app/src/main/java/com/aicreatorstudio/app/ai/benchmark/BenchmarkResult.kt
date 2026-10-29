package com.aicreatorstudio.app.ai.benchmark

import com.aicreatorstudio.app.ai.Transcription

/**
 * PHASE B7 — structured output of the per-model benchmark.
 *
 * The benchmark is invoked from an instrumentation test
 * ([com.aicreatorstudio.app.ai.WhisperModelBenchmarkTest])
 * and is not exposed to the production UI. The
 * [WhisperModelBenchmark] runner produces one
 * [ModelBenchmarkResult] per spec and aggregates them into
 * a [BenchmarkReport].
 *
 * No `wordErrorRate` field is included. The brief is
 * explicit: "Do NOT claim word error rate unless a verified
 * ground-truth transcript exists." The project has no
 * ground-truth transcript for the Persian fixture, so the
 * benchmark only reports raw counts, timings, and
 * confidence aggregates — never a WER.
 */
data class ModelBenchmarkResult(
    /** The model id this row reports on, e.g. "whisper-medium" or "whisper-tiny". */
    val modelId: String,
    /** "whisper" for production specs; reserved for future engines. */
    val engineId: String,
    /** Number of subtitle segments returned. Zero on failure. */
    val segmentCount: Int,
    /** Number of recognized words returned. Zero on failure. */
    val wordCount: Int,
    /** Number of segments with zero words (would never appear on screen). */
    val emptySegmentCount: Int,
    /** Wall-clock time spent on `whisper_full`, in milliseconds. */
    val processingTimeMs: Long,
    /** Audio duration the engine transcribed, in milliseconds. */
    val audioDurationMs: Long,
    /** `processingTimeMs / audioDurationMs`. `< 1.0` is faster than real time. */
    val realTimeFactor: Double,
    /**
     * Average across all words' `confidence` (skipping nulls). Null when
     * whisper.cpp reported no confidence for any word.
     */
    val averageConfidence: Float?,
    /**
     * The full transcription, kept for the developer's inspection
     * (e.g. `adb pull cacheDir/whisper_benchmark_report.json`).
     * May be null when the model failed to load or transcribe.
     */
    val transcription: Transcription? = null,
    /**
     * Free-form note, e.g. "not installed" when the spec is not
     * present on disk. Empty string on a clean run.
     */
    val note: String = ""
) {
    /** True when the benchmark for this spec produced a usable transcription. */
    val isSuccessful: Boolean
        get() = transcription != null && wordCount > 0
}

/**
 * The full benchmark report. One row per spec, plus the audio
 * fixture used. The instrumentation test writes this object as
 * JSON to `cacheDir/whisper_benchmark_report.json` so a developer
 * can `adb pull` it after the run.
 */
data class BenchmarkReport(
    val fixturePath: String,
    val fixtureDurationMs: Long,
    val rows: List<ModelBenchmarkResult>,
    /** Wall-clock time the entire benchmark took, in milliseconds. */
    val totalWallTimeMs: Long
)
