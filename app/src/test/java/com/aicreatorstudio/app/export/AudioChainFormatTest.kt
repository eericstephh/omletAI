package com.aicreatorstudio.app.export

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the ordering rules in the export audio chain that a device
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
 *   caused by UnhandledAudioFormatException: Unhandled input format: ...
 * ```
 *
 * `encoding=4` is `ENCODING_PCM_FLOAT`. The stack ended in
 * `SonicAudioProcessor.configure`, reached through
 * `SpeedChangingAudioProcessor.onConfigure` — a Sonic stage handed a
 * float buffer. At 1× no Sonic stage exists, so nothing rejected it;
 * adding the speed stage is what exposed the mismatch.
 *
 * The first fix put `ToInt16PcmAudioProcessor` at the **head** of the
 * chain and did not help, because `VolumeScalingAudioProcessor` sat
 * behind it and re-declared `ENCODING_PCM_FLOAT` from its own
 * `onConfigure` while reading the buffer as floats. The chain converted
 * to 16-bit and then un-converted itself, and the Sonic stage behind
 * that got float again.
 *
 * ## The two rules that actually hold
 *
 * 1. The float-only processors (`VolumeScalingAudioProcessor`,
 *    `VoiceEffectAudioProcessor`, `FadeAudioProcessor`) must run on the
 *    float side, never between a 16-bit conversion and a Sonic stage.
 * 2. The chain must **end** in 16-bit, always. Media3 takes the mixer
 *    format from the *first* registered input's output and then
 *    requires every later input to match it, and it appends its own
 *    `SonicAudioProcessor` / `ChannelMixingAudioProcessor` — both
 *    16-bit-only — to every input after the first. A chain ending in
 *    float would poison the mixer and break input 1.
 *
 * ## Why a source-shape test
 *
 * `buildAudioProcessors` is private and builds private processor
 * classes, so it cannot be called from a JVM test. What matters is not
 * the list contents but the **order**, and order is visible in the
 * source. A refactor that moves a float stage past the converter, or
 * that drops the trailing converter, is exactly the regression this
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

    private fun at(body: String, marker: String): Int {
        val i = body.indexOf(marker)
        assertTrue("$marker not found in the chain", i >= 0)
        return i
    }

    @Test
    fun `the chain ends with a 16-bit converter, unconditionally`() {
        val body = chainBody()
        val tail = body.lastIndexOf("processors += ToInt16PcmAudioProcessor()")
        assertTrue(
            "the chain must end with an unconditional 16-bit converter: Media3 takes the " +
                "mixer format from input 0's output and appends a 16-bit-only Sonic stage " +
                "to every later input, so a chain ending in float breaks the composition",
            tail >= 0
        )
        assertTrue(
            "the trailing converter must come after the fade stage, not inside the " +
                "hasSonic branch",
            tail > at(body, "FadeAudioProcessor(")
        )
        assertTrue(
            "the converter must not be gated on a needsSonic/if(hasSonic) condition — the " +
                "old head-of-chain shape is what failed on 2026-10-31",
            !body.contains("if (needsSonic)")
        )
    }

    @Test
    fun `float-only stages run before the converter and never between it and a Sonic`() {
        val body = chainBody()
        val sonicBranch = at(body, "if (hasSonic) {")
        val converter = at(body, "ToInt16PcmAudioProcessor()")

        // The float-only stages are all ahead of the conversion.
        for (floatStage in listOf(
            "VolumeScalingAudioProcessor(",
            "VoiceEffectAudioProcessor.build("
        )) {
            assertTrue(
                "$floatStage must run before the 16-bit converter — it reads float PCM " +
                    "and declares float, so feeding it 16-bit both corrupts the audio " +
                    "and re-declares the wrong format to the next stage",
                at(body, floatStage) < sonicBranch
            )
        }

        // And the conversion is ahead of every Sonic stage.
        assertTrue(
            "the converter must precede the pitch SonicAudioProcessor",
            converter < at(body, "SonicAudioProcessor()")
        )
        assertTrue(
            "the converter must precede the speed stage",
            converter < at(body, "speedStage?.let")
        )
        assertTrue(
            "the fade must run after the speed stage so its clip-local clock is in " +
                "output time, matching RenderSegment.outputDurationMs",
            at(body, "speedStage?.let") < at(body, "FadeAudioProcessor(")
        )
    }

    @Test
    fun `a float converter restores float for the fade stage`() {
        val body = chainBody()
        val back = at(body, "ToFloatPcmAudioProcessor()")
        assertTrue(
            "the fade is float-only, so something has to convert the Sonic stages' 16-bit " +
                "output back to float before it runs",
            back > at(body, "speedStage?.let") && back < at(body, "FadeAudioProcessor(")
        )
    }

    @Test
    fun `the speed provider is computed once so the gate and the stage cannot disagree`() {
        val body = chainBody()
        val declarations = Regex("val curveProvider =").findAll(body).count()
        assertTrue(
            "curveProvider must be declared exactly once — the hasSonic gate and the " +
                "SpeedChangingAudioProcessor stage must agree, and two evaluations could drift",
            declarations == 1
        )
        assertTrue(
            "hasSonic must include the constant-speed branch, not only the curve and " +
                "pitch ones — the 2× export that failed had no curve",
            body.contains("segment.speed != 1f && segment.speed > 0f")
        )
    }

    @Test
    fun `the hand-written float converter mirrors ToInt16's contract`() {
        val src = engineSource()
        val start = src.indexOf("private class ToFloatPcmAudioProcessor")
        assertTrue("ToFloatPcmAudioProcessor not found", start >= 0)
        val end = src.indexOf("\nprivate class ", start + 1)
        val body = src.substring(start, if (end > start) end else src.length)

        assertTrue(
            "it must be a no-op for float input, so it can be inserted without " +
                "disturbing a chain that never left float",
            body.contains("C.ENCODING_PCM_FLOAT -> AudioProcessor.AudioFormat.NOT_SET")
        )
        assertTrue(
            "it must convert 16-bit to float",
            body.contains("C.ENCODING_PCM_16BIT -> AudioProcessor.AudioFormat(")
        )
        assertTrue(
            "it must reject anything else rather than silently misreading it",
            body.contains("UnhandledAudioFormatException(inputAudioFormat)")
        )
        assertTrue(
            "16-bit samples are 2 bytes, so the sample count is remaining/2 — a /4 here " +
                "would read the buffer as float and produce garbage",
            body.contains("remaining / 2")
        )
    }
}
