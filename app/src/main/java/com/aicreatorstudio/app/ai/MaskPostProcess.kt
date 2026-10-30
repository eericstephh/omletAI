package com.aicreatorstudio.app.ai

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared mask post-processing math for the Background Remove tool.
 *
 * This is the feature-parity core identified in
 * `docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-10-25.md` (§8): the raw
 * segmentation model produces a low-resolution, slightly wobbly mask,
 * and the *visible* edge quality comes from a post-process chain that
 * runs on the mask itself — not from the model.
 *
 * The chain, in the order CapCut's `DilateErode` shader runs it:
 *
 * 1. **Expand** (`expansion`): `> 0` dilates (grows the subject),
 *    `< 0` erodes (shrinks it). Dilation is an exact separable max
 *    filter; erosion is the shader's *soft* form
 *    `pow(clamp(min − (max − center)·(|k|/5), 0, 1), 1 + |k|/4)`.
 * 2. **Feather** (`feather`): separable Gaussian, implemented as the
 *    classical 3-pass box blur (indistinguishable from a real Gaussian
 *    at these radii and O(N) regardless of radius), wrapped in the
 *    shader's gamma weighting `blur(m^1.4)^(1/1.4)` — see
 *    [FEATHER_GAMMA].
 * 3. **Reverse**: `mask ← 1 − mask`.
 *
 * ## Units
 *
 * Both [expansion] and [feather] are stored **normalized** so the look
 * is resolution-independent (CapCut's shader works in UV space the
 * same way). The device-pixel mappings below were *derived* from the
 * shader code (`kernelSize = ui/8`, tap step `|k|/15 / (aspect·normSize)`
 * with `normSize = 240`) — CapCut's exact UI slider ranges were never
 * observed, only its internal transforms (see §11 of the doc).
 *
 * - `expansion ∈ −1..+1`. `+1` ≈ CapCut's +100 UI value, whose shader
 *   reach is `shortSide / 240 · 12.5 = shortSide / 19.2` pixels.
 * - `feather ∈ 0..1`. `1.0` ≈ CapCut's `inputEdgeSmooth = 1.0`, whose
 *   Gaussian sigma is `4 / 240 · shortSide = shortSide / 60` pixels.
 *
 * The gamma weighting around the blur *is* implemented (2026-10-29);
 * see [FEATHER_GAMMA] for the formula, the evidence, and the one
 * remaining ambiguity in it.
 *
 * ## Performance
 *
 * Every pass is O(N) — no pass samples radius² pixels (a naïve window
 * max/blur is exactly what made the chain unusable inside the export
 * loop). A full chain at the sidecar's native resolution (~405×720) is
 * a handful of float sweeps per mask.
 *
 * Everything in this file is pure JVM code (no `android.*`) so it is
 * unit testable; see `MaskPostProcessTest`.
 */
object MaskPostProcess {

    /**
     * CapCut's `normSize` shader constant. All kernel offsets are
     * expressed as fractions of this virtual size, which is what makes
     * the look resolution-independent.
     */
    const val NORM_REF_SIZE: Float = 240f

    /**
     * The DilateErode shader converts the user value with
     * `kernelSize = erode_dilate_kernel_size / 8`, so a ±100 UI slider
     * reaches ±12.5 kernel units. This constant is that `100 / 8`.
     */
    const val EXPANSION_UI_SCALE: Float = 12.5f

    /** Default [feather] for freshly created settings (≈ the old 2 `featherPx` at a 720-side mask). */
    const val DEFAULT_FEATHER: Float = 0.15f

    /**
     * The DilateErode shader's edge-smooth weighting: the mask is
     * raised to this power **before** the Gaussian and to its
     * reciprocal **after** it —
     *
     * ```
     * out = blur(mask ^ 1.4) ^ (1 / 1.4)
     * ```
     *
     * ### Why this is safe
     *
     * The two powers are exact inverses, so any *constant* field
     * survives unchanged: flat foreground stays 1, flat background
     * stays 0. The weighting therefore cannot shift or dim the mask
     * as a whole — it only reshapes the ramp the blur creates. It is
     * also the identity when the blur is skipped, which is why it can
     * never disturb the `isNoOp` fast path.
     *
     * ### What it does to the edge
     *
     * The pre-power darkens midtones (`m^1.4 ≤ m`), the post-power
     * lifts them back (`x^(1/1.4) ≥ x`), so on a step edge the
     * composite is `Φ^(1/1.4)` where `Φ` is the plain blurred ramp:
     * the 50 % crossing moves *outward* (to ≈ 0.61) and the falloff
     * steepens on the outside while gaining a longer inner tail. Net
     * effect at `feather = 1` is roughly a 20 % outward bias of the
     * contour with the same total reach.
     *
     * ### Evidence and the remaining ambiguity
     *
     * The ordering is recorded as **observed** in
     * `docs/CAPCUT_RE_BACKGROUND_REMOVAL_2026-10-25.md` §8.5
     * ("`gamma = 1.4` before and `1/1.4` after") and it is *not* in
     * that doc's §11 list of unconfirmed items; the earlier note in
     * this file claiming the direction was unverified was a hedge
     * without a source and has been removed.
     *
     * What genuinely cannot be settled from the shader dump is which
     * of the two exponents is applied first. The other reading —
     * `blur(m^(1/1.4))^1.4` — is the mirror image (an *inward* bias
     * of the same magnitude). §8.5 is taken literally here; flipping
     * this constant to its reciprocal is the entire change if a
     * device comparison ever shows the opposite.
     */
    const val FEATHER_GAMMA: Float = 1.4f

