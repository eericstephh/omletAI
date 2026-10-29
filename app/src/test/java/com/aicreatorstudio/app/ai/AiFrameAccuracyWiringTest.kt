package com.aicreatorstudio.app.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-19 — guards the frame-accuracy fix for the on-device AI tools.
 *
 * ## The defect this pins
 *
 * Background Remove, Auto Frame and OCR sampled frames with
 * `MediaMetadataRetriever.getFrameAtTime(ts, OPTION_CLOSEST_SYNC)`. That
 * option returns the closest **sync frame** (keyframe), so:
 *
 *  - every timestamp inside one GOP decoded to the same picture — a 10 fps
 *    matting pass over a 2 s GOP wrote 20 identical masks;
 *  - each mask was labelled with the *requested* timestamp rather than the
 *    picture's own PTS, so masks lagged the frame by up to half a GOP and
 *    the cutout slid off the person;
 *  - Auto Frame's subject trace became a staircase, which the 1-Euro filter
 *    then smoothed into a crop that jumped once per GOP;
 *  - the returned bitmap carried no display rotation, so crop centres and
 *    ROI coordinates were computed in the wrong orientation on portrait
 *    sources.
 *
 * The fix routes all three engines through [SequentialFrameSampler], which
 * decodes the stream once, reports the real PTS rebased to clip-local time,
 * and applies the display rotation.
 *
 * Compose/MediaCodec cannot run under this project's JVM stack, so the
 * contract is asserted structurally against the real sources (the same
 * shape as `ExtractAudioWiringTest` / `AudioLaneLifecycleWiringTest`).
 * Positive token matches only: the engines' KDocs legitimately *mention*
 * the old option while explaining the fix, so a negative assertion on the
 * raw text would be a false alarm.
 */
class AiFrameAccuracyWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private fun engine(name: String): String = source(
        "app/src/main/java/com/aicreatorstudio/app/ai/engines/$name.kt"
    )

    @Test
    fun `the sampler reports real presentation timestamps rebased to clip zero`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/media/SequentialFrameSampler.kt"
        )
        assertTrue(
            "the sampler must read decoder PTS, not the requested index",
            src.contains("presentationTimeUs")
        )
        assertTrue(
            "PTS must be rebased so the first frame is 0 (clip-local)",
            src.contains("basePtsUs")
        )
        assertTrue(
            "display rotation must be applied before the frame reaches an engine",
            src.contains("postRotate")
        )
        assertTrue(
            "the emitted timestamp must be derived from the PTS",
            src.contains("(ptsUs - basePtsUs) / 1000L")
        )
    }

    @Test
    fun `background remove samples through the sequential sampler`() {
        val body = engine("MediaPipeMattingEngine")
        assertTrue(body.contains("SequentialFrameSampler.sample("))
        assertTrue(
            "matting must bound the working frame instead of converting 4K per sample",
            body.contains("maxSide = ANALYSIS_MAX_SIDE")
        )
        assertTrue(
            "cancellation must still reach the decode loop",
            body.contains("isCancelled = { ownerJob?.isActive == false }")
        )
        assertTrue(
            "the sidecar key must be the picture's PTS, not i * interval",
            body.contains("requireNotNull(sidecarDir), timeMs, alpha")
        )
    }

    @Test
    fun `auto frame samples through the sequential sampler`() {
        val body = engine("SaliencyTrackerEngine")
        assertTrue(body.contains("SequentialFrameSampler.sample("))
        assertTrue(body.contains("maxSide = ANALYSIS_MAX_SIDE"))
        assertTrue(
            "the pose timestamp must be the sampled frame's own time",
            body.contains("consumeFrame(frame.bitmap, frame.timeMs)")
        )
    }

    @Test
    fun `ocr samples through the sequential sampler`() {
        val body = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/ocr/TesseractOcrEngine.kt"
        )
        assertTrue(body.contains("SequentialFrameSampler.sample("))
        assertTrue(
            "OCR must cap the analysis frame so 4K does not build an 8K copy",
            body.contains("maxSide = OCR_ANALYSIS_MAX_SIDE")
        )
        assertTrue(
            "an OCR result must carry the sampled frame's timestamp",
            body.contains("analyzed.copy(timestampMs = timeMs)")
        )
    }
}
