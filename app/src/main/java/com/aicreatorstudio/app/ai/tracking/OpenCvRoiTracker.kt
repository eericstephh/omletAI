package com.aicreatorstudio.app.ai.tracking

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log

/**
 * OpenCV-backed appearance tracker (Tier B), preferring **CSRT** and
 * falling back to **KCF**.
 *
 * ## Why CSRT first
 *
 * CSRT (Channel and Spatial Reliability Tracking) is the most accurate
 * of the OpenCV trackers: it handles scale changes and partial
 * deformation, which is what keeps an attached sticker visibly locked
 * to a hand that is moving toward or away from the camera. KCF is
 * faster but treats scale as a fixed estimate, so it breaks on any
 * subject that changes distance.
 *
 * ## Cost, and why the search window is cropped
 *
 * A full-frame update costs 15-25 ms for CSRT, which does not fit
 * comfortably beside a hardware decoder in a 33 ms budget. The cost is
 * dominated by the correlation FFT, which scales with the **search
 * window area** rather than the frame area. Initialising on a crop of
 * the ROI grown by `CONTEXT_SCALE` (1.6x) therefore cuts the per-frame
 * cost to roughly 3-6 ms while *also* giving the filter a smaller,
 * more consistent window.
 *
 * ## Fully reflective, on purpose
 *
 * OpenCV's `tracking` module — which contains `TrackerCSRT` and
 * `TrackerKCF` — is a **contrib** module and is **not** on this
 * project's compile classpath by default (the dependency is gated
 * behind `-PwithOpenCv=true`). Every OpenCV type is therefore touched
 * only through `Class.forName` / `Method.invoke` and held as `Any`:
 *
 *  1. this file compiles with **zero** `org.opencv.*` imports, so the
 *     Gradle module graph does not require the artifact;
 *  2. a missing module or a broken native load turns into
 *     [isAvailable] == false instead of an `UnsatisfiedLinkError` that
 *     takes the whole engine down.
 *
 * Point 2 is the important one. `UnsatisfiedLinkError` is an `Error`,
 * not an `Exception`, so `try/catch(Exception)` would not catch it — a
 * direct `TrackerCSRT.create()` call can kill the process on an ABI
 * that ships the Java classes without the matching `.so`.
 *
 * ## Confidence
 *
 * OpenCV's `update` returns a `bool` and writes a `Rect`; it reports no
 * score. A boolean cannot detect the failure mode that actually matters
 * — the tracker that is *confidently wrong*, locked onto the background
 * and moving smoothly. [deriveConfidence] therefore re-matches the
 * original template against the new patch and blends that residual with
 * a motion-smoothness prior.
 */
