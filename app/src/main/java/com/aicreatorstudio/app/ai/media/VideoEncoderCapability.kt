package com.aicreatorstudio.app.ai.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

/**
 * 2026-10-20 — resolves WHICH video encoder to use, in WHICH color
 * format, at WHICH resolution.
 *
 * ## The bug this fixes
 *
 * [VideoFramePipeline] used to do this:
 *
 * ```kotlin
 * val yuvFormat = selectEncoderColorFormat(MIME_AVC)   // probes ALL encoders
 * encoder = MediaCodec.createEncoderByType(MIME_AVC)     // picks the DEFAULT one
 * encoder.configure(formatWith(yuvFormat), ...)          // hopes they agree
 * ```
 *
 * Three separate things have to agree for `MediaCodec.configure` to
 * succeed, and the old code guaranteed none of them:
 *
 *  1. **The color format must belong to the encoder that is actually
 *     used.** The probe enumerated every AVC encoder on the device
 *     and returned `COLOR_FormatYUV420Planar` if ANY of them
 *     supported planar *or flexible*. But `createEncoderByType`
 *     instantiates the platform's DEFAULT AVC encoder — frequently
 *     the software encoder `c2.android.avc.encoder`, which accepts
 *     **only** `COLOR_FormatYUV420Flexible`. Configuring that
 *     encoder with `COLOR_FormatYUV420Planar` throws
 *     `IllegalArgumentException: unsupported color format` (or a
 *     `MediaCodec.CodecException`), and the whole AI pipeline — AI
 *     Upscale, Motion Tracking — dies on the first frame.
 *     `COLOR_FormatYUV420Flexible` is not a valid answer for a
 *     ByteBuffer-mode encoder at all: it only works with a Surface.
 *  2. **The resolution must be one the encoder supports.** The old
 *     code asked for up to 3840×2160 with no capability check. Many
 *     devices' hardware AVC encoders cap at 1920×1080; `configure`
 *     then throws.
 *  3. **The encoder instance must be the probed one.** See (1).
 *
 * Which devices fail therefore depends on the vendor's codec
 * ordering and encoder limits — which is exactly why the app worked
 * on one phone and died on another, with no pattern the user could
 * see.
 *
 * ## The fix
 *
 * Treat the encoder, the color format and the size as ONE decision:
 * pick a specific codec by name, a color format *that* codec
 * accepts, and a size *that* codec supports. The selection logic is
 * a pure function ([select]) so it is fully JVM-testable;
 * [listEncoders] is the only part that touches the framework.
 */
object VideoEncoderCapability {

    private const val TAG = "VideoEncoderCapability"

    /** `COLOR_FormatYUV420Planar` (I420). */
    const val COLOR_PLANAR: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

    /** `COLOR_FormatYUV420SemiPlanar` (NV12). */
    const val COLOR_SEMI_PLANAR: Int =
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar

    /**
     * `COLOR_FormatYUV420Flexible`.
     *
     * Recorded so the "why not flexible" note is searchable, but it
     * is deliberately NEVER returned: a flexible-format encoder is
     * driven through a Surface, and this pipeline feeds the encoder
     * YUV bytes through a ByteBuffer (that is how the frame PTS stay
     * exact — see [VideoFramePipeline]'s encoder comment).
     */
    const val COLOR_FLEXIBLE: Int =
        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    /**
     * One encoder the device has, with everything needed to decide
     * whether it can do the job.
     *
     * @param colorFormats the input color formats this encoder accepts.
     * @param widthRange the encoder's overall supported width range.
     * @param heightRange the encoder's overall supported height range.
     * @param widthsForHeight the widths this encoder accepts for a
     *   given height, or null when it accepts none at that height.
     *   Android exposes this per-height, not as a rectangle, so it is
     *   a function.
     * @param isSizeSupported the framework's own answer
     *   (`VideoCapabilities.isSizeSupported`). Null means "fall back
     *   to the range arithmetic".
     */
    data class EncoderOption(
        val codecName: String,
        val colorFormats: Set<Int>,
        val widthRange: IntRange,
        val heightRange: IntRange,
        val widthsForHeight: (Int) -> IntRange?,
        val isSizeSupported: ((Int, Int) -> Boolean)? = null
    )

    /**
     * The chosen encoder, color format and (possibly clamped) size.
     */
    data class Selection(
        val codecName: String,
        val colorFormat: Int,
        val width: Int,
        val height: Int
    )

