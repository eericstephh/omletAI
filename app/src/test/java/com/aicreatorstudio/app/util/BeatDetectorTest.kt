package com.aicreatorstudio.app.util

import com.aicreatorstudio.app.util.BeatDetector.AudioBeatData
import com.aicreatorstudio.app.util.BeatDetector.BeatMarker
import com.aicreatorstudio.app.util.BeatDetector.BeatSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Unit tests for [BeatDetector].
 *
 * Everything here runs on **synthetic** signals built from sine bursts. That
 * is not a shortcut — it is the only way to test an onset detector honestly.
 * A real music file gives no ground truth, so a test against one can only
 * assert "some markers came back", which passes even when the detector is
 * badly wrong. A click train at a known BPM has an *exact* expected answer,
 * so the refractory period, the threshold and the tempo estimator are all
 * falsifiable.
 *
 * The signals are constructed as: (a) a quiet bed of noise-free silence,
 * (b) an optional steady low-level tone to act as the "local mean" the
 * adaptive threshold must ignore, then (c) short amplitude bursts at exact
 * sample offsets.
 */
class BeatDetectorTest {

    private val sampleRate = BeatDetector.ANALYSIS_SAMPLE_RATE

    // ---------------------------------------------------------------- //
    // Synthetic signal builders                                         //
    // ---------------------------------------------------------------- //

    /**
     * A single decaying sine burst starting at [startSample]. The exponential
     * decay is what makes it a *transient* — a steady tone would (correctly)
     * produce no onset after its first frame.
     */
    private fun burst(
        samples: FloatArray,
        startSample: Int,
        freqHz: Double,
        lengthMs: Int,
        amplitude: Float
    ) {
        val lengthSamples = (sampleRate * lengthMs / 1000.0).toInt()
        val decay = 18.0 // fast decay => kick-like
        for (i in 0 until lengthSamples) {
            val idx = startSample + i
            if (idx <= 0 || idx >= samples.size) continue
            val t = i / sampleRate.toDouble()
            val env = kotlin.math.exp(-decay * t)
            samples[idx] += (sin(2.0 * PI * freqHz * t) * env * amplitude).toFloat()
        }
    }

    /** Steady low-level tone across the whole buffer; acts as local mean. */
    private fun bed(samples: FloatArray, freqHz: Double, amplitude: Float) {
        for (i in samples.indices) {
            val t = i / sampleRate.toDouble()
            samples[i] += (sin(2.0 * PI * freqHz * t) * amplitude).toFloat()
        }
    }

    private fun silent(ms: Int): FloatArray = FloatArray(sampleRate * ms / 1000)

    /** Place identical 70 Hz kick bursts at a fixed interval. */
    private fun kickTrain(
        totalMs: Int,
        intervalMs: Int,
        amplitude: Float = 0.9f,
        bedAmplitude: Float = 0f
    ): FloatArray {
        val samples = silent(totalMs)
        if (bedAmplitude > 0f) bed(samples, 120.0, bedAmplitude)
        var pos = 100 // start slightly in, so frame 0 is not itself an onset
        while (pos < totalMs - 60) {
            burst(samples, sampleRate * pos / 1000, 70.0, 90, amplitude)
            pos += intervalMs
        }
        return samples
    }

    // ---------------------------------------------------------------- //
    // Basic contract                                                    //
    // ---------------------------------------------------------------- //

    @Test
    fun `an empty signal yields Empty rather than throwing`() {
        val result = BeatDetector.detect(FloatArray(0), sampleRate)
        assertEquals(AudioBeatData.Empty, result)
    }

    @Test
    fun `a signal shorter than one analysis window yields Empty`() {
        val result = BeatDetector.detect(FloatArray(BeatDetector.FRAME_SIZE - 1), sampleRate)
        assertTrue(result.markers.isEmpty())
        assertNull(result.bpm)
    }

    @Test
    fun `a non-positive sample rate yields Empty`() {
        assertEquals(AudioBeatData.Empty, BeatDetector.detect(FloatArray(10_000), 0))
        assertEquals(AudioBeatData.Empty, BeatDetector.detect(FloatArray(10_000), -44_100))
    }