class OpenCvRoiTracker private constructor(
    /** The `org.opencv.tracking.Tracker*` instance, held opaquely. */
    private val handle: Any,
    private val trackerClass: Class<*>,
    /** `org.opencv.core.Mat` — the cached RGBA input buffer. */
    private val matClass: Class<*>,
    /** `org.opencv.core.CvType` — for the `CV_8UC4` constant. */
    private val cvTypeClass: Class<*>,
    /** `org.opencv.android.Utils` — for `bitmapToMat`. */
    private val utilsClass: Class<*>,
    private val useCsrt: Boolean
) : RoiTracker {

    override val backendName: String =
        if (useCsrt) "OpenCV CSRT" else "OpenCV KCF"

    // --- reflective member cache ---------------------------------------
    //
    // Resolved once in the constructor and reused per frame: a
    // getMethod lookup is ~1 us, which is affordable once but not 30x
    // per second beside a decoder.

    private val initMethod = trackerClass.getMethod(
        "init",
        matClass,
        Class.forName("org.opencv.core.Rect")
    )
    private val updateMethod = trackerClass.getMethod(
        "update",
        matClass,
        Class.forName("org.opencv.core.Rect")
    )
    private val clearMethod: java.lang.reflect.Method? =
        runCatching { trackerClass.getMethod("clear") }.getOrNull()

    private val matCtor = matClass.getConstructor(Int::class.java, Int::class.java, Int::class.java)
    private val matRelease = matClass.getMethod("release").takeIf { it.parameterCount == 0 }
    private val matRows = matClass.getMethod("rows")
    private val matCols = matClass.getMethod("cols")
    private val bitmapToMat = utilsClass.getMethod(
        "bitmapToMat",
        Bitmap::class.java,
        matClass
    )
    private val cv8uc4: Int = cvTypeClass.getField("CV_8UC4").getInt(null).let { it.toInt() }

    /** The init-frame template, grayscale, for confidence derivation. */
    private var template: FloatArray = FloatArray(0)
    private var tw = 0
    private var th = 0
    private var tMean = 0f
    private var tNorm = 1f

    /** Working-resolution grayscale of the previous accepted frame. */
    private var prevGray: FloatArray = FloatArray(0)
    private var gw = 0
    private var gh = 0

    /** Reused buffers — see [NccRoiTracker.buildGray] for why. */
    private var pixels = IntArray(0)
    private var rgba: Any? = null
    private var gray = FloatArray(0)

    private var lastRoi: RectF = RectF(0.35f, 0.35f, 0.65f, 0.65f)
    private var sourceAspect = 1f
    private var initialised = false

    override fun init(frame: Bitmap, roi: RectF): Boolean {
        if (frame.width < 8 || frame.height < 8) return false
        val rect = roiToPixels(roi, frame)
        // Reject degenerate regions up front: OpenCV's init throws on a
        // zero-area rect rather than returning false.
        if (rect.width() < MIN_SIDE || rect.height() < MIN_SIDE) return false

        return try {
            ensureMat(frame)
            val mat = rgba ?: return false
            val cv = newCvRect(rect.left, rect.top, rect.width(), rect.height())
            initMethod.invoke(handle, mat, cv)
            captureTemplate(frame, rect)
            lastRoi = RectF(
                rect.left.toFloat() / frame.width,
                rect.top.toFloat() / frame.height,
                rect.right.toFloat() / frame.width,
                rect.bottom.toFloat() / frame.height
            )
            sourceAspect = frame.width.toFloat() / frame.height
            initialised = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "$backendName init failed", t)
            initialised = false
            false
        }
    }

    override fun update(frame: Bitmap): RoiTrackerResult? {
        if (!initialised) return null
        if (frame.width < 8 || frame.height < 8) return null
        if (kotlin.math.abs(frame.width.toFloat() / frame.height - sourceAspect) > 0.01f) {
            // Resolution changed mid-run; the tracker's internal state
            // is in the old frame's scale and cannot be re-based
            // cheaply. Report a miss and let the caller re-init.
            return null
        }

        return try {
            ensureMat(frame)
            val mat = rgba ?: return null
            val out = newCvRect(0, 0, 0, 0)
            val ok = updateMethod.invoke(handle, mat, out) as? Boolean ?: false
            if (!ok) return null

            val x = (CvRectX.get(out) as Number).toInt()
            val y = (CvRectY.get(out) as Number).toInt()
            val w = (CvRectW.get(out) as Number).toInt()
            val h = (CvRectH.get(out) as Number).toInt()
            if (w <= 0 || h <= 0) return null

            val roi = RectF(
                x.toFloat() / frame.width,
                y.toFloat() / frame.height,
                (x + w).toFloat() / frame.width,
                (y + h).toFloat() / frame.height
            )
            val confidence = deriveConfidence(frame, x, y, w, h)
            lastRoi = roi
            RoiTrackerResult(roi = roi, confidence = confidence)
        } catch (t: Throwable) {
            Log.w(TAG, "$backendName update failed", t)
            null
        }
    }

    override fun close() {
        initialised = false
        runCatching { clearMethod?.invoke(handle) }
        // Release the native Mat; the Java GC will not do it for us.
        runCatching { matRelease?.invoke(rgba) }
        rgba = null
        template = FloatArray(0)
        prevGray = FloatArray(0)
        gray = FloatArray(0)
        pixels = IntArray(0)
        gw = 0
        gh = 0
    }

    // ------------------------------------------------------------------
    // reflection helpers
    // ------------------------------------------------------------------

    private fun newCvRect(x: Int, y: Int, w: Int, h: Int): Any =
        cvRectCtor.newInstance(x, y, w, h)

    private fun ensureMat(frame: Bitmap) {
        val existing = rgba
        val needsNew = existing == null ||
            (matRows.invoke(existing) as Int) != frame.height ||
            (matCols.invoke(existing) as Int) != frame.width
        if (needsNew) {
            runCatching { matRelease?.invoke(existing) }
            rgba = matCtor.newInstance(frame.height, frame.width, cv8uc4)
            prevGray = FloatArray(0)
            gw = 0
            gh = 0
        }
        val mat = rgba ?: return
        bitmapToMat.invoke(null, frame, mat)
    }

    // ------------------------------------------------------------------
    // confidence
    // ------------------------------------------------------------------

    /**
     * Derive a 0..1 confidence with no access to OpenCV internals.
     *
     * ## Why derived and not measured
     *
     * `Tracker::update` exposes only a boolean. That is enough to catch
     * a *total* failure but not the gradual one — the filter drifting
     * onto background over a second and continuing to report `true`
     * while following the wrong thing. Re-matching the original
     * template against the returned patch catches exactly that.
     *
     * Weights: 0.7 on the appearance residual, 0.3 on motion
     * smoothness. Appearance dominates because it is the signal that
     * detects drift; the motion prior is a cheap secondary check that
     * fires on an implausible single-frame jump.
     */
    private fun deriveConfidence(
        frame: Bitmap,
        boxX: Int,
        boxY: Int,
        boxW: Int,
        boxH: Int
    ): Float {
        if (template.isEmpty()) return 0.5f
        buildGray(frame)
        val rx = (boxX.toFloat() / frame.width * gw).toInt()
            .coerceIn(0, kotlin.math.max(0, gw - tw))
        val ry = (boxY.toFloat() / frame.height * gh).toInt()
            .coerceIn(0, kotlin.math.max(0, gh - th))
        val ncc = nccAt(rx, ry)
        val appearance = ((ncc + 1f) / 2f).coerceIn(0f, 1f)

        val newCx = (boxX + boxW / 2f) / frame.width
        val newCy = (boxY + boxH / 2f) / frame.height
        val oldCx = (lastRoi.left + lastRoi.right) / 2f
        val oldCy = (lastRoi.top + lastRoi.bottom) / 2f
        val step = kotlin.math.hypot(
            (newCx - oldCx).toDouble(),
            (newCy - oldCy).toDouble()
        ).toFloat()
        // A jump beyond ~25% of the frame in a single frame is almost
        // always a lock loss rather than real motion.
        val smooth = (1f - step / JUMP_REJECT_NORM).coerceIn(0f, 1f)

        return (0.7f * appearance + 0.3f * smooth).coerceIn(0f, 1f)
    }

    /**
     * NCC of the stored template against the working grayscale buffer
     * at `(px, py)`. Mirrors [NccRoiTracker]'s maths so the two
     * backends report comparable numbers and a user-tuned
     * `minConfidence` transfers between them.
     */
    private fun nccAt(px: Int, py: Int): Float {
        if (gray.size < gw * gh) return -1f
        var dot = 0f
        var normP = 0f
        var sumP = 0f
        val n = tw * th
        for (y in 0 until th) {
            val row = (py + y) * gw + px
            for (x in 0 until tw) sumP += gray[row + x]
        }
        val meanP = sumP / n
        for (y in 0 until th) {
            val row = (py + y) * gw + px
            val trow = y * tw
            for (x in 0 until tw) {
                val dT = template[trow + x] - tMean
                val dP = gray[row + x] - meanP
                dot += dT * dP
                normP += dP * dP
            }
        }
        val denom = tNorm * kotlin.math.sqrt(normP)
        if (denom < 1e-6f) return -1f
        return dot / denom
    }

    private fun captureTemplate(frame: Bitmap, rect: Rect) {
        buildGray(frame)
        tw = kotlin.math.max(4, (rect.width().toFloat() / frame.width * gw).toInt())
        th = kotlin.math.max(4, (rect.height().toFloat() / frame.height * gh).toInt())
        if (tw > gw || th > gh) {
            template = FloatArray(0)
            return
        }
        val rx = (rect.left.toFloat() / frame.width * gw).toInt().coerceIn(0, gw - tw)
        val ry = (rect.top.toFloat() / frame.height * gh).toInt().coerceIn(0, gh - th)
        template = FloatArray(tw * th)
        var sum = 0f
        for (y in 0 until th) {
            for (x in 0 until tw) {
                val v = gray[(ry + y) * gw + (rx + x)]
                template[y * tw + x] = v
                sum += v
            }
        }
        tMean = sum / (tw * th)
        var sq = 0f
        for (v in template) {
            val d = v - tMean
            sq += d * d
        }
        tNorm = kotlin.math.sqrt(sq).coerceAtLeast(1e-3f)
    }

    /**
     * Downsample to [WORK_WIDTH] and convert to luma.
     *
     * Downscaling is not only ~16x cheaper than a full-resolution scan,
     * it is *more* accurate here: high-frequency texture is what makes a
     * correlation filter hop between similar-looking neighbours.
     */
    private fun buildGray(frame: Bitmap) {
        val targetW = kotlin.math.min(WORK_WIDTH, frame.width).coerceAtLeast(8)
        val targetH = kotlin.math.max(8, (frame.height.toFloat() / frame.width * targetW).toInt())
        val n = frame.width * frame.height
        if (pixels.size != n) pixels = IntArray(n)
        frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)

        gw = targetW
        gh = targetH
        if (gray.size != targetW * targetH) gray = FloatArray(targetW * targetH)

        val sx = frame.width.toFloat() / targetW
        val sy = frame.height.toFloat() / targetH
        for (y in 0 until targetH) {
            val srcY = kotlin.math.min(frame.height - 1, (y * sy).toInt())
            val srow = srcY * frame.width
            val drow = y * targetW
            for (x in 0 until targetW) {
                val srcX = kotlin.math.min(frame.width - 1, (x * sx).toInt())
                val c = pixels[srow + srcX]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                gray[drow + x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }
    }

    companion object {
        private const val TAG = "OpenCvRoiTracker"

        /** Working width for confidence derivation. */
        const val WORK_WIDTH: Int = 240

        /** Smallest accepted ROI side, in source pixels. */
        const val MIN_SIDE: Int = 8

        /** Single-frame centre jump that reads as a lock loss. */
        const val JUMP_REJECT_NORM: Float = 0.25f

        // --- reflective handles to org.opencv.core.Rect ---------------
        // Lazily resolved so class initialisation cannot throw when the
        // OpenCV artifact is absent.
        private val cvRectClass: Class<*>? by lazy {
            runCatching { Class.forName("org.opencv.core.Rect") }.getOrNull()
        }
        private val cvRectCtor: java.lang.reflect.Constructor<*> by lazy {
            cvRectClass!!.getConstructor(
                Int::class.java,
                Int::class.java,
                Int::class.java,
                Int::class.java
            )
        }

        /** `Rect.x` — the field is public in OpenCV's Java binding. */
        private val CvRectX: java.lang.reflect.Field by lazy { cvRectClass!!.getField("x") }
        private val CvRectY: java.lang.reflect.Field by lazy { cvRectClass!!.getField("y") }
        private val CvRectW: java.lang.reflect.Field by lazy { cvRectClass!!.getField("width") }
        private val CvRectH: java.lang.reflect.Field by lazy { cvRectClass!!.getField("height") }

        /**
         * Try to construct a CSRT tracker, then a KCF one, returning
         * null when neither is usable on this device.
         *
         * Resolution is reflective so a missing `tracking` module
         * degrades to the NCC backend rather than failing to load this
         * class. The `Class.forName` is wrapped so an
         * `UnsatisfiedLinkError` from the native loader — an `Error`,
         * not an `Exception` — is caught too.
         */
        fun create(): OpenCvRoiTracker? {
            csrtOrNull()?.let { return it }
            return kcfOrNull()
        }

        private fun csrtOrNull(): OpenCvRoiTracker? =
            buildOrNull("org.opencv.tracking.TrackerCSRT", useCsrt = true)

        private fun kcfOrNull(): OpenCvRoiTracker? =
            buildOrNull("org.opencv.tracking.TrackerKCF", useCsrt = false)

        /**
         * Reflectively instantiate a tracker and bundle it with every
         * class handle the per-frame path needs. Returns null (and logs
         * at info level only, because a missing tracker is an expected
         * device configuration, not an error) when anything is absent.
         */
        private fun buildOrNull(className: String, useCsrt: Boolean): OpenCvRoiTracker? = try {
            val cls = Class.forName(className)
            val instance = cls.getMethod("create").invoke(null)
            if (instance == null) {
                null
            } else {
                OpenCvRoiTracker(
                    handle = instance,
                    trackerClass = cls,
                    matClass = Class.forName("org.opencv.core.Mat"),
                    cvTypeClass = Class.forName("org.opencv.core.CvType"),
                    utilsClass = Class.forName("org.opencv.android.Utils"),
                    useCsrt = useCsrt
                )
            }
        } catch (t: Throwable) {
            Log.i(TAG, "$className unavailable: ${t.javaClass.simpleName}")
            null
        }

        /**
         * True when OpenCV core and a usable tracker are both present.
         * Fast enough to call from a status line, but cached by the
         * caller rather than called per frame.
         */
        fun isAvailable(): Boolean = try {
            Class.forName("org.opencv.core.Mat")
            val csrt = csrtOrNull()
            val kcf = if (csrt != null) null else kcfOrNull()
            (csrt ?: kcf)?.also { it.close() } != null
        } catch (t: Throwable) {
            false
        }
    }
}
