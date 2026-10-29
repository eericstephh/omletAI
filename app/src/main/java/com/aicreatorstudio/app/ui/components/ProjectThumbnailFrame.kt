package com.aicreatorstudio.app.ui.components

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime cache for Home project thumbnails, keyed by the clip
 * URI. 96px-wide frames are tiny (~30-60 KB each), so caching per process
 * keeps list scrolling free of re-decodes without any eviction complexity.
 *
 * 2026-10-20 — the cache lives behind a `by lazy` holder.
 *
 * It used to be a top-level `private val`, which compiles to a STATIC
 * field initialised in the file class's `<clinit>`. That is exactly
 * the shape R8's class merging can reorder: the field can end up
 * uninitialised (null) when the write below runs, and the release
 * build then died with
 *
 * ```
 * NullPointerException: Attempt to invoke virtual method
 * 'java.lang.Object java.util.concurrent.ConcurrentHashMap.put(...)'
 * on a null object reference
 * ```
 *
 * on the IO worker that decodes the thumbnails — i.e. the Home
 * screen. A `by lazy` holder is initialised on FIRST ACCESS, under
 * a lock, so its value cannot be observed as null no matter how R8
 * arranges the static initialisers.
 */
private object ProjectThumbnailCache {
    val frames: ConcurrentHashMap<String, ImageBitmap> = ConcurrentHashMap()
}

/**
 * 2026-10-05 — REAL project thumbnail for the Home list (B4).
 *
 * Decodes the first sync frame of [uri] off the main thread (once per
 * process, cached by URI) and draws it cropped into [modifier].
 *
 * While decoding — or when there is no video clip, or the decode failed —
 * renders [fallback], which is the card's original gradient+icon box, so
 * the worst case is exactly the pre-B4 look. Decode failures never
 * surface as errors.
 */
@Composable
fun ProjectThumbnailFrame(
    uri: String?,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit
) {
    if (uri.isNullOrBlank()) {
        Box(modifier = modifier) { fallback() }
        return
    }

    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf(ProjectThumbnailCache.frames[uri]) }

    LaunchedEffect(uri) {
        if (bitmap != null) return@LaunchedEffect
        val decoded: ImageBitmap? = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context.applicationContext, Uri.parse(uri))
                    retriever.getFrameAtTime(
                        0L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )?.let { frame ->
                        val targetWidth = 96
                        val scale = targetWidth.toFloat() / frame.width.coerceAtLeast(1)
                        val targetHeight = (frame.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(frame, targetWidth, targetHeight, true)
                            .asImageBitmap()
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
        }
        if (decoded != null) {
            ProjectThumbnailCache.frames[uri] = decoded
            bitmap = decoded
        }
    }

    Box(modifier = modifier) {
        val frame = bitmap
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            fallback()
        }
    }
}
