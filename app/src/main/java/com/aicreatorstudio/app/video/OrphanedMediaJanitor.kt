package com.aicreatorstudio.app.video

import java.io.File
import java.net.URI

/**
 * Deletes media files that no project references any more (2026-10-05).
 *
 * Deleting a project used to remove only its database rows: the imported
 * copies under `filesDir/media/<uuid>.<ext>` stayed on disk forever, so
 * storage grew unboundedly with every create/delete cycle.
 *
 * Pure file logic with NO Android imports, so the JVM suite can pin the
 * safety rules with temp folders. The caller ([MediaStorageManager])
 * supplies the real media dir and the URIs of the doomed project's clips
 * plus every URI still referenced by surviving projects.
 *
 * ## Safety rules — a file is deleted ONLY when ALL of these hold
 *
 *  1. Its URI parses as a `file:` URI (content:// URIs are never touched).
 *  2. The file resolves inside [mediaDir] (canonical-path comparison, so
 *     `../` escape attempts cannot delete unrelated files).
 *  3. Its canonical path is not in the referenced set.
 */
object OrphanedMediaJanitor {

    /** Deletes unreferenced files; returns how many were removed. */
    fun deleteUnreferenced(
        mediaDir: File,
        candidateUris: List<String>,
        referencedUris: Set<String>
    ): Int {
        if (candidateUris.isEmpty()) return 0
        val referencedPaths = referencedUris.mapNotNullTo(mutableSetOf()) { uri ->
            toFile(uri)?.let { runCatching { it.canonicalFile }.getOrNull() }?.path
        }
        var deleted = 0
        for (uri in candidateUris) {
            val canonical = toFile(uri)?.let { runCatching { it.canonicalFile }.getOrNull() }
                ?: continue
            val parent = canonical.parentFile ?: continue
            val insideMediaDir = runCatching {
                parent.canonicalFile == mediaDir.canonicalFile
            }.getOrDefault(false)
            if (insideMediaDir && canonical.path !in referencedPaths && canonical.delete()) {
                deleted++
            }
        }
        return deleted
    }

    /** Converts a `file:` URI string to a File; null for anything else. */
    private fun toFile(uri: String): File? {
        if (!uri.startsWith("file:", ignoreCase = true)) return null
        return runCatching { File(URI(uri).path) }.getOrNull()
    }
}
