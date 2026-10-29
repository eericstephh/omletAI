package com.aicreatorstudio.app.ai.highlight

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.aicreatorstudio.app.ai.AudioExtractor
import com.aicreatorstudio.app.ai.voice.VoiceNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Plain word timing, mapped by the host from overlays.
 *
 * 2026-10-20 — TIMEBASE FIX: the base used to be documented only
 * as "clip-local ms", which was read as "including the trim offset"
 * and made the engine compare the words against the wrong timeline.
 * The base is explicit now: offsets into the ANALYSED (post-trim)
 * span of the clip — 0 = the first frame of the trimmed clip, the
 * same base as [SignalWindow.startMs] and [HighlightCandidate.startMs].
 * [HighlightEngine] lifts the times onto the source-media clock
 * before comparing them (see the timebase notes in
 * [HighlightEngine.analyze]) and also accepts times that already live
 * on that clock.
 */
data class WordTiming(val startMs: Long, val endMs: Long)

/**
 * Plain beat mark, mapped by the host from beatData.
 *
 * 2026-10-20 — TIMEBASE FIX: same base as [WordTiming] — offsets
 * into the analysed (post-trim) span of the clip.
 */
data class BeatMark(val timeMs: Long, val intensity: Float)

/**
 * PHASE 7 — Local Smart Highlight analysis engine.
 *
 * Orchestrates existing local signals over the SELECTED video clip:
 * decode once (16 kHz mono) → VAD probabilities + RMS windows (same
 * buffers, single passes) → sparse visual frame-diff (one retriever,
 * ≤120 samples, 160 px luma, bitmaps recycled) → bin host-provided
 * subtitle words + beat marks → [HighlightPlanner].
 *
 * No Whisper run, no new model, no network. Bounded memory: one PCM
 * buffer (freed before planning), ≤120 tiny bitmaps alive one at a
 * time. Fully cancellable at every stage.
 *
 * TIMEBASE (2026-10-20): the engine keeps TWO clocks apart on
 * purpose. The WINDOW GRID lives in offsets into the analysed
 * (post-trim) span of the clip (0 = first frame of the trimmed clip)
 * — that is what [SignalWindow] and [HighlightCandidate] report to
 * the host. Every LOOKUP lives on the SOURCE-MEDIA clock (trimStartMs
 * + offset): the audio window, the VAD frames, the sampled video
 * frames and the subtitle/beat comparison.
 */
class HighlightEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioExtractor: AudioExtractor
) {
    suspend fun analyze(
        clipId: String,
        fileName: String,
        clipUri: Uri,
        trimStartMs: Long,
        effectiveDurationMs: Long,
        config: HighlightConfig = HighlightConfig(),
        subtitleWords: List<WordTiming> = emptyList(),
        beatMarks: List<BeatMark> = emptyList(),
        enableVisual: Boolean = true,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): HighlightAnalysis {
        require(effectiveDurationMs > 0) { "empty clip" }
        onProgress(0f)

        // ---- audio: single decode, shared by VAD + energy ----
        val (samples, _, _) = audioExtractor.decodeTo16kMonoFloat(
            clipUri,
            onProgress = { onProgress(it * 0.35f) },
            checkCancelled = checkCancelled
        )
        coroutineContext[Job]?.ensureActive()
        checkCancelled?.invoke()
        onProgress(0.38f)

        // ---- VAD probabilities (existing Silero stack) ----
        val vad = com.aicreatorstudio.app.ai.vad.SileroVadDetector(context)
        val probabilities: FloatArray
        try {
            vad.loadModel()
            probabilities = vad.detect(samples, 0.5f) { f ->
                onProgress(0.38f + f * 0.22f)
            }
        } finally {
            runCatching { vad.close() }
        }
        coroutineContext[Job]?.ensureActive()
        checkCancelled?.invoke()

        // ---- windows over the effective span ----
        // 2026-10-20 — TIMEBASE FIX: the windows are compared against
        // the subtitle words and the beat marks, and that comparison
        // happens on the SOURCE-MEDIA clock (trimStartMs + offset into
        // the analysed span) — the same clock the audio/VAD/video
        // lookups of this engine already use. Lift the host times onto
        // that clock first, otherwise a trimmed clip (trimStartMs > 0)
        // counts the subtitles/beats of the WRONG part of the footage.
        val (wordsOnClock, beatsOnClock) = toSourceClock(
            subtitleWords, beatMarks, trimStartMs, effectiveDurationMs
        )
        val windows = buildWindows(
            trimStartMs, effectiveDurationMs, samples, probabilities,
            wordsOnClock, beatsOnClock, config
        )
        onProgress(0.65f)

        // ---- visual frame-diff (sparse, bounded, recyclable) ----
        val visual = if (enableVisual) {
            sampleVisualActivity(
                clipUri, trimStartMs, effectiveDurationMs, config,
                onProgress = { onProgress(0.65f + it * 0.25f) },
                checkCancelled = checkCancelled
            )
        } else emptyMap()
        coroutineContext[Job]?.ensureActive()
        checkCancelled?.invoke()
        for ((i, w) in windows.withIndex()) {
            visual[i]?.let { windows[i] = w.copy(visualActivity = it) }
        }
        onProgress(0.92f)

        val candidates = HighlightPlanner.plan(windows, clipId, config)
        onProgress(1f)
        return HighlightAnalysis(
            clipId = clipId,
            fileName = fileName,
            durationMs = effectiveDurationMs,
            windows = windows,
            candidates = candidates,
            signalsAvailable = availableSignals(subtitleWords, beatMarks, enableVisual)
        )
    }

    private fun buildWindows(
        trimStartMs: Long,
        effectiveDurationMs: Long,
        samples16k: FloatArray,
        probabilities: FloatArray,
        subtitleWords: List<WordTiming>,
        beatMarks: List<BeatMark>,
        config: HighlightConfig
    ): MutableList<SignalWindow> {
        val out = mutableListOf<SignalWindow>()
        var start = 0L
        val frameMs = 32L // Silero frame geometry
        // RMS accumulation per window in one pass over the span.
        while (start < effectiveDurationMs) {
            val end = min(start + config.windowMs, effectiveDurationMs)
            // Source-media offsets into the decoded buffer.
            val srcFrom = ((trimStartMs + start) * 16L).coerceAtLeast(0L)
            val srcTo = ((trimStartMs + end) * 16L).coerceAtMost(samples16k.size.toLong())
            var sumSq = 0.0
            var n = 0L
            var i = srcFrom
            while (i < srcTo) {
                val v = samples16k[i.toInt()].toDouble()
                sumSq += v * v
                n++
                i++
            }
            val rmsDb = if (n == 0L) Float.NEGATIVE_INFINITY
            else VoiceNormalizer.peakToDb(
                kotlin.math.sqrt(sumSq / n).toFloat()
            )

            // Speech ratio from VAD frames overlapping the window.
            val frameFrom = ((trimStartMs + start) / frameMs).toInt().coerceAtLeast(0)
            val frameTo = ((trimStartMs + end) / frameMs).toInt()
                .coerceAtMost(probabilities.size)
            var speech = 0
            var total = 0
            for (f in frameFrom until frameTo) {
                total++
                if (probabilities[f] >= 0.5f) speech++
            }
            val speechRatio = if (total == 0) null else speech.toFloat() / total

            // 2026-10-20 — TIMEBASE FIX: [start, end) is an offset into
            // the analysed span; on the source-media clock (the clock
            // the audio/VAD/video lookups of this engine use) the very
            // same window is [trimStartMs + start, trimStartMs + end).
            // The word/beat times were lifted onto that clock in
            // analyze(), so the comparison below has to use it too —
            // the old code compared clip-local times against the raw
            // analysed-span offsets, which measured the subtitle/beat
            // density of a shifted part of the clip.
            val winStart = trimStartMs + start
            val winEnd = trimStartMs + end
            // Subtitle density: words overlapping the window.
            var wordCount = 0
            for (w in subtitleWords) {
                if (w.startMs < winEnd && w.endMs > winStart) wordCount++
            }
            val wps = if (subtitleWords.isEmpty()) null
            else wordCount.toFloat() / ((winEnd - winStart).coerceAtLeast(1L) / 1000f)

            // Beats inside the window.
            val inWindow = beatMarks.filter { it.timeMs >= winStart && it.timeMs < winEnd }
            val beatDensity: Float?
            val beatIntensity: Float?
            if (beatMarks.isEmpty()) {
                beatDensity = null
                beatIntensity = null
            } else {
                beatDensity = inWindow.size.toFloat()
                beatIntensity = if (inWindow.isEmpty()) 0f
                else inWindow.sumOf { it.intensity.coerceIn(0f, 1f).toDouble() }
                    .toFloat() / inWindow.size
            }

            out.add(
                SignalWindow(
                    startMs = start,
                    endMs = end,
                    speechRatio = speechRatio,
                    rmsDb = rmsDb,
                    beatDensity = beatDensity,
                    beatIntensity = beatIntensity,
                    wordsPerSecond = wps
                )
            )
            if (end >= effectiveDurationMs) break
            start += config.strideMs.coerceAtLeast(500L)
        }
        // Beat density normalization needs the global max: rescale here.
        val maxBeats = out.maxOfOrNull { it.beatDensity ?: 0f } ?: 0f
        if (maxBeats > 0f) {
            for (i in out.indices) {
                out[i].beatDensity?.let { d ->
                    out[i] = out[i].copy(beatDensity = (d / maxBeats).coerceIn(0f, 1f))
                }
            }
        }
        // Neighborhood mean RMS for contextual energy.
        val means = out.map { it.rmsDb }
        for (i in out.indices) {
            val neighbors = listOfNotNull(
                means.getOrNull(i - 1)?.takeIf { it.isFinite() },
                means.getOrNull(i + 1)?.takeIf { it.isFinite() }
            )
            if (neighbors.isNotEmpty()) {
                out[i] = out[i].copy(neighborRmsDb = neighbors.average().toFloat())
            }
        }
        return out
    }

    /**
     * Sparse luma frame-diff: ~1 Hz samples (cap [MAX_VISUAL_SAMPLES]),
     * 160 px wide, mean-absolute-diff vs the previous sample mapped
     * onto overlapping windows (max). Bitmaps recycled immediately.
     */
    private suspend fun sampleVisualActivity(
        clipUri: Uri,
        trimStartMs: Long,
        effectiveDurationMs: Long,
        config: HighlightConfig,
        onProgress: (Float) -> Unit,
        checkCancelled: (() -> Unit)?
    ): Map<Int, Float> {
        val strideMs = maxOf(
            1000L,
            effectiveDurationMs / MAX_VISUAL_SAMPLES
        )
        val retriever = MediaMetadataRetriever()
        val diffs = mutableListOf<Pair<Long, Float>>() // (clip-local ms, mad)
        try {
            retriever.setDataSource(context, clipUri)
            var prev: FloatArray? = null
            var t = 0L
            var done = 0
            val total = (effectiveDurationMs / strideMs).toInt().coerceAtLeast(1)
            while (t < effectiveDurationMs) {
                coroutineContext[Job]?.ensureActive()
                checkCancelled?.invoke()
                // 2026-10-20 — VISUAL FIX (the VISUAL signal was dead):
                // OPTION_CLOSEST_SYNC returns the nearest SYNC frame (a
                // keyframe). Inside one GOP every sample therefore
                // returned the SAME picture, so the frame-to-frame
                // difference stayed 0 for the whole GOP and the
                // strongest motion cue of the ranking was silently
                // lost. OPTION_CLOSEST returns the frame actually
                // closest to the requested timestamp, so consecutive
                // samples show the real picture change. (Two samples
                // that land on the same picture still give a
                // difference of 0 — that is content, not a bug.)
                val bmp = runCatching {
                    retriever.getFrameAtTime(
                        (trimStartMs + t) * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                }.getOrNull()
                try {
                    if (bmp != null) {
                        val scaled = android.graphics.Bitmap.createScaledBitmap(
                            bmp,
                            VISUAL_WIDTH,
                            (VISUAL_WIDTH * bmp.height / bmp.width.coerceAtLeast(1))
                                .coerceIn(1, 320),
                            true
                        )
                        try {
                            val w = scaled.width
                            val h = scaled.height
                            val px = IntArray(w * h)
                            scaled.getPixels(px, 0, w, 0, 0, w, h)
                            val luma = FloatArray(w * h)
                            for (k in px.indices) {
                                val p = px[k]
                                luma[k] = (0.299f * ((p shr 16) and 0xFF) +
                                    0.587f * ((p shr 8) and 0xFF) +
                                    0.114f * (p and 0xFF))
                            }
                            val p = prev
                            if (p != null && p.size == luma.size) {
                                var sum = 0.0
                                for (k in luma.indices) {
                                    sum += abs(luma[k] - p[k]).toDouble()
                                }
                                val mad = (sum / luma.size).toFloat()
                                // MAD 0..255 → activity 0..1 (2 = still, 20+ = action).
                                diffs.add(t to ((mad - 2f) / 18f).coerceIn(0f, 1f))
                            }
                            prev = luma
                        } finally {
                            runCatching { scaled.recycle() }
                        }
                    }
                } finally {
                    runCatching { bmp?.recycle() }
                }
                done++
                onProgress(done.toFloat() / total.coerceAtLeast(1))
                t += strideMs
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 2026-10-20 — CancellationException extends
            // IllegalStateException → Exception, so the old blanket
            // `catch (e: Exception)` swallowed the cancellation and
            // the "best-effort visual" path returned an empty map —
            // which meant a CANCELLED analysis ran to completion and
            // reported success. Cancellation must propagate.
            throw e
        } catch (e: Exception) {
            // Visual is best-effort: analysis continues audio-only.
            return emptyMap()
        } finally {
            runCatching { retriever.release() }
        }
        // Bin diffs onto window indices (window = start/stride grid).
        val out = mutableMapOf<Int, Float>()
        for ((timeMs, activity) in diffs) {
            val idx = (timeMs / config.strideMs.coerceAtLeast(500L)).toInt()
            out[idx] = maxOf(out[idx] ?: 0f, activity)
        }
        return out
    }

    /**
     * 2026-10-20 — TIMEBASE FIX: lift the host-provided subtitle word
     * / beat mark times onto the SOURCE-MEDIA clock, the clock every
     * lookup of this engine uses (audio window = trimStartMs + offset,
     * VAD frames of the same span, video frames sampled at trimStartMs
     * + t).
     *
     * Why: the analysis windows are offsets into the analysed (post-trim)
     * span, but they are compared against the word/beat times.
     * Comparing the two directly mixes two timelines, so a clip that
     * was trimmed (trimStartMs > 0) counted the subtitles and the beats
     * of a DIFFERENT part of the footage — the SUBTITLES and RHYTHM
     * signals were silently computed against a shifted timeline.
     *
     * Which base do the host times carry? The host maps overlays and
     * persisted markers onto the clip by subtracting the clip timeline
     * start, i.e. they are offsets into the analysed span (0 = first
     * frame of the trimmed clip) — the same base that [WordTiming] /
     * [BeatMark] document. Those are lifted by +trimStartMs. A host
     * that hands over times which are ALREADY on the source clock
     * (they reach past the end of the analysed span) is left
     * untouched, so both host conventions are accepted while the
     * engine itself only ever compares on ONE timeline.
     */
    private fun toSourceClock(
        subtitleWords: List<WordTiming>,
        beatMarks: List<BeatMark>,
        trimStartMs: Long,
        effectiveDurationMs: Long
    ): Pair<List<WordTiming>, List<BeatMark>> {
        // trimStartMs == 0: both clocks are identical, nothing to lift.
        if (trimStartMs <= 0L) return subtitleWords to beatMarks
        // The host clamps its times into the analysed span, so a time
        // BEYOND that span can only come from the source clock.
        val alreadyOnSourceClock = subtitleWords.any {
            it.startMs > effectiveDurationMs || it.endMs > effectiveDurationMs
        } || beatMarks.any { it.timeMs > effectiveDurationMs }
        if (alreadyOnSourceClock) return subtitleWords to beatMarks
        val words = subtitleWords.map {
            it.copy(startMs = it.startMs + trimStartMs, endMs = it.endMs + trimStartMs)
        }
        val beats = beatMarks.map { it.copy(timeMs = it.timeMs + trimStartMs) }
        return words to beats
    }

    private fun availableSignals(
        subtitleWords: List<WordTiming>,
        beatMarks: List<BeatMark>,
        visual: Boolean
    ): Set<HighlightSignal> = buildSet {
        add(HighlightSignal.SPEECH)
        add(HighlightSignal.ENERGY)
        if (beatMarks.isNotEmpty()) add(HighlightSignal.RHYTHM)
        if (visual) add(HighlightSignal.VISUAL)
        if (subtitleWords.isNotEmpty()) add(HighlightSignal.SUBTITLES)
    }

    companion object {
        const val MAX_VISUAL_SAMPLES: Long = 120L
        const val VISUAL_WIDTH: Int = 160
        const val MAX_ANALYZE_DURATION_MS: Long = 15L * 60L * 1000L
    }
}

/** Engine output: windows + ranked candidates + availability. */
data class HighlightAnalysis(
    val clipId: String,
    val fileName: String,
    val durationMs: Long,
    val windows: List<SignalWindow>,
    val candidates: List<HighlightCandidate>,
    val signalsAvailable: Set<HighlightSignal>
)