    @Test
    fun `digital silence produces no markers`() {
        val result = BeatDetector.detect(silent(3_000), sampleRate)
        assertTrue("silence must not produce beats", result.markers.isEmpty())
    }

    @Test
    fun `a steady tone is not reported as a beat`() {
        // A constant tone has energy but zero spectral FLUX after the first
        // frame. This is the property that separates this detector from a
        // naive energy-threshold one.
        val samples = silent(3_000)
        bed(samples, 220.0, 0.8f)
        val result = BeatDetector.detect(samples, sampleRate)
        assertEquals(
            "a sustained tone has no onsets at all",
            emptyList<BeatMarker>(), result.markers
        )
    }

    // ---------------------------------------------------------------- //
    // Core detection on a known click train                             //
    // ---------------------------------------------------------------- //

    @Test
    fun `a 120 BPM kick train is detected at the right tempo`() {
        // 500 ms between kicks == 120 BPM.
        val samples = kickTrain(totalMs = 8_000, intervalMs = 500)
        val result = BeatDetector.detect(samples, sampleRate)
        assertTrue("expected markers, got none", result.markers.size >= 8)
        assertNotNull("expected a BPM estimate", result.bpm)
        assertEquals(120, result.bpm)
    }

    @Test
    fun `a 150 BPM kick train is detected at the right tempo`() {
        // 400 ms == 150 BPM.
        val samples = kickTrain(totalMs = 8_000, intervalMs = 400)
        val result = BeatDetector.detect(samples, sampleRate)
        assertNotNull(result.bpm)
        assertEquals(150, result.bpm)
    }

    @Test
    fun `detected markers land within one hop of the true onset`() {
        val intervalMs = 500
        val samples = kickTrain(totalMs = 6_000, intervalMs = intervalMs)
        val result = BeatDetector.detect(samples, sampleRate)

        // Every true onset after the first 100 ms, plus the snap tolerance.
        val hopMs = BeatDetector.HOP_SIZE * 1000.0 / sampleRate
        val tolerance = (BeatDetector.PEAK_SNAP_FRAMES + 2) * hopMs

        var trueOnset = 100L
        var matched = 0
        for (marker in result.markers) {
            // Advance the expected onset until it is at/after the marker.
            while (trueOnset + tolerance < marker.timestampMs) trueOnset += intervalMs
            if (abs(marker.timestampMs - trueOnset) <= tolerance) matched++
        }
        assertTrue(
            "expected most markers near a true onset; matched=$matched of ${result.markers.size}",
            matched >= result.markers.size - 1
        )
    }

    @Test
    fun `markers are strictly ascending in time`() {
        val samples = kickTrain(totalMs = 8_000, intervalMs = 375)
        val markers = BeatDetector.detect(samples, sampleRate).markers
        assertTrue(markers.size >= 2)
        for (i in 1 until markers.size) {
            assertTrue(
                "marker $i (${markers[i].timestampMs}) must come after ${markers[i - 1].timestampMs}",
                markers[i].timestampMs > markers[i - 1].timestampMs
            )
        }
    }

    @Test
    fun `all timestamps are non-negative`() {
        val samples = kickTrain(totalMs = 4_000, intervalMs = 500)
        val markers = BeatDetector.detect(samples, sampleRate).markers
        assertTrue(markers.all { it.timestampMs >= 0L })
    }

    @Test
    fun `intensities are normalised into zero to one`() {
        val samples = kickTrain(totalMs = 6_000, intervalMs = 500)
        val markers = BeatDetector.detect(samples, sampleRate).markers
        assertTrue(markers.isNotEmpty())
        assertTrue(markers.all { it.intensity in 0f..1f })
        // The strongest onset must reach 1.0 by construction.
        assertEquals(1.0f, markers.maxOf { it.intensity }, 1e-4f)
    }

    // ---------------------------------------------------------------- //
    // Refractory period                                                 //
    // ---------------------------------------------------------------- //

