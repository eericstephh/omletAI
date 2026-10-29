package com.aicreatorstudio.app.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoDimensions
import com.aicreatorstudio.app.util.DispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * Reads the metadata this app cares about (name, duration, resolution,
 * size) from a video the user picked, without decoding or touching any
 * actual frame data. This is metadata-only — the video/editing pipeline
 * itself is built later on top of what gets saved here.
 */
class VideoMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) {

    /**
     * @throws Exception if the URI can't be read as a video (e.g. corrupt
     *   file, revoked permission, unsupported format). Callers decide how
     *   to surface that to the user.
     */
    suspend fun extract(uri: Uri): VideoClip = withContext(dispatchers.io) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val rawWidth = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawHeight = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            // ---- 2026-09-30 orientation fix ----
            //
            // Display dimensions = coded frame + rotation, and the swap
            // itself now lives in `model.VideoDimensions` so it is
            // JVM-testable and can be cross-checked. Two things changed
            // here and both matter:
            //
            //  * the rotation is normalised (`-90`, `270`, `450` all fold
            //    into `[0, 360)`), so a quarter turn can no longer be
            //    dropped by an exact `== 90` test;
            //
            //  * the container's rotation metadata is VERIFIED against one
            //    actually-decoded frame before it is trusted. On a
            //    meaningful set of devices/containers
            //    `METADATA_KEY_VIDEO_WIDTH/HEIGHT` already carry the
            //    rotation, and swapping again then flips a landscape clip
            //    into portrait — the exact asymmetry this fixes. A
            //    `getFrameAtTime` bitmap has rotation applied, so its
            //    orientation is ground truth.
            //
            // The probe is best-effort and advisory: it can only decide
            // orientation, never pixel counts, and a failure falls back to
            // the metadata reading rather than failing the import.
            val (probeWidth, probeHeight) = runCatching {
                val frame = retriever.getFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
                val w = frame?.width ?: 0
                val h = frame?.height ?: 0
                frame?.recycle()
                w to h
            }.getOrDefault(0 to 0)

            val (displayWidth, displayHeight) = VideoDimensions.resolve(
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                rotation = rotation,
                probeWidth = probeWidth,
                probeHeight = probeHeight
            )

            val (fileName, fileSize) = queryNameAndSize(uri)

            VideoClip(
                id = UUID.randomUUID().toString(),
                uri = uri.toString(),
                fileName = fileName,
                durationMs = durationMs,
                width = displayWidth,
                height = displayHeight,
                fileSizeBytes = fileSize,
                importedAtEpochMillis = System.currentTimeMillis()
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * [MediaMetadataRetriever] doesn't expose display name or file size, so
     * those come from the content resolver via the standard [OpenableColumns].
     */
    private fun queryNameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "Untitled video"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }
}
