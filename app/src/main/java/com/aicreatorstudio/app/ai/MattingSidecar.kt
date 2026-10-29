package com.aicreatorstudio.app.ai

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/**
 * Owns the on-disk layout for per-frame matting alpha masks.
 *
 * ## Layout
 *
 * ```
 * <filesDir>/projects/<projectId>/matting/<clipId>/manifest.json
 * <filesDir>/projects/<projectId>/matting/<clipId>/<timestampMs>.png
 * <filesDir>/projects/<projectId>/matting/<clipId>/<timestampMs>.png
 * <filesDir>/projects/<projectId>/matting/<clipId>/<timestampMs>.png
 * ...
 * ```
 *
 * Each PNG is a single-channel alpha bitmap. 2026-10-17: the PNG is
 * sized to the engine's **bounded analysis resolution** (~720 px max
 * side), not necessarily the source resolution — the export
 * pre-renderer rescales every PNG to the frame it composites anyway
 * (one bilinear resample at composite time), so holding full-source
 * PNGs only burned CPU/memory. The PNG path is the source-clip
 * timestamp in milliseconds, so a fresh inference pass overwrites the
 * old frames atomically (the manifest is rewritten last).
 *
 * The project JSON stays small: it carries only the [com.aicreatorstudio.app.model.MattingSettings]
 * (threshold, feather, replacement background URI, algorithm id).
 * The per-frame alpha data is rebuilt from the source video on
 * demand and re-verified against [ManifestEntry.sha256] at load time.
 *
 * ## Why a sidecar instead of a Room blob
 *
 * - Bitmaps do not survive a JSON column without Base64 (4/3x bloat).
 * - A per-frame file lets the export pipeline seek to the right
 *   frame without decoding everything.
 * - The `filesDir` path means Android may evict it under storage
 *   pressure, in which case the manifest verification fails and the
 *   UI tells the user to re-run the engine.
 *
 * ## Threading
 *
 * All functions are blocking file I/O. Callers should wrap them in
 * `withContext(Dispatchers.IO)`.
 */
object MattingSidecar {

    /**
     * 2026-10-20 — file names and the staging/backup suffixes used by
     * the atomic write pass.
     */
    private const val MANIFEST_NAME = "manifest.json"
    private const val STAGING_SUFFIX = ".staging"
    private const val BACKUP_SUFFIX = ".old"

    /**
     * Directory containing the manifest + per-frame PNGs for a single
     * (project, clip) pair. Created on demand; safe to call when the
     * engine has never been run for this clip (the directory simply
     * does not exist).
     */
    fun directoryFor(context: Context, projectId: String, clipId: String): File {
        val root = File(context.filesDir, "projects/$projectId/matting/$clipId")
        if (!root.exists()) root.mkdirs()
        return root
    }

