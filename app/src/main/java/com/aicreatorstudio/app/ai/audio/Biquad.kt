package com.aicreatorstudio.app.ai.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One second-order IIR section (RBJ audio-EQ cookbook form),
 * direct-form I, coefficients pre-normalised by `a0`.
 *
 * Pure Kotlin with no Android dependency so the whole filter chain
 * is unit-testable on the JVM: the tests feed sine waves and assert
 * on the measured attenuation, which is the only way to prove a
 * filter does what its comment claims.
 *
 * State is per instance, so a filter MUST NOT be shared between
 * concurrent streams. [reset] exists for block processing.
 */
class Biquad internal constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double
) {

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = xd
        y2 = y1
        y1 = y
        return y.toFloat()
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    companion object {

        /** Butterworth Q for a single 2nd-order section. */
        const val Q_BUTTERWORTH_2: Double = 0.7071067811865476

        /** Butterworth section Qs for a 4th-order cascade (low → high). */
        val Q_BUTTERWORTH_4: DoubleArray = doubleArrayOf(0.541196100146197, 1.306562964876377)

        fun lowPass(sampleRate: Double, cutoffHz: Double, q: Double): Biquad {
            val (cosW, sinW) = omega(sampleRate, cutoffHz)
            val alpha = sinW / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 - cosW) / 2.0) / a0
            val b1 = (1.0 - cosW) / a0
            val b2 = b0
            val a1 = (-2.0 * cosW) / a0
            val a2 = (1.0 - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }

        fun highPass(sampleRate: Double, cutoffHz: Double, q: Double): Biquad {
            val (cosW, sinW) = omega(sampleRate, cutoffHz)
            val alpha = sinW / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = ((1.0 + cosW) / 2.0) / a0
            val b1 = (-(1.0 + cosW)) / a0
            val b2 = b0
            val a1 = (-2.0 * cosW) / a0
            val a2 = (1.0 - alpha) / a0
            return Biquad(b0, b1, b2, a1, a2)
        }

        /**
         * Butterworth cascade of [order] (2 or 4) at [cutoffHz].
         *
         * A 4th-order section gives ~24 dB/octave, which is what a
         * decimation anti-alias filter needs: a 2nd-order section
         * leaks roughly a fifth of the energy that folds back into
         * the speech band when 48 kHz is decimated to 16 kHz.
         */
        fun cascade(
            sampleRate: Double,
            cutoffHz: Double,
            order: Int,
            lowPass: Boolean
        ): Array<Biquad> = when (order) {
            2 -> arrayOf(
                if (lowPass) lowPass(sampleRate, cutoffHz, Q_BUTTERWORTH_2)
                else highPass(sampleRate, cutoffHz, Q_BUTTERWORTH_2)
            )
            4 -> Q_BUTTERWORTH_4.map { q ->
                if (lowPass) lowPass(sampleRate, cutoffHz, q)
                else highPass(sampleRate, cutoffHz, q)
            }.toTypedArray()
            else -> throw IllegalArgumentException("order must be 2 or 4 (got $order)")
        }

        /** `(cos w0, sin w0)` with a Nyquist guard. */
        private fun omega(sampleRate: Double, cutoffHz: Double): Pair<Double, Double> {
            require(sampleRate > 0.0) { "sampleRate must be positive" }
            val nyquist = sampleRate / 2.0
            // Clamping instead of throwing: a caller that asks for a
            // cutoff at/above Nyquist gets the closest legal filter
            // rather than a crash mid-transcription.
            val f = cutoffHz.coerceIn(1.0, nyquist * 0.99)
            val w0 = 2.0 * PI * f / sampleRate
            return Pair(cos(w0), sin(w0))
        }

        /** Applies [filters] in series to one sample. */
        fun processChain(filters: Array<Biquad>, x: Float): Float {
            var v = x
            for (f in filters) v = f.process(v)
            return v
        }

        /** |H| of a single section at [freqHz] — used by tests. */
        internal fun magnitudeAt(
            sampleRate: Double,
            cutoffHz: Double,
            q: Double,
            freqHz: Double,
            lowPass: Boolean
        ): Double {
            val f = if (lowPass) lowPass(sampleRate, cutoffHz, q)
            else highPass(sampleRate, cutoffHz, q)
            val w = 2.0 * PI * freqHz / sampleRate
            val z1r = cos(w); val z1i = -sin(w)
            val z2r = cos(2 * w); val z2i = -sin(2 * w)
            val numR = f.b0 + f.b1 * z1r + f.b2 * z2r
            val numI = f.b1 * z1i + f.b2 * z2i
            val denR = 1.0 + f.a1 * z1r + f.a2 * z2r
            val denI = f.a1 * z1i + f.a2 * z2i
            val num = sqrt(numR * numR + numI * numI)
            val den = sqrt(denR * denR + denI * denI)
            return if (den == 0.0) Double.MAX_VALUE else num / den
        }
    }
}