    /**
     * Below this sigma the 3-box Gaussian degenerates to zero-radius
     * passes (a no-op); skipping it keeps the export's default path
     * cheap.
     */
    private const val MIN_USEFUL_SIGMA: Float = 0.80f

    /**
     * Device-pixel reach of [expansion] for a mask whose short side is
     * [shortSide]: `+1` grows the subject by `shortSide / 20` pixels,
     * matching the shader's `|kernelSize| / normSize · shortSide` at
     * the ±100 kernel scale.
     */
    fun expansionRadiusPx(expansion: Float, shortSide: Int): Int {
        if (shortSide <= 0) return 0
        val e = expansion.coerceIn(-1f, 1f)
        return (abs(e) * shortSide / 20f).roundToInt()
    }

    /**
     * Gaussian sigma (pixels) for [feather] with a mask short side of
     * [shortSide]: `feather = 1` → `shortSide / 60`.
     */
    fun featherSigmaPx(feather: Float, shortSide: Int): Float {
        if (shortSide <= 0) return 0f
        return feather.coerceIn(0f, 1f) * shortSide / 60f
    }

    /** True when the chain would not change the mask at all. */
    fun isNoOp(expansion: Float, feather: Float, reverse: Boolean): Boolean =
        expansion.coerceIn(-1f, 1f) == 0f &&
            feather.coerceIn(0f, 1f) < 0.01f &&
            !reverse

    /**
     * Runs the full chain in place on [alpha] (row-major 0..1 floats,
     * `w×h`). Returns [alpha] for fluency.
     *
     * @param shortSideOverride the short side the normalized knobs are
     *   mapped against. `null` (the default) uses `min(w, h)`, which is
     *   right whenever the array *is* the mask. Callers that process a
     *   mask at a different resolution than they composite at pass their
     *   own value so the look stays resolution-independent.
     *
     *   2026-10-29 — this parameter is new here. `MaskBitmaps` had a
     *   `shortSideOverride` of its own that it computed and then **never
     *   passed on**, so the KDoc promised resolution-independent scaling
     *   that the code did not do. It is wired through now.
     */
    fun apply(
        alpha: FloatArray,
        w: Int,
        h: Int,
        expansion: Float,
        feather: Float,
        reverse: Boolean,
        shortSideOverride: Int? = null
    ): FloatArray {
        if (w <= 0 || h <= 0) return alpha
        val shortSide = shortSideOverride ?: minOf(w, h)
        val radius = expansionRadiusPx(expansion, shortSide)
        if (radius > 0) {
            if (expansion > 0f) {
                dilate(alpha, w, h, radius)
            } else {
                softErode(alpha, w, h, radius, abs(expansion) * EXPANSION_UI_SCALE)
            }
        }
        val sigma = featherSigmaPx(feather, shortSide)
        if (sigma >= MIN_USEFUL_SIGMA) gaussianFeather(alpha, w, h, sigma)
        if (reverse) invert(alpha)
        return alpha
    }

    /** `alpha ← 1 − alpha` for every pixel. */
    fun invert(alpha: FloatArray) {
        for (i in alpha.indices) alpha[i] = 1f - alpha[i]
    }

    /**
     * Exact rectangular **max** filter (the subject grows by
     * [radiusPx]). Two O(N) passes — max over rows, then max over
     * columns — yield the exact 2-D max over the `(2r+1)²` window.
     */
    fun dilate(alpha: FloatArray, w: Int, h: Int, radiusPx: Int) {
        if (radiusPx <= 0 || w <= 0 || h <= 0) return
        val tmp = FloatArray(alpha.size)
        windowPass(alpha, tmp, w, h, horizontal = true, radius = radiusPx, wantMax = true)
        windowPass(tmp, alpha, w, h, horizontal = false, radius = radiusPx, wantMax = true)
    }