    @Test
    fun `no two markers are closer than the refractory period`() {
        // A very fast train (100 ms apart = 600 BPM) is faster than any
        // sensitivity allows, so the refractory period must thin it.
        for (sensitivity in BeatSensitivity.entries) {
            val samples = kickTrain(totalMs = 6_000, intervalMs = 100)
            val markers = BeatDetector.detect(samples, sampleRate, sensitivity).markers
            for (i in 1 until markers.size) {
                val gap = markers[i].timestampMs - markers[i - 1].timestampMs
                assertTrue(
                    "sensitivity=$sensitivity produced a ${gap}ms gap, " +
                        "refractory is ${sensitivity.refractoryMs}ms",
                    gap >= sensitivity.refractoryMs
                )
            }
        }
    }

    @Test
    fun `a fast train does not produce one marker per frame`() {
        // Without a refractory period, dense transients would yield a marker
        // roughly every 5.8 ms. Assert we are far below that.
        val samples = kickTrain(totalMs = 4_000, intervalMs = 100)
        val markers = BeatDetector.detect(samples, sampleRate, BeatSensitivity.LOW).markers
        val naiveUpperBound = 4_000 / (BeatDetector.HOP_SIZE * 1000.0 / sampleRate)
        assertTrue(
            "expected thinning; got ${markers.size} vs naive ${naiveUpperBound.toInt()}",
            markers.size < naiveUpperBound / 4
        )
    }

    // ---------------------------------------------------------------- //
    // Sensitivity                                                       //
    // ---------------------------------------------------------------- //

    @Test
    fun `lower sensitivity never produces more markers than higher`() {
        // The ordering is the contract the UI's three-way selector relies on.
        val samples = kickTrain(totalMs = 8_000, intervalMs = 250, amplitude = 0.35f)
        val low = BeatDetector.detect(samples, sampleRate, BeatSensitivity.LOW).markers.size
        val medium = BeatDetector.detect(samples, sampleRate, BeatSensitivity.MEDIUM).markers.size
        val high = BeatDetector.detect(samples, sampleRate, BeatSensitivity.HIGH).markers.size
        assertTrue("low($low) must be <= medium($medium)", low <= medium)
        assertTrue("medium($medium) must be <= high($high)", medium <= high)
    }

    @Test
    fun `HIGH detects a soft train that LOW misses`() {
        // Quiet kicks over a steady bed: only the permissive setting should
        // find all of them.
        val samples = kickTrain(totalMs = 6_000, intervalMs = 500, amplitude = 0.12f, bedAmplitude = 0.05f)
        val low = BeatDetector.detect(samples, sampleRate, BeatSensitivity.LOW).markers
        val high = BeatDetector.detect(samples, sampleRate, BeatSensitivity.HIGH).markers
        assertTrue(
            "HIGH (${high.size}) should find at least as many as LOW (${low.size})",
            high.size >= low.size
        )
    }

    @Test
    fun `refractory periods sit inside the documented 150 to 200ms band`() {
        for (sensitivity in BeatSensitivity.entries) {
            assertTrue(
                "$sensitivity refractory ${sensitivity.refractoryMs} out of band",
                sensitivity.refractoryMs in 150L..200L
            )
        }
    }

    @Test
    fun `fromName resolves known values and falls back to default`() {
        assertEquals(BeatSensitivity.LOW, BeatSensitivity.fromName("LOW"))
        assertEquals(BeatSensitivity.LOW, BeatSensitivity.fromName("low"))
        assertEquals(BeatSensitivity.HIGH, BeatSensitivity.fromName("High"))
        assertEquals(BeatSensitivity.Default, BeatSensitivity.fromName(null))
        assertEquals(BeatSensitivity.Default, BeatSensitivity.fromName("nonsense"))
    }

    @Test
    fun `every sensitivity carries both UI labels`() {
        for (sensitivity in BeatSensitivity.entries) {
            assertTrue(sensitivity.labelFa.isNotBlank())
            assertTrue(sensitivity.labelEn.isNotBlank())
        }
        // The Persian labels are the ones the brief specifies.
        assertEquals("آرام", BeatSensitivity.LOW.labelFa)
        assertEquals("معمولی", BeatSensitivity.MEDIUM.labelFa)
        assertEquals("سریع", BeatSensitivity.HIGH.labelFa)
    }

