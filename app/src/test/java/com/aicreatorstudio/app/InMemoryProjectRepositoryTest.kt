package com.aicreatorstudio.app

import com.aicreatorstudio.app.db.EditorStateJson
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.ClipLookSettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.HighlightRange
import com.aicreatorstudio.app.model.MattingBackgroundMode
import com.aicreatorstudio.app.model.MattingSettings
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedPoint
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoEffect
import com.aicreatorstudio.app.model.VoiceCleanSettings
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.video.InMemoryProjectRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryProjectRepositoryTest {

    private fun clip(id: String = "clip-1") = VideoClip(
        id = id,
        uri = "content://sample/$id",
        fileName = "$id.mp4",
        durationMs = 10_000L,
        width = 1280,
        height = 720,
        fileSizeBytes = 500_000L,
        importedAtEpochMillis = 0L
    )

    @Test
    fun `creating a project from a clip stores exactly that clip`() = runTest {
        val repository = InMemoryProjectRepository()

        val project = repository.createProjectFromClip(clip(), name = "My Project")

        assertEquals("My Project", project.name)
        assertEquals(1, project.clips.size)
        assertEquals(listOf(project), repository.projects.value)
    }

    @Test
    fun `new projects are inserted at the front of the list`() = runTest {
        val repository = InMemoryProjectRepository()

        val first = repository.createProjectFromClip(clip("a"), name = "First")
        val second = repository.createProjectFromClip(clip("b"), name = "Second")

        assertEquals(listOf(second, first), repository.projects.value)
    }

    @Test
    fun `adding a clip appends it to the existing project`() = runTest {
        val repository = InMemoryProjectRepository()
        val project = repository.createProjectFromClip(clip("a"), name = "Project")

        repository.addClipToProject(project.id, clip("b"))

        val updated = repository.getProject(project.id)
        assertTrue(updated != null && updated.clips.size == 2)
    }

    /**
     * 2026-10-20 — the in-memory `saveAiTools` used to copy only five
     * of the eleven fields in `ClipAiTools`: `motionTrack`,
     * `ocrResult`, `speedCurve`, the look block and `voiceEffect` were
     * silently dropped, even though the KDoc claimed "both paths keep
     * the in-memory VideoProject consistent".
     */
    @Test
    fun `saving ai tools carries every field onto the clip`() = runTest {
        val repository = InMemoryProjectRepository()
        val project = repository.createProjectFromClip(clip("a"), name = "Project")

        val tools = mapOf(
            "a" to EditorStateJson.ClipAiTools(
                matting = MattingSettings(
                    enabled = true,
                    threshold = 0.4f,
                    backgroundMode = MattingBackgroundMode.IMAGE
                ),
                autoFrame = AutoFrameSettings(enabled = true, targetAspectWidth = 9f),
                cropKeyframes = listOf(
                    CropKeyframe(timeMs = 120L, centerX = 0.3f, centerY = 0.4f)
                ),
                motionTrack = MotionTrackSettings(enabled = true, overlayId = "ov-1"),
                voiceClean = VoiceCleanSettings(enabled = true, strength = 0.8f),
                ocrResult = "encoded-ocr",
                speedCurve = SpeedCurve(points = listOf(SpeedPoint(0f, 1f))),
                look = ClipLookSettings(
                    videoEffect = VideoEffect.NOIR,
                    opacity = 0.5f,
                    fillMode = FillMode.MANUAL
                ),
                highlightRanges = listOf(HighlightRange(startMs = 10L, endMs = 20L)),
                voiceEffect = VoiceEffect.CHIPMUNK
            )
        )

        repository.saveAiTools(project.id, tools)

        val saved = repository.getProject(project.id)?.clips?.firstOrNull()
        assertTrue("the clip must still be there", saved != null)
        assertTrue("matting must survive", saved!!.matting.enabled)
        assertEquals("the background mode must survive", MattingBackgroundMode.IMAGE, saved.matting.backgroundMode)
        assertTrue("auto frame must survive", saved.autoFrame.enabled)
        assertEquals("the trajectory must survive", 1, saved.cropKeyframes.size)
        assertTrue("motion tracking must survive", saved.motionTrack.enabled)
        assertEquals("the overlay id must survive", "ov-1", saved.motionTrack.overlayId)
        assertTrue("voice clean must survive", saved.voiceClean.enabled)
        assertEquals("the OCR result must survive", "encoded-ocr", saved.ocrResult)
        assertTrue("the speed curve must survive", saved.speedCurve != null)
        assertEquals("the look block must survive", VideoEffect.NOIR, saved.videoEffect)
        assertEquals("the fill mode must survive", FillMode.MANUAL, saved.fillMode)
        assertEquals("opacity must survive", 0.5f, saved.opacity, 1e-6f)
        assertEquals("the highlight ranges must survive", 1, saved.highlightRanges.size)
        assertEquals("the voice effect must survive", VoiceEffect.CHIPMUNK, saved.voiceEffect)
    }
}
