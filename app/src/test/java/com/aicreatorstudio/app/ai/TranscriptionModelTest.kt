package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the in-memory data model used by the upgraded
 * Auto-Subtitle / Transcription feature.
 *
 * Covers [TranscribedWord] / [Subtitle] / [Transcription] semantics
 * — visibility / active-word lookup, empty-state, and the contract
 * that segment timing is derived from the first/last word rather
 * than invented.
 */
class TranscriptionModelTest {

    @Test
    fun `TranscribedWord isActiveAt is inclusive start exclusive end`() {
        val w = TranscribedWord(
            text = "Hello", startTimeMs = 1_000L, endTimeMs = 1_500L
        )
        // Just before, at start, middle, just before end, at end
        assertEquals(false, w.isActiveAt(999L))
        assertEquals(true,  w.isActiveAt(1_000L))
        assertEquals(true,  w.isActiveAt(1_250L))
        assertEquals(true,  w.isActiveAt(1_499L))
        assertEquals(false, w.isActiveAt(1_500L))
        assertEquals(false, w.isActiveAt(2_000L))
    }

    @Test
    fun `Subtitle isVisibleAt uses the segment window`() {
        val sub = Subtitle(
            text = "Hello world",
            startTimeMs = 1_000L,
            endTimeMs = 2_000L
        )
        assertEquals(false, sub.isVisibleAt(0L))
        assertEquals(true,  sub.isVisibleAt(1_000L))
        assertEquals(true,  sub.isVisibleAt(1_500L))
        assertEquals(false, sub.isVisibleAt(2_000L))
    }

    @Test
    fun `Subtitle activeWordAt returns the word containing the playhead`() {
        val words = listOf(
            TranscribedWord(text = "Hello",   startTimeMs = 1_000L, endTimeMs = 1_450L),
            TranscribedWord(text = "everyone",startTimeMs = 1_450L, endTimeMs = 2_000L),
            TranscribedWord(text = "welcome", startTimeMs = 2_000L, endTimeMs = 2_500L)
        )
        val sub = Subtitle(
            text = "Hello everyone welcome",
            startTimeMs = 1_000L,
            endTimeMs = 2_500L,
            words = words
        )
        // Position inside the gap between words -> no active word.
        // (The recognizer's gap is 0ms here; the boundary is on
        // `everyone`'s start, not between Hello and everyone.)
        assertEquals(words[0], sub.activeWordAt(1_200L))
        assertEquals(words[1], sub.activeWordAt(1_700L))
        assertEquals(words[2], sub.activeWordAt(2_200L))
        assertNull(sub.activeWordAt(0L))
        assertNull(sub.activeWordAt(3_000L))
    }

    @Test
    fun `Subtitle activeWordAt returns null when no words attached`() {
        val sub = Subtitle(text = "Hi", startTimeMs = 0L, endTimeMs = 1_000L)
        assertNull(sub.activeWordAt(500L))
    }

    @Test
    fun `Transcription Empty is actually empty`() {
        assertEquals(true, Transcription.Empty.isEmpty)
        assertEquals(0, Transcription.Empty.segments.size)
        assertEquals(0, Transcription.Empty.words.size)
    }

    @Test
    fun `Transcription preserves all segments and all words separately`() {
        val w1 = TranscribedWord(text = "Hello",   startTimeMs = 0L, endTimeMs = 500L)
        val w2 = TranscribedWord(text = "world",   startTimeMs = 500L, endTimeMs = 1_000L)
        val s1 = Subtitle(text = "Hello world", startTimeMs = 0L, endTimeMs = 1_000L, words = listOf(w1, w2))
        val t = Transcription(segments = listOf(s1), words = listOf(w1, w2))
        assertEquals(1, t.segments.size)
        assertEquals(2, t.words.size)
        assertEquals(false, t.isEmpty)
        // The same word object lives in both lists, so the karaoke
        // highlight can look up its id from either side without
        // remapping.
        assertEquals(s1.words[0].id, t.words[0].id)
    }
}
