package com.aicreatorstudio.app.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.aicreatorstudio.app.ai.MaskPostProcess
import com.aicreatorstudio.app.ai.MattingPreviewPlate
import com.aicreatorstudio.app.ai.MattingSidecar
import com.aicreatorstudio.app.export.MaskIndexMath
import com.aicreatorstudio.app.model.MattingBackgroundMode
import com.aicreatorstudio.app.model.VideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Draws the Background Remove result **in the live preview**.
 *
 * ## What it is
 *
 * A flat-colour bitmap with a hole where the subject is, drawn over the
 * video: the inverse of the mask, filled with the mode's colour. That is
 * the same composite the export performs for the removed area, expressed
 * as one layer instead of a frame rewrite — which is the only route
 * available when the video is a `TextureView` (see the long "Layer 2.5"
 * note in `EditorScreen`; nothing here captures the video's layer, so
 * that failure mode is not re-opened).
 *
 * ## What it is not
 *
 * `BLUR`, `IMAGE` and `VIDEO` backgrounds are **not** previewed — they
 * need the background's own content, not a colour. [MattingPreviewPlate.isPreviewable]
 * is the single list of what is covered, and the panel's hint still says
 * the export is authoritative.
 *
 * ## Cost control
 *
 * The sidecar is sampled at 10 fps, so the mask is only re-resolved when
 * the quantised playhead moves 100 ms. Results are kept in a small cache
 * keyed by file path + the three post-process knobs, so scrubbing back
 * and forth re-uses a decoded plate instead of decoding the PNG again.
 */
@Composable
fun MattingPreviewOverlay(
    projectId: String?,
    clip: VideoClip,
    projectPositionMs: Long,
    canvasFillArgb: Int,
    modifier: Modifier = Modifier
) {
    val settings = clip.matting
    if (projectId == null || !settings.enabled) return
    if (!MattingPreviewPlate.isPreviewable(settings.backgroundMode)) return

    // TRANSPARENT means "fill with the canvas colour" — the same resolver
    // the export uses (`CanvasLetterbox.fadeColorArgb`), so the plate
    // matches the letterbox bars behind it. COLOR is the user's swatch.
    val fillArgb = if (settings.backgroundMode == MattingBackgroundMode.COLOR) {
        settings.backgroundColor
    } else {
        canvasFillArgb
    }

    val context = LocalContext.current
    val sourceMs = MattingPreviewPlate.sourcePositionMs(
        projectPositionMs = projectPositionMs,
        timelineStartMs = clip.timelineStartMs,
        trimStartMs = clip.trimStartMs
    )
    // 100 ms is the sidecar's own sampling step, so this is the finest
    // granularity the data can support.
    val bucket = MattingPreviewPlate.quantise(sourceMs)

    val plate by produceState<ImageBitmap?>(
        initialValue = null,
        // One composite key: `produceState` only offers overloads up to
        // five separate keys, and every one of these must restart it.
        key1 = listOf(
            projectId, clip.id, bucket,
            settings.expansion, settings.feather, settings.reverse,
            settings.backgroundMode, fillArgb
        )
    ) {
        value = withContext(Dispatchers.IO) {
            PlateCache.get(
                context = context,
                projectId = projectId,
                clipId = clip.id,
                sourceMs = bucket,
                expansion = settings.expansion,
                feather = settings.feather,
                reverse = settings.reverse,
                fillArgb = fillArgb
            )
        }
    }

    plate?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier.fillMaxSize(),
            // The mask has the frame's aspect ratio; stretching it to the
            // video rect is the same single resample the export performs.
            contentScale = ContentScale.FillBounds
        )
    }
}

/**
 * Decoded-plate cache.
 *
 * Deliberately tiny and dumb: matting is a single-clip tool in practice,
 * and the whole point is to stop a scrub from decoding 358 PNGs. Eight
 * entries at ~0.6 MB each is a few MB, which is less than one 4K frame.
 */
private object PlateCache {

    private const val MAX_ENTRIES = 8

    private class Entry(val bitmap: ImageBitmap)

    private val entries = object : LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    fun get(
        context: android.content.Context,
        projectId: String,
        clipId: String,
        sourceMs: Long,
        expansion: Float,
        feather: Float,
        reverse: Boolean,
        fillArgb: Int
    ): ImageBitmap? {
        val dir = MattingSidecar.directoryFor(context, projectId, clipId)
        if (!dir.isDirectory) return null
        val png = nearestPng(dir, sourceMs) ?: return null
        val key = "${png.absolutePath}|${png.length()}|$expansion|$feather|$reverse|$fillArgb"

        synchronized(entries) { entries[key] }?.let { return it.bitmap }

        val bitmap = buildPlate(png, expansion, feather, reverse, fillArgb) ?: return null
        val image = bitmap.asImageBitmap()
        synchronized(entries) { entries[key] = Entry(image) }
        return image
    }

    /**
     * Nearest sidecar PNG at or after [sourceMs], reusing
     * [MaskIndexMath.nearestIndex] so the preview and the export pick the
     * same frame for the same timestamp.
     */
    private fun nearestPng(dir: File, sourceMs: Long): File? {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: return null
        if (files.isEmpty()) return null
        val pairs = files.mapNotNull { f ->
            f.nameWithoutExtension.toLongOrNull()?.let { it to f }
        }.sortedBy { it.first }
        if (pairs.isEmpty()) return null
        val stamps = LongArray(pairs.size) { pairs[it].first }
        val idx = MaskIndexMath.nearestIndex(stamps, sourceMs)
        if (idx < 0) return null
        return pairs[idx].second
    }

    private fun buildPlate(
        png: File,
        expansion: Float,
        feather: Float,
        reverse: Boolean,
        fillArgb: Int
    ): Bitmap? {
        // The decoded PNG is IMMUTABLE — never write into it (2026-10-29,
        // see MaskBitmaps). Read pixels out, build a new bitmap.
        val decoded = BitmapFactory.decodeFile(png.absolutePath) ?: return null
        val w = decoded.width
        val h = decoded.height
        if (w <= 0 || h <= 0) {
            decoded.recycle()
            return null
        }
        val pixels = IntArray(w * h)
        decoded.getPixels(pixels, 0, w, 0, 0, w, h)
        decoded.recycle()

        val alpha = MattingPreviewPlate.alphaOf(pixels)
        // Same chain, same order, same short-side mapping as the export,
        // so the edge the user sees is the edge the file gets.
        MaskPostProcess.apply(alpha, w, h, expansion, feather, reverse)
        val plate = MattingPreviewPlate.plateOf(alpha, fillArgb)
        return Bitmap.createBitmap(plate, w, h, Bitmap.Config.ARGB_8888)
    }
}
