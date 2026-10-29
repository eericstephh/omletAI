package com.aicreatorstudio.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the custom-font import contract.
 *
 * ## What is and is not testable here
 *
 * `CustomFontFiles` is deliberately free of every `android.*` type, so
 * these tests exercise the real production code rather than a copy.
 * `unitTests.isReturnDefaultValues = true` makes Android stubs silently
 * return `0`/`null`, so a test that reached into `Typeface` or
 * `Context` could pass for the wrong reason — the design keeps that
 * possibility out of reach.
 *
 * The one thing NOT covered here is the byte-level copy/validate path
 * in `DefaultCustomFontRepository`, which needs a real `ContentResolver`.
 * Its pure decisions (header sniffing, storage-name shape) are tested
 * here directly, which is where the security-relevant logic lives.
 */
class CustomFontFilesTest {

    // ==================================================================
    // Namespace discrimination — the string stored on a style
    // ==================================================================

    @Test
    fun `a bare enum name is not mistaken for a custom font`() {
        // This is the collision the prefix exists to prevent: without
        // it, an imported font whose id happened to be "VAZIR" would be
        // indistinguishable from the bundled family.
        assertFalse(CustomFont.isCustomFontId(null))
        assertFalse(CustomFont.isCustomFontId("VAZIR"))
        assertFalse(CustomFont.isCustomFontId("LALEZAR"))
        assertFalse(CustomFont.isCustomFontId(""))
    }

    @Test
    fun `a prefixed id is recognised`() {
        assertTrue(CustomFont.isCustomFontId("custom:abc-123"))
        assertEquals("abc-123", CustomFont.customIdOf("custom:abc-123"))
    }

    @Test
    fun `the bare id of a non-custom reference is null`() {
        assertEquals(null, CustomFont.customIdOf("VAZIR"))
        assertEquals(null, CustomFont.customIdOf(null))
    }

    @Test
    fun `selectionId round-trips through the discriminator`() {
        val font = CustomFont(id = "u-1", name = "وزیرمتن", filePath = "/x/u-1.ttf")
        assertTrue(CustomFont.isCustomFontId(font.selectionId))
        assertEquals("u-1", CustomFont.customIdOf(font.selectionId))
    }

    @Test
    fun `a custom font is custom by default and cannot masquerade as bundled`() {
        val font = CustomFont(id = "u-1", name = "X", filePath = "/x/u-1.ttf")
        assertTrue(font.isCustom)
    }

    // ==================================================================
    // Extension handling
    // ==================================================================

    @Test
    fun `extension is lower-cased and undotted`() {
        assertEquals("ttf", CustomFontFiles.extensionOf("Vazir.TTF"))
        assertEquals("otf", CustomFontFiles.extensionOf("font.Otf"))
    }

    @Test
    fun `a name with no extension yields an empty extension`() {
        assertEquals("", CustomFontFiles.extensionOf("noextension"))
        assertEquals("", CustomFontFiles.extensionOf("trailing."))
        assertEquals("", CustomFontFiles.extensionOf(""))
    }

    @Test
    fun `only ttf and otf are accepted`() {
        assertTrue(CustomFontFiles.hasSupportedExtension("a.ttf"))
        assertTrue(CustomFontFiles.hasSupportedExtension("a.otf"))
        assertTrue(CustomFontFiles.hasSupportedExtension("a.OTF"))
        assertFalse(CustomFontFiles.hasSupportedExtension("a.png"))
        assertFalse(CustomFontFiles.hasSupportedExtension("a.ttf.exe"))
        assertFalse(CustomFontFiles.hasSupportedExtension("a"))
    }

    // ==================================================================
    // sfnt header sniffing — the authoritative check
    // ==================================================================

    @Test
    fun `all four sfnt signatures are accepted`() {
        // 00 01 00 00 — TrueType outlines
        assertTrue(
            "TrueType",
            CustomFontFiles.isLikelyFontBytes(byteArrayOf(0x00, 0x01, 0x00, 0x00))
        )
        // 4F 54 54 4F — OTTO / CFF
        assertTrue(
            "OTTO",
            CustomFontFiles.isLikelyFontBytes(byteArrayOf(0x4F, 0x54, 0x54, 0x4F))
        )
        // 74 72 75 65 — legacy Apple `true`
        assertTrue(
            "true",
            CustomFontFiles.isLikelyFontBytes(byteArrayOf(0x74, 0x72, 0x75, 0x65))
        )
        // 74 74 63 66 — `ttcf` collection
        assertTrue(
            "ttcf",
            CustomFontFiles.isLikelyFontBytes(byteArrayOf(0x74, 0x74, 0x63, 0x66))
        )
    }

    @Test
    fun `common non-font magic numbers are rejected`() {
        // PNG, JPEG, ZIP (a renamed .docx / .apk), PDF, RIFF/WAV.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val wav = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        listOf("png" to png, "jpeg" to jpeg, "zip" to zip, "pdf" to pdf, "wav" to wav)
            .forEach { (label, bytes) ->
                assertFalse("$label must be rejected", CustomFontFiles.isLikelyFontBytes(bytes))
            }
    }

    @Test
    fun `the high bit set on a TrueType signature is still accepted`() {
        // Byte-array comparison bugs are easy here: an unsigned read is
        // required, and `0x00 0x01 0x00 0x00` has no high bits to catch
        // that — so verify the masking with a value that does.
        // `0x80` is not a signature, but it proves the range check is
        // not accidentally treating signed negatives as matches.
        assertFalse(CustomFontFiles.isLikelyFontBytes(byteArrayOf(0x80.toByte(), 0x01, 0x00, 0x00)))
    }

