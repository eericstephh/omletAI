package com.aicreatorstudio.app.ai.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Silero VAD (Voice Activity Detector) using ONNX Runtime.
 *
 * Processes 16 kHz mono float32 PCM in 512-sample frames (32 ms)
 * and outputs per-frame speech probabilities via the Silero VAD v6
 * ONNX model bundled in assets.
 *
 * After inference, [detectSilenceIntervals] converts the per-frame
 * probabilities into contiguous silence regions with configurable
 * speech padding and minimum silence duration.
 */
class SileroVadDetector(private val context: Context) : AutoCloseable {

    /**
     * PHASE 2 — lazily created on [loadModel], never at construction.
     * Loading the ONNX Runtime native library eagerly meant merely
     * instantiating the detector (or anything holding one, e.g.
     * [SilenceCutter] for pure clip math in unit tests) paid the
     * native-load cost even when VAD never runs.
     */
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    /**
     * Silero VAD internal LSTM state — reset between clips.
     *
     * 2026-10-20 — SHAPE/SEMANTICS FIX.
     *
     * The bundled `silero_vad.onnx` (graph `spox_graph`) declares:
     *
     * ```
     * input  : FLOAT32 [batch, samples]   (dynamic)
     * state  : FLOAT32 [2, batch, 128]     (dynamic middle dim)
     * sr     : INT64   []                  (scalar sample rate)
     * output : FLOAT32 [batch, 1]
     * stateN : FLOAT32 [2, batch, 128]
     * ```
     *
     * The previous code drove it as a **v4-style** model
     * (`input`/`sr`/`h`/`c` → `output`/`hn`/`cn`), which is a different
     * graph entirely: `h`/`c` are not inputs of this model, `sr` is
     * INT64 (not INT32) and there are only **two** outputs, not three.
     * Every `detect()` call therefore threw before producing a single
     * probability — which is why "حذف سکوت" silently did nothing
     * (the caller swallowed the error) and Smart-Highlight analysis
     * failed outright.
     *
     * The `state` tensor is a **single** buffer of `[2, batch, 128]`
     * where row 0 is the LSTM hidden state and row 1 is the cell
     * state (NOT two LSTM layers). With batch = 1 that is exactly
     * 2 × 1 × 128 = 256 floats, kept here as a flat buffer in the
     * model's own row-major order.
     */
    /** Number of floats in the `state` tensor (2 rows × 128). */
    private val STATE_SIZE: Int = 2 * 128

    private var stateFlat: FloatArray = FloatArray(STATE_SIZE)

    private var sampleRateHistory = mutableListOf<Int>()

    /**
     * Load the ONNX model from assets. Must be called once before
     * [detect]. The model file is `silero_vad.onnx` inside
     * `assets/ai-models/silero-vad/`.
     */
    fun loadModel() {
        if (session != null) return
        val environment = OrtEnvironment.getEnvironment()
        env = environment
        val modelBytes = context.assets.open("ai-models/silero-vad/silero_vad.onnx")
            .use { it.readBytes() }
        session = environment.createSession(modelBytes)
    }

    /**
     * Reset the internal LSTM state for a new audio stream.
     * Must be called before [detect] when processing a new clip.
     */
    fun resetState() {
        stateFlat = FloatArray(STATE_SIZE)
        sampleRateHistory.clear()
    }

    /**
     * Run Silero VAD on a 16 kHz mono float32 audio buffer.
     *
     * @param samples 16 kHz mono float PCM in [-1, 1]
     * @param threshold Speech probability threshold (0..1). Frames
     *   above this are classified as speech. Default 0.5.
     * @return List of per-frame speech probabilities (each frame = 512 samples = 32 ms)
     */
    fun detect(
        samples: FloatArray,
        threshold: Float = 0.5f,
        onProgress: (Float) -> Unit = {}
    ): FloatArray {
        val environment = env
            ?: throw IllegalStateException("Model not loaded. Call loadModel() first.")
        val ortSession = session ?: throw IllegalStateException("Model not loaded. Call loadModel() first.")
        resetState()

        val frameSize = 512
        val numFrames = samples.size / frameSize
        val probabilities = FloatArray(numFrames)
        // PHASE 1 perf: reuse one frame buffer instead of allocating a
        // copy per 32 ms frame (10 min audio = ~18k allocations before).
        val frame = FloatArray(frameSize)

        for (i in 0 until numFrames) {
            val start = i * frameSize
            System.arraycopy(samples, start, frame, 0, frameSize)

            // 2026-10-20 — the model signature is v5-style:
            //   in : input(F32[1,N])  state(F32[2,1,128])  sr(INT64 scalar)
            //   out: output(F32[N,1]) stateN(F32[2,1,128])
            // The previous code passed "h"/"c" (not inputs of this
            // graph) and read a third output that does not exist, so
            // every frame threw. See the class-level state docs.
            var inputTensor: OnnxTensor? = null
            var srTensor: OnnxTensor? = null
            var stateTensor: OnnxTensor? = null
            var output: ai.onnxruntime.OrtSession.Result? = null
            try {
                val inT = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(frame),
                    longArrayOf(1, frameSize.toLong())
                )
                // `sr` is INT64 in the graph — an INT32 tensor is
                // rejected by ORT as a type mismatch.
                val srT = OnnxTensor.createTensor(environment, longArrayOf(16000L))
                val stT = OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(stateFlat),
                    longArrayOf(2, 1, 128)
                )
                inputTensor = inT
                srTensor = srT
                stateTensor = stT

                val inputs = mapOf(
                    "input" to inT,
                    "sr" to srT,
                    "state" to stT
                )

                output = ortSession.run(inputs)

                probabilities[i] = readProbability(output[0].value)

                // stateN comes back as [2, 1, 128]; flatten it back
                // into the model's row-major layout for the next frame.
                writeStateBack(output[1].value)
            } finally {
                // Tensors must be released even when `run()` throws —
                // otherwise every failed frame leaks native memory.
                runCatching { inputTensor?.close() }
                runCatching { srTensor?.close() }
                runCatching { stateTensor?.close() }
                runCatching { output?.close() }
            }

