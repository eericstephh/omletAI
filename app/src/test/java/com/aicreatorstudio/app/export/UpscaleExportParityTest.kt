package com.aicreatorstudio.app.export

import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.model.UpscalerPreset
import com.aicreatorstudio.app.model.UpscalerSettings
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VideoProject
import com.aicreatorstudio.app.ui.screens.editor.ColorGradingMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-21 — the "normal export ignores the upscaler" gap.
 *
 * ## The gap
 *
 * The Upscaler panel applied a contrast-adaptive sharpening (CAS) pass
 * to the **preview** only. The export had no field to receive the
 * settings at all, so the exported file carried none of the
 * enhancement the user could see on screen. On top of that, the
 * per-clip settings were written to neither a Room column nor the
 * AI-tools blob, so reopening the project dropped the user's choice.
 *
 * ## The fix
 *
 * 1. `RenderSegment` carries the settings, and
 *    `RenderGraphBuilder` fills them from the clip.
 * 2. The complete colour chain — B/C/S → effect → grade → CAS — is
 *    built by ONE function, `ColorGradingMath.fullPreviewExportMatrix`,
 *    which the preview and the export both call.
 * 3. The settings ride the AI-tools blob, so they survive a reopen.
 */
class UpscaleExportParityTest {

    // -----------------------------------------------------------------
    // The shared chain
    // -----------------------------------------------------------------

    @Test
    fun `no upscale strength leaves the grade matrix untouched`() {
        val grade = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f
        )
        val withUpscale = ColorGradingMath.fullPreviewExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f,
            upscaleStrength = 0f
        )
        assertEquals(grade.joinToString(), withUpscale.joinToString())
    }

    @Test
    fun `the CAS pass runs AFTER the grade, exactly as the preview did`() {
        val grade = ColorGradingMath.fullExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1.2f,
            contrast = 1.1f,
            saturation = 1f
        )
        val cas = ColorGradingMath.casMatrixArray(0.55f)
        val expected = ColorGradingMath.concatColorMatrix(cas, grade)
        val actual = ColorGradingMath.fullPreviewExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1.2f,
            contrast = 1.1f,
            saturation = 1f,
            upscaleStrength = 0.55f
        )
        assertEquals(expected.joinToString(), actual.joinToString())
    }

    @Test
    fun `an active upscale changes the matrix`() {
        val plain = ColorGradingMath.fullPreviewExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f,
            upscaleStrength = 0f
        )
        val sharpened = ColorGradingMath.fullPreviewExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f,
            upscaleStrength = 0.8f
        )
        assertFalse(
            "the CAS pass must change the matrix",
            plain.joinToString() == sharpened.joinToString()
        )
    }

    @Test
    fun `a zero-strength CAS matrix is the identity`() {
        val cas = ColorGradingMath.casMatrixArray(0f)
        assertTrue(
            "expected the identity, got ${cas.joinToString()}",
            ColorGradingMath.isIdentityMatrix(cas)
        )
    }

    @Test
    fun `a non-finite strength is treated as no enhancement`() {
        val plain = ColorGradingMath.fullPreviewExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f,
            upscaleStrength = 0f
        )
        val nan = ColorGradingMath.fullPreviewExportMatrix(
            grading = ColorGrading.Identity,
            effect = VideoEffect.NONE,
            brightness = 1f,
            contrast = 1f,
            saturation = 1f,
            upscaleStrength = Float.NaN
        )
        assertEquals(plain.joinToString(), nan.joinToString())
    }

    // -----------------------------------------------------------------
    // The render graph carries the settings
    // -----------------------------------------------------------------

    @Test
    fun `the render segment carries the clip's upscale settings`() {
        val settings = UpscalerSettings(
            preset = UpscalerPreset.ULTRA_4K,
            strength = 0.7f
        )
        val clip = sampleClip(id = "c1", upscale = settings)
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        assertTrue(result is RenderGraphResult.Success)
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(settings, segment.upscale)
    }

    @Test
    fun `a clip without upscale settings carries the identity`() {
        val clip = sampleClip(id = "c1")
        val result = RenderGraphBuilder.build(sampleProject(clips = listOf(clip)))
        val segment = (result as RenderGraphResult.Success).segments.first()
        assertEquals(UpscalerSettings.Identity, segment.upscale)
    }

    // -----------------------------------------------------------------
    // The settings survive a reopen
    // -----------------------------------------------------------------

    @Test
    fun `the upscale settings ride the AI-tools blob`() {
        // The blob is what the repository writes and reads back, so a
        // setting that is not in it is a setting the user loses on
        // reopen — which is exactly what used to happen.
        val source = readSource(
            "app/src/main/java/com/aicreatorstudio/app/db/EditorStateJson.kt"
        )
        assertTrue(
            "the blob must write the upscale preset",
            source.contains("put(\"preset\", look.upscale.preset.name)")
        )
        assertTrue(
            "the blob must write the upscale strength",
            source.contains("put(\"strength\", look.upscale.strength.toDouble())")
        )
        assertTrue(
            "the blob must read the upscale settings back",
            source.contains("val upscale = if (up == null)")
        )
    }

    @Test
    fun `the clip's upscale settings are written into the blob`() {
        val source = readSource(
            "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
        )
        assertTrue(
            "the clip's upscale settings must reach the persisted blob",
            source.contains("upscale = clip.upscale")
        )
    }

    @Test
    fun `the repository restores the upscale settings onto the clip`() {
        val source = readSource(
            "app/src/main/java/com/aicreatorstudio/app/db/RoomProjectRepository.kt"
        )
        assertTrue(
            "the clip must get its upscale settings back on load",
            source.contains("upscale = aiTools?.look?.upscale")
        )
    }

    // -----------------------------------------------------------------
    // The preview and the export call the SAME function
    // -----------------------------------------------------------------

    @Test
    fun `the preview and the export share one colour chain`() {
        val preview = readSource(
            "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
        )
        val export = readSource(
            "app/src/main/java/com/aicreatorstudio/app/export/VideoExportEngine.kt"
        )
        assertTrue(
            "the preview must use the shared chain",
            preview.contains("fullPreviewExportMatrix(")
        )
        assertTrue(
            "the export must use the shared chain",
            export.contains("fullPreviewExportMatrix(")
        )
    }

    @Test
    fun `the export resolves the global upscale default`() {
        val source = readSource(
            "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
        )
        assertTrue(
            "the export must fall back to the global upscaler default",
            source.contains("globalUpscale")
        )
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun readSource(relative: String): String {
        var dir: File = File(".").absoluteFile
        val root = generateSequence(dir) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: throw IllegalStateException("settings.gradle.kts not found")
        val f = File(root, relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private fun sampleClip(
        id: String,
        upscale: UpscalerSettings = UpscalerSettings.Identity
    ): VideoClip = VideoClip(
        id = id,
        uri = "content://media/$id",
        fileName = id,
        durationMs = 1_000,
        width = 1920,
        height = 1080,
        fileSizeBytes = 1_000_000,
        importedAtEpochMillis = 0L,
        timelineStartMs = 0,
        upscale = upscale
    )

    private fun sampleProject(clips: List<VideoClip>): VideoProject = VideoProject(
        id = "p1",
        name = "Test",
        clips = clips,
        createdAtEpochMillis = 0L,
        canvasPreset = com.aicreatorstudio.app.model.CanvasPreset.FREE,
        canvasBackground = com.aicreatorstudio.app.model.CanvasBackground.Black
    )
}
