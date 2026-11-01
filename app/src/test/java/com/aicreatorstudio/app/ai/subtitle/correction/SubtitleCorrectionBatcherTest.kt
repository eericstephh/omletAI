package com.aicreatorstudio.app.ai.subtitle.correction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batching tests.
 *
 * The properties that matter are structural: order is preserved, no
 * batch exceeds the caps, and a batch boundary NEVER falls inside a
 * segment (which is how "do not split Persian characters or malformed
 * text" is guaranteed — segments are whole words by construction).
 */
class SubtitleCorrectionBatcherTest {

    private fun seg(id: String, text: String) = CorrectionInputSegment(id, text)

    @Test
    fun `order is preserved across batches`() {
        val input = (1..25).map { seg("s$it", "متن شماره $it") }
        val batches = SubtitleCorrectionBatcher.plan(input, maxSegments = 10)

        assertEquals(3, batches.size)
        val flat = batches.flatMap { it.segments }
        assertEquals(input.map { it.id }, flat.map { it.id })
    }

    @Test
    fun `no batch exceeds the segment cap`() {
        val input = (1..30).map { seg("s$it", "متن") }
        val batches = SubtitleCorrectionBatcher.plan(input, maxSegments = 7)
        assertTrue(batches.all { it.segments.size <= 7 })
        assertEquals(30, batches.sumOf { it.segments.size })
    }

    @Test
    fun `no batch exceeds the character cap`() {
        val input = (1..20).map { seg("s$it", "ا".repeat(200)) }
        val batches = SubtitleCorrectionBatcher.plan(input, maxSegments = 50, maxChars = 900)
        assertTrue(batches.all { batch -> batch.segments.sumOf { it.text.length } <= 900 })
    }

    @Test
    fun `a single oversized segment gets its own batch rather than being split`() {
        val huge = "ب".repeat(5_000)
        val input = listOf(seg("big", huge), seg("small", "کوچک"))
        val batches = SubtitleCorrectionBatcher.plan(input, maxChars = 100)

        assertEquals(2, batches.size)
        assertEquals(listOf("big"), batches[0].segments.map { it.id })
        // The text arrives whole — never truncated.
        assertEquals(huge, batches[0].segments[0].text)
    }

    @Test
    fun `context is attached on both sides and never repeats the batch`() {
        val input = (1..6).map { seg("s$it", "جمله $it") }
        val batches = SubtitleCorrectionBatcher.plan(input, maxSegments = 2, maxChars = 10_000)

        // First batch has no "before"; last has no "after".
        assertNull(batches.first().contextBefore)
        assertNull(batches.last().contextAfter)

        val middle = batches[1]
        assertTrue(middle.contextBefore!!.contains("جمله 2"))
        assertTrue(middle.contextAfter!!.contains("جمله 5"))

        // Context is reference-only: its ids must NOT appear in the
        // batch's own segment list, or the parser would expect them.
        val batchIds = middle.segments.map { it.id }.toSet()
        assertEquals(setOf("s3", "s4"), batchIds)
    }

    @Test
    fun `blank segments are dropped`() {
        val input = listOf(seg("a", "متن"), seg("b", "   "), seg("c", ""))
        val batches = SubtitleCorrectionBatcher.plan(input)
        assertEquals(listOf("a"), batches.flatMap { it.segments }.map { it.id })
    }

    @Test
    fun `no segments plans no batches`() {
        assertTrue(SubtitleCorrectionBatcher.plan(emptyList()).isEmpty())
        assertTrue(
            SubtitleCorrectionBatcher.plan(listOf(seg("a", " "))).isEmpty()
        )
    }
}
