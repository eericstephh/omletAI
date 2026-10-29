package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-13 — Extract Audio from Video (استخراج صدا از ویدیو).
 *
 * Pure-JVM coverage of [AudioExtractionPlanner] — the decisions the
 * `MediaExtractor`/`MediaMuxer` service consults. The codec plumbing
 * itself is Android-only and is guarded structurally by
 * `ExtractAudioWiringTest`; what is tested here are the branching rules
 * that decide whether an extraction succeeds, where it lands, and what
 * the user is told when it fails.
 */
class AudioExtractionTest {

    // ---- track choice ----

    @Test
    fun `finds the first audio track`() {
        val mimes = listOf("video/avc", "audio/mp4a-latm", "video/avc")
        assertEquals(1, AudioExtractionPlanner.firstAudioTrackIndex(mimes))
    }

    @Test
    fun `picks the FIRST audio track when a container carries several`() {
        // The clip's audio must be deterministic even on a file with
        // commentary / alternate-language tracks.
        val mimes = listOf("video/avc", "audio/opus", "audio/mp4a-latm")
        assertEquals(
            1,
            AudioExtractionPlanner.firstAudioTrackIndex(mimes)
        )
    }

    @Test
    fun `video with no audio track is reported as -1`() {
        val mimes = listOf("video/avc", "video/hevc", "text/plain")
        assertEquals(-1, AudioExtractionPlanner.firstAudioTrackIndex(mimes))
    }

    @Test
    fun `a null MIME never counts as an audio track`() {
        // A video track whose format lost its MIME must not be fed to the
        // audio muxer — that produces a silent, unplayable file.
        val mimes = listOf<Any?>(null, "video/avc")
        @Suppress("UNCHECKED_CAST")
        assertEquals(-1, AudioExtractionPlanner.firstAudioTrackIndex(mimes as List<String?>))
    }

    @Test
    fun `empty container is reported as -1`() {
        assertEquals(-1, AudioExtractionPlanner.firstAudioTrackIndex(emptyList()))
    }

    // ---- copy vs transcode ----

    @Test
    fun `AAC in MP4 takes the stream-copy fast path`() {
        assertTrue(AudioExtractionPlanner.isStreamCopy("audio/mp4a-latm"))
        assertTrue(AudioExtractionPlanner.isStreamCopy("audio/aac"))
    }

    @Test
    fun `non-AAC codecs take the decode-encode fallback`() {
        listOf("audio/opus", "audio/vorbis", "audio/raw", "audio/flac", "audio/3gpp")
            .forEach { mime ->
                assertFalse(
                    "$mime must not stream-copy into an MP4 muxer",
                    AudioExtractionPlanner.isStreamCopy(mime)
                )
            }
    }

    @Test
    fun `unknown and absent MIME falls back rather than copying blind`() {
        assertFalse(AudioExtractionPlanner.isStreamCopy("audio/mpeg"))
        assertFalse(AudioExtractionPlanner.isStreamCopy(null))
    }

    // ---- output naming ----

    @Test
    fun `output file name carries the timestamp and m4a extension`() {
        assertEquals(
            "extracted_1728800000000.m4a",
            AudioExtractionPlanner.outputFileName(1728800000000L)
        )
    }

    @Test
    fun `two extractions never collide on a file name`() {
        assertTrue(
            AudioExtractionPlanner.outputFileName(1L) !=
                AudioExtractionPlanner.outputFileName(2L)
        )
    }

    // ---- timeline placement ----

    @Test
    fun `first audio clip starts at zero`() {
        // A project with no audio must not receive a track that begins
        // mid-video with silence before it.
        assertEquals(0L, AudioExtractionPlanner.resolveStartMs(playheadMs = 7_500L, hasExistingAudio = false))
    }

    @Test
    fun `later audio clips start at the playhead`() {
        assertEquals(
            7_500L,
            AudioExtractionPlanner.resolveStartMs(playheadMs = 7_500L, hasExistingAudio = true)
        )
    }

    @Test
    fun `negative playhead coerces to zero`() {
        // Some devices report -1 before the first frame.
        assertEquals(
            0L,
            AudioExtractionPlanner.resolveStartMs(playheadMs = -1L, hasExistingAudio = true)
        )
    }

    // ---- failure vocabulary ----

    @Test
    fun `every user-visible failure has a Persian message`() {
        // CANCELLED is never shown — the user initiated it.
        AudioExtractionFailure.entries
            .filter { it != AudioExtractionFailure.CANCELLED }
            .forEach { failure ->
                assertTrue(
                    "$failure must have a non-empty Persian message",
                    failure.messageFa.isNotBlank()
                )
            }
        assertTrue(AudioExtractionFailure.CANCELLED.messageFa.isEmpty())
    }

    @Test
    fun `the no-audio-track failure names the reason`() {
        assertTrue(AudioExtractionFailure.NO_AUDIO_TRACK.messageFa.contains("ترک صوتی"))
    }

    // ---- fallback encoder sizing ----

    @Test
    fun `fallback bitrate scales with channels and stays bounded`() {
        assertEquals(64_000, AudioExtractionPlanner.encodeBitrate(48_000, 1))
        assertEquals(128_000, AudioExtractionPlanner.encodeBitrate(48_000, 2))
        // Below 44.1 kHz the per-channel rate drops a tier.
        assertEquals(48_000, AudioExtractionPlanner.encodeBitrate(8_000, 1))
        assertEquals(96_000, AudioExtractionPlanner.encodeBitrate(22_050, 2))
        // Channels clamp to 1..2: a >2-channel source is re-encoded as
        // stereo rather than at an unbounded bitrate.
        assertEquals(128_000, AudioExtractionPlanner.encodeBitrate(48_000, 4))
        // A zero channel count (defensive) falls back to the mono rate.
        assertEquals(64_000, AudioExtractionPlanner.encodeBitrate(48_000, 0))
    }
}
