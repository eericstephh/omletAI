package com.aicreatorstudio.app.ui.screens.editor

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو) wiring guards.
 *
 * Compose UI cannot be rendered under this project's JVM test stack, so
 * the UI half of the feature is asserted structurally against the real
 * source — the same shape as `SubtitleEditingWiringTest` /
 * `ProgressBarWiringTest`. What is behaviourally covered lives in
 * `AudioExtractionTest` (the pure planner); this class proves the
 * pieces are connected: picker launcher → ViewModel → extractor,
 * extractor → filesDir/audio clip, and every user-visible surface
 * (toolbar chip, panel row, banner, spinner) exists.
 *
 * The failure it guards against is the quiet one: a refactor that leaves
 * the pure planner fully tested while the UI stops calling it, so the
 * feature silently disappears with a green suite.
 */
class ExtractAudioWiringTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun read(relativePath: String): String {
        val f = File(repoRoot(), relativePath)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private fun viewModelSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    )

    private fun serviceSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ai/media/AudioExtractionService.kt"
    )

    private fun specSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/ContextualToolbarSpec.kt"
    )

    private fun screenSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
    )

    private fun panelSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/AudioPanel.kt"
    )

    private fun uiStateSource() = read(
        "app/src/main/java/com/aicreatorstudio/app/model/EditorUiState.kt"
    )

    /** Slice from [marker] up to the next occurrence of [terminator]. */
    private fun slice(src: String, marker: String, terminator: String): String {
        val start = src.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val from = start + marker.length
        val end = src.indexOf(terminator, from)
        assertTrue("terminator not found after $marker: $terminator", end >= 0)
        return src.substring(from, end)
    }

    // ---- extractor service ----

    @Test
    fun `the service demuxes with MediaExtractor and MediaMuxer into an MP4`() {
        val src = serviceSource()
        assertTrue(src.contains("import android.media.MediaExtractor"))
        assertTrue(src.contains("import android.media.MediaMuxer"))
        assertTrue(
            "output container must be MPEG-4 so the .m4a is playable everywhere",
            src.contains("MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4")
        )
    }

    @Test
    fun `the service writes into filesDir audio directory`() {
        val src = serviceSource()
        assertTrue(src.contains("context.filesDir"))
        assertTrue(src.contains("AUDIO_OUTPUT_DIR"))
        assertTrue(
            "the output dir constant must be \"audio\"",
            serviceSource().contains("const val AUDIO_OUTPUT_DIR = \"audio\"")
        )
    }

    @Test
    fun `the service consults the planner for the copy-vs-transcode decision`() {
        val src = serviceSource()
        assertTrue(
            "the AAC fast path must be a planner decision, not a second implementation",
            src.contains("AudioExtractionPlanner.isStreamCopy(")
        )
        assertTrue(
            "the fallback must re-encode through MediaCodec",
            src.contains("MediaFormat.MIMETYPE_AUDIO_AAC") &&
                src.contains("createEncoderByType(")
        )
    }

    @Test
    fun `the service returns data outcomes instead of throwing`() {
        val src = serviceSource()
        assertTrue(src.contains("AudioExtractionOutcome.Success"))
        assertTrue(src.contains("AudioExtractionOutcome.Failure"))
        // Cancellation must propagate, not be swallowed as a failure.
        assertTrue(src.contains("catch (ce: CancellationException)"))
        assertTrue(src.contains("throw ce"))
    }

    @Test
    fun `the service stamps the output with the planner file name`() {
        assertTrue(
            serviceSource().contains("AudioExtractionPlanner.outputFileName(")
        )
    }

    // ---- ViewModel ----

    @Test
    fun `the ViewModel exposes extractAudioFromVideo`() {
        val src = viewModelSource()
        assertTrue(src.contains("fun extractAudioFromVideo(uri: Uri)"))
        // The extraction must run on the injected service, off the main
        // thread — not re-implemented inline in the ViewModel.
        assertTrue(
            "the ViewModel must delegate to AudioExtractionService",
            src.contains("audioExtractionService.extract(uri)")
        )
        assertTrue(
            "the extraction must run on an IO dispatcher",
            slice(src, "fun extractAudioFromVideo(uri: Uri) {", "\n    }")
                .contains("withContext(Dispatchers.IO)")
        )
        // Re-entrancy guard for the non-blocking loading state.
        assertTrue(
            "a second tap during extraction must be ignored",
            slice(src, "fun extractAudioFromVideo(uri: Uri) {", "\n    }")
                .contains("if (_uiState.value.isExtractingAudio) return")
        )
    }

    @Test
    fun `a successful extraction pushes history and creates the audio clip`() {
        val body = slice(viewModelSource(), "fun extractAudioFromVideo(uri: Uri) {", "\n    /**")
        assertTrue("the mutation must be undoable", body.contains("historyDelegate.push()"))
        assertTrue(body.contains("project.copy(clips = project.clips + clip)"))
        assertTrue(
            "the clip must be an audio-only object like every other audio clip",
            body.contains("isAudioOnly = true")
        )
        assertTrue(
            "placement must go through the planner (playhead / 0)",
            body.contains("AudioExtractionPlanner.resolveStartMs(")
        )
        assertTrue(
            "the new object must be selected in the audio context",
            body.contains("timeline.selectAudioClip(clip.id)")
        )
        assertTrue(
            "the persisted project must be written after the mutation",
            body.contains("persistClips()")
        )
    }

    @Test
    fun `a successful extraction shows the green confirmation banner`() {
        val body = slice(viewModelSource(), "fun extractAudioFromVideo(uri: Uri) {", "\n    /**")
        assertTrue(
            body.contains("EXTRACT_AUDIO_SUCCESS_MESSAGE") &&
                body.contains("editorConfirmation =")
        )
    }

    @Test
    fun `a failed extraction names its reason instead of a silent no-op`() {
        val body = slice(viewModelSource(), "fun extractAudioFromVideo(uri: Uri) {", "\n    /**")
        assertTrue(body.contains("extractAudioError = outcome.failure.messageFa"))
        val uiState = uiStateSource()
        assertTrue(uiState.contains("val isExtractingAudio: Boolean = false"))
        assertTrue(uiState.contains("val extractAudioError: String? = null"))
        assertTrue(
            "the banner must be dismissible",
            viewModelSource().contains("fun consumeExtractAudioError()")
        )
    }

    // ---- toolbar ----

    @Test
    fun `the audio toolbar offers the extract action`() {
        val src = specSource()
        assertTrue(src.contains("val ExtractAudioChip: ContextChip.Action = ContextChip.Action("))
        assertTrue(src.contains("actionId = \"extract_audio\""))
        assertTrue(src.contains("icon = Icons.Filled.AudioFile"))
        // The chip must live in the audio row.
        assertTrue(slice(src, "val AudioChips: List<ContextChip> = listOf(", "\n    )").contains("ExtractAudioChip"))
    }

    @Test
    fun `the toolbar action is wired to the system video picker`() {
        val src = screenSource()
        // The contract's constructor is parameterless; the video filter is
        // the launch input — both must be present.
        assertTrue(src.contains("ActivityResultContracts.OpenDocument()"))
        assertTrue(src.contains("extractAudioLauncher.launch(arrayOf(\"video/*\"))"))
        assertTrue(
            "the picker result must reach the ViewModel",
            src.contains("viewModel.extractAudioFromVideo(it)")
        )
        assertTrue(
            "the toolbar action must open the picker",
            slice(src, "\"extract_audio\" ->", "\n                }").contains("extractAudioLauncher.launch(")
        )
    }

    // ---- panel ----

    @Test
    fun `the audio panel carries the extraction row and loading state`() {
        val src = panelSource()
        assertTrue(src.contains("onExtractAudio: () -> Unit"))
        assertTrue(src.contains("isExtractingAudio: Boolean = false"))
        assertTrue(src.contains("استخراج صدا از ویدیو"))
        assertTrue(src.contains("در حال استخراج صدا…"))
        assertTrue(
            "the loading state must not block the panel",
            src.contains("clickable(enabled = !isExtractingAudio")
        )
    }

    @Test
    fun `the screen forwards the extraction callbacks to the panel`() {
        val src = screenSource()
        assertTrue(src.contains("onExtractAudio = onExtractAudioFromVideo"))
        assertTrue(src.contains("isExtractingAudio = uiState.isExtractingAudio"))
    }

    @Test
    fun `a failed extraction shows a dismissible amber banner`() {
        val src = screenSource()
        assertTrue(src.contains("if (uiState.extractAudioError != null)"))
        val body = slice(src, "if (uiState.extractAudioError != null) {", "\n        // 2026-10-13 — positive confirmations")
        assertTrue(body.contains("viewModel.consumeExtractAudioError()"))
    }
}