    /**
     * Choose an encoder, a color format and a size.
     *
     * Preference order:
     *  1. an encoder that accepts the requested size EXACTLY;
     *  2. among equals, planar over semi-planar (planar needs no
     *     per-pixel swizzle in [VideoFramePipeline.bitmapToYuv420Fit]);
     *  3. otherwise the size with the best aspect-ratio fidelity,
     *     preferring the larger one on a tie — so a 4K request on a
     *     1080p-only device still produces the best picture the
     *     device can make instead of failing.
     *
     * @return null when the device has no AVC encoder that can take
     *   YUV bytes at all.
     */
    fun select(
        options: List<EncoderOption>,
        requestedWidth: Int,
        requestedHeight: Int
    ): Selection? {
        if (requestedWidth <= 0 || requestedHeight <= 0) return null

        var exact: Selection? = null
        var exactRank = Int.MAX_VALUE
        var best: Selection? = null
        var bestAspectError = Double.MAX_VALUE
        var bestArea = 0L
        var bestRank = Int.MAX_VALUE

        for (option in options) {
            val colorFormat = byteBufferColorFormat(option) ?: continue
            val rank = if (colorFormat == COLOR_PLANAR) 0 else 1
            val size = fitSize(
                requestedWidth = requestedWidth,
                requestedHeight = requestedHeight,
                widthRange = option.widthRange,
                heightRange = option.heightRange,
                widthsForHeight = option.widthsForHeight,
                isSizeSupported = option.isSizeSupported
            ) ?: continue
            val (w, h) = size
            if (w == requestedWidth && h == requestedHeight) {
                if (rank < exactRank) {
                    exactRank = rank
                    exact = Selection(option.codecName, colorFormat, w, h)
                }
                continue
            }
            val area = w.toLong() * h.toLong()
            val aspectError = kotlin.math.abs(
                w.toDouble() / h.toDouble() - requestedWidth.toDouble() / requestedHeight
            )
            // Better aspect wins; on a tie the larger area wins; on a
            // tie the simpler color format wins.
            val better = best == null ||
                aspectError < bestAspectError - 1e-6 ||
                (kotlin.math.abs(aspectError - bestAspectError) <= 1e-6 && area > bestArea) ||
                (kotlin.math.abs(aspectError - bestAspectError) <= 1e-6 &&
                    area == bestArea && rank < bestRank)
            if (better) {
                bestAspectError = aspectError
                bestArea = area
                bestRank = rank
                best = Selection(option.codecName, colorFormat, w, h)
            }
        }
        return exact ?: best
    }

    /**
     * The color format this encoder accepts for **ByteBuffer** input,
     * or null when it accepts none.
     *
     * `COLOR_FormatYUV420Flexible` is deliberately not accepted: it
     * is a Surface-only mode. Choosing it for a ByteBuffer encoder is
     * what broke the pipeline on devices whose default AVC encoder is
     * the software one.
     */
    private fun byteBufferColorFormat(option: EncoderOption): Int? = when {
        COLOR_PLANAR in option.colorFormats -> COLOR_PLANAR
        COLOR_SEMI_PLANAR in option.colorFormats -> COLOR_SEMI_PLANAR
        else -> null
    }