    /**
     * The DilateErode shader's **soft erosion** (`kernelSize < 0`):
     *
     * ```
     * s   = clamp(min − (max − center) · (|k|/5), 0, 1)
     * out = s ^ (1 + |k|/4)
     * ```
     *
     * where `min`/`max` are the exact 2-D min/max over the `(2r+1)²`
     * window and `center` is the untouched pixel. A pixel fully
     * surrounded by background (`min ≈ max ≈ center ≈ 0`) stays 0, and
     * the power term pushes mid-range values down — so the erosion
     * bites hardest exactly where the neighbourhood is confidently
     * background.
     *
     * @param radiusPx window radius in device pixels
     * @param kUnits CapCut kernel units (`|expansion| · [EXPANSION_UI_SCALE]`)
     */
    fun softErode(alpha: FloatArray, w: Int, h: Int, radiusPx: Int, kUnits: Float) {
        if (radiusPx <= 0 || w <= 0 || h <= 0) return
        val min = minMap(alpha, w, h, radiusPx)
        val max = maxMap(alpha, w, h, radiusPx)
        val scale = kUnits / 5f
        val gamma = 1f + kUnits / 4f
        for (i in alpha.indices) {
            val c = alpha[i]
            val s = (min[i] - (max[i] - c) * scale).coerceIn(0f, 1f)
            alpha[i] = s.pow(gamma)
        }
    }

    /**
     * Separable Gaussian blur over the alpha channel as three box
     * passes (the classical "boxes for Gaussian" approximation — for
     * these sigma values visually indistinguishable from a true
     * Gaussian and O(N) regardless of radius). Edges are clamped
     * (replicated), matching the shader's `proc_border 1` semantics.
     *
     * When [gamma] differs from 1 the whole blur is wrapped in the
     * shader's weighting: `blur(m^γ)^(1/γ)` — see [FEATHER_GAMMA].
     * Note it wraps the *three passes as a group*, not each pass;
     * per-pass application would compound into `γ³`.
     *
     * @param gamma the pre-blur exponent; `1f` disables the weighting
     *   and reproduces the plain Gaussian (kept for tests and for
     *   isolating the gamma's visual effect).
     */
    fun gaussianFeather(
        alpha: FloatArray,
        w: Int,
        h: Int,
        sigmaPx: Float,
        gamma: Float = FEATHER_GAMMA
    ) {
        if (sigmaPx < MIN_USEFUL_SIGMA || w <= 0 || h <= 0) return
        // gamma == 1f must stay bit-identical to the old plain blur.
        val weighted = gamma > 0f && gamma != 1f
        if (weighted) gammaPass(alpha, gamma)
        val radii = boxesForGauss(sigmaPx.toDouble(), 3)
        val tmp = FloatArray(alpha.size)
        for (r in radii) {
            if (r <= 0) continue
            boxPass(alpha, tmp, w, h, horizontal = true, radius = r)
            boxPass(tmp, alpha, w, h, horizontal = false, radius = r)
        }
        if (weighted) gammaPass(alpha, 1f / gamma)
    }

    /**
     * `alpha ← alpha^exponent` for every pixel, clamped to 0..1 first.
     *
     * The clamp is not cosmetic: `Math.pow` returns **NaN** for a
     * negative base with a fractional exponent, and one NaN spreads
     * across the whole mask through the blur's window sums. A mask
     * should already be in 0..1 (8-bit source, and every pass in this
     * file is a convex combination), so this only ever guards against
     * a caller handing us garbage.
     *
     * Cost: two extra full sweeps of `pow` per mask. That is a few
     * milliseconds at sidecar resolution — small next to the model
     * forward pass, and it only runs when the blur runs. A 256-entry
     * LUT would make it free if export profiling ever flags it.
     */
    fun gammaPass(alpha: FloatArray, exponent: Float) {
        if (exponent == 1f) return
        for (i in alpha.indices) {
            alpha[i] = alpha[i].coerceIn(0f, 1f).pow(exponent)
        }
    }

    // ----------------------------------------------------------------
    // primitives
    // ----------------------------------------------------------------

