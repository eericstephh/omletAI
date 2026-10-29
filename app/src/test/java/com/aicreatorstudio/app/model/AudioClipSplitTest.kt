package com.aicreatorstudio.app.model

import com.aicreatorstudio.app.util.BeatDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [AudioClipSplit] — the arithmetic behind «برش» on an audio object.
 *
 * Why this matters: the split button used to reshape only `project.clips`, so
 * a cut left the "Video Audio" lane as one unbroken tile. The moment that lane
 * is cut too, `clipOffsetMs` becomes the field that decides whether the right
 * half plays the *rest* of the file or silently replays the beginning — a bug
 * you cannot hear until the second half of a long take.
 */
class AudioClipSplitTest {

    private fun clip(
        start: Long = 0L,
        duration: Long = 10_000L,
        offset: Long = 0L,
        fadeIn: Long = 0L,
        fadeOut: Long = 0L,
        origin: String? = "video-1"
    ) = AudioClip(
        id = "audio-1",
        sourceUri = "content://media/1",
        fileName = "take.m4a",
        startTimeMs = start,
        durationMs = duration,
        clipOffsetMs = offset,
        fadeInMs = fadeIn,
        fadeOutMs = fadeOut,
        originVideoClipId = origin
    )

    @Test
    fun `split halves the span and keeps the two pieces contiguous`() {
        val (left, right) = AudioClipSplit.split(
            clip = clip(start = 2_000L, duration = 10_000L),
            atTimelineMs = 6_000L,
            rightHalfId = "audio-2"
        )!!

        assertEquals("audio-1", left.id)
        assertEquals(2_000L, left.startTimeMs)
        assertEquals(4_000L, left.durationMs)

        assertEquals("audio-2", right.id)
        assertEquals(6_000L, right.startTimeMs)
        assertEquals(6_000L, right.durationMs)

        // The join is exact: the right half begins where the left ends.
        assertEquals(left.endTimeMs, right.startTimeMs)
        assertEquals(12_000L, right.endTimeMs)
    }

    @Test
    fun `the right half advances the source read offset`() {
        // Cutting 4 s into a clip that already starts 3 s into the file means
        // the right half must start reading at 7 s — not at 3 s, and never at
        // 0.
        val (left, right) = AudioClipSplit.split(
            clip = clip(start = 0L, duration = 10_000L, offset = 3_000L),
            atTimelineMs = 4_000L
        )!!

        assertEquals(3_000L, left.clipOffsetMs)
        assertEquals(7_000L, right.clipOffsetMs)
        // Total source coverage is unchanged by a cut.
        assertEquals(
            left.durationMs + right.durationMs,
            clip(duration = 10_000L).durationMs
        )
    }

    @Test
    fun `a cut never introduces a fade at the join`() {
        val (left, right) = AudioClipSplit.split(
            clip = clip(fadeIn = 500L, fadeOut = 800L),
            atTimelineMs = 4_000L
        )!!

        // Left keeps the user's fade-in, right keeps the fade-out, and the
        // cut point itself stays at full level on both sides.
        assertEquals(500L, left.fadeInMs)
        assertEquals(0L, left.fadeOutMs)
        assertEquals(0L, right.fadeInMs)
        assertEquals(800L, right.fadeOutMs)
    }

    @Test
    fun `the right half is retargeted at the second video clip`() {
        val (left, right) = AudioClipSplit.split(
            clip = clip(origin = "video-1"),
            atTimelineMs = 5_000L,
            rightHalfId = "audio-2",
            rightOriginVideoClipId = "video-2"
        )!!

        // Two objects pointing at the same video would make
        // `seedVideoAudioClipsIfNeeded` seed a duplicate on the next load.
        assertEquals("video-1", left.originVideoClipId)
        assertEquals("video-2", right.originVideoClipId)
    }

    @Test
    fun `a standalone object stays standalone on both halves`() {
        val (left, right) = AudioClipSplit.split(
            clip = clip(origin = null),
            atTimelineMs = 5_000L
        )!!

        assertNull(left.originVideoClipId)
        assertNull(right.originVideoClipId)
    }

    @Test
    fun `volume mute and voice effect are carried to both halves`() {
        val source = clip().copy(volume = 0.4f, muted = true, voiceEffect = VoiceEffect.NONE)
        val (left, right) = AudioClipSplit.split(source, atTimelineMs = 5_000L)!!

        assertEquals(0.4f, left.volume, 0.0001f)
        assertEquals(0.4f, right.volume, 0.0001f)
        assertTrue(left.muted)
        assertTrue(right.muted)
        assertEquals(source.voiceEffect, right.voiceEffect)
    }

    @Test
    fun `a cut on either edge is refused`() {
        val source = clip(start = 1_000L, duration = 5_000L)

        assertNull("start edge", AudioClipSplit.split(source, atTimelineMs = 1_000L))
        assertNull("end edge", AudioClipSplit.split(source, atTimelineMs = 6_000L))
        assertNull("before the clip", AudioClipSplit.split(source, atTimelineMs = 0L))
        assertNull("after the clip", AudioClipSplit.split(source, atTimelineMs = 9_000L))
    }

    @Test
    fun `the smallest possible cut still produces two non-empty halves`() {
        val result = AudioClipSplit.split(
            clip = clip(start = 0L, duration = 2L),
            atTimelineMs = 1L
        )
        assertNotNull(result)
        assertEquals(1L, result!!.first.durationMs)
        assertEquals(1L, result.second.durationMs)
    }

    @Test
    fun `beat data survives on both halves with their own read offset`() {
        // Markers are stored in SOURCE time and the renderer filters them by
        // `clipOffsetMs..clipOffsetMs + durationMs`, so keeping the blob on
        // both halves is what makes each half paint only its own ticks —
        // without re-running beat detection after every cut.
        val beats = BeatDetector.AudioBeatData(markers = emptyList())
        val source = clip(offset = 1_000L, duration = 8_000L).copy(beatData = beats)
        val (left, right) = AudioClipSplit.split(source, atTimelineMs = 5_000L)!!

        assertEquals(beats, left.beatData)
        assertEquals(beats, right.beatData)
        assertEquals(1_000L, left.clipOffsetMs)
        // 1 s already skipped at import + the 5 s that belong to the left
        // half = 6 s into the file.
        assertEquals(6_000L, right.clipOffsetMs)
    }
}
