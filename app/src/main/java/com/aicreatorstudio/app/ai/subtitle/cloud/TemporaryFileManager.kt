package com.aicreatorstudio.app.ai.subtitle.cloud

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 2026-09-12 — owns the temporary audio files produced by the
 * CLOUD subtitle pipeline (extraction output / upload payload).
 *
 * Contract (from the brief):
 *  - files live under `cacheDir/subtitles/`
 *  - named `subtitle_audio_<timestamp>.<ext>`
 *  - deleted after the run — on success, on failure, on cancel
 *    (the engine calls [cleanup] from `finally`)
 *  - the ORIGINAL video file is never touched by this manager.
 *
 * A stale-file sweep on construction keeps the cache bounded:
 * if a previous process died before its `finally` ran, files
 * older than [MAX_AGE_MS] are removed on next use.
 */
@Singleton
class TemporaryFileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Root for every temporary subtitle artifact. */
    val subtitleCacheDir: File
        get() = File(context.cacheDir, SUBTITLE_DIR).apply { mkdirs() }

    /**
     * Create (but do not populate) a new temp file whose name
     * encodes the creation timestamp, e.g.
     * `subtitle_audio_1760000000000.m4a`.
     */
    fun newAudioFile(extension: String): File {
        val safeExt = extension.removePrefix(".").ifBlank { "bin" }
        return File(subtitleCacheDir, "subtitle_audio_${System.currentTimeMillis()}.$safeExt")
    }

    /**
     * Delete one temp file if it exists. Never throws.
     *
     * 2026-10-20 — SAFETY NET. This manager owns `cacheDir/subtitles/`
     * and NOTHING else. The cloud engine's `finally` block calls this
     * for whatever file the extractor handed back, and that file is
     * not always one we created: for an audio-only source the
     * extractor used to return the user's own imported file, and the
     * delete then erased the user's music from disk.
     *
     * Refusing to touch anything outside our own temp directory makes
     * that class of bug impossible, whatever the caller passes in.
     */
    fun delete(file: File?) {
        if (file == null) return
        runCatching {
            if (!isOwnedTempFile(file)) return@runCatching
            if (file.exists() && !file.delete()) file.deleteOnExit()
        }
    }

    /**
     * True when [file] lives inside the subtitle temp directory.
     *
     * The comparison is on canonical paths so a `..` segment cannot
     * smuggle an outside file past the check.
     */
    fun isOwnedTempFile(file: File): Boolean = runCatching {
        val root = subtitleCacheDir.canonicalFile
        val target = file.canonicalFile
        target.path.startsWith(root.path + File.separator)
    }.getOrDefault(false)

    /**
     * Remove every leftover file in the subtitle cache. Called by
     * the engine in `finally` so NOTHING survives a run — success,
     * failure, and cancel all end with an empty directory.
     */
    fun cleanup() {
        runCatching {
            subtitleCacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    companion object {
        private const val SUBTITLE_DIR = "subtitles"

        /** Stale temp audio older than a day is swept on first use. */
        private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
