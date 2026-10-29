package com.aicreatorstudio.app.ai.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 2026-10-21 — DIAGNOSTIC for the native crash observed on a real
 * device (Xiaomi 2107113SG, Android 14).
 *
 * ## The crash
 *
 * ```
 * Fatal signal 6 (SIGABRT) in tid 14694 (DefaultDispatch)
 * Abort message: 'JNI DETECTED ERROR IN APPLICATION: non-zero capacity for
 *   nullptr pointer: 1
 *     in call to NewDirectByteBuffer
 *     from android.media.ImageReader$SurfaceImage$SurfacePlane[]
 *            ImageReader$SurfaceImage.nativeCreatePlanes(int, int, long)'
 * ```
 *
 * with the application frames:
 *
 * ```
 * MediaPipeMattingEngine$process$2.invokeSuspend
 *   -> SequentialFrameSampler.sample
 *     -> VideoFramePipeline.imageToBitmap
 *       -> VideoFramePipeline.yuv420ToBitmap      <-- image.planes[1]/[2]
 * ```
 *
 * ## The hypothesis under test
 *
 * The `ImageReader` is sized from `MediaMetadataRetriever`
 * (`METADATA_KEY_VIDEO_WIDTH/HEIGHT`), but a MediaCodec decoder writes
 * the **coded, cropped** frame size taken from the *track format*. When
 * those two disagree, the reader's plane arithmetic runs off the end of
 * the real buffer and produces a third plane whose address is null
 * while its capacity is 1 — which ART's JNI check turns into an
 * immediate `abort()`. A native abort cannot be caught, so the whole
 * app dies.
 *
 * This test PRINTS the two sizes and asserts that the reader is sized
 * from the track format, which is the only size the decoder is
 * guaranteed to write.
 */
@RunWith(AndroidJUnit4::class)
class DecoderSizeDiagnosticTest {

    private fun context(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    /** Copy the bundled sample clip out of assets so the extractor can read it. */
    private fun sampleVideo(): File {
        val ctx = context()
        val out = File(ctx.cacheDir, "diagnostic-sample.mp4")
        if (!out.exists() || out.length() == 0L) {
            ctx.assets.open("sample/Animation_storyboard_for_cooking_202609010012.mp4")
                .use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    /** The size the decoder is guaranteed to write: the track's cropped size. */
    private fun trackSize(file: File): Pair<Int, Int>? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (!(f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) continue
                var w = f.getInteger(MediaFormat.KEY_WIDTH)
                var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                if (f.containsKey(MediaFormat.KEY_CROP_LEFT) &&
                    f.containsKey(MediaFormat.KEY_CROP_RIGHT) &&
                    f.containsKey(MediaFormat.KEY_CROP_TOP) &&
                    f.containsKey(MediaFormat.KEY_CROP_BOTTOM)
                ) {
                    val l = f.getInteger(MediaFormat.KEY_CROP_LEFT)
                    val t = f.getInteger(MediaFormat.KEY_CROP_TOP)
                    val r = f.getInteger(MediaFormat.KEY_CROP_RIGHT)
                    val b = f.getInteger(MediaFormat.KEY_CROP_BOTTOM)
                    if (r > l && b > t) {
                        w = r - l
                        h = b - t
                    }
                }
                return w to h
            }
            null
        } finally {
            runCatching { ex.release() }
        }
    }

    /** The size the app currently uses to size the ImageReader. */
    private fun retrieverSize(file: File): Pair<Int, Int>? {
        val mr = MediaMetadataRetriever()
        return try {
            mr.setDataSource(file.absolutePath)
            val w = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (w <= 0 || h <= 0) null else w to h
        } finally {
            runCatching { mr.release() }
        }
    }

    @Test
    fun retrieverSizeMatchesTheSizeTheDecoderActuallyWrites() {
        val file = sampleVideo()
        assertTrue("the sample clip must exist", file.exists() && file.length() > 0)

        val track = trackSize(file)
        val retriever = retrieverSize(file)
        assertTrue("the track format must expose a video size", track != null)
        assertTrue("the retriever must expose a video size", retriever != null)

        val (tw, th) = track!!
        val (rw, rh) = retriever!!

        println("DIAG track=${tw}x$th retriever=${rw}x$rh")

        assertEquals(
            "the ImageReader must be sized from the TRACK format " +
                "(what the decoder writes), not from MediaMetadataRetriever " +
                "(what the display reports) — a mismatch makes " +
                "ImageReader.nativeCreatePlanes abort the process",
            tw, rw
        )
        assertEquals(
            "the ImageReader must be sized from the TRACK format",
            th, rh
        )
    }
}