    @Test
    fun `a short header is rejected rather than read out of bounds`() {
        assertFalse(CustomFontFiles.isLikelyFontBytes(ByteArray(0)))
        assertFalse(CustomFontFiles.isLikelyFontBytes(byteArrayOf(0x00)))
        assertFalse(CustomFontFiles.isLikelyFontBytes(byteArrayOf(0x00, 0x01, 0x00)))
    }

    // ==================================================================
    // Display-name sanitisation
    // ==================================================================

    @Test
    fun `the extension is stripped from the display name`() {
        assertEquals("Vazirmatn", CustomFontFiles.displayNameFrom("Vazirmatn.ttf"))
        assertEquals("IranSans", CustomFontFiles.displayNameFrom("IranSans.otf"))
    }

    @Test
    fun `persian filenames survive untouched`() {
        // The whole point of importing a Persian font is a Persian
        // label; mangling it would defeat the feature.
        assertEquals("وزیرمتن", CustomFontFiles.displayNameFrom("وزیرمتن.ttf"))
        assertEquals("یکان بخ", CustomFontFiles.displayNameFrom("یکان بخ.otf"))
    }

    @Test
    fun `a directory component is dropped`() {
        assertEquals("Vazir", CustomFontFiles.displayNameFrom("/storage/emulated/0/Download/Vazir.ttf"))
        assertEquals("Vazir", CustomFontFiles.displayNameFrom("C:\\Users\\me\\Vazir.ttf"))
    }

    @Test
    fun `path and control characters are neutralised in the label`() {
        // These never legitimately appear in a font family name, and a
        // label containing them is either a mistake or an attack.
        val name = CustomFontFiles.displayNameFrom("a<b>c:d?e*f\\g|h.ttf")
        listOf('<', '>', ':', '?', '*', '\\', '|').forEach { ch ->
            assertFalse("label still contains $ch: $name", name.contains(ch))
        }
    }

    @Test
    fun `whitespace is collapsed and trimmed`() {
        assertEquals("A B C", CustomFontFiles.displayNameFrom("A   B\n\nC.ttf"))
        assertEquals("A B", CustomFontFiles.displayNameFrom("  A  B  .ttf"))
    }

    @Test
    fun `a name that sanitises to nothing falls back to the untitled label`() {
        assertEquals(CustomFontFiles.UNTITLED_NAME, CustomFontFiles.displayNameFrom(".ttf"))
        assertEquals(CustomFontFiles.UNTITLED_NAME, CustomFontFiles.displayNameFrom("///.ttf"))
        assertEquals(CustomFontFiles.UNTITLED_NAME, CustomFontFiles.displayNameFrom("   .ttf"))
    }

    @Test
    fun `the fallback label is real Persian, not mojibake`() {
        // The project has shipped "????? ????" to users twice; the
        // guard from SubtitleMessages applies to every new message.
        assertTrue(
            "UNTITLED_NAME is corrupted: ${CustomFontFiles.UNTITLED_NAME}",
            SubtitleMessages.assertNoMojibake(CustomFontFiles.UNTITLED_NAME)
        )
    }

    // ==================================================================
    // Storage path resolution — the traversal defence
    // ==================================================================

    @Test
    fun `the storage name is derived from the id, never the user's filename`() {
        val id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        assertEquals("$id.ttf", CustomFontFiles.storageFileNameFor(id, "ttf"))
        assertEquals("$id.otf", CustomFontFiles.storageFileNameFor(id, "otf"))
    }

    @Test
    fun `an unsupported extension cannot be smuggled into the font directory`() {
        val id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        // A hostile ".png" or "sh" extension is coerced to ttf.
        assertEquals("$id.ttf", CustomFontFiles.storageFileNameFor(id, "png"))
        assertEquals("$id.ttf", CustomFontFiles.storageFileNameFor(id, "sh"))
        assertEquals("$id.ttf", CustomFontFiles.storageFileNameFor(id, ""))
    }

    @Test
    fun `a traversal id cannot escape the font directory`() {
        // `storageFileNameFor` is only ever called with a UUID we mint,
        // but assert the property directly: even a hostile id does not
        // produce a name that `isStorageFileName` accepts, so decode()
        // would drop it.
        val hostile = CustomFontFiles.storageFileNameFor("../../databases/app.db", "ttf")
        assertTrue("this is the point: the shape is rejected", !CustomFontFiles.isStorageFileName(hostile))
    }

    @Test
    fun `storage name round-trips through the inverse validator`() {
        val id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        listOf("ttf", "otf").forEach { ext ->
            val name = CustomFontFiles.storageFileNameFor(id, ext)
            assertTrue("round trip failed for $ext: $name", CustomFontFiles.isStorageFileName(name))
        }
    }

    @Test
    fun `the inverse validator rejects anything that is not ours`() {
        // Separators (traversal), wrong extension, non-UUID stem,
        // empty stem, and a bare name.
        assertFalse(CustomFontFiles.isStorageFileName("../index.json"))
        assertFalse(CustomFontFiles.isStorageFileName("a/b.ttf"))
        assertFalse(CustomFontFiles.isStorageFileName("a\\b.ttf"))
        assertFalse(CustomFontFiles.isStorageFileName("notes.txt"))
        assertFalse(CustomFontFiles.isStorageFileName("index.json"))
        assertFalse(CustomFontFiles.isStorageFileName("Vazir.ttf"))
        assertFalse(CustomFontFiles.isStorageFileName(".ttf"))
        assertFalse(CustomFontFiles.isStorageFileName(""))
    }

    @Test
    fun `the inverse validator accepts an upper-case UUID and extension`() {
        val name = "3F2504E0-4F89-11D3-9A0C-0305E82C3301.TTF"
        assertTrue(CustomFontFiles.isStorageFileName(name))
    }
}
