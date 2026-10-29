package com.aicreatorstudio.app.ai.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * JVM tests for [WindowPlanner] — the function that decides where
 * the 30 s whisper windows are cut.
 */
class WindowPlannerTest {

    private val sr = 16_000

    private fun speech(total: Int, amplitude: Double = 0.3): FloatArray =
        FloatArray(total) { i ->
            (amplitude * sin(2.0 * PI * 180.0 * i / sr)).toFloat()
        }

    private fun silence(buf: FloatArray, fromMs: Int, toMs: Int) {
        val a = (fromMs.toLong() * sr / 1000L).toInt()
        val b = (toMs.toLong() * sr / 1000L).toInt()
        for (i in a until minOf(b, buf.size)) buf[i] = 0f
    }

    @Test
    fun `empty buffer yields a single boundary`() {
        val b = WindowPlanner.boundaries(FloatArray(0), sr, sr)
        assertEquals(listOf(0), b.toList())
    }

    @Test
    fun `buffer shorter than one window is a single window`() {
        val b = WindowPlanner.boundaries(FloatArray(sr / 2) { 0.1f }, sr, sr)
        assertEquals(listOf(0, sr / 2), b.toList())
    }

    @Test
    fun `boundaries start at zero and end at the buffer length`() {
        val buf = speech(sr * 4)
        val b = WindowPlanner.boundaries(buf, sr, sr)
        assertEquals(0, b.first())
        assertEquals(buf.size, b.last())
    }

    @Test
    fun `boundaries are strictly increasing`() {
        val buf = speech(sr * 7)
        val b = WindowPlanner.boundaries(buf, sr, sr)
        for (i in 1 until b.size) {
            assertTrue("boundary $i (${b[i]}) must exceed ${b[i - 1]}", b[i] > b[i - 1])
        }
    }

    @Test
    fun `cut lands inside the pause instead of on the grid`() {
        // 3 s of speech with a 100 ms hole straddling the nominal
        // 1 s boundary. The planner must move the cut into the hole.
        val buf = speech(sr * 3)
        silence(buf, 950, 1050)
        val b = WindowPlanner.boundaries(buf, sr, sr)
        val cut = b[1]
        assertTrue(
            "cut at ${cut}ms must land in the 950..1050ms pause",
            cut in (940 * sr / 1000)..(1060 * sr / 1000)
        )
    }

    @Test
    fun `every window respects the minimum fill ratio`() {
        val buf = speech(sr * 6)
        silence(buf, 400, 900)
        silence(buf, 1400, 1900)
        silence(buf, 2400, 2900)
        silence(buf, 3400, 3900)
        silence(buf, 4400, 4900)
        val b = WindowPlanner.boundaries(buf, sr, sr)
        val minWindow = (sr * WindowPlanner.MIN_FILL_RATIO).toInt()
        for (i in 1 until b.size - 1) {
            assertTrue(
                "window ${i - 1} is ${b[i] - b[i - 1]} samples, below $minWindow",
                b[i] - b[i - 1] >= minWindow
            )
        }
    }

    @Test
    fun `all-silent buffer still terminates`() {
        val b = WindowPlanner.boundaries(FloatArray(sr * 5), sr, sr)
        assertEquals(0, b.first())
        assertEquals(sr * 5, b.last())
        assertTrue("must not loop forever", b.size >= 2)
    }

    @Test
    fun `quietest index prefers the lower-energy candidate`() {
        val buf = FloatArray(1000) { 0.5f }
        for (i in 400 until 600) buf[i] = 0f
        val idx = WindowPlanner.quietestIndex(buf, 0, 999, 100)
        assertTrue("picked $idx, expected inside 400..600", idx in 400..600)
    }
}
