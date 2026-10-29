package com.aicreatorstudio.app.util

import android.graphics.Bitmap
import com.aicreatorstudio.app.model.ChromaKeySettings
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// File-private maths. Declared at file scope rather than inside the object so
// that both `ChromaKeyEngine` and the file-private `KeyPlane` below can use
// them without any question about nested-class visibility.
// ---------------------------------------------------------------------------

/** BT.601 full-range luma weights. */
private const val KR = 0.299f
private const val KG = 0.587f
private const val KB = 0.114f

/** The chroma planes are centred on 128 in 8-bit YCbCr. */
private const val CHROMA_MID = 128f

/** BT.601 Cb for an 8-bit RGB triple. */
private fun chromaCb(r: Int, g: Int, b: Int): Float =
    CHROMA_MID - 0.168736f * r - 0.331264f * g + 0.5f * b

/** BT.601 Cr for an 8-bit RGB triple. */
private fun chromaCr(r: Int, g: Int, b: Int): Float =
    CHROMA_MID + 0.5f * r - 0.418688f * g - 0.081312f * b

/**
 * Hermite `smoothstep`. Returns `0` at [edge0], `1` at [edge1], and a curve
 * with zero first derivative at both ends in between.
 *
 * A degenerate ramp ([edge1] `<=` [edge0], i.e. `smoothness == 0`) is a hard
 * step, so the slider's minimum gives a crisp aliased edge rather than a
 * division by zero.
 */
