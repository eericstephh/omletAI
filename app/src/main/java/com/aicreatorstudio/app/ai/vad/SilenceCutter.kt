package com.aicreatorstudio.app.ai.vad

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aicreatorstudio.app.ai.AudioExtractor
import com.aicreatorstudio.app.model.VideoClip
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Orchestrates auto-silence cutting across video clips.
 *
 * Pipeline per clip:
 *  1. Decode audio to 16 kHz mono float via [AudioExtractor]
 *  2. Run Silero VAD to detect speech probabilities
 *  3. Convert probabilities to silence intervals
 *  4. Split the clip at silence boundaries
 *  5. Remove silent segments, keep speech segments
 *  6. Ripple-shift remaining segments to close gaps
 *
 * The caller (EditorViewModel) handles the actual timeline
 * mutation; this class returns the new clip list.
 */
class SilenceCutter(private val context: Context) {

    private val vadDetector = SileroVadDetector(context)
    private val audioExtractor = AudioExtractor(context)

    /**
     * Process a list of video clips, removing silent sections.
     *
     * @param clips The current project clips (video + audio)
     * @param speechThreshold VAD speech probability threshold (0..1)
     * @param speechPadMs Padding around speech segments (ms)
     * @param minSilenceMs Minimum silence duration to cut (ms)
     * @param onProgress Progress callback (0f..1f)
     * @param checkCancelled Cancellation check (throws CancellationException)
     * @return [CutResult] with the new clip list and statistics
     */
    suspend fun cutSilence(
        clips: List<VideoClip>,
        speechThreshold: Float = 0.5f,
        speechPadMs: Float = 300f,
        minSilenceMs: Float = 500f,
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): CutResult {
        vadDetector.loadModel()

        val videoClips = clips.filter { !it.isAudioOnly }
        if (videoClips.isEmpty()) {
            return CutResult(
                newClips = clips,
                totalSilencesRemoved = 0,
                totalSilenceMsRemoved = 0
            )
        }

        val newClips = mutableListOf<VideoClip>()
        var totalSilencesRemoved = 0
        var totalSilenceMsRemoved = 0L
        var accumulatedTimeShift = 0L

        for ((index, clip) in videoClips.withIndex()) {
            coroutineContext.ensureActive()
            checkCancelled?.invoke()

            val clipProgress = index.toFloat() / videoClips.size
            onProgress(clipProgress * 0.7f) // 70% for VAD detection

            try {
                val uri = Uri.parse(clip.uri)
                val (samples, sampleRate, _) = audioExtractor.decodeTo16kMonoFloat(uri)

                coroutineContext.ensureActive()
                checkCancelled?.invoke()

                val probabilities = vadDetector.detect(samples, speechThreshold) { frameProgress ->
                    onProgress(clipProgress * 0.7f + frameProgress * 0.3f / videoClips.size)
                }

                val silenceIntervals = vadDetector.detectSilenceIntervals(
                    probabilities,
                    speechThreshold,
                    speechPadMs,
                    minSilenceMs
                )

                // Map silence intervals into segments to KEEP (speech).
                // Shared helper with buildCutClips so one-shot and
                // preview→apply always agree.
                val clipDuration = clip.effectiveDurationMs
                val base = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
                val speechSegments = speechSegmentsFor(clipDuration, silenceIntervals)

                if (speechSegments.isEmpty()) {
                    // Entire clip is silence — remove it
                    totalSilencesRemoved++
                    totalSilenceMsRemoved += clipDuration
                    continue
                }

                // Create new clips for each speech segment
                for ((segStart, segEnd) in speechSegments) {
                    val segDuration = segEnd - segStart
                    if (segDuration <= 0) continue

                    val newClip = clip.copy(
                        id = UUID.randomUUID().toString(),
                        trimStartMs = clip.trimStartMs + segStart,
                        trimEndMs = clip.trimStartMs + segEnd,
                        // 2026-10-20 — the position is set by the
                        // sequential re-lay at the end of this function,
                        // which is the single source of truth.
                        //
                        // The old inline expression computed
                        // `base + segStart - accumulatedTimeShift + (the
                        // removed gap)`, which ADDED the silence gap
                        // back instead of closing it — the opposite of
                        // what a ripple edit does. The value was dead
                        // anyway (the re-lay overwrote it), but a
                        // reader had to work out that, and the
                        // "silence removed" statistic disagreed with
                        // the positions the expression implied.
                        timelineStartMs = base
                    )
                    newClips.add(newClip)
                }

                // Calculate how much time was removed from this clip
                val speechTotal = speechSegments.sumOf { it.second - it.first }
                val removedFromClip = clipDuration - speechTotal
                if (removedFromClip > 0) {
                    totalSilencesRemoved++
                    totalSilenceMsRemoved += removedFromClip
                }
                accumulatedTimeShift += removedFromClip

            } catch (e: Exception) {
                // If VAD fails for a clip, keep it unchanged.
                //
                // 2026-10-20 — LOG THE FAILURE. This catch is the reason
                // "حذف سکوت" looked like it worked while doing nothing:
                // every VAD error (including the ONNX signature mismatch
                // that made `detect()` throw on EVERY frame) was
                // swallowed here, the clip was kept unchanged, and the
                // tool still reported success. The failure is now
                // recorded so the diagnostics panel can show it.
                Log.w(TAG, "VAD failed for clip ${clip.id} — keeping it unchanged", e)
                newClips.add(clip)
            }
        }

        // Re-lay clips on the timeline sequentially (close all gaps)
        var timelinePos = 0L
        val finalClips = newClips.map { clip ->
            val adjusted = clip.copy(timelineStartMs = timelinePos)
            timelinePos += adjusted.effectiveDurationMs
            adjusted
        }

        // Re-add audio-only clips (music/SFX) with adjusted positions
        val audioOnlyClips = clips.filter { it.isAudioOnly }
        val allClips = finalClips + audioOnlyClips

        onProgress(1f)
        return CutResult(
            newClips = allClips,
            totalSilencesRemoved = totalSilencesRemoved,
            totalSilenceMsRemoved = totalSilenceMsRemoved
        )
    }

