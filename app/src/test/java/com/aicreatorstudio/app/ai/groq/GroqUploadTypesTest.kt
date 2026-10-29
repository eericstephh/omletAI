package com.aicreatorstudio.app.ai.groq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-13 FILENAME FIX — pins the resolution that keeps Groq
 * from rejecting uploads with "file must be one of the following
 * types: [flac mp3 mp4 mpeg mpga m4a ogg opus wav webm]":
 * the multipart filename must carry a supported extension and
 * the part Content-Type must be truthful.
 */
class GroqUploadTypesTest {

    // ----------------------------------------------------------------
    // extension helpers
    // ----------------------------------------------------------------

    @Test
    fun `supported extension list matches the documented groq set`() {
        assertEquals(
            setOf("flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "opus", "wav", "webm"),
            GroqUploadTypes.SUPPORTED_EXTENSIONS
        )
    }

    @Test
    fun `extension extraction handles dots case and blanks`() {
        assertEquals("mp3", GroqUploadTypes.extensionOf("song.MP3"))
        assertEquals("m4a", GroqUploadTypes.extensionOf("clip.m4a"))
        assertNull(GroqUploadTypes.extensionOf("noext"))
        assertNull(GroqUploadTypes.extensionOf(null))
        assertNull(GroqUploadTypes.extensionOf("trailingdot."))
    }

    @Test
    fun `hasSupportedExtension is exact - unsupported suffixes fail`() {
        assertTrue(GroqUploadTypes.hasSupportedExtension("a.mp3"))
        assertTrue(GroqUploadTypes.hasSupportedExtension("a.WAV"))
        // The exact error case from the bug: no extension at all.
        assertFalse(GroqUploadTypes.hasSupportedExtension("audio"))
        assertFalse(GroqUploadTypes.hasSupportedExtension("audio.bin"))
        assertFalse(GroqUploadTypes.hasSupportedExtension("upload.tmp"))
    }

    // ----------------------------------------------------------------
    // filename resolution (the regression: "audio" must never ship)
    // ----------------------------------------------------------------

    @Test
    fun `display name with supported extension is kept verbatim`() {
        assertEquals(
            "my-video.mp4",
            GroqUploadTypes.resolveUploadFileName("my-video.mp4", null)
        )
        assertEquals(
            "voice.M4A",
            GroqUploadTypes.resolveUploadFileName("voice.M4A", null)
        )
    }

    @Test
    fun `extension-less name gets one from the resolver mime`() {
        // A gallery content:// display name without extension but a
        // known MIME type — the exact content-URI scenario.
        assertEquals(
            "track.mp3",
            GroqUploadTypes.resolveUploadFileName("track", "audio/mpeg")
        )
        assertEquals(
            "recording.m4a",
            GroqUploadTypes.resolveUploadFileName("recording", "audio/mp4")
        )
    }

    @Test
    fun `mime takes priority over sniffed bytes when both exist`() {
        assertEquals(
            "song.wav",
            GroqUploadTypes.resolveUploadFileName(
                rawName = "song",
                mimeType = "audio/wav",
                pathSuffixExtension = null,
                sniffedExtension = "mp3"
            )
        )
    }

    @Test
    fun `path suffix is used when mime is unknown`() {
        assertEquals(
            "clip.mp4",
            GroqUploadTypes.resolveUploadFileName(
                rawName = "clip",
                mimeType = null,
                pathSuffixExtension = "mp4",
                sniffedExtension = null
            )
        )
    }

    @Test
    fun `magic bytes rescue a bare temp name`() {
        // temp file named "subtitle_audio_1760000000000" (no ext)
        // whose bytes are a real RIFF/WAVE file.
        assertEquals(
            "subtitle_audio_1760000000000.wav",
            GroqUploadTypes.resolveUploadFileName(
                rawName = "subtitle_audio_1760000000000",
                mimeType = null,
                pathSuffixExtension = null,
                sniffedExtension = "wav"
            )
        )
    }

    @Test
    fun `unknown format fails honestly instead of guessing`() {
        // Nothing identifiable: no extension, no mime, no magic —
        // must return null so the caller REFUSES to upload (the
        // brief forbids blind requests).
        assertNull(
            GroqUploadTypes.resolveUploadFileName("audio", null, null, null)
        )
        // A Groq-unsupported mime (aac) with a nameless source too.
        assertNull(
            GroqUploadTypes.resolveUploadFileName("audio", "audio/aac", null, null)
        )
    }

    @Test
    fun `dot-base name gets a clean base not an empty one`() {
        assertEquals(
            "audio.flac",
            GroqUploadTypes.resolveUploadFileName(".", "audio/flac")
        )
    }

    // ----------------------------------------------------------------
    // sanitization (header-safety)
    // ----------------------------------------------------------------

    @Test
    fun `path separators and quotes are stripped from the filename`() {
        val resolved = GroqUploadTypes.resolveUploadFileName(
            "../../etc/passwd.mp3",
            null
        )
        // All path separators neutralized — no directory traversal
        // can survive in the Content-Disposition header.
        assertTrue(resolved!!.contains(".._.._etc_passwd.mp3"))
        assertTrue(!resolved.contains('/'))
        assertTrue(!resolved.contains('\\'))
        // A quoted name can never split the header.
        val quoted = GroqUploadTypes.sanitizeFileName("my \"song\".mp3")
        assertEquals("my song.mp3", quoted)
    }

    @Test
    fun `crlf injection is neutralized`() {
        val evil = "a\r\nb.mp3"
        val resolved = GroqUploadTypes.resolveUploadFileName(evil, null)
        assertEquals("ab.mp3", resolved)
    }

    @Test
    fun `blank names fall back to the audio base`() {
        assertEquals(
            "audio.mp3",
            GroqUploadTypes.resolveUploadFileName("  ", "audio/mpeg")
        )
    }

    // ----------------------------------------------------------------
    // Content-Type truthfulness
    // ----------------------------------------------------------------

    @Test
    fun `part mime is derived from the final extension`() {
        assertEquals("audio/mpeg", GroqUploadTypes.mimeTypeForExtension("mp3"))
        assertEquals("audio/mpeg", GroqUploadTypes.mimeTypeForExtension("mpga"))
        assertEquals("video/mpeg", GroqUploadTypes.mimeTypeForExtension("mpeg"))
        assertEquals("audio/mp4", GroqUploadTypes.mimeTypeForExtension("m4a"))
        assertEquals("video/mp4", GroqUploadTypes.mimeTypeForExtension("mp4"))
        assertEquals("audio/wav", GroqUploadTypes.mimeTypeForExtension("wav"))
        assertEquals("audio/flac", GroqUploadTypes.mimeTypeForExtension("flac"))
        assertEquals("audio/ogg", GroqUploadTypes.mimeTypeForExtension("ogg"))
        assertEquals("audio/opus", GroqUploadTypes.mimeTypeForExtension("opus"))
        assertEquals("video/webm", GroqUploadTypes.mimeTypeForExtension("webm"))
        assertNull(GroqUploadTypes.mimeTypeForExtension("bin"))
        assertNull(GroqUploadTypes.mimeTypeForExtension(null))
    }

    @Test
    fun `android content mime variants all resolve`() {
        assertEquals("mp3", GroqUploadTypes.extensionForMime("audio/mpeg"))
        assertEquals("m4a", GroqUploadTypes.extensionForMime("audio/x-m4a"))
        assertEquals("wav", GroqUploadTypes.extensionForMime("audio/x-wav"))
        assertEquals("webm", GroqUploadTypes.extensionForMime("video/webm"))
        assertEquals(
            "mp4",
            GroqUploadTypes.extensionForMime("VIDEO/MP4; charset=utf-8")
        )
        assertNull(GroqUploadTypes.extensionForMime("application/octet-stream"))
        assertNull(GroqUploadTypes.extensionForMime(null))
    }

    // ----------------------------------------------------------------
    // magic-number sniffing (real container detection)
    // ----------------------------------------------------------------

    @Test
    fun `id3 tag is detected as mp3`() {
        val head = ByteArray(16)
        head[0] = 'I'.code.toByte(); head[1] = 'D'.code.toByte(); head[2] = '3'.code.toByte()
        assertEquals("mp3", GroqUploadTypes.sniffExtension(head))
    }

    @Test
    fun `mpeg frame sync is detected as mp3`() {
        val head = ByteArray(16)
        head[0] = 0xFF.toByte(); head[1] = 0xFB.toByte()
        assertEquals("mp3", GroqUploadTypes.sniffExtension(head))
    }

    @Test
    fun `riff wave is detected as wav`() {
        val head = ByteArray(16)
        "RIFF".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        "WAVE".forEachIndexed { i, c -> head[8 + i] = c.code.toByte() }
        assertEquals("wav", GroqUploadTypes.sniffExtension(head))
    }

    @Test
    fun `flac magic is detected`() {
        val head = ByteArray(16)
        "fLaC".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        assertEquals("flac", GroqUploadTypes.sniffExtension(head))
    }

    @Test
    fun `ogg container is detected`() {
        val head = ByteArray(16)
        "OggS".forEachIndexed { i, c -> head[i] = c.code.toByte() }
        assertEquals("ogg", GroqUploadTypes.sniffExtension(head))
    }

    @Test
    fun `ftyp box is detected as mp4`() {
        val head = ByteArray(16)
        head[4] = 'f'.code.toByte(); head[5] = 't'.code.toByte()
        head[6] = 'y'.code.toByte(); head[7] = 'p'.code.toByte()
        assertEquals("mp4", GroqUploadTypes.sniffExtension(head))
    }

    @Test
    fun `unknown head returns null - never a fake type`() {
        val head = ByteArray(16) // all zeros
        assertNull(GroqUploadTypes.sniffExtension(head))
        assertNull(GroqUploadTypes.sniffExtension(ByteArray(4)))
    }
}
