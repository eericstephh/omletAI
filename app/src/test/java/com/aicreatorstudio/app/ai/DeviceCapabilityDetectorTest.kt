package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE B1.6 — JVM regression guard for the static /
 * pure-Kotlin surface of [DeviceCapabilityDetector] and
 * [DeviceCapability]. The Android-bound probe itself
 * (ABI + RAM + CPU + storage) needs an instrumentation
 * context, so this test pins the parts that can be
 * asserted on a plain JVM: companion constants, sealed
 * type shape, [DeviceCapability.Unsupported.persianCaption]
 * rendering, and the per-spec `minRamMb` floor.
 *
 * The runtime test is the instrumentation test
 * `DeviceCapabilityProbeInstrumentationTest` which runs
 * on a real Android process; this file only covers the
 * static contract.
 */
class DeviceCapabilityDetectorTest {

    // ---- 1. Companion constants (gate thresholds) ----

    @Test
    fun `SUPPORTED_ABIS matches the ABIs actually packaged in the APK`() {
        // ## 2026-10-11 ABI AUDIT CORRECTION
        //
        // This assertion previously required the set
        // `{arm64-v8a, armeabi-v7a, x86_64}`. That matched neither
        // the build nor the shipped artefact:
        //
        //   - `app/build.gradle.kts` sets
        //     `abiFilters += listOf("arm64-v8a")`.
        //   - The built APK was verified to contain native libraries
        //     **only** under `lib/arm64-v8a/`.
        //
        // So the old expectation was pinning a falsehood: it would
        // have failed the moment the (correct) code was written, and
        // it told users on 32-bit or x86 devices that they were
        // supported when the app could not load its own native
        // library.
        //
        // The set must be exactly what ships. Keep this in lockstep
        // with `abiFilters` in `app/build.gradle.kts` and with the
        // `abis` list in `WhisperNativeSymbolsTest`.
        assertEquals(
            setOf("arm64-v8a"),
            DeviceCapabilityDetector.SUPPORTED_ABIS
        )
    }



    @Test
    fun `MIN_CORES is 4 to drive the encoder and decoder in parallel`() {
        // whisper.cpp uses 4 threads; 2-core phones hang
        // for 30+ seconds per audio window.
        assertEquals(4, DeviceCapabilityDetector.MIN_CORES)
    }

    @Test
    fun `MIN_STORAGE_MB is 4 GB to cover journal atomic-rename and partial overhead`() {
        // 4 GB safety margin above the model file size
        // covers journal + atomic-rename + cacheDir partial.
        assertEquals(4_096L, DeviceCapabilityDetector.MIN_STORAGE_MB)
    }

    // ---- 2. Sealed type shape ----



    @Test
    fun `DeviceCapability Reason has all four gate-failure modes`() {
        // The UI exhaustively switches on the reason to
        // pick the right caption. A refactor that drops a
        // reason would force a when {} with an else
        // branch and silently default; the test pins the
        // complete set.
        val reasons = DeviceCapability.Reason.entries.map { it.name }.toSet()
        assertEquals(
            setOf(
                "INSUFFICIENT_RAM",
                "INSUFFICIENT_CPU",
                "INSUFFICIENT_STORAGE",
                "UNSUPPORTED_ABI"
            ),
            reasons
        )
    }

    // ---- 3. Persian caption rendering ----



    @Test
    fun `Unsupported persianCaption is non-blank for every Reason`() {
        // The model picker renders this caption directly
        // on the row. A blank caption would visually
        // disable the row with no explanation.
        for (reason in DeviceCapability.Reason.entries) {
            for (spec in WhisperModelSpec.entries) {
                val caption = DeviceCapability.Unsupported(
                    spec = spec,
                    reason = reason,
                    detail = ""
                ).persianCaption
                assertTrue(
                    "persianCaption for ${spec.name} / $reason " +
                        "must be non-blank; got: '$caption'",
                    caption.isNotBlank()
                )
            }
        }
    }

    // ---- 4. Spec thresholds (cross-check with WhisperModelSpec) ----


    @Test
    fun `every WhisperModelSpec minRamMb matches its AiModelCatalog entry`() {
        // The detector reads `spec.minRamMb`; the
        // catalog has its own `minRamMb`. A drift
        // would make the engine gate against a
        // different number than the catalog advertises.
        for (spec in WhisperModelSpec.entries) {
            // 2026-09-11 — CLOUD_API has no local catalog entry.
            if (spec == WhisperModelSpec.CLOUD_API) continue
            val catalogRam = spec.aiSpec().minRamMb
            assertNotNull(
                "AiModelCatalog.${spec.aiSpec().id}.minRamMb must be set",
                catalogRam
            )
            assertEquals(
                "WhisperModelSpec.${spec.name}.minRamMb and " +
                    "AiModelCatalog.${spec.aiSpec().id}.minRamMb must match",
                spec.minRamMb, catalogRam!!
            )
        }
    }
}
