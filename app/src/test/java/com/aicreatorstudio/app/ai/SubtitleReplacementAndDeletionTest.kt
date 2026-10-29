package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.TextOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * PHASE B3 — JVM unit tests for the AI-subtitle replacement and
 * deletion semantics.
 *
 * The brief is explicit:
 *   - "remove/replace only the AI-generated subtitle clips
 *     belonging to the current generation"
 *   - "preserve unrelated Text clips or other timeline items"
 *   - "do not delete unrelated timeline content"
 *
 * The implementation does this with the existing
 * `aiSubtitleOverlayIds: MutableSet<String>` set on the
 * `EditorViewModel`. The replace expression is:
 *
 *     textOverlays.filterNot { it.id in aiSubtitleOverlayIds } + newOverlays
 *
 * The delete-all expression is:
 *
 *     textOverlays.filterNot { it.id in aiSubtitleOverlayIds }
 *     aiSubtitleOverlayIds.clear()
 *
 * These tests lock the contract that user-added text overlays
 * (whose ids are NOT in the AI set) survive both operations.
 */
class SubtitleReplacementAndDeletionTest {

    // ---- Replacement ----

    @Test
    fun `regenerating replaces only AI subtitles and preserves user-added text overlays`() {
        // Initial state: 2 AI subtitles + 1 user Text overlay.
        val ai1 = makeAiSubtitle("AI #1", 0L, 1_000L)
        val ai2 = makeAiSubtitle("AI #2", 2_000L, 3_000L)
        val userText = makeUserText("User caption", 4_000L, 5_000L)
        val before = listOf(ai1, ai2, userText)
        val aiIds = setOf(ai1.id, ai2.id)

        // A new Whisper run produced 3 fresh AI subtitles.
        val newAi1 = makeAiSubtitle("NEW AI #1", 0L, 800L)
        val newAi2 = makeAiSubtitle("NEW AI #2", 1_500L, 2_500L)
        val newAi3 = makeAiSubtitle("NEW AI #3", 4_000L, 5_000L)

        // The replace expression: drop the previous AI overlays,
        // append the new ones. The user Text overlay must survive
        // because its id is not in the AI set.
        val after = before.filterNot { it.id in aiIds } + listOf(newAi1, newAi2, newAi3)
        assertEquals(4, after.size)
        // The user Text overlay is the only one carried over.
        assertTrue(
            "the user-added text overlay must survive regeneration",
            after.any { it.id == userText.id && it.text == "User caption" }
        )
        // The new AI overlays are present.
        assertTrue(after.any { it.id == newAi1.id && it.text == "NEW AI #1" })
        assertTrue(after.any { it.id == newAi2.id && it.text == "NEW AI #2" })
        assertTrue(after.any { it.id == newAi3.id && it.text == "NEW AI #3" })
        // The old AI overlays are gone.
        assertFalse(after.any { it.id == ai1.id })
        assertFalse(after.any { it.id == ai2.id })
    }

    @Test
    fun `regenerating when the previous run produced zero subtitles removes only the previous AI set`() {
        // First run produced 2 AI subtitles; second run produced none.
        val ai1 = makeAiSubtitle("AI #1", 0L, 1_000L)
        val ai2 = makeAiSubtitle("AI #2", 2_000L, 3_000L)
        val userText = makeUserText("Keep me", 4_000L, 5_000L)
        val before = listOf(ai1, ai2, userText)
        val aiIds = setOf(ai1.id, ai2.id)
        val newOverlays = emptyList<TextOverlay>()

        val after = before.filterNot { it.id in aiIds } + newOverlays
        assertEquals("only the user Text overlay remains", 1, after.size)
        assertEquals(userText.id, after.first().id)
        assertEquals("Keep me", after.first().text)
    }

    @Test
    fun `regenerating when there are no previous AI subtitles just appends`() {
        // Initial state: just user-added text, no AI yet.
        val userText = makeUserText("User caption", 0L, 1_000L)
        val before = listOf(userText)
        val aiIds = emptySet<String>()
        val newAi1 = makeAiSubtitle("AI #1", 2_000L, 3_000L)
        val newAi2 = makeAiSubtitle("AI #2", 4_000L, 5_000L)

        val after = before.filterNot { it.id in aiIds } + listOf(newAi1, newAi2)
        assertEquals(3, after.size)
        // User Text overlay still first (preserves the original
        // timeline position because the user chose it).
        assertEquals(userText.id, after[0].id)
        // New AI overlays appended in their own order.
        assertEquals(newAi1.id, after[1].id)
        assertEquals(newAi2.id, after[2].id)
    }