    /**
     * The largest size the encoder supports that is as close as
     * possible to the request.
     *
     * Android's API is per-height: `getSupportedWidthsFor(height)`
     * returns the RANGE of widths valid for that exact height, and
     * there is no rectangular "supported sizes" list. So the search
     * is: try the requested height first (the common case is an exact
     * hit), then walk the heights down and take the width that best
     * preserves the aspect ratio.
     *
     * H.264 requires even dimensions, so every candidate is rounded
     * down to an even number.
     */
    fun fitSize(
        requestedWidth: Int,
        requestedHeight: Int,
        widthRange: IntRange,
        heightRange: IntRange,
        widthsForHeight: (Int) -> IntRange?,
        isSizeSupported: ((Int, Int) -> Boolean)? = null
    ): Pair<Int, Int>? {
        if (requestedWidth <= 0 || requestedHeight <= 0) return null
        val minW = maxOf(widthRange.first, 2)
        val maxW = maxOf(widthRange.last, minW)
        val minH = maxOf(heightRange.first, 2)
        val maxH = maxOf(heightRange.last, minH)
        val reqAspect = requestedWidth.toDouble() / requestedHeight
        val reqEvenW = requestedWidth and 1.inv()
        val reqEvenH = requestedHeight and 1.inv()

        // 1. The exact request, when the encoder takes it.
        if (reqEvenW in minW..maxW && reqEvenH in minH..maxH) {
            val wRange = widthsForHeight(reqEvenH)
            val inRange = wRange != null && reqEvenW in wRange
            val frameworkOk = isSizeSupported?.invoke(reqEvenW, reqEvenH) ?: true
            if (inRange && frameworkOk) return reqEvenW to reqEvenH
        }

        // 2. Candidate heights: the requested one first, then down.
        val heights = ArrayList<Int>(64)
        if (reqEvenH in minH..maxH) heights.add(reqEvenH)
        var h = maxH and 1.inv()
        while (h >= minH) {
            if (h != reqEvenH) heights.add(h)
            h -= 2
        }

        var best: Pair<Int, Int>? = null
        var bestError = Double.MAX_VALUE
        var bestArea = 0L
        for (candidateH in heights) {
            val wRange = widthsForHeight(candidateH) ?: continue
            val lo = maxOf(wRange.first, minW)
            val hi = minOf(wRange.last, maxW)
            if (lo > hi) continue
            // The width that preserves the aspect ratio, rounded to an
            // EVEN number (H.264 requires even dimensions).
            //
            // Rounding matters: 3840/2160 * 1080 evaluates to
            // 1919.9999999999998 in binary floating point, so a
            // truncating `toInt()` produced 1918 instead of 1920 and
            // the encoder was asked for a size it did not list.
            val ideal = (kotlin.math.round(reqAspect * candidateH).toInt() and 1.inv())
                .coerceIn(lo, hi)
            // The ideal width for this height, then its even
            // neighbours in case the ideal is rejected. The order
            // MATTERS: the ideal is tried first, so the best possible
            // width is not missed in favour of a neighbour.
            val candidates = intArrayOf(ideal, ideal - 2, ideal + 2)
            for (rawW in candidates) {
                val w = rawW.coerceIn(lo, hi)
                if (w < lo || w > hi) continue
                if (isSizeSupported != null && !isSizeSupported(w, candidateH)) continue
                val error = kotlin.math.abs(w.toDouble() / candidateH - reqAspect)
                val area = w.toLong() * candidateH.toLong()
                val better = best == null ||
                    error < bestError - 1e-6 ||
                    (kotlin.math.abs(error - bestError) <= 1e-6 && area > bestArea)
                if (better) {
                    bestError = error
                    bestArea = area
                    best = w to candidateH
                }
                break
            }
        }
        return best
    }

    /**
     * The AVC encoders this device actually has, with their
     * capabilities.
     *
     * The framework is the only part that cannot be unit-tested, so
     * it is kept to a single function and the decision logic lives in
     * [select].
     */
    fun listEncoders(mime: String): List<EncoderOption> {
        val result = ArrayList<EncoderOption>()
        val infos: List<MediaCodecInfo> = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
        }.getOrDefault(emptyList<MediaCodecInfo>())
        for (info in infos) {
            if (!info.isEncoder) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val video = runCatching { caps.videoCapabilities }.getOrNull() ?: continue
            result.add(
                EncoderOption(
                    codecName = info.name,
                    colorFormats = caps.colorFormats.toSet(),
                    widthRange = runCatching { video.supportedWidths }
                        .getOrNull()
                        ?.let { it.lower..it.upper }
                        ?: (16..4096),
                    heightRange = runCatching { video.supportedHeights }
                        .getOrNull()
                        ?.let { it.lower..it.upper }
                        ?: (16..4096),
                    widthsForHeight = { height ->
                        runCatching { video.getSupportedWidthsFor(height) }
                            .getOrNull()
                            ?.let { it.lower..it.upper }
                    },
                    isSizeSupported = { w, h ->
                        runCatching { video.isSizeSupported(w, h) }.getOrDefault(false)
                    }
                )
            )
        }
        Log.i(
            TAG,
            "encoders for $mime: " + result.joinToString { o ->
                "${o.codecName} formats=${o.colorFormats.size} " +
                    "range=${o.widthRange.first}..${o.widthRange.last}x" +
                    "${o.heightRange.first}..${o.heightRange.last}"
            }
        )
        return result
    }
}