    /**
     * One clamped sliding-window max/min pass, horizontal or vertical,
     * in O(n) via a monotonic deque. Reads [src], writes [dst]; the
     * arrays may alias (in-place order is supported).
     */
    private fun windowPass(
        src: FloatArray,
        dst: FloatArray,
        w: Int,
        h: Int,
        horizontal: Boolean,
        radius: Int,
        wantMax: Boolean
    ) {
        val lineLen = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w     // stride inside one line
        val lineStep = if (horizontal) w else 1 // stride between lines
        val q = IntArray(lineLen)
        for (line in 0 until lines) {
            val base = line * lineStep
            var front = 0
            var back = 0
            // Prime the deque with the samples that are already inside
            // the FIRST clamped window [0..radius]. Without this the
            // indices 0..radius-1 are never pushed and every left-edge
            // window silently drops them (2026-10-26 border bug).
            val prime = minOf(radius, lineLen)
            for (i in 0 until prime) {
                val pv = src[base + step * i]
                while (back > front) {
                    val lv = src[base + step * q[back - 1]]
                    val dominated = if (wantMax) lv <= pv else lv >= pv
                    if (!dominated) break
                    back--
                }
                q[back++] = i
            }
            for (o in 0 until lineLen) {
                // Push the newest element that just entered the window's
                // right edge; drop everything it dominates.
                val right = o + radius
                if (right < lineLen) {
                    val rv = src[base + step * right]
                    while (back > front) {
                        val lv = src[base + step * q[back - 1]]
                        val dominated = if (wantMax) lv <= rv else lv >= rv
                        if (!dominated) break
                        back--
                    }
                    q[back++] = right
                }
                // Evict indices that left the window's left edge.
                val left = o - radius
                while (front < back && q[front] < left) front++
                dst[base + step * o] = src[base + step * q[front]]
            }
        }
    }

    /** Exact 2-D max over every `(2r+1)²` window. Allocates its result. */
    private fun maxMap(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        windowPass(src, tmp, w, h, horizontal = true, radius = r, wantMax = true)
        windowPass(tmp, out, w, h, horizontal = false, radius = r, wantMax = true)
        return out
    }

    /** Exact 2-D min over every `(2r+1)²` window. Allocates its result. */
    private fun minMap(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        windowPass(src, tmp, w, h, horizontal = true, radius = r, wantMax = false)
        windowPass(tmp, out, w, h, horizontal = false, radius = r, wantMax = false)
        return out
    }

    /**
     * One clamped box-blur pass (sliding sum, O(n)) over rows or
     * columns. Reads [src], writes [dst].
     *
     * Border semantics are **clamped replication** — samples outside
     * the line repeat the edge sample — matching the shader's
     * `proc_border 1`: the window sum at position `o` is
     * `Σ v[clamp(i)]` for `i ∈ [o−r, o+r]`, so the flat regions at
     * either end keep their value and the blur never darkens or
     * brightens the borders (2026-10-26 border bug: the old sliding
     * bookkeeping shifted every window one sample right and lost the
     * edge replication, which degraded the whole line after three
     * passes).
     */
    private fun boxPass(
        src: FloatArray,
        dst: FloatArray,
        w: Int,
        h: Int,
        horizontal: Boolean,
        radius: Int
    ) {
        val lineLen = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w
        val lineStep = if (horizontal) w else 1
        val last = lineLen - 1
        val norm = 1f / (2 * radius + 1)
        for (line in 0 until lines) {
            val base = line * lineStep
            // o = 0 window is [−r, r] clamped: `radius` replicas of the
            // left edge sample plus the clamped real samples 0..r
            // (which degenerate to right-edge replicas when r ≥ line).
            val v0 = src[base]
            var acc = v0 * radius
            for (i in 0..radius) acc += src[base + step * minOf(i, last)]
            for (o in 0 until lineLen) {
                dst[base + step * o] = acc * norm
                // Moving o → o+1: replicated edge bookkeeping — the
                // left count of v[0]/v[last] shrinks by one per step,
                // so sample `o+r+1` enters and `o−r` leaves (NOT
                // `o+r`/`o−r−1`; inside the line both reduce to the
                // same real window).
                val inIdx = (o + radius + 1).coerceIn(0, last)
                val outIdx = (o - radius).coerceIn(0, last)
                acc += src[base + step * inIdx] - src[base + step * outIdx]
            }
        }
    }

    /**
     * The classical "boxes for Gaussian" split: radii `r1..rn` whose
     * three box passes approximate a Gaussian of standard deviation
     * [sigma]. Returns *radii* (a radius of 0 disables that pass).
     */
    private fun boxesForGauss(sigma: Double, n: Int): IntArray {
        if (sigma <= 0.0) return IntArray(n)
        val wIdeal = sqrt((12.0 * sigma * sigma / n) + 1.0)
        var wl = floor(wIdeal).toInt()
        if (wl % 2 == 0) wl--
        if (wl < 1) wl = 1
        val wu = wl + 2
        val mIdeal = (12.0 * sigma * sigma - n * wl * wl - n * 4.0 * wl - n * 3.0) / (-4.0 * wl - 4.0)
        val m = mIdeal.roundToInt()
        return IntArray(n) { i ->
            val width = if (i < m) wl else wu
            (width - 1) / 2
        }
    }
}