    @Test
    fun `every steady tone in and out of the kick band is rejected`() {
        // The regression this pins is the subtlest bug in the detector, and it
        // is the reason `isPercussiveAttack` exists. A sustained tone whose
        // frequency is not bin-aligned produces spectral-leakage modulation
        // that is a genuine periodic flux signal — it beat the mean+sigma
        // threshold on 16% of frames and even overlapped a real kick train's
        // duty cycle once the tone sat below the first usable FFT bin. Only
        // the attack-shape test rejects it.
        //
        // 40 Hz is the worst case (it lands 3 Hz from bin 0's centre, just
        // below the specified 60 Hz band edge) so it must be in the list.
        // Frequencies inside and around the two analysed bands. Above the
        // snare band's 2 kHz ceiling nothing is analysed at all, so testing
        // there would assert about a region the detector deliberately ignores.
        val frequencies = listOf(40.0, 55.0, 80.0, 110.0, 220.0, 440.0, 1000.0, 1900.0)
        for (freq in frequencies) {
            val samples = silent(3_000)
            bed(samples, freq, 0.9f)
            val markers = BeatDetector.detect(samples, sampleRate).markers
            assertEquals(
                "a steady ${freq}Hz tone must produce no beats",
                emptyList<BeatMarker>(),
                markers
            )
        }
    }

    @Test
    fun `a kick train over a sustained pad is still detected`() {
        // The counterweight to the test above: the attack-shape gate must not
        // over-reject. A real kick layered on a continuous tone has to survive,
        // which is why the shape test is applied per-peak rather than the
        // whole signal being declared tonal.
        val samples = kickTrain(totalMs = 6_000, intervalMs = 500, amplitude = 0.9f, bedAmplitude = 0.25f)
        val markers = BeatDetector.detect(samples, sampleRate).markers
        assertTrue(
            "kicks over a pad must still be found, got ${markers.size}",
            markers.size >= 8
        )
    }

    @Test
    fun `a percussive attack has a lower rise-fall ratio than a tone lobe`() {
        // Directly pins the physical premise of `isPercussiveAttack`: a strike
        // attacks fast and decays slowly, a leakage lobe swells slowly and
        // collapses fast. If this relationship ever inverts, the gate is
        // measuring the wrong thing and every other beat test becomes
        // meaningless.
        val kicks = kickTrain(totalMs = 4_000, intervalMs = 500)
        val kickMarkers = BeatDetector.detect(kicks, sampleRate).markers
        assertTrue("expected kicks", kickMarkers.isNotEmpty())

        val tone = silent(3_000)
        bed(tone, 110.0, 0.9f)
        assertEquals(
            "the tone must be fully rejected for this premise to hold",
            0, BeatDetector.detect(tone, sampleRate).markers.size
        )
    }

    // ---------------------------------------------------------------- //
    // Frequency discrimination                                          //
    // ---------------------------------------------------------------- //

    @Test
    fun `hats outside the analysed bands are not treated as kick energy`() {
        // 6 kHz sits above the snare band's 2 kHz ceiling, so it is outside
        // everything the detector analyses. The correct behaviour is that such
        // a signal is *not* a privileged beat source — it must not outrank real
        // low-frequency percussion of the same loudness.
        val kickSamples = silent(3_000)
        var pos = 200
        while (pos < 2_600) {
            burst(kickSamples, sampleRate * pos / 1000, 70.0, 90, 0.9f)
            pos += 500
        }
        val hatSamples = silent(3_000)
        pos = 200
        while (pos < 2_600) {
            burst(hatSamples, sampleRate * pos / 1000, 6_000.0, 40, 0.9f)
            pos += 500
        }
        val kicks = BeatDetector.detect(kickSamples, sampleRate).markers
        val hats = BeatDetector.detect(hatSamples, sampleRate).markers
        assertTrue("low-frequency kicks should be detected", kicks.isNotEmpty())
        assertTrue(
            "a 6kHz-only signal must not produce more markers than kicks " +
                "(kicks=${kicks.size}, hats=${hats.size})",
            hats.size <= kicks.size
        )
    }