    /**
     * Returns the PNG file at the first timestamp **at or after**
     * [sourceLocalMs] in [dir], or `null` if the directory is empty or
     * missing.
     *
     * 2026-10-20 — RENAMED. The old name was `nearestTimestampPng` and
     * its KDoc claimed "the closest entry", but the implementation
     * returned the *ceiling* (the first mask at or after the target),
     * not the nearest. The export path relies on the ceiling behaviour
     * — the mask computed from a picture at least as recent as the
     * frame — so the BEHAVIOUR was right and the NAME was wrong. A
     * helper called "nearest" that returns the ceiling is exactly the
     * kind of trap that makes a future caller pick the wrong mask.
     *
     * The export path no longer uses this helper at all (it indexes
     * the directory once and binary-searches — see
     * [com.aicreatorstudio.app.export.MaskIndexMath]); it is kept for
     * single-shot lookups.
     */
    fun firstTimestampAtOrAfterPng(dir: File, sourceLocalMs: Long): File? {
        if (!dir.exists() || !dir.isDirectory) return null
        val candidates = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }
            ?: return null
        if (candidates.isEmpty()) return null
        // Sort by the parsed timestamp, not by name: "1000.png" sorts
        // before "900.png" as a string, and a stray non-numeric file
        // must be ignored rather than treated as timestamp 0.
        val sorted = candidates
            .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { it to f } }
            .sortedBy { it.first }
            .map { it.second }
        if (sorted.isEmpty()) return null
        var idx = sorted.size // default: take the last
        for (i in sorted.indices) {
            val ts = sorted[i].nameWithoutExtension.toLongOrNull() ?: continue
            if (ts >= sourceLocalMs) { idx = i; break }
        }
        val pickIdx = idx.coerceIn(0, sorted.size - 1)
        return sorted[pickIdx]
    }

    /**
     * Writes a fresh manifest.json describing the per-frame mask
     * PNGs that were just produced. The manifest carries:
     *
     *   - `algorithm` - the engine id that produced the masks
     *     (e.g. "mediapipe-selfie-multiclass-v1")
     *   - `modelSha256` - the SHA-256 of the model file used. The
     *     live preview / export pipeline refuses to consume the masks
     *     when the model on disk has been swapped.
     *   - `sourceWidth` / `sourceHeight` - the source frame size
     *   - `intervalMs` - the sampling interval used to produce the
     *     frames (e.g. 100 = 10 fps). The preview uses this to
     *     extrapolate between samples.
     *   - `frameSha256s` - the SHA-256 of each PNG, keyed by the
     *     timestamp. Lets [verify] detect bit-rot without reading
     *     the full bitmap.
     *
     * @return the manifest file, so callers can log / display its
     *   path on error.
     */
    fun writeManifest(
        context: Context,
        projectId: String,
        clipId: String,
        algorithm: String,
        modelSha256: String?,
        sourceWidth: Int,
        sourceHeight: Int,
        intervalMs: Long,
        frames: List<FrameWrite>
    ): File {
        // 2026-10-20 — atomic: the frames are staged and promoted at
        // the end, so a failure cannot destroy the previous result.
        val staging = beginWritePass(context, projectId, clipId)
        val written = frames.map { f -> writeFramePng(staging, f.timestampMs, f.bitmap) }
        return finalizeManifest(
            dir = staging,
            algorithm = algorithm,
            modelSha256 = modelSha256,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            intervalMs = intervalMs,
            frames = written
        )
    }

    /**
     * Start a **streaming** sidecar pass and return the directory the
     * engine should write frames into.
     *
     * The engine calls [writeFramePng] per analysed frame (bounded
     * memory: only one mask bitmap is alive at a time) and
     * [finalizeManifest] once at the end.
     *
     * 2026-10-20 — ATOMIC PASS.
     *
     * The old implementation deleted the previous PNGs up front, so
     * the engine wrote straight into the live directory. A crash, an
     * OOM or a process death between the first and the last frame
     * left the project with **no masks at all** — the previously
     * applied Background Remove result was destroyed by a failed
     * re-run, and the export then failed with "masks are missing or
     * outdated".
     *
     * The pass now writes into a **sibling staging directory**. The
     * live directory stays intact and readable for the whole run;
     * [finalizeManifest] swaps the staged directory in with two
     * renames, which are atomic on the same filesystem.
     */
    fun beginWritePass(context: Context, projectId: String, clipId: String): File {
        val liveDir = directoryFor(context, projectId, clipId)
        // Clear any leftover staging dir from a previous crashed run.
        val staging = File(liveDir.parentFile, liveDir.name + STAGING_SUFFIX)
        staging.listFiles()?.forEach { runCatching { it.delete() } }
        runCatching { staging.delete() }
        staging.mkdirs()
        return staging
    }

    /**
     * Swap the staged pass into the live sidecar directory.
     *
     * Two renames, both atomic on the same filesystem:
     * the live directory becomes `<clipId>.old` and the staged one
     * becomes `<clipId>`. The backup is then deleted. A reader that
     * arrives in the microsecond window sees no live directory at all
     * and treats the masks as unavailable — which is safe, unlike the
     * old behaviour where a failed run left a half-written set of
     * masks that the export would happily composite.
     */
    private fun promoteStagingPass(stagingDir: File): File {
        val parent = stagingDir.parentFile
            ?: return File(stagingDir, MANIFEST_NAME)
        val liveDir = File(parent, stagingDir.name.removeSuffix(STAGING_SUFFIX))
        val backup = File(parent, liveDir.name + BACKUP_SUFFIX)
        runCatching {
            backup.listFiles()?.forEach { it.delete() }
            backup.delete()
        }
        if (liveDir.exists()) {
            val moved = runCatching { liveDir.renameTo(backup) }.getOrDefault(false)
            if (!moved) {
                // Rename failed (e.g. the directory is in use) — fall
                // back to a copy so the pass still completes.
                backup.mkdirs()
                liveDir.listFiles()?.forEach { f ->
                    runCatching { f.copyTo(File(backup, f.name), overwrite = true) }
                }
                liveDir.listFiles()?.forEach { runCatching { it.delete() } }
            }
        }
        val promoted = runCatching { stagingDir.renameTo(liveDir) }.getOrDefault(false)
        if (!promoted) {
            stagingDir.listFiles()?.forEach { f ->
                runCatching { f.copyTo(File(liveDir, f.name), overwrite = true) }
            }
        }
        runCatching {
            backup.listFiles()?.forEach { it.delete() }
            backup.delete()
        }
        return File(liveDir, MANIFEST_NAME)
    }

    /**
     * One streamed frame entry. [fileName] / [sha256] are recorded
     * while the file is on hand so the final manifest does not need
     * to re-read anything.
     */
    data class WrittenFrame(
        val timestampMs: Long,
        val fileName: String,
        val sha256: String
    )

    /**
     * Persist one mask PNG **at the bitmap's own size** (the engine
     * already bounds the analysis resolution) and hash it. The export
     * rescales each PNG to the frame it composites — the same single
     * bilinear resample the old full-resolution sidecar produced,
     * minus the full-resolution bitmap + compress cost per frame.
     */
    fun writeFramePng(dir: File, timestampMs: Long, bitmap: Bitmap): WrittenFrame {
        val png = File(dir, "$timestampMs.png")
        png.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return WrittenFrame(
            timestampMs = timestampMs,
            fileName = png.name,
            sha256 = sha256OfFile(png)
        )
    }

    /**
     * Write the manifest for a streamed pass. Content-identical to
     * what [writeManifest] produces; only the write order differs.
     *
     * 2026-10-20 — when [dir] is a staging directory (as returned by
     * [beginWritePass]) the manifest is written there and the whole
     * pass is then promoted into the live sidecar directory. The live
     * directory keeps its previous (valid) masks until that moment.
     */
    fun finalizeManifest(
        dir: File,
        algorithm: String,
        modelSha256: String?,
        sourceWidth: Int,
        sourceHeight: Int,
        intervalMs: Long,
        frames: List<WrittenFrame>
    ): File {
        val isStaging = dir.name.endsWith(STAGING_SUFFIX) && dir.parentFile != null
        if (isStaging) {
            val stagingManifest = finalizeManifestIn(dir, frames, algorithm, modelSha256, sourceWidth, sourceHeight, intervalMs)
            // The manifest was written into the staging dir; promote
            // every file (frames + manifest) into the live dir.
            promoteStagingPass(dir)
            return stagingManifest
        }
        return finalizeManifestIn(dir, frames, algorithm, modelSha256, sourceWidth, sourceHeight, intervalMs)
    }

    private fun finalizeManifestIn(
        dir: File,
        frames: List<WrittenFrame>?,
        algorithm: String,
        modelSha256: String?,
        sourceWidth: Int,
        sourceHeight: Int,
        intervalMs: Long
    ): File {
        val manifestEntries = (frames ?: emptyList())
            .map { f -> ManifestEntry(f.timestampMs, f.fileName, f.sha256) }
        val manifest = Manifest(
            algorithm = algorithm,
            modelSha256 = modelSha256,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            intervalMs = intervalMs,
            frames = manifestEntries
        )
        val out = File(dir, MANIFEST_NAME)
        out.writeText(manifest.encode())
        return out
    }

    /**
     * Read the manifest for [projectId]/[clipId] and verify every
     * referenced PNG against its stored SHA-256. Returns `true` when
     * the manifest is present and every frame's hash matches. Returns
     * `false` on any read error, JSON parse failure, or hash mismatch
     * - callers MUST treat the masks as untrusted in that case.
     */
    fun verify(
        context: Context,
        projectId: String,
        clipId: String,
        expectedModelSha: String?
    ): Boolean {
        val dir = directoryFor(context, projectId, clipId)
        val manifestFile = File(dir, MANIFEST_NAME)
        if (!manifestFile.exists()) return false
        val manifest = try {
            Manifest.decode(manifestFile.readText())
        } catch (_: Throwable) {
            return false
        }
        // 2026-10-20 — a manifest with NO frames is not a valid
        // sidecar. The old check looped over an empty list, completed
        // trivially and returned `true`, so an empty sidecar was
        // treated as "masks are ready" and the export composited
        // nothing at all.
        if (manifest.frames.isEmpty()) return false
        if (expectedModelSha != null &&
            manifest.modelSha256 != null &&
            !manifest.modelSha256.equals(expectedModelSha, ignoreCase = true)
        ) {
            return false
        }
        for (entry in manifest.frames) {
            val png = File(dir, entry.fileName)
            if (!png.exists()) return false
            if (sha256OfFile(png).equals(entry.sha256, ignoreCase = true).not()) {
                return false
            }
        }
        return true
    }

    /**
     * Delete the entire matting sidecar for a clip. Idempotent.
     * Called when a clip is removed from the project so the orphaned
     * PNGs do not keep consuming storage.
     */
    fun delete(context: Context, projectId: String, clipId: String) {
        val dir = File(context.filesDir, "projects/$projectId/matting/$clipId")
        if (dir.exists()) {
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            runCatching { dir.delete() }
        }
    }

    /**
     * Delete all matting sidecars for a project whose `clipId` is not
     * in [validClipIds]. Used at app exit / project open to garbage
     * collect orphans left by clip deletions.
     */
    fun cleanupOrphans(context: Context, projectId: String, validClipIds: Set<String>) {
        val root = File(context.filesDir, "projects/$projectId/matting")
        if (!root.exists()) return
        val children = root.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory && child.name !in validClipIds) {
                child.listFiles()?.forEach { runCatching { it.delete() } }
                runCatching { child.delete() }
            }
        }
    }

    /**
     * Compute the SHA-256 of [spec]'s model file as currently on
     * disk. Returns `null` if the file is missing or cannot be read.
     * Used by the matting engine to record the model identity in
     * the manifest, and by [verify] to detect model swaps.
     */
    fun modelSha256OrNull(context: Context, spec: AiModelSpec): String? {
        val modelFile = File(context.filesDir, "models/${spec.dirName}/${spec.fileName}")
        if (!modelFile.exists()) return null
        return try {
            sha256OfFile(modelFile)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * [CatalogEntry] overload: same layout (`filesDir/models/...`),
     * used by engines on the centralized [AiModelCatalog] path.
     */
    fun modelSha256OrNull(context: Context, spec: CatalogEntry): String? {
        val modelFile = AiModels.modelFile(context, spec)
        if (!modelFile.exists()) return null
        return try {
            sha256OfFile(modelFile)
        } catch (_: Throwable) {
            null
        }
    }

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- Wire format ----

    /**
     * One frame the engine wants to persist. [timestampMs] is the
     * source-local time at which [bitmap] was sampled.
     */
    data class FrameWrite(val timestampMs: Long, val bitmap: Bitmap)

    private data class ManifestEntry(
        val timestampMs: Long,
        val fileName: String,
        val sha256: String
    )

    private data class Manifest(
        val algorithm: String,
        val modelSha256: String?,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val intervalMs: Long,
        val frames: List<ManifestEntry>
    ) {
        /**
         * Hand-rolled JSON to avoid pulling a JSON dep into the AI
         * package. The shape is small and stable.
         */
        fun encode(): String {
            val sb = StringBuilder()
            sb.append('{')
            sb.append("\"algorithm\":").append(jsonString(algorithm)).append(',')
            sb.append("\"modelSha256\":").append(jsonStringOrNull(modelSha256)).append(',')
            sb.append("\"sourceWidth\":").append(sourceWidth).append(',')
            sb.append("\"sourceHeight\":").append(sourceHeight).append(',')
            sb.append("\"intervalMs\":").append(intervalMs).append(',')
            sb.append("\"frames\":[")
            for ((i, f) in frames.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append("{\"timestampMs\":").append(f.timestampMs)
                sb.append(",\"fileName\":").append(jsonString(f.fileName))
                sb.append(",\"sha256\":").append(jsonString(f.sha256))
                sb.append('}')
            }
            sb.append("]}")
            return sb.toString()
        }

        companion object {
            /**
             * Parse the hand-rolled JSON. Tolerates extra whitespace
             * and the field shape we own. Anything we do not
             * recognise is skipped (forward-compat).
             */
            fun decode(text: String): Manifest {
                val obj = MiniJson.parseObject(text)
                val frames = (obj["frames"] as? List<*>)
                    ?.mapNotNull { entry ->
                        val map = entry as? Map<String, Any?> ?: return@mapNotNull null
                        val ts = (map["timestampMs"] as? Number)?.toLong() ?: return@mapNotNull null
                        val fn = map["fileName"] as? String ?: return@mapNotNull null
                        val sh = map["sha256"] as? String ?: return@mapNotNull null
                        ManifestEntry(ts, fn, sh)
                    }
                    ?: emptyList()
                return Manifest(
                    algorithm = obj["algorithm"] as? String ?: "",
                    modelSha256 = obj["modelSha256"] as? String,
                    sourceWidth = (obj["sourceWidth"] as? Number)?.toInt() ?: 0,
                    sourceHeight = (obj["sourceHeight"] as? Number)?.toInt() ?: 0,
                    intervalMs = (obj["intervalMs"] as? Number)?.toLong() ?: 0L,
                    frames = frames
                )
            }
        }
    }

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun jsonStringOrNull(s: String?): String =
        s?.let { jsonString(it) } ?: "null"
}

/**
 * Tiny JSON reader that handles just the four shapes this package
 * produces: objects, arrays, strings, numbers, and null. Enough to
 * round-trip our hand-rolled manifest; not a general-purpose parser.
 */
internal object MiniJson {

    fun parseObject(text: String): Map<String, Any?> {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (p.hasMore()) throw IllegalArgumentException("trailing data at ${p.pos}")
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: throw IllegalArgumentException("expected object root")
    }

    private class Parser(val text: String) {
        var pos = 0
        fun hasMore() = pos < text.length
        fun skipWs() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }
        fun readValue(): Any? {
            skipWs()
            if (pos >= text.length) throw IllegalArgumentException("unexpected end")
            return when (text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBool()
                'n' -> readNull()
                else -> readNumber()
            }
        }
        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWs()
                val key = readString()
                skipWs(); expect(':')
                val value = readValue()
                out[key] = value
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected , or } at $pos")
                }
            }
        }
        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') { pos++; return out }
            while (true) {
                val v = readValue()
                out += v
                skipWs()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected , or ] at $pos")
                }
            }
        }
        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (pos >= text.length) break
                    when (val esc = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        // 2026-10-20 — the form feed was written as a
                        // LITERAL control character in the source, which
                        // is correct but invisible: any tool that
                        // normalises or strips control characters would
                        // silently turn it into an empty string and
                        // every `\f` in a manifest would vanish. The
                        // explicit escape says the same thing and
                        // survives editing.
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > text.length) break
                            val hex = text.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> sb.append(esc)
                    }
                } else sb.append(c)
            }
            throw IllegalArgumentException("unterminated string")
        }
        private fun readBool(): Boolean {
            return if (text.startsWith("true", pos)) { pos += 4; true }
            else if (text.startsWith("false", pos)) { pos += 5; false }
            else throw IllegalArgumentException("expected bool at $pos")
        }
        private fun readNull(): Any? {
            if (text.startsWith("null", pos)) { pos += 4; return null }
            throw IllegalArgumentException("expected null at $pos")
        }
        private fun readNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val s = text.substring(start, pos)
            return if (s.contains('.') || s.contains('e') || s.contains('E')) {
                s.toDouble()
            } else s.toLong()
        }
        private fun expect(c: Char) {
            if (pos >= text.length || text[pos] != c) {
                throw IllegalArgumentException("expected '$c' at $pos")
            }
            pos++
        }
        private fun peek(): Char = if (pos < text.length) text[pos] else ' '
    }
}
