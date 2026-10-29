package com.aicreatorstudio.app.data.font

import com.aicreatorstudio.app.model.CustomFont
import com.aicreatorstudio.app.model.CustomFontFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the imported-font metadata codec.
 *
 * This is the highest-value pure test in the feature: the codec is the
 * only place that turns user-reachable bytes into file paths, so a
 * traversal hole here would be an arbitrary-file-read, and a dropped
 * field would silently disappear a user's font on restart.
 */
class CustomFontMetadataJsonTest {

    private val dir = "/data/user/0/com.aicreatorstudio.app/files/fonts"

    private val idA = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
    private val idB = "9c858901-8a57-4791-81fe-4c455b099bc9"

    private fun font(id: String, name: String, ext: String) = CustomFont(
        id = id,
        name = name,
        filePath = "$dir/${CustomFontFiles.storageFileNameFor(id, ext)}"
    )

    // ==================================================================
    // Round trip
    // ==================================================================

    @Test
    fun `an encoded list decodes back to the same fonts`() {
        val fonts = listOf(
            font(idA, "وزیرمتن", "ttf"),
            font(idB, "IranSans", "otf")
        )
        val restored = CustomFontMetadataJson.decode(CustomFontMetadataJson.encode(fonts), dir)
        assertEquals(fonts, restored)
    }

    @Test
    fun `an empty list round-trips to an empty list`() {
        val json = CustomFontMetadataJson.encode(emptyList())
        assertEquals(emptyList<CustomFont>(), CustomFontMetadataJson.decode(json, dir))
    }

    @Test
    fun `the absolute path is NOT persisted, only the leaf name`() {
        // The directory can move (it does, between devices / reinstall
        // paths); storing an absolute path would bake it in. A test can
        // prove the leaf-only contract by encoding with one directory
        // and decoding with another.
        val json = CustomFontMetadataJson.encode(listOf(font(idA, "Vazir", "ttf")))
        assertFalse("absolute path leaked: $json", json.contains(dir))

        val other = "/some/other/files/fonts"
        val restored = CustomFontMetadataJson.decode(json, other)
        assertEquals("$other/$idA.ttf", restored.single().filePath)
    }

    @Test
    fun `a trailing separator on the directory does not double up`() {
        val json = CustomFontMetadataJson.encode(listOf(font(idA, "Vazir", "ttf")))
        val restored = CustomFontMetadataJson.decode(json, "$dir/")
        assertEquals("$dir/$idA.ttf", restored.single().filePath)
    }

    @Test
    fun `the format version is written`() {
        val json = CustomFontMetadataJson.encode(listOf(font(idA, "Vazir", "ttf")))
        assertTrue("version missing: $json", json.contains("\"version\""))
        assertTrue("wrong version: $json", json.contains(CustomFontMetadataJson.VERSION.toString()))
    }

    // ==================================================================
    // Traversal / hostile input — the security half
    // ==================================================================

    @Test
    fun `a tampered entry naming an outside path is dropped, not honoured`() {
        // An attacker with write access to index.json (rooted device)
        // rewrites a filename to escape the directory. decode() must
        // refuse it rather than produce an arbitrary-file-read.
        val json = """
            {"version":1,"fonts":[
              {"id":"$idA","name":"evil","file":"../../databases/app.db"}
            ]}
        """.trimIndent()
        val restored = CustomFontMetadataJson.decode(json, dir)
        assertTrue("a traversal entry must be dropped, got: $restored", restored.isEmpty())
    }

    @Test
    fun `an absolute path in the file field is dropped`() {
        // `isStorageFileName` rejects separators outright, so neither
        // a POSIX nor a Windows absolute path can survive.
        val posix = """{"version":1,"fonts":[{"id":"$idA","name":"e","file":"/etc/passwd"}]}"""
        val windows = """{"version":1,"fonts":[{"id":"$idA","name":"e","file":"C:\\Windows\\x.ttf"}]}"""
        assertTrue(CustomFontMetadataJson.decode(posix, dir).isEmpty())
        assertTrue(CustomFontMetadataJson.decode(windows, dir).isEmpty())
    }

    @Test
    fun `an entry whose name is not a uuid is dropped`() {
        val json = """{"version":1,"fonts":[{"id":"$idA","name":"e","file":"Vazir.ttf"}]}"""
        assertTrue(CustomFontMetadataJson.decode(json, dir).isEmpty())
    }

    @Test
    fun `a non-font extension is dropped even with a valid uuid stem`() {
        val json = """{"version":1,"fonts":[{"id":"$idA","name":"e","file":"$idA.png"}]}"""
        assertTrue(CustomFontMetadataJson.decode(json, dir).isEmpty())
    }

    @Test
    fun `a good entry survives alongside a hostile one`() {
        // Partial recovery matters: one corrupt entry must not cost the
        // user every other imported font.
        val json = """
            {"version":1,"fonts":[
              {"id":"$idA","name":"good","file":"$idA.ttf"},
              {"id":"$idB","name":"evil","file":"../../../etc/passwd"},
              {"id":"x","name":"bad stem","file":"notspecial.ttf"}
            ]}
        """.trimIndent()
        val restored = CustomFontMetadataJson.decode(json, dir)
        assertEquals(1, restored.size)
        assertEquals("good", restored.single().name)
        assertEquals("$dir/$idA.ttf", restored.single().filePath)
    }

    // ==================================================================
    // Malformed input must never throw
    // ==================================================================

    @Test
    fun `null, blank and garbage input decode to empty rather than throwing`() {
        assertEquals(emptyList<CustomFont>(), CustomFontMetadataJson.decode(null, dir))
        assertEquals(emptyList<CustomFont>(), CustomFontMetadataJson.decode("", dir))
        assertEquals(emptyList<CustomFont>(), CustomFontMetadataJson.decode("   ", dir))
        assertEquals(emptyList<CustomFont>(), CustomFontMetadataJson.decode("{not json", dir))
        assertEquals(emptyList<CustomFont>(), CustomFontMetadataJson.decode("[]", dir))
    }

    @Test
    fun `an unknown version is refused rather than misread`() {
        // Forwards- and backwards-compatibility: a v2 file written by a
        // newer build must not be silently interpreted as v1.
        val future = """{"version":2,"fonts":[{"id":"$idA","name":"n","file":"$idA.ttf"}]}"""
        val past = """{"version":0,"fonts":[{"id":"$idA","name":"n","file":"$idA.ttf"}]}"""
        assertTrue(CustomFontMetadataJson.decode(future, dir).isEmpty())
        assertTrue(CustomFontMetadataJson.decode(past, dir).isEmpty())
    }

    @Test
    fun `a missing version is treated as unknown and refused`() {
        val json = """{"fonts":[{"id":"$idA","name":"n","file":"$idA.ttf"}]}"""
        assertTrue(CustomFontMetadataJson.decode(json, dir).isEmpty())
    }

    @Test
    fun `an entry missing its id or file is skipped`() {
        val noId = """{"version":1,"fonts":[{"name":"n","file":"$idA.ttf"}]}"""
        val noFile = """{"version":1,"fonts":[{"id":"$idA","name":"n"}]}"""
        assertTrue(CustomFontMetadataJson.decode(noId, dir).isEmpty())
        assertTrue(CustomFontMetadataJson.decode(noFile, dir).isEmpty())
    }

    @Test
    fun `a missing name falls back to the untitled label`() {
        val json = """{"version":1,"fonts":[{"id":"$idA","file":"$idA.ttf"}]}"""
        val restored = CustomFontMetadataJson.decode(json, dir)
        assertEquals(CustomFontFiles.UNTITLED_NAME, restored.single().name)
    }
}
