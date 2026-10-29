package com.aicreatorstudio.app.ai.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeviceCapabilityDetector.recommend] using the
 * pure-function [DeviceCapabilityDetector.Probe] input. The live
 * `probe()` requires a real Android `Context`; the recommendation
 * policy itself is pure and is what the bottom sheet relies on.
 */
class DeviceCapabilityDetectorTest {

    @Test
    fun `flagship probe recommends high quality`() {
        val probe = DeviceCapabilityDetector.Probe(
            totalRamBytes = 8L * 1024L * 1024L * 1024L, // 8 GiB
            availableRamBytes = 6L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
            gpuSupportsCompute = true,
            sdkInt = 34
        )
        val rec = DeviceCapabilityDetector.recommend(probe)
        assertEquals(UpscaleProfile.HighQuality, rec)
    }

    @Test
    fun `low ram probe recommends fast`() {
        val probe = DeviceCapabilityDetector.Probe(
            totalRamBytes = 1L * 1024L * 1024L * 1024L, // 1 GiB
            availableRamBytes = 256L * 1024L * 1024L,
            cpuCoreCount = 8,
            gpuSupportsCompute = true,
            sdkInt = 34
        )
        val rec = DeviceCapabilityDetector.recommend(probe)
        assertEquals(UpscaleProfile.Fast, rec)
    }

    @Test
    fun `few cores probe recommends fast`() {
        val probe = DeviceCapabilityDetector.Probe(
            totalRamBytes = 6L * 1024L * 1024L * 1024L,
            availableRamBytes = 4L * 1024L * 1024L * 1024L,
            cpuCoreCount = 2, // way below LOW_CORE_THRESHOLD
            gpuSupportsCompute = true,
            sdkInt = 34
        )
        val rec = DeviceCapabilityDetector.recommend(probe)
        assertEquals(UpscaleProfile.Fast, rec)
    }

    @Test
    fun `no compute probe recommends fast regardless of ram and cores`() {
        val probe = DeviceCapabilityDetector.Probe(
            totalRamBytes = 12L * 1024L * 1024L * 1024L,
            availableRamBytes = 10L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
            gpuSupportsCompute = false,
            sdkInt = 34
        )
        val rec = DeviceCapabilityDetector.recommend(probe)
        assertEquals(UpscaleProfile.Fast, rec)
    }

    @Test
    fun `mid range probe defaults to fast for safety`() {
        val probe = DeviceCapabilityDetector.Probe(
            totalRamBytes = 3L * 1024L * 1024L * 1024L, // above LOW, below HIGH
            availableRamBytes = 2L * 1024L * 1024L * 1024L,
            cpuCoreCount = 6, // many cores
            gpuSupportsCompute = true,
            sdkInt = 30 // < 33 — not a flagship by the SDK bar
        )
        val rec = DeviceCapabilityDetector.recommend(probe)
        assertEquals(UpscaleProfile.Fast, rec)
    }

    @Test
    fun `probe helpers are internally consistent`() {
        val flagship = DeviceCapabilityDetector.Probe(
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            availableRamBytes = 4L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
            gpuSupportsCompute = true,
            sdkInt = 34
        )
        assertTrue(flagship.isHighRam)
        assertTrue(flagship.isManyCores)
        assertTrue(flagship.isFlagship)

        val lowEnd = DeviceCapabilityDetector.Probe(
            totalRamBytes = 1L * 1024L * 1024L * 1024L,
            availableRamBytes = 256L * 1024L * 1024L * 1024L,
            cpuCoreCount = 4,
            gpuSupportsCompute = false,
            sdkInt = 26
        )
        assertFalse(lowEnd.isHighRam)
        assertFalse(lowEnd.isManyCores)
        assertFalse(lowEnd.isFlagship)
    }

    @Test
    fun `recommendation is stable across recomputes`() {
        val probe = DeviceCapabilityDetector.Probe(
            totalRamBytes = 8L * 1024L * 1024L * 1024L,
            availableRamBytes = 4L * 1024L * 1024L * 1024L,
            cpuCoreCount = 8,
            gpuSupportsCompute = true,
            sdkInt = 34
        )
        val first = DeviceCapabilityDetector.recommend(probe)
        val second = DeviceCapabilityDetector.recommend(probe)
        assertEquals(first, second)
    }
}
