package com.aicreatorstudio.app.ai.upscale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the ESRGAN upscale safety gate
 * ([EsrganUpscalePipeline.gateRefusal]) — the pure policy that
 * keeps weak devices from OOMing instead of crashing.
 */
class UpscaleGateTest {

    private fun gate(
        availRamMb: Long = 4000,
        cores: Int = 8,
        abi: String = "arm64-v8a",
        freeStorageBytes: Long = Long.MAX_VALUE
    ) = EsrganUpscalePipeline.Gate(
        totalRamMb = 8000,
        availRamMb = availRamMb,
        cores = cores,
        abi = abi,
        api = 34,
        freeStorageBytes = freeStorageBytes
    )

    @Test
    fun `healthy flagship passes both scales`() {
        assertNull(EsrganUpscalePipeline.gateRefusal(gate(), 2, 5_000_000L))
        assertNull(EsrganUpscalePipeline.gateRefusal(gate(), 4, 5_000_000L))
    }

    @Test
    fun `low ram refuses with a user-facing reason`() {
        val reason = EsrganUpscalePipeline.gateRefusal(gate(availRamMb = 500), 4, 5_000_000L)
        assertNotNull(reason)
        assertTrue(reason!!.contains("RAM", ignoreCase = true))
    }

    @Test
    fun `four-x needs more headroom than two-x`() {
        // 1000 MB free: enough for 2x, not for 4x.
        assertNull(EsrganUpscalePipeline.gateRefusal(gate(availRamMb = 1000), 2, 5_000_000L))
        assertNotNull(EsrganUpscalePipeline.gateRefusal(gate(availRamMb = 1000), 4, 5_000_000L))
    }

    @Test
    fun `single core device is refused`() {
        val reason = EsrganUpscalePipeline.gateRefusal(gate(cores = 1), 2, 5_000_000L)
        assertNotNull(reason)
        assertTrue(reason!!.contains("core", ignoreCase = true))
    }

    @Test
    fun `unknown abi is refused`() {
        val reason = EsrganUpscalePipeline.gateRefusal(gate(abi = "mips"), 2, 5_000_000L)
        assertNotNull(reason)
        assertTrue(reason!!.contains("mips"))
    }

    @Test
    fun `all three shipping abis pass`() {
        listOf("arm64-v8a", "armeabi-v7a", "x86_64").forEach { abi ->
            assertNull(
                "abi $abi should pass",
                EsrganUpscalePipeline.gateRefusal(gate(abi = abi), 2, 5_000_000L)
            )
        }
    }

    @Test
    fun `tight storage is refused`() {
        val reason = EsrganUpscalePipeline.gateRefusal(
            gate(freeStorageBytes = 100_000_000L), 2, 5_000_000L
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("storage", ignoreCase = true))
    }

    @Test
    fun `model id constant matches the lockfile entry`() {
        assertEquals("esrgan_tflite_4x", EsrganUpscalePipeline.MODEL_ID)
    }

    @Test
    fun `output cap is 4k - encoders and the java heap cannot do 8k`() {
        // 2026-09-08 CRASH FIX — the old 8K cap let a 1080p 4x run
        // request a 33 MP output (132 MB bitmap + ~50 MB YUV frame +
        // 132 MB pixel array) which OOM-crashed the process and, even
        // when it survived, was rejected by every phone AVC encoder.
        // The cap is now 4K UHD; the neural pass still runs at full
        // factor and is fit-down per tile.
        assertEquals(3840, EsrganUpscalePipeline.MAX_OUTPUT_W)
        assertEquals(2160, EsrganUpscalePipeline.MAX_OUTPUT_H)
    }
}