            if (i % 100 == 0) {
                onProgress(i.toFloat() / numFrames)
            }
        }
        onProgress(1f)
        return probabilities
    }

    /**
     * Read the single speech probability out of the `output` tensor.
     *
     * The graph declares `output` as FLOAT32 `[batch, 1]`, so ORT hands
     * back an `Array<FloatArray>` where each row holds one value. We
     * feed one frame at a time, so the first row is the answer. The
     * single-`FloatArray` branch is a defensive fallback for a future
     * rank-1 export of the same model.
     */
    private fun readProbability(value: Any?): Float = when (value) {
        is Array<*> -> {
            val row = value.firstOrNull()
            when (row) {
                is FloatArray -> row.firstOrNull() ?: 0f
                is Array<*> -> ((row.firstOrNull() as? Float) ?: 0f)
                is Float -> row
                else -> 0f
            }
        }
        is FloatArray -> value.firstOrNull() ?: 0f
        is Float -> value
        else -> 0f
    }

    /**
     * Copy the model's `stateN` output back into [stateFlat].
     *
     * The tensor is `[2, 1, 128]`: row 0 is the LSTM hidden state and
     * row 1 is the cell state. Accepts the 3-D `Array<Array<FloatArray>>`
     * shape ORT produces, and tolerates a flattened 2-D variant.
     */
    private fun writeStateBack(value: Any?) {
        when (value) {
            is Array<*> -> {
                var offset = 0
                for (layer in value) {
                    when (layer) {
                        is Array<*> -> for (row in layer) {
                            if (row is FloatArray) {
                                for (v in row) {
                                    if (offset >= stateFlat.size) return
                                    stateFlat[offset++] = v
                                }
                            }
                        }
                        is FloatArray -> for (v in layer) {
                            if (offset >= stateFlat.size) return
                            stateFlat[offset++] = v
                        }
                    }
                }
            }
            is FloatArray -> {
                var offset = 0
                for (v in value) {
                    if (offset >= stateFlat.size) return
                    stateFlat[offset++] = v
                }
            }
        }
    }

    /**
     * Convert per-frame speech probabilities into contiguous silence
     * intervals (startMs, endMs) in the original audio timeline.
     *
     * PHASE 1: delegates to [SilenceSegmentMapper] (separate pre/post
     * padding + per-segment confidence). This legacy overload keeps the
     * symmetric [speechPadMs] → both sides mapping so existing callers
     * ([SilenceCutter]) behave byte-identically to before.
     *
     * New code should use [analyze] + [SilenceDetectionConfig] instead.
     *
     * @param probabilities Per-frame speech probabilities from [detect]
     * @param threshold Speech/silence classification threshold
     * @param speechPadMs Padding (ms) added before and after each
     *   speech segment to avoid cutting into words. Default 300 ms.
     * @param minSilenceMs Minimum silence duration (ms) to keep as a
     *   cuttable gap. Silences shorter than this are left intact.
     *   Default 500 ms.
     * @return List of silence intervals (startMs, endMs) sorted by start time
     */
    fun detectSilenceIntervals(
        probabilities: FloatArray,
        threshold: Float = 0.5f,
        speechPadMs: Float = 300f,
        minSilenceMs: Float = 500f
    ): List<SilenceInterval> {
        return SilenceSegmentMapper.map(
            probabilities,
            SilenceDetectionConfig(threshold, minSilenceMs, speechPadMs)
        ).map { SilenceInterval(startMs = it.startMs, endMs = it.endMs) }
    }

    /**
     * PHASE 1 — score already-computed VAD probabilities into
     * [SilenceSegment]s (with confidence + asymmetric padding).
     * Raw-audio inference stays in [detect]; this covers callers that
     * already own probabilities (previews, tests) without a model round.
     */
    fun analyzeProbabilities(
        probabilities: FloatArray,
        config: SilenceDetectionConfig = SilenceDetectionConfig()
    ): SilenceAnalysisResult {
        val segments = SilenceSegmentMapper.map(probabilities, config)
        val durationMs = (probabilities.size * SilenceSegmentMapper.FRAME_DURATION_MS).toLong()
        return SilenceAnalysisResult(segments = segments, analyzedDurationMs = durationMs)
    }

    override fun close() {
        session?.close()
        session = null
        // NOTE: OrtEnvironment is a process-wide singleton — never close
        // it here; just drop our reference.
        env = null
    }

    /**
     * A contiguous silence interval in the audio timeline.
     *
     * @property startMs Start of the silent region in milliseconds
     * @property endMs End of the silent region in milliseconds
     */
    data class SilenceInterval(
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = endMs - startMs
    }
}
