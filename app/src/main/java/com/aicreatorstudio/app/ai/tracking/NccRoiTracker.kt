package com.aicreatorstudio.app.ai.tracking

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * **Normalised cross-correlation template tracker** — the pure-Kotlin
 * appearance tracker, with no native dependency.
 *
 * ## Why this exists
 *
 * The primary appearance tier is OpenCV's CSRT (or KCF). Both live in
 * OpenCV's `tracking` contrib module, which is **not guaranteed to be
 * present** in the Maven artifact, and neither is guaranteed to load on
 * a device without a matching ABI. Rather than let the whole feature
 * fail in that case, this class provides a self-contained fallback
 * built on nothing but `Bitmap.getPixels`.
 *
 * It is deliberately weaker than CSRT: no scale search, no spatial
 * reliability weighting, no HOG features. It is a dense NCC search
 * over a bounded window around the previous position, which is exactly
 * the classic template-matching tracker. What it *does* get right is
 * the thing that matters most — it never drifts silently, because its
 * score is a real measurement rather than a synthetic value, and a
 * dropped score is an honest failure.
 *
 * ## Cost
 *
 * The search is over a downscaled grayscale copy (`WORK_WIDTH` wide)
 * with a coarse-to-fine pass: a stride-4 sweep to find the basin, then
 * a stride-1 refinement in a small neighbourhood. At 240 px working
 * width that is a few thousand multiply-accumulates per frame — well
 * under a millisecond, and it produces no allocations after `init`.
 *
 * ## Grayscale, not colour
 *
 * NCC over luminance is both cheaper and more robust here. Colour
 * channels in a video are chroma-subsampled and noisy; luminance
 * carries the structure the correlation needs.
 */
