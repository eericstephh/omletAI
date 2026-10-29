package com.aicreatorstudio.app.video

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aicreatorstudio.app.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app-private media library (`filesDir/media/`).
 *
 * Why this exists — the project-restoration bug: media picked through
 * the photo picker / document picker comes back as a `content://` URI
 * backed by a *transient* read grant. The grant dies when the app task
 * is dismissed (or the device reboots), so any project that stored the
 * raw picker URI showed a black preview and refused to play after
 * SAVE → EXIT → REOPEN.
 *
 * The fix: as soon as media is picked we copy the bytes into our own
 * storage and persist the `file://` URI instead. Importing keeps the
 * original extension when the provider exposes a real display name so
 * downstream tooling (timeline, export engine) keeps recognizing the
 * file. [isMediaAccessible] is the load-time probe that powers the
 * "Media Missing / Relink" state for legacy rows that still carry a
 * dead picker URI.
 */
@Singleton
class MediaStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) {

    private val mediaDir: File
        get() = File(context.filesDir, MEDIA_DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * Copies the media at [sourceUri] into app-owned storage and returns
     * a `file://` URI that stays valid for the lifetime of the install.
     *
     * @throws IllegalStateException when the source cannot be opened
     * @throws java.io.IOException when the copy fails mid-stream
     */
    suspend fun importToAppStorage(sourceUri: Uri): Uri = withContext(dispatchers.io) {
        val displayName = queryDisplayName(sourceUri)
        val extension = extensionFor(sourceUri, displayName)
        val destination = File(mediaDir, "${UUID.randomUUID()}$extension")

        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw IllegalStateException("Cannot open media: $sourceUri")
        input.use { stream ->
            destination.outputStream().use { output -> stream.copyTo(output) }
        }
        Uri.fromFile(destination)
    }

    /**
     * Cheap readability probe used at project load to detect clips whose
     * backing URI is no longer reachable (revoked picker grants, deleted
     * files, cleared caches). Never throws; returns `false` when the
     * media cannot be opened for reading. `file://` URIs inside the app's
     * own media directory are the normal post-fix case and simply hit
     * the filesystem.
     */
    suspend fun isMediaAccessible(uriString: String): Boolean = withContext(dispatchers.io) {
        if (uriString.isBlank()) return@withContext false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext false
        when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return@withContext false
                val file = File(path)
                file.exists() && file.canRead()
            }
            "content" -> runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@runCatching false
                pfd.close()
                true
            }.getOrDefault(false)
            // App resources (built-in SFX) and anything the platform
            // resolves internally are assumed reachable — flagging those
            // would show false "media missing" states.
            else -> true
        }
    }

    /**
     * 2026-10-05 — orphaned-media cleanup after a project deletion.
     *
     * [candidateUris] are the deleted project's clip URIs; [referencedUris]
     * are every clip URI still referenced by surviving projects. Delegates
     * to [OrphanedMediaJanitor] (pure, unit-tested) with the real media
     * dir, and swallows failures — storage cleanup must never turn a
     * successful project deletion into an error.
     */
    fun deleteUnreferencedProjectMedia(candidateUris: List<String>, referencedUris: Set<String>) {
        runCatching {
            OrphanedMediaJanitor.deleteUnreferenced(mediaDir, candidateUris, referencedUris)
        }
    }

    /** Best-effort display-name lookup (real file name when available). */
    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        var cursor: android.database.Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            val column = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME) ?: -1
            if (column >= 0 && cursor?.moveToFirst() == true) cursor.getString(column) else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Resolves the destination extension. Photo picker entries sometimes
     * carry opaque ids (`video:42`) instead of names, so fall back to the
     * MIME type. The extension is cosmetic — players sniff the container —
     * but keeping it recognizable makes debugging far easier.
     */
    private fun extensionFor(uri: Uri, displayName: String?): String {
        val fromName = displayName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 && !it.contains('/') }
            ?.lowercase()
        if (fromName != null) return ".$fromName"

        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return when {
            mime?.startsWith("video/") == true -> ".mp4"
            mime?.startsWith("audio/") == true -> ".m4a"
            mime?.startsWith("image/") == true -> ".jpg"
            else -> ".mp4"
        }
    }

    private companion object {
        const val MEDIA_DIR_NAME = "media"
    }
}
