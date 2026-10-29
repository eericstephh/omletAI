package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [SrtFormatter].
 *
 * These cover the spec-mandated time format (`HH:MM:SS,mmm` with a
 * comma decimal separator), the numbered cue block format, the
 * UTF-8 / Persian / Arabic pass-through, and the failure-mode
 * clamping (empty / negative / inverted timestamps).
 */
class SrtFormatterTest {

    @Test
    fun `timestamp uses HH MM SS comma mmm format regardless of locale`() {
        assertEquals("00:00:01,000", SrtFormatter.formatTimestamp(1_000L))
        assertEquals("00:01:00,500", SrtFormatter.formatTimestamp(60_500L))
        assertEquals("01:00:00,000", SrtFormatter.formatTimestamp(3_600_000L))
        assertEquals("12:34:56,789", SrtFormatter.formatTimestamp(45_296_789L))
    }

    @Test
    fun `timestamp pads single-digit hours minutes seconds with zero`() {
        assertEquals("00:00:00,001", SrtFormatter.formatTimestamp(1L))
        assertEquals("00:00:09,099", SrtFormatter.formatTimestamp(9_099L))
    }

    @Test
    fun `negative timestamps clamp to zero`() {
        assertEquals("00:00:00,000", SrtFormatter.formatTimestamp(-50L))
    }

    @Test
    fun `empty subtitle list produces empty SRT`() {
        assertEquals("", SrtFormatter.format(emptyList()))
    }

    @Test
    fun `single subtitle produces one numbered cue with correct timing`() {
        val out = SrtFormatter.format(
            listOf(
                Subtitle(
                    text = "Hello",
                    startTimeMs = 1_000L,
                    endTimeMs = 2_500L
                )
            )
        )
        assertEquals("1\n00:00:01,000 --> 00:00:02,500\nHello\n\n", out)
    }

    @Test
    fun `multiple subtitles are numbered sequentially with correct timing`() {
        val out = SrtFormatter.format(
            listOf(
                Subtitle(text = "Hello everyone,", startTimeMs = 1_000L, endTimeMs = 2_000L),
                Subtitle(text = "Welcome to my channel.", startTimeMs = 2_000L, endTimeMs = 3_500L),
                Subtitle(text = "Today we are going to...", startTimeMs = 3_500L, endTimeMs = 5_200L)
            )
        )
        // The SRT spec puts a blank line between cues AND a trailing
        // blank line at the end of the file. The formatter writes
        // both, so the expected literal includes the trailing `\n`.
        val expected =
            "1\n" +
            "00:00:01,000 --> 00:00:02,000\n" +
            "Hello everyone,\n" +
            "\n" +
            "2\n" +
            "00:00:02,000 --> 00:00:03,500\n" +
            "Welcome to my channel.\n" +
            "\n" +
            "3\n" +
            "00:00:03,500 --> 00:00:05,200\n" +
            "Today we are going to...\n" +
            "\n"
        assertEquals(expected, out)
    }

    @Test
    fun `blank-text subtitles are skipped so the numbering stays clean`() {
        val out = SrtFormatter.format(
            listOf(
                Subtitle(text = "  ", startTimeMs = 0L, endTimeMs = 1_000L),
                Subtitle(text = "Visible", startTimeMs = 1_000L, endTimeMs = 2_000L)
            )
        )
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nVisible\n\n", out)
    }

    @Test
    fun `Persian Arabic and mixed-script text pass through unchanged`() {
        val persian = Subtitle(text = "سلام دنیا", startTimeMs = 0L, endTimeMs = 1_000L)
        val arabic = Subtitle(text = "مرحبا بالعالم", startTimeMs = 1_000L, endTimeMs = 2_000L)
        val mixed = Subtitle(text = "Hello سلام", startTimeMs = 2_000L, endTimeMs = 3_000L)
        val out = SrtFormatter.format(listOf(persian, arabic, mixed))
        // The Persian, Arabic, and mixed text must round-trip without
        // any escaping or normalisation. This is the property that
        // guarantees the file is readable in VLC, DaVinci, etc.
        assertTrue("Persian text missing", out.contains("سلام دنیا"))
        assertTrue("Arabic text missing", out.contains("مرحبا بالعالم"))
        assertTrue("Mixed-script text missing", out.contains("Hello سلام"))
        // No debug IDs / internal fields should leak into the file.
        assertFalse("Internal id leaked", out.contains("id="))
        assertFalse("Confidence leaked", out.contains("conf="))
    }

    @Test
    fun `negative timestamps are clamped to zero in the output`() {
        val out = SrtFormatter.format(
            listOf(Subtitle(text = "Hi", startTimeMs = -500L, endTimeMs = 1_000L))
        )
        assertEquals("1\n00:00:00,000 --> 00:00:01,000\nHi\n\n", out)
    }

    @Test
    fun `end before start is forced to be at least one ms after start`() {
        val out = SrtFormatter.format(
            listOf(Subtitle(text = "Hi", startTimeMs = 5_000L, endTimeMs = 4_500L))
        )
        // end was 4500 (before start 5000) -> coerced to start + 1
        assertTrue(
            "expected end >= start + 1ms but was: $out",
            out.contains("00:00:05,000 --> 00:00:05,001")
        )
    }

    @Test
    fun `output uses UTF-8 bytes and never contains IDs or debug fields`() {
        val subtitle = Subtitle(
            id = "should-not-appear",
            text = "Hello",
            startTimeMs = 0L,
            endTimeMs = 1_000L,
            words = listOf(
                TranscribedWord(
                    id = "also-should-not-appear",
                    text = "Hello",
                    startTimeMs = 0L,
                    endTimeMs = 1_000L,
                    confidence = 0.99f
                )
            ),
            confidence = 0.99f
        )
        val out = SrtFormatter.format(listOf(subtitle))
        assertFalse("sub.id leaked", out.contains("should-not-appear"))
        assertFalse("word.id leaked", out.contains("also-should-not-appear"))
        assertFalse("confidence leaked", out.contains("0.99"))
        // Sanity check: the body UTF-8 encodes without throwing.
        val bytes = out.toByteArray(Charsets.UTF_8)
        assertTrue(bytes.isNotEmpty())
    }
}