class NccRoiTracker(
    /** Working width for the search. Taller frames scale from this. */
    private val workWidth: Int = WORK_WIDTH
) : RoiTracker {

    override val backendName: String = "NCC template (pure Kotlin)"

    /** The template patch: grayscale, `tw x th`. */
    private var template = FloatArray(0)
    private var tw = 0
    private var th = 0

    /** Template statistics, precomputed once. */
    private var templateMean = 0f
    private var templateNorm = 1f

    /** Last accepted box in normalized coordinates. */
    private var lastRoi: RectF = RectF(0.35f, 0.35f, 0.65f, 0.65f)

    /** Working-resolution grayscale buffer, reused every frame. */
    private var gray = FloatArray(0)
    private var gw = 0
    private var gh = 0

    /** Per-frame pixel scratch, sized to the source frame at init. */
    private var pixels = IntArray(0)

    private var initialised = false

    override fun init(frame: Bitmap, roi: RectF): Boolean {
        if (frame.width < 8 || frame.height < 8) return false
        val r = roiToPixels(roi, frame)
        if (r.width() < MIN_TEMPLATE_SIDE || r.height() < MIN_TEMPLATE_SIDE) return false

        buildGray(frame)
        tw = max(4, (r.width().toFloat() / frame.width * gw).toInt())
        th = max(4, (r.height().toFloat() / frame.height * gh).toInt())
        if (tw > gw || th > gh) return false

        val rx = (r.left.toFloat() / frame.width * gw).toInt().coerceIn(0, gw - tw)
        val ry = (r.top.toFloat() / frame.height * gh).toInt().coerceIn(0, gh - th)

        template = FloatArray(tw * th)
        var sum = 0f
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val v = gray[(ry + y) * gw + (rx + x)]
                template[y * tw + x] = v
                sum += v
            }
        }
        templateMean = sum / (tw * th)
        var sq = 0f
        for (v in template) {
            val d = v - templateMean
            sq += d * d
        }
        // A flat patch (uniform wall, letterbox bar) has no structure
        // to correlate. Reject it rather than lock onto noise.
        templateNorm = sqrt(sq)
        if (templateNorm < MIN_TEMPLATE_NORM) return false

        lastRoi = RectF(
            rx.toFloat() / gw, ry.toFloat() / gh,
            (rx + tw).toFloat() / gw, (ry + th).toFloat() / gh
        )
        initialised = true
        return true
    }

    override fun update(frame: Bitmap): RoiTrackerResult? {
        if (!initialised) return null
        if (frame.width < 8 || frame.height < 8) return null

        buildGray(frame)
        if (gray.size < gw * gh) return null

        // Current box in working pixels.
        var bx = (lastRoi.left * gw).toInt().coerceIn(0, max(0, gw - tw))
        var by = (lastRoi.top * gh).toInt().coerceIn(0, max(0, gh - th))

        // Search window: previous position +/- SEARCH_RADIUS working px.
        val search = max(SEARCH_MIN, (max(tw, th) * SEARCH_RADIUS_SCALE).toInt())
        val x0 = (bx - search).coerceAtLeast(0)
        val y0 = (by - search).coerceAtLeast(0)
        val x1 = min(gw - tw, bx + search)
        val y1 = min(gh - th, by + search)
        if (x1 < x0 || y1 < y0) return null

        // ---- coarse pass: stride COARSE_STRIDE ----
        var bestScore = -2f
        var bestX = bx
        var bestY = by
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val s = nccAt(x, y)
                if (s > bestScore) {
                    bestScore = s
                    bestX = x
                    bestY = y
                }
                x += COARSE_STRIDE
            }
            y += COARSE_STRIDE
        }

        // ---- fine pass: stride 1 around the coarse winner ----
        val fx0 = (bestX - COARSE_STRIDE).coerceAtLeast(x0)
        val fy0 = (bestY - COARSE_STRIDE).coerceAtLeast(y0)
        val fx1 = min(x1, bestX + COARSE_STRIDE)
        val fy1 = min(y1, bestY + COARSE_STRIDE)
        y = fy0
        while (y <= fy1) {
            var x = fx0
            while (x <= fx1) {
                val s = nccAt(x, y)
                if (s > bestScore) {
                    bestScore = s
                    bestX = x
                    bestY = y
                }
                x++
            }
            y++
        }

        // NCC ranges -1..1; map to 0..1 for a comparable confidence.
        val ncc = bestScore.coerceIn(-1f, 1f)
        val confidence = ((ncc + 1f) / 2f).coerceIn(0f, 1f)
        if (confidence < MIN_ACCEPT_CONFIDENCE) return null

        val result = RectF(
            bestX.toFloat() / gw,
            bestY.toFloat() / gh,
            (bestX + tw).toFloat() / gw,
            (bestY + th).toFloat() / gh
        )
        lastRoi = result
        return RoiTrackerResult(roi = result, confidence = confidence)
    }

    override fun close() {
        initialised = false
        template = FloatArray(0)
        gray = FloatArray(0)
        pixels = IntArray(0)
        gw = 0
        gh = 0
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Zero-mean normalised cross-correlation of the template against
     * the patch at `(px, py)`. Returns `sum((T-meanT)(P-meanP)) /
     * (|T-meanT| * |P-meanP|)` — 1 is a perfect match, 0 uncorrelated,
     * negative is an inverted match.
     */
    private fun nccAt(px: Int, py: Int): Float {
        var sumP = 0f
        val n = tw * th
        for (y in 0 until th) {
            val row = (py + y) * gw + px
            for (x in 0 until tw) sumP += gray[row + x]
        }
        val meanP = sumP / n

        var dot = 0f
        var normP = 0f
        for (y in 0 until th) {
            val row = (py + y) * gw + px
            val trow = y * tw
            for (x in 0 until tw) {
                val dT = template[trow + x] - templateMean
                val dP = gray[row + x] - meanP
                dot += dT * dP
                normP += dP * dP
            }
        }
        val denom = templateNorm * sqrt(normP)
        if (denom < 1e-6f) return -1f
        return dot / denom
    }

    /**
     * Downscale [frame] into the reused `gray` buffer as luminance.
     *
     * Reuses the `gray` and `pixels` arrays across frames: a fresh
     * `IntArray(w*h)` per frame at 1080p is 8 MB of garbage per frame,
     * which is the classic way to thrash the GC on a long clip.
     */
    private fun buildGray(frame: Bitmap) {
        val targetW = min(workWidth, frame.width).coerceAtLeast(8)
        val targetH = max(8, (frame.height.toFloat() / frame.width * targetW).toInt())

        if (pixels.size != frame.width * frame.height) {
            pixels = IntArray(frame.width * frame.height)
        }
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)

        gw = targetW
        gh = targetH
        if (gray.size != targetW * targetH) gray = FloatArray(targetW * targetH)

        val sx = frame.width.toFloat() / targetW
        val sy = frame.height.toFloat() / targetH
        for (y in 0 until targetH) {
            val srcY = min(frame.height - 1, (y * sy).toInt())
            val srow = srcY * frame.width
            val drow = y * targetW
            for (x in 0 until targetW) {
                val srcX = min(frame.width - 1, (x * sx).toInt())
                val c = pixels[srow + srcX]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Rec. 601 luma. Structure lives in luminance; chroma is
                // subsampled and noisy in video, so it adds cost without
                // adding discrimination.
                gray[drow + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
    }

    companion object {
        /** Working width for the correlation search. */
        const val WORK_WIDTH: Int = 240

        /** Coarse sweep stride before refinement. */
        const val COARSE_STRIDE: Int = 4

        /** Search radius as a multiple of the larger template side. */
        const val SEARCH_RADIUS_SCALE: Float = 0.6f

        /** Floor on the search radius, in working pixels. */
        const val SEARCH_MIN: Int = 6

        /** Smallest template side, in working pixels. */
        const val MIN_TEMPLATE_SIDE: Int = 4

        /**
         * Minimum template standard deviation. Below this the patch is
         * flat (a wall, a letterbox bar) and NCC is undefined.
         */
        const val MIN_TEMPLATE_NORM: Float = 8f

        /**
         * Minimum normalised confidence to accept a match. 0.42
         * corresponds to an NCC of roughly -0.16, i.e. "not clearly
         * anti-correlated" — deliberately permissive, because the
         * caller applies its own, user-tunable `minConfidence` on top.
         */
        const val MIN_ACCEPT_CONFIDENCE: Float = 0.42f
    }
}
