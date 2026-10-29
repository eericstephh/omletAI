package com.aicreatorstudio.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [AudioPairingService] (PART 14 of the brief).
 *
 * `MediaExtractor` is an Android system class, so the test uses
 * the [AudioPairingService.pairForVideoWithProbe] seam that lets
 * the JVM stand-in return whatever the test wants. The Android
 * implementation of the probe is plain (it iterates track formats
 * and looks for the audio MIME prefix) and is exercised by the
 * `AudioPairingServiceAndroidTest` instrumentation suite.
 *
 * Enforced invariants:
 *  - Source without an audio track produces NO AudioClip (no fake
 *    silent placeholder is fabricated).
 *  - Source WITH an audio track produces an AudioClip whose
 *    `startTimeMs` / `durationMs` exactly match the parent video
 *    — drift must be zero.
 *  - The pairing back-references the parent `videoClipId` via
 *    `originVideoClipId`.
 *  - Non-positive duration is treated as "no audio", even when the
 *    probe would say yes (a zero-length source cannot have an
 *    audio track to pair).
 */
class AudioPairingServiceTest {

    private val uri: String = "file:///tmp/test.mp4"

    @Test
    fun noAudioTrackYieldsNull() {
        val svc = AudioPairingService()
        val out = svc.pairForVideoWithProbe(
            videoUri = uri,
            videoClipId = "v-1",
            startTimeMs = 0L,
            durationMs = 5_000L,
            fileName = "test.mp4",
            probe = { false }
        )
        assertNull(out)
    }

    @Test
    fun audioTrackProducesPairedClip() {
        val svc = AudioPairingService()
        val out = svc.pairForVideoWithProbe(
            videoUri = uri,
            videoClipId = "v-1",
            startTimeMs = 1_500L,
            durationMs = 12_345L,
            fileName = "recording.mp4",
            probe = { true }
        )
        assertNotNull(out)
        val clip = out!!
        assertEquals(uri, clip.sourceUri)
        assertEquals(1_500L, clip.startTimeMs)
        assertEquals(12_345L, clip.durationMs)
        assertEquals(12_345L, clip.endTimeMs - clip.startTimeMs)
        assertEquals(0L, clip.clipOffsetMs)
        assertEquals(0L, clip.sourceStartMs)
        assertEquals(12_345L, clip.sourceEndMs)
        assertEquals(1f, clip.volume, 1e-6f)
        assertFalse(clip.muted)
        assertEquals("recording.mp4", clip.fileName)
        assertEquals("v-1", clip.originVideoClipId)
        assertTrue(clip.isPairedToVideo)
        assertNotNull(clip.waveformCacheKey)
        assertTrue(clip.waveformCacheKey!!.startsWith("wf-"))
    }

    @Test
    fun zeroDurationYieldsNullEvenWithAudio() {
        val svc = AudioPairingService()
        val out = svc.pairForVideoWithProbe(
            videoUri = uri,
            videoClipId = "v-2",
            startTimeMs = 0L,
            durationMs = 0L,
            fileName = "zero.mp4",
            probe = { true }
        )
        assertNull(out)
    }

    @Test
    fun negativeDurationYieldsNull() {
        val svc = AudioPairingService()
        val out = svc.pairForVideoWithProbe(
            videoUri = uri,
            videoClipId = "v-3",
            startTimeMs = 0L,
            durationMs = -1L,
            fileName = "neg.mp4",
            probe = { true }
        )
        assertNull(out)
    }

    @Test
    fun blankFileNameFallsBackToOriginalAudio() {
        val svc = AudioPairingService()
        val out = svc.pairForVideoWithProbe(
            videoUri = uri,
            videoClipId = "v-4",
            startTimeMs = 0L,
            durationMs = 1_000L,
            fileName = "  ",
            probe = { true }
        )
        assertNotNull(out)
        assertEquals("Original audio", out!!.fileName)
    }

    @Test
    fun pairingIsUniquePerCall() {
        val svc = AudioPairingService()
        val a = svc.pairForVideoWithProbe(
            videoUri = uri, videoClipId = "v-5",
            startTimeMs = 0L, durationMs = 1_000L, fileName = null,
            probe = { true }
        )
        val b = svc.pairForVideoWithProbe(
            videoUri = uri, videoClipId = "v-5",
            startTimeMs = 0L, durationMs = 1_000L, fileName = null,
            probe = { true }
        )
        assertNotNull(a)
        assertNotNull(b)
        assertFalse("each pairing must mint a fresh id", a!!.id == b!!.id)
    }

    @Test
    fun startTimeIsClampedToZero() {
        val svc = AudioPairingService()
        val out = svc.pairForVideoWithProbe(
            videoUri = uri, videoClipId = "v-6",
            startTimeMs = -500L, durationMs = 2_000L, fileName = null,
            probe = { true }
        )
        assertNotNull(out)
        assertEquals(0L, out!!.startTimeMs)
        assertEquals(2_000L, out.durationMs)
    }
}