package com.aicreatorstudio.app.ai.benchmark

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.AiModels
import com.aicreatorstudio.app.ai.CatalogEntry
import com.aicreatorstudio.app.ai.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PHASE B7 — developer-only model-comparison benchmark.
 *
 * The brief is explicit:
 *  - "Produce a model comparison benchmark (Tiny / Small / Medium / Large-v3)"
 *  - "The benchmark must run on-device, offline, and be reproducible"
 *  - "Add a developer/debug-only benchmark entry point if necessary. Do not
 *    expose experimental benchmark controls to normal users unless the
 *    existing product architecture already has a developer/debug mode."
 *
 * The existing product architecture has no developer mode and no debug
 * menu. To honour "do not expose experimental benchmark controls to
 * normal users", this runner is **not** called from any production
 * code path. It is invoked only by the
 * `WhisperModelBenchmarkTest` instrumentation test (developer-only
 * test source set). The production UI never instantiates this class.
 *
 * ## Honest skip for off-catalog specs
 *
 * The production [com.aicreatorstudio.app.ai.WhisperModelSpec] enum
 * only contains MEDIUM + LARGE_V3 (Tiny / Small were removed in
 * PHASE B1.6). The benchmark still iterates the four logical
 * model sizes — Tiny, Small, Medium, Large-v3 — so a developer can
 * see "not installed" for the off-catalog rows instead of silently
 * omitting them. For off-catalog sizes we construct a [CatalogEntry]
 * by hand (mirroring the production AiModelCatalog fields) and
 * call [AiModels.isVerifiedInstalled]; when false the row's
 * `note = "not installed"` and the row is **not** transcribe-attempted.
 *
 * ## Threading
 *
 * `runBenchmark` is `suspend` and runs each model's transcribe on
 * `Dispatchers.IO`. whisper.cpp is CPU-bound; the work is serialised
 * across the four models in this runner to keep peak memory bounded
 * and to keep `whisper_benchmark_report.json` deterministic.
 */
object WhisperModelBenchmark {

    private const val TAG = "B7Benchmark"