private fun smoothstepRaw(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun packArgb(a: Int, r: Int, g: Int, b: Int): Int =
    (a shl 24) or (r shl 16) or (g shl 8) or b

private fun clamp8(v: Float): Int =
    if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

/**
 * 2026-10-13 — Chroma Key (green/blue screen removal).
 *
 * ## What this file owns
 *
 * The **only** implementation of chroma keying in the app. Both renderers
 * consume it:
 *
 *  - `export/OverlayCompositeRenderer.kt` — the CPU frame pre-pass that
 *    produces the exported file;
 *  - `ui/screens/editor/OverlayPipLayer.kt` — the Compose preview.
 *
 * Neither may carry a copy of the maths. Two implementations kept in
 * agreement "by review" is the failure mode this project keeps hitting, and
 * the mismatch it produces is only ever visible in the exported file — the
 * worst possible place to find it.
 *
 * ## Why the core is `Int`-based and not `Bitmap`-based
 *
 * Everything that decides the output — the distance metric, the alpha ramp,
 * the spill suppression — operates on a packed ARGB `Int` and returns a
 * packed ARGB `Int`. That makes the algorithm **pure Kotlin with no
 * `android.*` dependency**, so it runs in the JVM unit test source set
 * against the real implementation rather than a stub. That matters here:
 * `unitTests.isReturnDefaultValues = true` makes `android.*` silently return
 * `0`/`0f`, so a test that leaned on `android.graphics.Color` or a `Bitmap`
 * would pass for the wrong reason.
 *
 * The only `Bitmap`-touching function is [applyInto], a thin `getPixels` /
 * `setPixels` wrapper with no arithmetic of its own.
 *
 * ## The metric: chroma distance, not RGB distance
 *
 * A green screen is never one colour. It is a gradient — lit at the top,
 * shadowed under the subject's chin — and in RGB those two greens can be 100
 * apart on every channel. Keying on RGB distance therefore forces the user to
 * raise `similarity` until the shadows go, which by then has eaten the
 * subject's edges too.
 *
 * In **YCbCr** the same two greens sit at almost the same `(Cb, Cr)` and
 * differ almost entirely in `Y`. Distance is measured in the chroma plane
 * only, so luminance is ignored and one threshold removes the whole screen.
 * That is why the industry keys on chroma, and it is why this does too.
 *
 * The BT.601 full-range matrix below is the one `MediaCodec`'s
 * `COLOR_FormatYUV420Flexible` output and every NLE use, so the numbers the
 * sliders speak line up with what the user sees in other tools.
 */
object ChromaKeyEngine {

    /** Both ends of every slider in [ChromaKeySettings], as a fraction. */
    const val MIN_LEVEL: Float = 0f
    const val MAX_LEVEL: Float = 1f

    /**
     * The largest chroma distance the 8-bit RGB cube can produce.
     *
     * Derived at class-init by measuring the 8 cube corners rather than
     * hard-coded, so it stays correct if the matrix above ever changes. It
     * is the **green↔magenta** diagonal, ≈272.28.
     *
     * ⚠️ Red↔cyan is *not* this value — it is 269.13, the second widest, and
     * naming it alongside green↔magenta in a KDoc is what this paragraph
     * used to do. Only green↔magenta reaches the maximum, which is why a
     * test asserting either "the maximum is the true cube maximum" must
     * measure, not name.
     *
     * This is the divisor that turns a raw distance into the `0..1` the
     * sliders speak. Using the true cube maximum means `similarity = 1.0`
     * genuinely keys everything, so the top of the slider is a real "off the
     * end" and not an arbitrary cut-off.
     */
    val maxChromaDistance: Float = run {
        val corners = intArrayOf(
            0x000000, 0xFF0000, 0x00FF00, 0x0000FF,
            0xFFFF00, 0xFF00FF, 0x00FFFF, 0xFFFFFF
        )
        val cb = FloatArray(corners.size)
        val cr = FloatArray(corners.size)
        for (i in corners.indices) {
            val r = (corners[i] ushr 16) and 0xFF
            val g = (corners[i] ushr 8) and 0xFF
            val b = corners[i] and 0xFF
            cb[i] = chromaCb(r, g, b)
            cr[i] = chromaCr(r, g, b)
        }
        var max = 0f
        for (i in corners.indices) {
            for (j in i + 1 until corners.size) {
                val dcb = cb[i] - cb[j]
                val dcr = cr[i] - cr[j]
                val d = sqrt(dcb * dcb + dcr * dcr)
                if (d > max) max = d
            }
        }
        max
    }

    // ---- input contract -------------------------------------------------

    /**
     * Clamp a settings value into the supported range.
     *
     * A non-finite level falls back to the **model default**, not to zero: a
     * corrupt `similarity` of `0` would mean "key nothing" and the user would
     * see the feature appear broken, whereas the default is a value that
     * actually works. The key colour's alpha is forced opaque so two equal
     * colours always compare equal and the metric never reads a channel that
     * does not participate.
     */
    fun sanitize(settings: ChromaKeySettings): ChromaKeySettings = settings.copy(
        keyColorArgb = settings.keyColorArgb or ChromaKeySettings.OPAQUE_ALPHA_MASK,
        similarity = level(settings.similarity, ChromaKeySettings.DEFAULT_SIMILARITY),
        smoothness = level(settings.smoothness, ChromaKeySettings.DEFAULT_SMOOTHNESS),
        spillReduction = level(
            settings.spillReduction,
            ChromaKeySettings.DEFAULT_SPILL_REDUCTION
        )
    )

    private fun level(value: Float, fallback: Float): Float =
        if (!value.isFinite()) fallback else value.coerceIn(MIN_LEVEL, MAX_LEVEL)

    // ---- the pure core --------------------------------------------------

    /** See [smoothstepRaw]. Exposed for tests and for callers that need it. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float =
        smoothstepRaw(edge0, edge1, x)

    /**
     * Normalised chroma-plane distance between [pixelArgb] and [keyArgb], in
     * `0..1`. Luminance is deliberately excluded — see the class KDoc.
     */
    fun chromaDistance(pixelArgb: Int, keyArgb: Int): Float {
        val pr = (pixelArgb ushr 16) and 0xFF
        val pg = (pixelArgb ushr 8) and 0xFF
        val pb = pixelArgb and 0xFF
        val kr = (keyArgb ushr 16) and 0xFF
        val kg = (keyArgb ushr 8) and 0xFF
        val kb = keyArgb and 0xFF
        val dcb = chromaCb(pr, pg, pb) - chromaCb(kr, kg, kb)
        val dcr = chromaCr(pr, pg, pb) - chromaCr(kr, kg, kb)
        val d = sqrt(dcb * dcb + dcr * dcr)
        return (d / maxChromaDistance).coerceIn(0f, 1f)
    }

    /**
     * The keyed pixel: alpha replaced by the key's decision, and the RGB
     * de-spilled.
     *
     * Convenience for tests and one-off calls — it re-derives the key's
     * chroma direction on every call. Batch callers use [keyPixelsInPlace],
     * which derives it once per frame.
     */
    fun keyPixel(pixelArgb: Int, settings: ChromaKeySettings): Int =
        KeyPlane(sanitize(settings)).apply(pixelArgb)

    /**
     * Key [count] packed ARGB pixels **in place**. One [KeyPlane] for the
     * whole batch, so the key colour's chroma and direction are computed once
     * per frame instead of once per pixel.
     *
     * @return the number of pixels written, or `0` when the key is off.
     */
    fun keyPixelsInPlace(pixels: IntArray, count: Int, settings: ChromaKeySettings): Int {
        val plane = KeyPlane(sanitize(settings))
        if (!plane.enabled) return 0
        val n = count.coerceIn(0, pixels.size)
        for (i in 0 until n) pixels[i] = plane.apply(pixels[i])
        return n
    }

    // ---- the Bitmap wrapper ---------------------------------------------

    /**
     * Key [src] into [dst], reusing [buffer] as scratch.
     *
     * Both bitmaps must be the same size and [dst] must be ARGB_8888. [src]
     * is **never mutated** — the export's overlay frames belong to their
     * decoder and are handed out again for later frames, so writing into one
     * would corrupt the following frame.
     *
     * @return `false` when the key is off, the arguments are unusable, or
     *   [src] is a hardware bitmap (whose pixels cannot be read). Callers
     *   must treat `false` as "draw the frame un-keyed" rather than "draw
     *   nothing".
     */
    fun applyInto(
        src: Bitmap,
        dst: Bitmap,
        settings: ChromaKeySettings,
        buffer: IntArray
    ): Boolean {
        if (!settings.isEnabled) return false
        if (src.isRecycled || dst.isRecycled) return false
        if (src.width != dst.width || src.height != dst.height) return false
        if (src.width <= 0 || src.height <= 0) return false
        if (dst.config != Bitmap.Config.ARGB_8888) return false
        val n = src.width * src.height
        if (buffer.size < n) return false
        return try {
            src.getPixels(buffer, 0, src.width, 0, 0, src.width, src.height)
            if (keyPixelsInPlace(buffer, n, settings) == 0) return false
            dst.setPixels(buffer, 0, dst.width, 0, 0, dst.width, dst.height)
            true
        } catch (t: Throwable) {
            // A hardware bitmap throws from `getPixels`, and a recycled one
            // from either call. Degrade to "not keyed" rather than failing
            // the whole export.
            false
        }
    }

    /**
     * A one-shot keyed copy of [src], for callers with no scratch bitmap of
     * their own (the preview's still-image path).
     *
     * Returns `null` when the key is off or the pixels could not be read, so
     * a caller can fall back to the original bitmap.
     */
    fun keyedCopy(src: Bitmap, settings: ChromaKeySettings): Bitmap? {
        if (!settings.isEnabled) return null
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val out = runCatching {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        val buffer = IntArray(src.width * src.height)
        return if (applyInto(src, out, settings, buffer)) {
            out
        } else {
            runCatching { out.recycle() }
            null
        }
    }
}

/**
 * Everything about a key that does not change from pixel to pixel: the
 * clamped levels and the key colour's chroma **direction**.
 *
 * Built once per frame. The direction is a unit vector in the `(Cb, Cr)`
 * plane pointing from neutral grey toward the key colour, which is the axis
 * spill suppression pulls pixels back along.
 */
private class KeyPlane(settings: ChromaKeySettings) {

    val enabled: Boolean = settings.isEnabled
    private val similarity = settings.similarity
    private val ramp = settings.similarity + settings.smoothness
    private val spill = settings.spillReduction

    private val keyCb: Float
    private val keyCr: Float
    private val dirCb: Float
    private val dirCr: Float
    private val hasDirection: Boolean

    init {
        val k = settings.opaqueKeyColorArgb
        val kr = (k ushr 16) and 0xFF
        val kg = (k ushr 8) and 0xFF
        val kb = k and 0xFF
        keyCb = chromaCb(kr, kg, kb)
        keyCr = chromaCr(kr, kg, kb)
        val dcb = keyCb - CHROMA_MID
        val dcr = keyCr - CHROMA_MID
        val len = sqrt(dcb * dcb + dcr * dcr)
        // A key colour on the neutral axis (black, white, or a grey) has no
        // hue to project onto, so spill suppression becomes a no-op for it
        // rather than a division by zero.
        hasDirection = len > 1e-3f
        dirCb = if (hasDirection) dcb / len else 0f
        dirCr = if (hasDirection) dcr / len else 0f
    }

    fun apply(argb: Int): Int {
        if (!enabled) return argb
        val srcA = (argb ushr 24) and 0xFF
        if (srcA == 0) return argb

        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF

        val cb = chromaCb(r, g, b)
        val cr = chromaCr(r, g, b)
        val dcb = cb - keyCb
        val dcr = cr - keyCr
        val distance = sqrt(dcb * dcb + dcr * dcr) / ChromaKeyEngine.maxChromaDistance

        // Inside `similarity` the pixel is gone; past `ramp` it is untouched;
        // between the two the Hermite curve gives the soft edge that keeps
        // hair and motion blur from looking cut out with scissors.
        val keyAlpha = smoothstepRaw(similarity, ramp, distance)
        // Multiply rather than replace, so a source frame that already
        // carries alpha keeps it.
        val outA = (srcA * (keyAlpha * 255f + 0.5f).toInt().coerceIn(0, 255) + 127) / 255
        if (outA == 0) return 0

        if (spill <= 0f || !hasDirection) return packArgb(outA, r, g, b)

        // ---- spill suppression ----
        //
        // Project the pixel's chroma onto the key's chroma direction. A
        // positive projection means the pixel leans toward the key hue — i.e.
        // it is carrying a reflection of the screen — and only that component
        // is pulled back toward neutral. A pixel leaning the *other* way (a
        // red shirt under a green screen) projects negative and is left
        // completely alone, which is why "non-key colours are untouched" is
        // exact here and not merely approximate.
        val pcb = cb - CHROMA_MID
        val pcr = cr - CHROMA_MID
        val projection = pcb * dirCb + pcr * dirCr
        if (projection <= 0f) return packArgb(outA, r, g, b)

        val pull = projection * spill
        val ncb = pcb - pull * dirCb
        val ncr = pcr - pull * dirCr

        // Invert BT.601 with Y held, so only the tint changes and the pixel
        // keeps its brightness.
        val y = KR * r + KG * g + KB * b
        val nr = y + 1.402f * ncr
        val ng = y - 0.344136f * ncb - 0.714136f * ncr
        val nb = y + 1.772f * ncb
        return packArgb(outA, clamp8(nr), clamp8(ng), clamp8(nb))
    }
}