    /**
     * PHASE 1 — pure analysis, no timeline mutation.
     *
     * Decodes each video clip and returns its scored silence segments so
     * the UI can PREVIEW what would be removed before anything is cut.
     * Call [buildCutClips] to apply a (possibly user-edited) analysis.
     *
     * Runs entirely on-device via [AudioExtractor] + [SileroVadDetector].
     */
    suspend fun analyzeClips(
        clips: List<VideoClip>,
        config: SilenceDetectionConfig = SilenceDetectionConfig(),
        onProgress: (Float) -> Unit = {},
        checkCancelled: (() -> Unit)? = null
    ): List<ClipSilenceAnalysis> {
        vadDetector.loadModel()

        val videoClips = clips.filter { !it.isAudioOnly }
        if (videoClips.isEmpty()) return emptyList()

        val out = mutableListOf<ClipSilenceAnalysis>()
        for ((index, clip) in videoClips.withIndex()) {
            coroutineContext.ensureActive()
            checkCancelled?.invoke()

            val clipProgress = index.toFloat() / videoClips.size
            onProgress(clipProgress * 0.7f)

            try {
                val uri = Uri.parse(clip.uri)
                val (samples, _, _) = audioExtractor.decodeTo16kMonoFloat(uri)

                coroutineContext.ensureActive()
                checkCancelled?.invoke()

                val probabilities = vadDetector.detect(samples, config.threshold) { frameProgress ->
                    onProgress(clipProgress * 0.7f + frameProgress * 0.3f / videoClips.size)
                }
                val result = vadDetector.analyzeProbabilities(probabilities, config)
                out.add(
                    ClipSilenceAnalysis(
                        clipId = clip.id,
                        fileName = clip.fileName,
                        clipDurationMs = clip.effectiveDurationMs,
                        segments = result.segments,
                        analyzedDurationMs = result.analyzedDurationMs
                    )
                )
            } catch (e: Exception) {
                // VAD failure → report no silences so the clip is kept whole.
                out.add(
                    ClipSilenceAnalysis(
                        clipId = clip.id,
                        fileName = clip.fileName,
                        clipDurationMs = clip.effectiveDurationMs,
                        segments = emptyList(),
                        analyzedDurationMs = 0L
                    )
                )
            }
        }
        onProgress(1f)
        return out
    }

    /**
     * PHASE 1 — build the cut clip list from an analysis WITHOUT
     * re-running inference. Pure function of (clips, analyses).
     *
     * PHASE 2: segments are normalized per clip (clamped to the clip,
     * overlaps merged) before splitting, so overlapping/adjacent or
     * slightly out-of-bounds segments can never produce invalid or
     * overlapping destructive operations.
     *
     * Relay + counting semantics match [cutSilence] exactly.
     */
    fun buildCutClips(
        clips: List<VideoClip>,
        analyses: List<ClipSilenceAnalysis>
    ): CutResult {
        val byId = analyses.associateBy { it.clipId }
        val newClips = mutableListOf<VideoClip>()
        var totalSilencesRemoved = 0
        var totalSilenceMsRemoved = 0L
        var accumulatedTimeShift = 0L

        for (clip in clips.filter { !it.isAudioOnly }) {
            val clipDuration = clip.effectiveDurationMs
            val analysis = byId[clip.id]
            val silences = SilenceSegmentMapper.normalize(
                analysis?.segments?.sortedBy { it.startMs } ?: emptyList(),
                clipDuration
            ).map { SileroVadDetector.SilenceInterval(it.startMs, it.endMs) }

            val base = clip.timelineStartMs.takeIf { it >= 0 } ?: 0L
            val speechSegments = speechSegmentsFor(clipDuration, silences)

            if (speechSegments.isEmpty()) {
                totalSilencesRemoved++
                totalSilenceMsRemoved += clipDuration
                continue
            }

            for ((segStart, segEnd) in speechSegments) {
                val segDuration = segEnd - segStart
                if (segDuration <= 0) continue

                val newClip = clip.copy(
                    id = UUID.randomUUID().toString(),
                    trimStartMs = clip.trimStartMs + segStart,
                    trimEndMs = clip.trimStartMs + segEnd,
                    timelineStartMs = base + segStart - accumulatedTimeShift +
                        (speechSegments.indexOf(segStart to segEnd)).let { idx ->
                            if (idx > 0) {
                                val prevEnd = speechSegments[idx - 1].second
                                segStart - prevEnd
                            } else 0L
                        }
                )
                newClips.add(newClip)
            }

            val speechTotal = speechSegments.sumOf { it.second - it.first }
            val removedFromClip = clipDuration - speechTotal
            if (removedFromClip > 0) {
                totalSilencesRemoved++
                totalSilenceMsRemoved += removedFromClip
            }
            accumulatedTimeShift += removedFromClip
        }

        var timelinePos = 0L
        val finalClips = newClips.map { clip ->
            val adjusted = clip.copy(timelineStartMs = timelinePos)
            timelinePos += adjusted.effectiveDurationMs
            adjusted
        }

        val audioOnlyClips = clips.filter { it.isAudioOnly }
        return CutResult(
            newClips = finalClips + audioOnlyClips,
            totalSilencesRemoved = totalSilencesRemoved,
            totalSilenceMsRemoved = totalSilenceMsRemoved
        )
    }

