package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-09-19 — range math for karaoke active-word highlighting.
 *
 * The ranges are LOGICAL offsets (bidi renders them); the contract
 * is that irregular whitespace never shifts them and repeats
 * resolve to the right instance — in RTL and LTR alike.
 */
class KaraokeRangesTest {

    @Test
    fun `index path finds the word with clean spacing`() {
        val r = KaraokeRanges.activeRange(
            "سلام دنیا",
            listOf("سلام", "دنیا"),
            1
        )
        assertEquals(5 until 9, r)
        assertEquals("دنیا", "سلام دنیا".substring(r!!.first, r.last + 1))
    }

    @Test
    fun `double spaces do not shift later indexes`() {
        // Old split(' ') code produced tokens [سلام, <empty>, دنیا]
        // and missed index 1 entirely.
        val r = KaraokeRanges.activeRange(
            "سلام  دنیا",
            listOf("سلام", "دنیا"),
            1
        )
        assertEquals(6 until 10, r)
    }

    @Test
    fun `newlines and tabs are token separators`() {
        val r = KaraokeRanges.activeRange(
            "سلام\nدنیا\tخوب",
            listOf("سلام", "دنیا", "خوب"),
            2
        )
        assertEquals("خوب", "سلام\nدنیا\tخوب".substring(r!!.first, r.last + 1))
    }

    @Test
    fun `repeated words resolve to the active instance`() {
        val text = "سلام دنیا سلام"
        val first = KaraokeRanges.activeRange(text, listOf("سلام", "دنیا", "سلام"), 0)
        val second = KaraokeRanges.activeRange(text, listOf("سلام", "دنیا", "سلام"), 2)
        assertEquals(0 until 4, first)
        assertEquals(10 until 14, second)
    }

    @Test
    fun `nth occurrence works when text drifted out of index sync`() {
        // Edited caption: an extra leading word shifts indexes, but
        // the 2nd "سلام" (words[2]) must still hit the 2nd token.
        val r = KaraokeRanges.activeRange(
            "اوه سلام سلام",
            listOf("سلام", "دنیا", "سلام"),
            2
        )
        assertEquals(9 until 13, r)
    }

    @Test
    fun `zwnj words stay whole`() {
        val text = "امروز می‌شود فردا"
        val r = KaraokeRanges.activeRange(
            text,
            listOf("امروز", "می‌شود", "فردا"),
            1
        )
        assertEquals("می‌شود", text.substring(r!!.first, r.last + 1))
    }

    @Test
    fun `unresolvable text returns null instead of a wrong word`() {
        assertNull(
            KaraokeRanges.activeRange(
                "خداحافظ",
                listOf("سلام"),
                0
            )
        )
        assertNull(KaraokeRanges.activeRange("", listOf("سلام"), 0))
        assertNull(KaraokeRanges.activeRange("سلام", listOf("سلام"), -1))
        assertNull(KaraokeRanges.activeRange("سلام", listOf("  "), 0))
    }

    @Test
    fun `latin text works the same way`() {
        val r = KaraokeRanges.activeRange(
            "hello  brave   world",
            listOf("hello", "brave", "world"),
            2
        )
        assertEquals("world", "hello  brave   world".substring(r!!.first, r.last + 1))
    }
}
