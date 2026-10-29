package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [resolveSubtitleSourceWindow] — the pure source-resolution
 * rules behind subtitle generation (2026-10-10 SUBTITLE ROUTING FIX).
 *
 * Covers the six routing behaviours the fix had to guarantee:
 *  - a loaded project generates WITHOUT a manual timeline clip tap
 *    (first-clip fallback);
 *  - a selected audio object / video clip wins over the fallback;
 *  - an empty project yields null plus a REAL Persian no-source message
 *    (the previous literal was corrupted into `??? ????` mojibake).
 */
class SubtitleSourceWindowTest {

    private fun clip(
        id: String,
        durationMs: Long = 10_000L,
        isAudioOnly: Boolean = false,
        uri: String = "file:///$id.mp4",
        trimStartMs: Long = 0L,
        trimEndMs: Long = -1L,
        timelineStartMs: Long = 0L,
        speed: Float = 1f
    ) = VideoClip(
        id = id,
        uri = uri,
        fileName = "$id.mp4",
        durationMs = durationMs,
        width = 1080,
        height = 1920,
        fileSizeBytes = 0L,
        importedAtEpochMillis = 0L,
        timelineStartMs = timelineStartMs,
        isAudioOnly = isAudioOnly,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        speed = speed
    )

    private fun audio(
        id: String,
        sourceUri: String = "file:///$id.mp3",
        startTimeMs: Long = 0L,
        durationMs: Long = 5_000L,
        clipOffsetMs: Long = 0L,
        sourceStartMs: Long = 0L,
        sourceEndMs: Long = -1L
    ) = AudioClip(
        id = id,
        sourceUri = sourceUri,
        fileName = "$id.mp3",
        startTimeMs = startTimeMs,
        durationMs = durationMs,
        clipOffsetMs = clipOffsetMs,
        sourceStartMs = sourceStartMs,
        sourceEndMs = sourceEndMs
    )

    private fun state(
        clips: List<VideoClip> = emptyList(),
        audioClips: List<AudioClip> = emptyList(),
        selectedClipId: String? = null,
        selectedAudioClipId: String? = null,
        selectedTextId: String? = null
    ) = EditorUiState(
        isLoading = false,
        project = VideoProject(
            id = "p1",
            name = "test",
            createdAtEpochMillis = 0L,
            clips = clips,
            audioClips = audioClips
        ),
        selectedClipId = selectedClipId,
        selectedAudioClipId = selectedAudioClipId,
        selectedTextId = selectedTextId
    )

    // ---- empty project → null + Persian error ----

    @Test
    fun `an empty project resolves to null`() {
        assertNull(resolveSubtitleSourceWindow(state()))
    }

    @Test
    fun `the no-source message is real Persian text, not mojibake`() {
        // Regression guard: the old ViewModel literal was corrupted into
        // "??? ???? ????? ..." so users saw question marks instead of an
        // explanation.
        val message = SUBTITLE_NO_SOURCE_MESSAGE_FA
        assertTrue("message must not contain '?': $message", '?' !in message)
        assertTrue("message must contain Persian letters: $message", message.any { it in 'آ'..'ی' })
    }

    // ---- no-selection fallback (loaded project, no manual clip tap) ----

    @Test
    fun `with no selection the first non-audio-only clip is used`() {
        // The "works without a manual timeline clip tap" contract: a freshly
        // loaded project has nothing selected, and generation must still
        // resolve the first video clip.
        val window = resolveSubtitleSourceWindow(
            state(clips = listOf(clip("a", uri = "file:///a.mp4")))
        )
        assertNotNull(window)
        assertEquals("file:///a.mp4", window!!.sourceUriString)
        assertEquals(0L, window.anchorMs)
        assertEquals(0L, window.windowStartMs)
        assertEquals(10_000L, window.windowEndMs)
        assertEquals(1f, window.speed)
    }

    @Test
    fun `an audio-only first clip is skipped in favour of the first video clip`() {
        val window = resolveSubtitleSourceWindow(
            state(
                clips = listOf(
                    clip("music", isAudioOnly = true, uri = "file:///music.mp3"),
                    clip("video", uri = "file:///video.mp4")
                )
            )
        )
        assertEquals("file:///video.mp4", window!!.sourceUriString)
    }

    @Test
    fun `an audio-only single clip is still used as a last resort`() {
        val window = resolveSubtitleSourceWindow(
            state(clips = listOf(clip("only", isAudioOnly = true, uri = "file:///only.mp3")))
        )
        assertEquals("file:///only.mp3", window!!.sourceUriString)
    }

    // ---- explicit selections win over the fallback ----

    @Test
    fun `the selected video clip wins over the first-clip fallback`() {
        val window = resolveSubtitleSourceWindow(
            state(
                clips = listOf(clip("first"), clip("picked", uri = "file:///picked.mp4")),
                selectedClipId = "picked"
            )
        )
        assertEquals("file:///picked.mp4", window!!.sourceUriString)
    }

    @Test
    fun `a selected audio object wins over the selected video clip`() {
        // resolveGenerationTarget's documented order: explicit audio first.
        val window = resolveSubtitleSourceWindow(
            state(
                clips = listOf(clip("video")),
                audioClips = listOf(audio("song", sourceUri = "file:///song.mp3")),
                selectedClipId = "video",
                selectedAudioClipId = "song"
            )
        )
        assertEquals("file:///song.mp3", window!!.sourceUriString)
        assertEquals(1f, window.speed)
    }

    // ---- window math ----

    @Test
    fun `trim window maps onto source time with the clip timeline anchor`() {
        val window = resolveSubtitleSourceWindow(
            state(
                clips = listOf(
                    clip(
                        "trimmed",
                        uri = "file:///trimmed.mp4",
                        durationMs = 8_000L,
                        trimStartMs = 2_000L,
                        trimEndMs = 6_000L,
                        timelineStartMs = 3_000L,
                        speed = 2f
                    )
                ),
                selectedClipId = "trimmed"
            )
        )
        assertEquals(3_000L, window!!.anchorMs)
        assertEquals(2_000L, window.windowStartMs)
        assertEquals(6_000L, window.windowEndMs)
        assertEquals(2f, window.speed)
    }

    @Test
    fun `an unset trim end falls back to trim start plus duration`() {
        val window = resolveSubtitleSourceWindow(
            state(
                clips = listOf(
                    clip("c", durationMs = 4_000L, trimStartMs = 1_000L, trimEndMs = 0L)
                ),
                selectedClipId = "c"
            )
        )
        assertEquals(1_000L, window!!.windowStartMs)
        assertEquals(5_000L, window.windowEndMs)
    }

    @Test
    fun `audio window uses the wider of clip offset and source start`() {
        val window = resolveSubtitleSourceWindow(
            state(
                audioClips = listOf(
                    audio(
                        "a",
                        sourceUri = "file:///a.mp3",
                        startTimeMs = 900L,
                        durationMs = 2_000L,
                        clipOffsetMs = 500L,
                        sourceStartMs = 1_200L,
                        sourceEndMs = 9_000L
                    )
                ),
                selectedAudioClipId = "a"
            )
        )
        assertEquals(900L, window!!.anchorMs)
        assertEquals(1_200L, window.windowStartMs)
        // min(clipOffset + duration = 2500, sourceEnd = 9000)
        assertEquals(2_500L, window.windowEndMs)
    }
}