    /**
     * Clip-local speech runs derived from sorted silence intervals:
     * everything NOT silence is kept. Shared by [cutSilence] (legacy
     * one-shot) and [buildCutClips] (preview → apply) so both agree.
     */
    private fun speechSegmentsFor(
        clipDurationMs: Long,
        silences: List<SileroVadDetector.SilenceInterval>
    ): List<Pair<Long, Long>> {
        val speechSegments = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for (silence in silences.sortedBy { it.startMs }) {
            if (silence.startMs > cursor) {
                speechSegments.add(cursor to silence.startMs)
            }
            cursor = silence.endMs
        }
        if (cursor < clipDurationMs) {
            speechSegments.add(cursor to clipDurationMs)
        }
        return speechSegments
    }

    /**
     * Per-clip silence preview (see [analyzeClips]).
     */
    data class ClipSilenceAnalysis(
        val clipId: String,
        val fileName: String,
        val clipDurationMs: Long,
        val segments: List<SilenceSegment>,
        val analyzedDurationMs: Long
    ) {
        val silenceMs: Long get() = segments.sumOf { it.durationMs }
    }

    /**
     * Result of the silence cutting operation.
     *
     * @property newClips The processed clip list with silences removed
     * @property totalSilencesRemoved Number of silence intervals cut
     * @property totalSilenceMsRemoved Total milliseconds of silence removed
     */
    data class CutResult(
        val newClips: List<VideoClip>,
        val totalSilencesRemoved: Int,
        val totalSilenceMsRemoved: Long
    )

    fun release() {
        vadDetector.close()
    }

    companion object {
        private const val TAG = "SilenceCutter"

        /**
         * PHASE 2 — fingerprint of the timeline an analysis was generated
         * from (requirement 10). Covers every video clip's identity,
         * trims, source duration and timeline placement. The ViewModel
         * stores it in [com.aicreatorstudio.app.model.AutoSilenceCutState.Preview]
         * and re-checks before toggle/apply — a mismatch means the user
         * edited the timeline mid-preview and the preview is stale.
         * Pure string build; call only on analyze/apply/toggle, never
         * per frame.
         */
        fun previewSourceKey(clips: List<VideoClip>): String {
            val video = clips.filter { !it.isAudioOnly }
            return buildString {
                append(video.size)
                append('|')
                for (c in video) {
                    append(c.id).append(':')
                        .append(c.trimStartMs).append(':')
                        .append(c.trimEndMs).append(':')
                        .append(c.durationMs).append(':')
                        .append(c.timelineStartMs).append(';')
                }
            }
        }

        /**
         * PHASE 2 — keep only the user-selected segments per clip.
         * Selection travels as [segmentId]s (`"$clipId:$sortedIndex"`,
         * assigned at analyze time); segments are re-sorted here so the
         * indices resolve identically. Unselected cuts stay in the
         * timeline; clips left with nothing selected are kept whole by
         * [buildCutClips]. Pure — no media touched.
         */
        fun filterSelected(
            analyses: List<ClipSilenceAnalysis>,
            selectedIds: Set<String>
        ): List<ClipSilenceAnalysis> {
            return analyses.map { a ->
                val sorted = a.segments.sortedBy { it.startMs }
                a.copy(segments = sorted.filterIndexed { index, _ ->
                    selectedIds.contains(segmentId(a.clipId, index))
                })
            }
        }

        /**
         * Deterministic per-segment ID shared by ViewModel, panel and
         * [filterSelected]: `"$clipId:$index"` over start-sorted
         * segments. Stable for the life of one analysis.
         */
        fun segmentId(clipId: String, sortedIndex: Int): String = "$clipId:$sortedIndex"
    }
}
