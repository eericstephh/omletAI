package com.aicreatorstudio.app.ai.vad

/**
 * PHASE 1 — Local silence-detection contract.
 *
 * Decouples the editor UI ([com.aicreatorstudio.app.viewmodel.EditorViewModel])
 * from the Silero/ONNX implementation ([SileroVadDetector]) so future
 * models can replace VAD without rewriting the editor:
 *
 * ```
 * UI / ViewModel
 *   ↓
 * SilenceDetector (this interface)
 *   ↓
 * LocalSilenceDetector (SileroVadDetector + SilenceSegmentMapper)
 *   ↓
 * ONNX Runtime (on-device)
 * ```
 *
 * All processing is local — no network, no API key.
 */
interface SilenceDetector {

    /**
     * Analyze 16 kHz mono float32 PCM ([AudioInput]) and return the
     * detected silence segments. Pure analysis — never mutates media.
     */
    suspend fun analyze(
        audio: AudioInput,
        config: SilenceDetectionConfig = SilenceDetectionConfig(),
        onProgress: (Float) -> Unit = {}
    ): SilenceAnalysisResult
}

/**
 * Totally offline audio buffer for silence analysis.
 *
 * @param samples 16 kHz mono float32 PCM in [-1, 1].
 */
data class AudioInput(
    val samples: FloatArray,
    val sampleRateHz: Int = 16_000
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioInput) return false
        return sampleRateHz == other.sampleRateHz && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * samples.contentHashCode() + sampleRateHz
}

/**
 * Tunable silence-detection parameters. No hardcoded values in callers.
 *
 * @param threshold Speech probability threshold (0..1). Frames at or
 *   above this are speech. Default 0.5.
 * @param minSilenceMs Minimum silence duration (ms) to report. Shorter
 *   gaps stay intact. Range 300–1500 ms typical. Default 500.
 * @param padBeforeMs Speech padding kept BEFORE each speech region so
 *   word onsets are never clipped. Default 300.
 * @param padAfterMs Speech padding kept AFTER each speech region so
 *   word tails/reverb are never clipped. Default 300.
 */
data class SilenceDetectionConfig(
    val threshold: Float = 0.5f,
    val minSilenceMs: Float = 500f,
    val padBeforeMs: Float = 300f,
    val padAfterMs: Float = 300f
) {
    /** Back-compat: symmetric padding maps to both sides. */
    constructor(
        threshold: Float,
        minSilenceMs: Float,
        speechPadMs: Float
    ) : this(
        threshold = threshold,
        minSilenceMs = minSilenceMs,
        padBeforeMs = speechPadMs,
        padAfterMs = speechPadMs
    )
}

/**
 * One detected silence region.
 *
 * @param startMs Start of silence in the analyzed audio timeline (ms).
 * @param endMs End of silence (ms).
 * @param confidence 0..1 — higher means more certain silence
 *   (1 − mean speech probability inside the region).
 */
data class SilenceSegment(
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1f
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Result of [SilenceDetector.analyze] for one audio stream.
 */
data class SilenceAnalysisResult(
    val segments: List<SilenceSegment>,
    /** Duration of the analyzed audio (ms). */
    val analyzedDurationMs: Long
) {
    val totalSilenceMs: Long get() = segments.sumOf { it.durationMs }
}