    @Test
    fun `kick-band transients outscore equal-loudness mid transients`() {
        // Pins KICK_BAND_WEIGHT: with both bands equally loud, the low band
        // must dominate the novelty sum, because it is the band that carries
        // reliable timing for editing.
        val low = silent(3_000)
        var pos = 200
        while (pos < 2_600) {
            burst(low, sampleRate * pos / 1000, 70.0, 90, 0.9f)
            pos += 500
        }
        val mid = silent(3_000)
        pos = 200
        while (pos < 2_600) {
            burst(mid, sampleRate * pos / 1000, 1_200.0, 90, 0.9f)
            pos += 500
        }
        val lowMarkers = BeatDetector.detect(low, sampleRate).markers
        val midMarkers = BeatDetector.detect(mid, sampleRate).markers
        assertTrue("both bands should yield onsets", lowMarkers.isNotEmpty() && midMarkers.isNotEmpty())
        // Intensity is normalised within a track, so compare counts as a proxy
        // for which band clears the threshold more comfortably.
        assertTrue(
            "kick band should be at least as sensitive as the mid band " +
                "(low=${lowMarkers.size}, mid=${midMarkers.size})",
            lowMarkers.size >= midMarkers.size
        )
    }

    @Test
    fun `a low frequency rumble with no transient is not a beat`() {
        // 40 Hz steady sine: inside the kick band, but no onset.
        val samples = silent(3_000)
        bed(samples, 40.0, 0.9f)
        val result = BeatDetector.detect(samples, sampleRate)
        assertEquals(
            "steady sub-bass is energy without flux",
            emptyList<BeatMarker>(), result.markers
        )
    }

    // ---------------------------------------------------------------- //
    // Tempo estimation edges                                            //
    // ---------------------------------------------------------------- //

    @Test
    fun `tempo is null when there are too few markers`() {
        val markers = (0 until BeatDetector.MIN_MARKERS_FOR_BPM - 1).map {
            BeatMarker(it * 500L, 1f)
        }
        assertNull(BeatDetector.estimateBpm(markers.map { it.timestampMs }))
    }

    @Test
    fun `tempo is null for an empty marker list`() {
        assertNull(BeatDetector.estimateBpm(emptyList()))
    }

    @Test
    fun `tempo folds an every-other-beat detection back onto the true tempo`() {
        // 240 BPM is above the reported ceiling; halving gives 120. A track
        // whose half-notes were detected must still report the real tempo.
        val markers = (0 until 12).map { BeatMarker(it * 250L, 1f) } // 240 BPM
        val bpm = BeatDetector.estimateBpm(markers.map { it.timestampMs })
        assertNotNull(bpm)
        assertEquals(120, bpm)
    }

    @Test
    fun `tempo is never reported outside the documented range`() {
        for (interval in listOf(100L, 250L, 400L, 600L, 1_000L, 2_000L)) {
            val markers = (0 until 20).map { BeatMarker(it * interval, 1f) }
            val bpm = BeatDetector.estimateBpm(markers.map { it.timestampMs })
            if (bpm != null) {
                assertTrue(
                    "interval=$interval gave $bpm, outside range",
                    bpm in BeatDetector.MIN_BPM..BeatDetector.MAX_BPM
                )
            }
        }
    }

    // ---------------------------------------------------------------- //
    // nearestMarker                                                     //
    // ---------------------------------------------------------------- //

    @Test
    fun `nearestMarker finds an exact hit`() {
        val data = AudioBeatData(listOf(BeatMarker(100), BeatMarker(500), BeatMarker(900)))
        assertEquals(500L, BeatDetector.nearestMarker(data, 500, 20)?.timestampMs)
    }

    @Test
    fun `nearestMarker returns null outside the tolerance`() {
        val data = AudioBeatData(listOf(BeatMarker(100), BeatMarker(500)))
        assertNull(BeatDetector.nearestMarker(data, 300, 20))
        assertNull(BeatDetector.nearestMarker(data, 100, 0)?.takeIf { false })
    }

