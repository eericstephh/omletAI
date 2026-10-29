package com.aicreatorstudio.app.ai.groq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-20 — guards for the two failure modes that made the Groq
 * cloud subtitle feature unusable (and destructive).
 *
 * ## The upload never left the device
 *
 * `GroqWhisperClient.querySize` read the length from an
 * `AssetFileDescriptor`. For a `file://` URI the platform ALWAYS
 * reports `AssetFileDescriptor.UNKNOWN_LENGTH` (-1) — see
 * `ContentResolver.openAssetFileDescriptor`, which builds
 * `new AssetFileDescriptor(pfd, 0, -1)` for the `file` scheme.
 *
 * The cloud engine uploads a local temp file (`Uri.fromFile(...)`), so
 * the size check failed for EVERY request and the run died before
 * touching the network with "حجم فایل قابل خواندن نیست — فایل را دوباره
 * انتخاب کنید" — a message about a file the user cannot even pick.
 *
 * ## The engine deleted the user's own audio
 *
 * When the source was an audio-only file, `SubtitleAudioExtractor`
 * returned the USER'S file (not a copy) and the engine's `finally`
 * block deleted it. Importing a music track and running cloud
 * subtitles erased the music from disk and broke the project lane.
 *
 * The extractor now always hands back a file it owns, and
 * `TemporaryFileManager.delete` refuses to touch anything outside its
 * own temp directory.
 *
 * `android.net.Uri` is stubbed to `null` in the JVM test stack, so the
 * assertions target the pure helpers plus a structural check of the
 * call sites.
 */
class GroqCloudUploadTest {

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("settings.gradle.kts not found above ${File(".").absolutePath}")
    }

    private fun source(relative: String): String {
        val f = File(repoRoot(), relative)
        assertTrue("missing file: $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `the local file size is the real length, not the platform's unknown length`() {
        val tmp = File.createTempFile("groq-size", ".m4a")
        try {
            tmp.writeBytes(ByteArray(4096) { it.toByte() })
            assertEquals(
                "the upload size for a local file must be its real length — " +
                    "AssetFileDescriptor reports UNKNOWN_LENGTH (-1) for file:// URIs",
                4096L, GroqWhisperClient.localFileSize(tmp.absolutePath)
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `a missing local file reports an unusable size instead of a fake one`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "definitely-not-here.m4a")
        assertTrue(
            "a missing file must not report a positive size",
            GroqWhisperClient.localFileSize(missing.absolutePath) <= 0L
        )
    }

    @Test
    fun `querySize resolves a file uri through the filesystem`() {
        val src = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/groq/GroqWhisperClient.kt"
        )
        assertTrue(
            "the file:// branch must read the real length from the filesystem",
            src.contains("val len = localFileSize(path)")
        )
        assertTrue(
            "the old broken read of AssetFileDescriptor.length must be gone from the " +
                "file:// path — the platform reports -1 for local files",
            !src.contains("openAssetFileDescriptor(uri, \"r\")?.use { it.length }")
        )
    }

    @Test
    fun `the cloud engine never deletes a file it did not create`() {
        val extractor = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/subtitle/cloud/SubtitleAudioExtractor.kt"
        )
        assertTrue(
            "an audio-only source must be COPIED into the temp directory — " +
                "handing back the user's own file let the engine delete it",
            extractor.contains("tempFiles.newAudioFile(ext)")
        )
        assertTrue(
            "the source file must be copied, not referenced",
            extractor.contains("input.copyTo(output)")
        )

        val manager = source(
            "app/src/main/java/com/aicreatorstudio/app/ai/subtitle/cloud/TemporaryFileManager.kt"
        )
        assertTrue(
            "the delete guard must check that the file belongs to the temp directory",
            manager.contains("if (!isOwnedTempFile(file)) return@runCatching")
        )
        assertTrue(
            "the ownership check must exist",
            manager.contains("fun isOwnedTempFile(file: File): Boolean")
        )
    }
}
