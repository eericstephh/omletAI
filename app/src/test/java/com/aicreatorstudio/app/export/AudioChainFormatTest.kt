package com.aicreatorstudio.app.export

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the one ordering rule in the export audio chain that a device
 * export proved the hard way.
 *
 * ## What happened
 *
 * On 2026-10-31 a 35.9 s clip exported fine at 1× and the **same clip
 * at 2×** failed at TRANSFORMER:
 *
 * ```
 * Audio error: Error while registering input 0,
 * audioFormat=AudioFormat[sampleRate=44100, channelCount=2, encoding=4]
 *   caused by UnhandledAudioFormatException
 * ```
 *
 * `encoding=4` is `ENCODING_PCM_FLOAT`. `SonicAudioProcessor.configure`
 * accepts only `ENCODING_PCM_16BIT` — `javap` on media3-common 1.5.0
 * shows the method comparing against the constant `2` and otherwise
 * throwing — and Media3's `AudioGraph` hands each item's chain the
 * format it mixes in, which is float, as soon as the composition holds
 * more than one audio sequence. At 1× no Sonic stage exists, so nothing
 * rejected the float buffer; adding the speed stage is what exposed it.
 *
 * `ToInt16PcmAudioProcessor` (media3-common) accepts float *and* 16-bit,
 * so it is a pass-through on the path that already worked.
 *
 * ## What this test does NOT claim
 *
 * Adding the converter did **not** clear the failure: the shipped build
 * still dies with the same `encoding=4` rejection, at the same point in
 * the export (the percentage the UI happened to be showing when it
 * failed — 5 %, 31 %, 91 % — is just polling lag, not a different
 * stage). So this file pins a property that is *necessary* (Sonic
 * cannot run on a float buffer) and deliberately does **not** assert
 * that the export now works. Whoever finishes the investigation should
 * extend it with the real invariant once it is known — the leading
 * hypothesis is a second `AudioGraphInput` registered for an
 * `EditedMediaItem` that carries no processors at all, so nothing
 * converts its float buffer before a Sonic stage somewhere downstream.
 *
 * ## Why a source-shape test
 *
 * `buildAudioProcessors` is private and builds private processor
 * classes, so it cannot be called from a JVM test. What matters is not
 * the list contents but the **order**: the converter must precede every
 * stage that assumes `ShortArray`. A refactor that moves it after
 * `SpeedChangingAudioProcessor`, or that drops the `needsSonic` guard
 * so it runs on untouched projects too, is exactly the regression this
 * file exists to catch.
 */
class AudioChainFormatTest {

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
        // The checkout is CRLF on Windows; normalise before slicing.
        return f.readText().replace("\r\n", "\n")
    }

    private fun engineSource(): String =
        source("app/src/main/java/com/aicreatorstudio/app/export/VideoExportEngine.kt")

    /** The body of `buildAudioProcessors`, up to the next top-level member. */
    private fun chainBody(): String {
        val src = engineSource()
        val start = src.indexOf("private fun buildAudioProcessors(")
        assertTrue("buildAudioProcessors not found", start >= 0)
        val end = src.indexOf("\n    private fun ", start + 1)
        assertTrue("could not find the end of buildAudioProcessors", end > start)
        return src.substring(start, end)
    }

    @Test
    fun `the chain starts with a 16-bit converter whenever a Sonic stage is present`() {
        val body = chainBody()
        assertTrue(
            "the chain must add ToInt16PcmAudioProcessor; SonicAudioProcessor throws " +
                "UnhandledAudioFormatException on the float format AudioGraph hands it",
            body.contains("ToInt16PcmAudioProcessor()")
        )
        assertTrue(
            "the converter must be gated on needsSonic so an untouched project's " +
                "exported audio stays byte-identical",
            body.contains("if (needsSonic)")
        )
        assertTrue(
            "needsSonic must include the constant-speed branch, not only the curve " +
                "and pitch ones — the 2× export that failed had no curve",
            body.contains("segment.speed != 1f && segment.speed > 0f")
        )
    }

    @Test
    fun `the converter precedes every processor that reads ShortArray`() {
        val body = chainBody()
        val converter = body.indexOf("ToInt16PcmAudioProcessor()")
        assertTrue("converter missing", converter >= 0)

        val consumers = listOf(
            "VolumeScalingAudioProcessor(",
            "VoiceEffectAudioProcessor.build(",
            "SonicAudioProcessor()",
            "SpeedChangingAudioProcessor(",
            "FadeAudioProcessor("
        )
        for (marker in consumers) {
            val at = body.indexOf(marker)
            assertTrue("$marker not found in the chain", at >= 0)
            assertTrue(
                "$marker runs before the 16-bit converter; it reads ShortArray and " +
                    "would misread the float buffer AudioGraph supplies",
                at > converter
            )
        }
    }

    @Test
    fun `the speed provider is computed once so the gate and the stage cannot disagree`() {
        val body = chainBody()
        val declarations = Regex("val curveProvider = ").findAll(body).count()
        assertTrue(
            "curveProvider must be declared exactly once — the needsSonic gate and the " +
                "SpeedChangingAudioProcessor stage must agree, and two evaluations could drift",
            declarations == 1
        )
    }
}