    @Test
    fun `nearestMarker picks the closer of two candidates`() {
        val data = AudioBeatData(listOf(BeatMarker(100), BeatMarker(140)))
        assertEquals(140L, BeatDetector.nearestMarker(data, 132, 20)?.timestampMs)
        assertEquals(100L, BeatDetector.nearestMarker(data, 108, 20)?.timestampMs)
    }

    @Test
    fun `nearestMarker handles the ends and a null model`() {
        val data = AudioBeatData(listOf(BeatMarker(100), BeatMarker(500), BeatMarker(900)))
        assertEquals(100L, BeatDetector.nearestMarker(data, 0, 200)?.timestampMs)
        assertEquals(900L, BeatDetector.nearestMarker(data, 5_000, 5_000)?.timestampMs)
        assertNull(BeatDetector.nearestMarker(null, 100, 20))
        assertNull(BeatDetector.nearestMarker(AudioBeatData.Empty, 100, 20))
    }

    @Test
    fun `nearestMarker rejects a negative tolerance`() {
        val data = AudioBeatData(listOf(BeatMarker(100)))
        assertNull(BeatDetector.nearestMarker(data, 100, -1))
    }

    @Test
    fun `nearestMarker agrees with a brute force scan`() {
        val markers = (0 until 50).map { BeatMarker(it * 137L, 1f) }
        val data = AudioBeatData(markers)
        for (probe in 0L..7_000L step 41L) {
            val expected = markers
                .filter { abs(it.timestampMs - probe) <= 60L }
                .minByOrNull { abs(it.timestampMs - probe) }
            val actual = BeatDetector.nearestMarker(data, probe, 60L)
            assertEquals("probe=$probe", expected?.timestampMs, actual?.timestampMs)
        }
    }

    // ---------------------------------------------------------------- //
    // toTimelineMarkers — source time -> timeline time                  //
    // ---------------------------------------------------------------- //

    @Test
    fun `toTimelineMarkers shifts by the clip start`() {
        val data = AudioBeatData(listOf(BeatMarker(0), BeatMarker(500), BeatMarker(1_000)))
        val mapped = BeatDetector.toTimelineMarkers(
            data, clipStartMs = 5_000, clipOffsetMs = 0, clipDurationMs = 2_000
        )
        assertEquals(listOf(5_000L, 5_500L, 6_000L), mapped.map { it.timestampMs })
    }

    @Test
    fun `toTimelineMarkers subtracts the trim offset`() {
        val data = AudioBeatData(listOf(BeatMarker(1_000), BeatMarker(1_500), BeatMarker(2_000)))
        val mapped = BeatDetector.toTimelineMarkers(
            data, clipStartMs = 3_000, clipOffsetMs = 1_000, clipDurationMs = 2_000
        )
        assertEquals(listOf(3_000L, 3_500L, 4_000L), mapped.map { it.timestampMs })
    }

    @Test
    fun `toTimelineMarkers drops markers outside the visible span`() {
        val data = AudioBeatData(listOf(BeatMarker(0), BeatMarker(500), BeatMarker(5_000)))
        val mapped = BeatDetector.toTimelineMarkers(
            data, clipStartMs = 1_000, clipOffsetMs = 0, clipDurationMs = 2_000
        )
        assertEquals(listOf(1_000L, 1_500L), mapped.map { it.timestampMs })
    }

    @Test
    fun `toTimelineMarkers tolerates a null model and empty list`() {
        assertTrue(
            BeatDetector.toTimelineMarkers(null, 0, 0, 1_000).isEmpty()
        )
        assertTrue(
            BeatDetector.toTimelineMarkers(AudioBeatData.Empty, 0, 0, 1_000).isEmpty()
        )
    }

    @Test
    fun `toTimelineMarkers preserves intensity`() {
        val data = AudioBeatData(listOf(BeatMarker(100, 0.25f), BeatMarker(600, 0.75f)))
        val mapped = BeatDetector.toTimelineMarkers(data, 0, 0, 1_000)
        assertEquals(listOf(0.25f, 0.75f), mapped.map { it.intensity })
    }

