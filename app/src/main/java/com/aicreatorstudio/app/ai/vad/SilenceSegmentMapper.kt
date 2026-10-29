package com.aicreatorstudio.app.ai.vad

/**
 * PHASE 1 — Pure probability→segment mapper for silence detection.
 *
 * Framework-free (no Android, no ONNX) so it is trivially unit-testable
 * on the JVM. [SileroVadDetector.detectSilenceIntervals] delegates here;
 * the asymmetric [SilenceDetectionConfig.padBeforeMs]/[padAfterMs] are
 * honored while the legacy symmetric call maps to both sides.
 *
 * Frame geometry: Silero VAD emits one probability per 512 samples at
 * 16 kHz → each frame spans exactly 32 ms.
 */
object SilenceSegmentMapper {

    const val FRAME_DURATION_MS: Float = 32f // 512 / 16000 * 1000

    fun map(
        probabilities: FloatArray,
        config: SilenceDetectionConfig = SilenceDetectionConfig()
    ): List<SilenceSegment> {
        if (probabilities.isEmpty()) return emptyList()

        val padBeforeFrames =
            (config.padBeforeMs / FRAME_DURATION_MS).toInt().coerceAtLeast(0)
        val padAfterFrames =
            (config.padAfterMs / FRAME_DURATION_MS).toInt().coerceAtLeast(0)
        val minSilenceFrames =
            (config.minSilenceMs / FRAME_DURATION_MS).toInt().coerceAtLeast(1)

        // 1. Classify each frame.
        val isSpeech = BooleanArray(probabilities.size) { probabilities[it] >= config.threshold }

        // 2. Grow speech regions asymmetrically: padBefore frames to the
        // left of every speech frame, padAfter frames to the right.
        val padded = isSpeech.copyOf()
        for (i in isSpeech.indices) {
            if (!isSpeech[i]) continue
            val from = (i - padBeforeFrames).coerceAtLeast(0)
            val to = (i + padAfterFrames).coerceAtMost(isSpeech.size - 1)
            for (j in from..to) padded[j] = true
        }

        // 3. Collect contiguous silence runs at/over the minimum length.
        // Confidence = 1 − mean speech probability inside the run.
        val segments = mutableListOf<SilenceSegment>()
        var runStart = -1
        var runProbSum = 0.0
        fun flush(runEndExclusive: Int) {
            if (runStart == -1) return
            val frames = runEndExclusive - runStart
            if (frames >= minSilenceFrames) {
                val meanProb = (runProbSum / frames).toFloat()
                segments.add(
                    SilenceSegment(
                        startMs = (runStart * FRAME_DURATION_MS).toLong(),
                        endMs = (runEndExclusive * FRAME_DURATION_MS).toLong(),
                        confidence = (1f - meanProb).coerceIn(0f, 1f)
                    )
                )
            }
            runStart = -1
            runProbSum = 0.0
        }
        for (i in padded.indices) {
            if (!padded[i]) {
                if (runStart == -1) {
                    runStart = i
                    runProbSum = 0.0
                }
                runProbSum += probabilities[i].toDouble().coerceIn(0.0, 1.0)
            } else {
                flush(i)
            }
        }
        flush(padded.size)
        return segments
    }

    /**
     * PHASE 2 — validate + normalize segments before presenting or
     * applying. Guarantees: sorted by start, clamped to
     * `[0, durationMs]`, zero/negative-duration dropped, overlapping or
     * touching runs merged (confidence = duration-weighted mean).
     * Never throws; out-of-range input degrades to fewer segments,
     * never to invalid timestamps.
     */
    fun normalize(
        segments: List<SilenceSegment>,
        durationMs: Long
    ): List<SilenceSegment> {
        if (segments.isEmpty() || durationMs <= 0) return emptyList()
        val clamped = segments.mapNotNull { s ->
            val start = s.startMs.coerceIn(0L, durationMs)
            val end = s.endMs.coerceIn(0L, durationMs)
            if (end <= start) null
            else SilenceSegment(start, end, s.confidence.coerceIn(0f, 1f))
        }.sortedBy { it.startMs }
        if (clamped.isEmpty()) return emptyList()

        val merged = mutableListOf<SilenceSegment>()
        var accStart = clamped[0].startMs
        var accEnd = clamped[0].endMs
        var accWeight = clamped[0].durationMs.toDouble()
        var accConf = clamped[0].confidence.toDouble() * accWeight
        fun emit() {
            merged.add(
                SilenceSegment(
                    accStart,
                    accEnd,
                    (accConf / accWeight).toFloat().coerceIn(0f, 1f)
                )
            )
        }
        for (next in clamped.drop(1)) {
            if (next.startMs <= accEnd) {
                // Overlap or touching: extend, keep duration-weighted
                // mean confidence across the whole merged run.
                accEnd = maxOf(accEnd, next.endMs)
                accWeight += next.durationMs
                accConf += next.confidence * next.durationMs
            } else {
                emit()
                accStart = next.startMs
                accEnd = next.endMs
                accWeight = next.durationMs.toDouble()
                accConf = next.confidence.toDouble() * accWeight
            }
        }
        emit()
        return merged
    }
}
