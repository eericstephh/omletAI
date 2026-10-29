package com.aicreatorstudio.app.ui.screens.editor

import com.aicreatorstudio.app.model.ToolPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-21 — the «نریشن» (voiceover) feature.
 *
 * ## Why it exists
 *
 * CapCut's voiceover is one of its signature capabilities: record a
 * narration over the video and drop it on the timeline as its own
 * audio object. This project had every *piece* of that except the
 * capture — an audio lane, audio clips with volume and fades, an
 * audio mixer, and an offline voice changer — but no recorder, and no
 * `RECORD_AUDIO` grant in the manifest.
 *
 * ## What this test pins
 *
 *  - the permission exists and is requested at the moment of the tap;
 *  - the take lands on the timeline as an audio object of its own, at
 *    the playhead, and is not a special case for the mixer;
 *  - the take is confirmed before it is placed, so a mis-fired
 *    recording does not leave a stray clip behind;
 *  - the recorder is released with the ViewModel, so it cannot keep
 *    holding the microphone;
 *  - the panel is reachable from the audio toolbar.
 */
class VoiceoverWiringTest {

    private fun repoRoot(): File {
        var dir: File = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: throw IllegalStateException("settings.gradle.kts not found")
        }
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    private val manifest =
        "app/src/main/AndroidManifest.xml"
    private val viewModel =
        "app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt"
    private val recorder =
        "app/src/main/java/com/aicreatorstudio/app/audio/VoiceoverRecorder.kt"
    private val panel =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/VoiceoverPanel.kt"
    private val editorScreen =
        "app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt"
    private val state =
        "app/src/main/java/com/aicreatorstudio/app/model/EditorUiState.kt"

    @Test
    fun `the manifest asks for the microphone`() {
        val src = source(manifest)
        assertTrue(
            "the voiceover cannot work without RECORD_AUDIO",
            src.contains("android.permission.RECORD_AUDIO")
        )
    }

    @Test
    fun `the permission is requested at the moment of the tap`() {
        val src = source(panel)
        assertTrue(
            "the panel must request the grant itself",
            src.contains("ActivityResultContracts.RequestPermission()")
        )
        assertTrue(
            "the panel must check the grant before recording",
            src.contains("Manifest.permission.RECORD_AUDIO")
        )
        assertTrue(
            "the panel must not record without the grant",
            src.contains("checkSelfPermission")
        )
    }

    @Test
    fun `the take lands on the timeline as its own audio object`() {
        val src = source(viewModel)
        assertTrue(
            "the take must become an audio-only clip",
            src.contains("isAudioOnly = true")
        )
        assertTrue(
            "the take must be categorised as a voice, not as music",
            src.contains("audioCategory = \"voice\"")
        )
        assertTrue(
            "the take must be placed at the playhead",
            src.contains("timelineStartMs = state.playbackPositionMs.coerceAtLeast(0L)")
        )
    }

    @Test
    fun `a captured take is confirmed before it is placed`() {
        val src = source(viewModel)
        assertTrue(
            "placing must require a captured take",
            src.contains("as? com.aicreatorstudio.app.model.VoiceoverState.Captured")
        )
        val stop = src.substringAfter("fun stopVoiceover()")
        val stopBody = stop.substringBefore("fun cancelVoiceover()")
        assertTrue(
            "stopping must NOT place the take on the timeline",
            !stopBody.contains("persistClips()")
        )
    }

    @Test
    fun `the recorder is released with the ViewModel`() {
        val src = source(viewModel)
        val cleared = src.substringAfter("override fun onCleared()")
            .substringBefore("super.onCleared()")
        assertTrue(
            "a live MediaRecorder holds the microphone — it must be released",
            cleared.contains("voiceoverRecorder.cancel()")
        )
    }

    @Test
    fun `the recorder writes into the app's own storage`() {
        val src = source(recorder)
        assertTrue(
            "the take must live in the app's own files dir",
            src.contains("context.filesDir")
        )
        assertTrue(
            "the take must be a normal media file the mixer can read",
            src.contains("MediaRecorder.OutputFormat.MPEG_4")
        )
        assertTrue(
            "the take must be AAC",
            src.contains("MediaRecorder.AudioEncoder.AAC")
        )
    }

    @Test
    fun `the panel is reachable from the audio toolbar`() {
        val chips = ContextualToolbarSpec.chipsFor(
            com.aicreatorstudio.app.model.EditorContext.AUDIO_SELECTED
        )
        val chip = chips.single { it.id == "voiceover" } as ContextChip.Panel
        assertEquals(ToolPanel.VOICEOVER, chip.toolPanel)
        assertTrue("the label must not be blank", chip.label.isNotBlank())
        assertTrue(
            "the label must fit a 60dp tile",
            chip.label.length <= 8
        )
    }

    @Test
    fun `the state carries the recorder's state`() {
        val src = source(state)
        assertTrue(
            "the panel must be able to read the recorder's state",
            src.contains("val voiceoverState: VoiceoverState = VoiceoverState.Idle")
        )
        assertTrue(
            "the panel enum must have the voiceover entry",
            src.contains("VOICEOVER")
        )
    }

    @Test
    fun `the actions travel through a composition local`() {
        // The editor screen's sub-panel call sits inside a lambda that is
        // already at the JVM's 64 KB method-size limit: adding one
        // argument there made the whole screen fail to compile with
        // `MethodTooLargeException`. A composition local costs that
        // lambda nothing.
        val src = source(editorScreen)
        assertTrue(
            "the screen must provide the actions",
            src.contains("LocalVoiceoverActions provides")
        )
        assertTrue(
            "the panel must read the actions from the local",
            src.contains("LocalVoiceoverActions.current")
        )
        assertTrue(
            "the sub-panel call must NOT take a voiceover parameter — " +
                "that is what overflowed the method",
            !src.contains("voiceoverActions: VoiceoverPanelActions")
        )
    }
}