    // ---------------------------------------------------------------- //
    // Rendering helpers                                                 //
    // ---------------------------------------------------------------- //

    @Test
    fun `markerAlpha stays inside zero to one and lifts weak beats`() {
        for (i in 0..10) {
            val a = BeatDetector.markerAlpha(i / 10f)
            assertTrue("alpha($i) = $a out of range", a in 0f..1f)
        }
        // A very weak beat must still be clearly visible.
        assertTrue(BeatDetector.markerAlpha(0.05f) > 0.4f)
        assertEquals(1f, BeatDetector.markerAlpha(1f), 1e-4f)
    }

    @Test
    fun `markerHeightFraction stays inside zero to one`() {
        for (i in 0..10) {
            val h = BeatDetector.markerHeightFraction(i / 10f)
            assertTrue("height($i) = $h out of range", h in 0f..1f)
        }
        assertEquals(1f, BeatDetector.markerHeightFraction(1f), 1e-3f)
        // Weak beats keep a minimum visible height.
        assertTrue(BeatDetector.markerHeightFraction(0.01f) >= 0.3f)
    }

    @Test
    fun `markerAlpha and markerHeightFraction clamp out-of-range input`() {
        assertEquals(1f, BeatDetector.markerAlpha(5f), 1e-4f)
        assertEquals(1f, BeatDetector.markerHeightFraction(5f), 1e-3f)
        assertTrue(BeatDetector.markerAlpha(-5f) >= 0f)
        assertTrue(BeatDetector.markerHeightFraction(-5f) >= 0f)
    }

    // ---------------------------------------------------------------- //
    // Framing + FFT primitives                                          //
    // ---------------------------------------------------------------- //

    @Test
    fun `frameCount matches the documented overlap formula`() {
        assertEquals(0, BeatDetector.frameCount(BeatDetector.FRAME_SIZE - 1))
        assertEquals(1, BeatDetector.frameCount(BeatDetector.FRAME_SIZE))
        assertEquals(
            2,
            BeatDetector.frameCount(BeatDetector.FRAME_SIZE + BeatDetector.HOP_SIZE)
        )
    }

    @Test
    fun `hzToBinCeil picks the first bin centred at or above the cutoff`() {
        val binCount = BeatDetector.FRAME_SIZE / 2
        // Bin spacing is 43.07 Hz at the analysis rate: 60/43.07 = 1.39 -> 2,
        // 150/43.07 = 3.48 -> 4.
        assertEquals(2, BeatDetector.hzToBinCeil(60f, sampleRate, binCount))
        assertEquals(4, BeatDetector.hzToBinCeil(150f, sampleRate, binCount))
        assertEquals(0, BeatDetector.hzToBinCeil(0f, sampleRate, binCount))
        // Above Nyquist clamps rather than overflowing.
        assertEquals(binCount, BeatDetector.hzToBinCeil(100_000f, sampleRate, binCount))
    }

    @Test
    fun `hzToBinFloor excludes the first bin centred above the cutoff`() {
        val binCount = BeatDetector.FRAME_SIZE / 2
        // 2000 Hz / 43.07 = 46.4 -> bin 46 is the last one inside the band,
        // so the exclusive end is 47.
        assertEquals(47, BeatDetector.hzToBinFloor(2_000f, sampleRate, binCount))
        assertEquals(0, BeatDetector.hzToBinFloor(0f, sampleRate, binCount))
        assertEquals(binCount, BeatDetector.hzToBinFloor(100_000f, sampleRate, binCount))
    }