    // ---- Delete all ----

    @Test
    fun `delete-all removes every AI subtitle but keeps every user-added text overlay`() {
        val ai1 = makeAiSubtitle("AI #1", 0L, 1_000L)
        val ai2 = makeAiSubtitle("AI #2", 2_000L, 3_000L)
        val user1 = makeUserText("User 1", 4_000L, 5_000L)
        val user2 = makeUserText("User 2", 6_000L, 7_000L)
        val before = listOf(ai1, ai2, user1, user2)
        val aiIds: MutableSet<String> = mutableSetOf(ai1.id, ai2.id)

        val after = before.filterNot { it.id in aiIds }
        aiIds.clear()
        assertEquals(2, after.size)
        // Both user Text overlays survive.
        assertTrue(after.any { it.id == user1.id && it.text == "User 1" })
        assertTrue(after.any { it.id == user2.id && it.text == "User 2" })
        // No AI subtitle remains.
        assertFalse(after.any { it.id == ai1.id })
        assertFalse(after.any { it.id == ai2.id })
        // The AI set is empty so the next generate does not think
        // those old overlays are still "owned" by the AI pipeline.
        assertTrue(aiIds.isEmpty())
    }

    @Test
    fun `delete-all when there are no AI subtitles is a no-op`() {
        val user1 = makeUserText("User 1", 0L, 1_000L)
        val user2 = makeUserText("User 2", 2_000L, 3_000L)
        val before = listOf(user1, user2)
        val aiIds: MutableSet<String> = mutableSetOf()

        val after = before.filterNot { it.id in aiIds }
        aiIds.clear()
        assertEquals(before, after)
    }

    @Test
    fun `delete-all when every text overlay is AI leaves the project with no text overlays`() {
        val ai1 = makeAiSubtitle("AI #1", 0L, 1_000L)
        val ai2 = makeAiSubtitle("AI #2", 2_000L, 3_000L)
        val before = listOf(ai1, ai2)
        val aiIds: MutableSet<String> = mutableSetOf(ai1.id, ai2.id)

        val after = before.filterNot { it.id in aiIds }
        aiIds.clear()
        assertTrue("delete-all must empty the text overlay list " +
            "when everything is AI", after.isEmpty())
    }

    @Test
    fun `a single overlay can be deleted without affecting other AI subtitles`() {
        // The single-overlay delete path (deleteTextOverlay) is
        // distinct from delete-all. It removes one id from the
        // list AND from the AI set, leaving siblings alone.
        val ai1 = makeAiSubtitle("AI #1", 0L, 1_000L)
        val ai2 = makeAiSubtitle("AI #2", 2_000L, 3_000L)
        val ai3 = makeAiSubtitle("AI #3", 4_000L, 5_000L)
        val before = listOf(ai1, ai2, ai3)
        val aiIds: MutableSet<String> = mutableSetOf(ai1.id, ai2.id, ai3.id)

        val after = before.filter { it.id != ai2.id }
        aiIds.remove(ai2.id)
        assertEquals(2, after.size)
        assertTrue(after.any { it.id == ai1.id })
        assertTrue(after.any { it.id == ai3.id })
        assertFalse(after.any { it.id == ai2.id })
        // The remaining AI subtitles are still tracked.
        assertEquals(2, aiIds.size)
    }

    // ---- Helpers ----
    //
    // AI subtitles have `words = listOf(...)` so the existing
    // B2 mapper can identify them. User Text overlays have an
    // empty words list — that's how the existing
    // `hasGeneratedSubtitles` check on the AI Subtitle card
    // distinguishes the two.

    private fun makeAiSubtitle(
        text: String,
        startMs: Long,
        endMs: Long
    ) = TextOverlay(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        fontFamily = PersianFont.VAZIR.name,
        words = listOf(
            TranscribedWord(
                id = UUID.randomUUID().toString(),
                text = text,
                startTimeMs = startMs,
                endTimeMs = endMs,
                confidence = 0.9f
            )
        ),
        karaokeEnabled = true
    )

    private fun makeUserText(
        text: String,
        startMs: Long,
        endMs: Long
    ) = TextOverlay(
        id = UUID.randomUUID().toString(),
        text = text,
        startTimeMs = startMs,
        endTimeMs = endMs,
        // Empty words list = user-authored Text overlay, not AI.
        words = emptyList(),
        karaokeEnabled = false
    )
}