    /**
     * The four logical model sizes the brief asks us to compare.
     * `id` and `dirName` and `fileName` come from the production
     * `models.lock.json` for MEDIUM + LARGE_V3; for Tiny + Small the
     * fields below mirror the historical lockfile so a developer who
     * has a side-loaded Tiny/Small GGML file can drop it into the
     * expected `dirName/fileName` and have `isVerifiedInstalled`
     * return true.
     */
    val SPECS: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "whisper-tiny",
            displayName = "Whisper Tiny",
            dirName = "whisper-tiny",
            fileName = "ggml-tiny.bin",
            minSizeBytes = 77_000_000L,
            language = "fa",
            whisperThreads = 2
        ),
        CatalogEntry(
            id = "whisper-small",
            displayName = "Whisper Small",
            dirName = "whisper-small",
            fileName = "ggml-small.bin",
            minSizeBytes = 488_000_000L,
            language = "fa",
            whisperThreads = 4
        ),
        CatalogEntry(
            id = "whisper-large-v3",
            displayName = "Whisper Large-v3",
            dirName = "whisper-large-v3",
            fileName = "ggml-large-v3.bin",
            minSizeBytes = 3_100_000_000L,
            language = "fa",
            whisperThreads = 4
        )
    )

    /**
     * Build a [BenchmarkReport] by iterating the four logical model
     * sizes against [audioUri]. Installed models are transcribed;
     * non-installed models are recorded with `note = "not installed"`
     * and no transcribe attempt is made.
     *
     * The report includes the full [com.aicreatorstudio.app.ai.Transcription]
     * for each successful row so the developer can inspect the raw
     * Whisper output after the run (via
     * `cacheDir/whisper_benchmark_report.json` written by the test).
     */
    suspend fun runBenchmark(
        context: Context,
        engine: WhisperEngine,
        audioUri: Uri,
        audioDurationMs: Long
    ): BenchmarkReport = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        val rows = ArrayList<ModelBenchmarkResult>(SPECS.size)
        for (spec in SPECS) {
            val rowStartedAt = System.nanoTime()
            val installed = AiModels.isVerifiedInstalled(context, spec)
            if (!installed) {
                Log.i(
                    TAG,
                    "model=${spec.id} note=\"not installed\" file=" +
                        AiModels.modelFile(context, spec).absolutePath
                )
                rows.add(
                    ModelBenchmarkResult(
                        modelId = spec.id,
                        engineId = "whisper",
                        segmentCount = 0,
                        wordCount = 0,
                        emptySegmentCount = 0,
                        processingTimeMs = 0L,
                        audioDurationMs = audioDurationMs,
                        realTimeFactor = 0.0,
                        averageConfidence = null,
                        transcription = null,
                        note = "not installed"
                    )
                )
                continue
            }
            // Spec is installed. Transcribe it.
            val result = engine.benchmarkTranscribe(spec, audioUri)
            val elapsedMs = (System.nanoTime() - rowStartedAt) / 1_000_000L
            val row = if (result.isSuccess) {
                val t = result.getOrNull()!!
                val emptySegs = t.segments.count { it.words.isEmpty() }
                val confs = t.words.mapNotNull { it.confidence }
                val avg = if (confs.isEmpty()) null else confs.average().toFloat()
                val rtf = if (audioDurationMs > 0)
                    elapsedMs.toDouble() / audioDurationMs.toDouble()
                else 0.0
                Log.i(
                    TAG,
                    "model=${spec.id} words=${t.words.size} " +
                        "segments=${t.segments.size} rtf=$rtf " +
                        "avg_confidence=$avg processingMs=$elapsedMs"
                )
                ModelBenchmarkResult(
                    modelId = spec.id,
                    engineId = "whisper",
                    segmentCount = t.segments.size,
                    wordCount = t.words.size,
                    emptySegmentCount = emptySegs,
                    processingTimeMs = elapsedMs,
                    audioDurationMs = audioDurationMs,
                    realTimeFactor = rtf,
                    averageConfidence = avg,
                    transcription = t,
                    note = ""
                )
            } else {
                Log.w(
                    TAG,
                    "model=${spec.id} note=\"load failed\" " +
                        "err=${result.exceptionOrNull()?.message}"
                )
                ModelBenchmarkResult(
                    modelId = spec.id,
                    engineId = "whisper",
                    segmentCount = 0,
                    wordCount = 0,
                    emptySegmentCount = 0,
                    processingTimeMs = elapsedMs,
                    audioDurationMs = audioDurationMs,
                    realTimeFactor = 0.0,
                    averageConfidence = null,
                    transcription = null,
                    note = "load failed"
                )
            }
            rows.add(row)
            // Release the engine's bench context so the next model
            // starts clean. The engine's `benchmarkTranscribe`
            // already frees its own native context in `finally`,
            // so this is a safety net.
            engine.release()
        }
        val totalWallMs = (System.nanoTime() - startedAt) / 1_000_000L
        BenchmarkReport(
            fixturePath = audioUri.toString(),
            fixtureDurationMs = audioDurationMs,
            rows = rows,
            totalWallTimeMs = totalWallMs
        )
    }

    /**
     * Compute the audio duration in milliseconds for [audioUri] using
     * [android.media.MediaMetadataRetriever]. Used by the
     * instrumentation test to populate `audioDurationMs` in the
     * report.
     */
    fun probeAudioDurationMs(context: Context, audioUri: Uri): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, audioUri)
            mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.w(TAG, "probeAudioDurationMs failed", e)
            0L
        } finally {
            try { mmr.release() } catch (_: Exception) { /* ignore */ }
        }
    }

    /**
     * Persist [report] to `cacheDir/whisper_benchmark_report.json`
     * as a stable, machine-readable artifact. The instrumentation
     * test calls this so a developer can `adb pull` the file after
     * the run. The report is intentionally a flat JSON shape (not
     * the kotlinx-serialization one) so it can be read by any tool.
     *
     * Returns the resolved file on success, null on I/O failure.
     */
    fun writeReport(context: Context, report: BenchmarkReport): File? {
        val out = File(context.cacheDir, "whisper_benchmark_report.json")
        return try {
            out.writeText(toJson(report))
            Log.i(TAG, "wrote ${out.absolutePath} (${out.length()} bytes)")
            out
        } catch (e: Exception) {
            Log.w(TAG, "writeReport failed", e)
            null
        }
    }

    private fun toJson(report: BenchmarkReport): String {
        val sb = StringBuilder(4096)
        sb.append("{\n")
        sb.append("  \"fixturePath\": ").append(jsonStr(report.fixturePath)).append(",\n")
        sb.append("  \"fixtureDurationMs\": ").append(report.fixtureDurationMs).append(",\n")
        sb.append("  \"totalWallTimeMs\": ").append(report.totalWallTimeMs).append(",\n")
        sb.append("  \"rows\": [\n")
        report.rows.forEachIndexed { idx, r ->
            sb.append("    {\n")
            sb.append("      \"modelId\": ").append(jsonStr(r.modelId)).append(",\n")
            sb.append("      \"engineId\": ").append(jsonStr(r.engineId)).append(",\n")
            sb.append("      \"segmentCount\": ").append(r.segmentCount).append(",\n")
            sb.append("      \"wordCount\": ").append(r.wordCount).append(",\n")
            sb.append("      \"emptySegmentCount\": ").append(r.emptySegmentCount).append(",\n")
            sb.append("      \"processingTimeMs\": ").append(r.processingTimeMs).append(",\n")
            sb.append("      \"audioDurationMs\": ").append(r.audioDurationMs).append(",\n")
            sb.append("      \"realTimeFactor\": ")
                .append(String.format("%.4f", r.realTimeFactor)).append(",\n")
            sb.append("      \"averageConfidence\": ")
                .append(r.averageConfidence?.toString() ?: "null").append(",\n")
            sb.append("      \"note\": ").append(jsonStr(r.note)).append(",\n")
            sb.append("      \"segments\": [\n")
            r.transcription?.segments?.forEachIndexed { sidx, s ->
                sb.append("        {\n")
                sb.append("          \"id\": ").append(jsonStr(s.id)).append(",\n")
                sb.append("          \"startTimeMs\": ").append(s.startTimeMs).append(",\n")
                sb.append("          \"endTimeMs\": ").append(s.endTimeMs).append(",\n")
                sb.append("          \"text\": ").append(jsonStr(s.text)).append("\n")
                sb.append("        }").append(if (sidx < r.transcription.segments.size - 1) "," else "").append("\n")
            }
            sb.append("      ]\n")
            sb.append("    }").append(if (idx < report.rows.size - 1) "," else "").append("\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