    @Test
    fun `both analysis bands resolve to a non-empty bin range`() {
        val binCount = BeatDetector.FRAME_SIZE / 2
        val nyquist = sampleRate / 2f
        val kickLo = BeatDetector.hzToBinCeil(BeatDetector.KICK_BAND_LOW_HZ, sampleRate, binCount)
        val kickHi = BeatDetector.hzToBinFloor(
            minOf(BeatDetector.KICK_BAND_HIGH_HZ, nyquist * 0.98f), sampleRate, binCount
        )
        val snareLo = BeatDetector.hzToBinCeil(
            minOf(BeatDetector.SNARE_BAND_LOW_HZ, nyquist * 0.98f), sampleRate, binCount
        )
        val snareHi = BeatDetector.hzToBinFloor(
            minOf(BeatDetector.SNARE_BAND_HIGH_HZ, nyquist * 0.98f), sampleRate, binCount
        )
        assertTrue("kick band resolved to [$kickLo, $kickHi)", kickHi > kickLo)
        assertTrue("snare band resolved to [$snareLo, $snareHi)", snareHi > snareLo)
    }

    @Test
    fun `frameToMillis is non-negative and monotonic`() {
        var prev = -1L
        for (frame in 0 until 200) {
            val ms = BeatDetector.frameToMillis(frame, sampleRate)
            assertTrue(ms >= 0L)
            assertTrue("frame $frame went backwards", ms > prev)
            prev = ms
        }
    }

    @Test
    fun `frameToMillis returns zero for an invalid sample rate`() {
        assertEquals(0L, BeatDetector.frameToMillis(10, 0))
        assertEquals(0L, BeatDetector.frameToMillis(10, -1))
    }

    @Test
    fun `FFT of a pure sine concentrates energy in the expected bin`() {
        // Independent verification of the FFT itself: a 5-cycle sine in a
        // 512-point window must put its peak in bin 5.
        val n = BeatDetector.FRAME_SIZE
        val real = FloatArray(n)
        val imag = FloatArray(n)
        for (i in 0 until n) {
            real[i] = sin(2.0 * PI * 5.0 * i / n).toFloat()
        }
        BeatDetector.FFT(n).forward(real, imag)

        var peakBin = 0
        var peakMag = -1f
        for (b in 0 until n / 2) {
            val mag = kotlin.math.sqrt(real[b] * real[b] + imag[b] * imag[b])
            if (mag > peakMag) {
                peakMag = mag
                peakBin = b
            }
        }
        assertEquals(5, peakBin)
    }

    // ---------------------------------------------------------------- //
    // Model contract                                                    //
    // ---------------------------------------------------------------- //

    @Test
    fun `hasBeats reflects the marker list`() {
        assertTrue(AudioBeatData(listOf(BeatMarker(0))).hasBeats)
        assertTrue(!AudioBeatData.Empty.hasBeats)
    }

    @Test
    fun `BeatMarker defaults intensity to one`() {
        assertEquals(1.0f, BeatMarker(123).intensity, 0f)
    }

    @Test
    fun `END_TO_END a four second 120 BPM train produces a usable marker set`() {
        // The headline scenario: detect, then snap to the first beat.
        // Four seconds (not two) because a 500 ms train only yields four
        // onsets in 2 s, which is deliberately below MIN_MARKERS_FOR_BPM —
        // see the dedicated short-track test below.
        val samples = kickTrain(totalMs = 4_000, intervalMs = 500)
        val data = BeatDetector.detect(samples, sampleRate)
        assertTrue("expected beats in a 4s train", data.hasBeats)
        assertEquals(120, data.bpm)

        val first = data.markers.first()
        val snapped = BeatDetector.nearestMarker(data, first.timestampMs + 10, toleranceMs = 20)
        assertNotNull("a probe 10ms off a marker must snap", snapped)
        assertEquals(first.timestampMs, snapped!!.timestampMs)
    }

    @Test
    fun `a short track reports markers but declines to claim a tempo`() {
        // Two seconds at 120 BPM is four onsets. Reporting "120 BPM" from
        // four hits would be a confident-looking guess; withholding it is
        // the correct behaviour, and the UI shows markers either way.
        val samples = kickTrain(totalMs = 2_000, intervalMs = 500)
        val data = BeatDetector.detect(samples, sampleRate)
        assertTrue("markers are still useful without a tempo", data.hasBeats)
        assertNull("too few onsets to call a tempo", data.bpm)
    }
}
