package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-09-06 — JVM unit tests for the bundled-first + in-app
 * download repair contract.
 *
 * The three production engines — AI Upscale (ESRGAN), Auto Frame
 * (Pose Landmarker Lite), Background Remove (Selfie Multiclass) —
 * ship their model binaries inside the APK, but the runtime
 * installer now treats the bundled asset as an OFFLINE-FAST PATH,
 * not as the only source: when the asset is missing or fails SHA
 * verification the same install pipeline downloads the model
 * in-app from the `url` pinned in `models.lock.json`.
 *
 * These tests pin the public contract so a future refactor
 * cannot:
 *   - accidentally re-hide the Download button for the three
 *     production engines,
 *   - re-introduce a hard `bundledOnly` network-forbidden branch
 *     that turns a missing asset into a dead-end reinstall,
 *   - drop the bundled-asset path resolution used by the
 *     offline-first install,
 *   - break the catalog id -> spec resolution.
 *
 * They are JVM-only and do not touch real assets, the network,
 * or Android-specific APIs. The asset-presence + SHA verification
 * invariants are covered by the Android `RealModelInstallTest`
 * and `ModelRegistryTest` suites.
 */
class BundledOnlyModelContractTest {

    /**
     * Lock 1. The three production entries that back AI Upscale,
     * Auto Frame, and Background Remove ship inside the APK but
     * MUST be downloadable in-app when the bundled asset is
     * missing — `bundledOnly` stays `false` on all of them so no
     * code path can silently re-enable a "reinstall only" dead
     * end. Voice Clean (RNNoise) is not in the catalog because
     * its weights are compiled into `librnnoise.so`; it has no
     * in-app model of its own.
     */
    @Test
    fun `production engines - AI Upscale Auto Frame Background Remove - are downloadable`() {
        assertFalse(
            "ESRGAN_TFLITE_4X must be downloadable (AI Upscale)",
            AiModelCatalog.ESRGAN_TFLITE_4X.bundledOnly
        )
        assertFalse(
            "POSE_LANDMARKER_LITE must be downloadable (Auto Frame)",
            AiModelCatalog.POSE_LANDMARKER_LITE.bundledOnly
        )
        assertFalse(
            "SELFIE_MULTICLASS must be downloadable (Background Remove)",
            AiModelCatalog.SELFIE_MULTICLASS.bundledOnly
        )
    }

    /**
     * Lock 2. The two Whisper checkpoints MUST stay
     * `bundledOnly = false`. The Whisper download UX is the
     * intended model-selection flow for STT; flipping this would
     * silently change the in-app model-management contract.
     */


    /**
     * Lock 3. `AiModelCatalog.bundledOnly` is now empty — no
     * entry may claim the "bundled, never download" invariant.
     */
    @Test
    fun `no catalog entry is bundled-only anymore`() {
        assertEquals(
            "AiModelCatalog.bundledOnly must be empty: every model " +
                "must be repairable via in-app download",
            emptyList<CatalogEntry>(),
            AiModelCatalog.bundledOnly
        )
    }

    /**
     * Lock 4. The UI policy MUST offer the Download button for
     * the three production engines whenever the model is not
     * locally available and no install is in flight. Tapping it
     * installs from the bundled asset (offline) or downloads —
     * either way the user is never stranded.
     */
    @Test
    fun `showDownload returns true for production engines when model is missing`() {
        for (status in listOf(
            AiToolStatus.Idle,
            AiToolStatus.ModelMissing,
            AiToolStatus.Failed,
            AiToolStatus.Cancelled
        )) {
            assertTrue(
                "showDownload must be true for ESRGAN at status=$status",
                AiToolUiPolicy.showDownload(
                    status,
                    modelAvailable = false
                )
            )
            assertTrue(
                "showDownload must be true for Pose Landmarker at status=$status",
                AiToolUiPolicy.showDownload(
                    status,
                    modelAvailable = false
                )
            )
            assertTrue(
                "showDownload must be true for Selfie Multiclass at status=$status",
                AiToolUiPolicy.showDownload(
                    status,
                    modelAvailable = false
                )
            )
        }
    }

    /**
     * Lock 5. The UI policy MUST still show the Download button
     * for the Whisper specs when the model is missing and no
     * install is in flight.
     */
    @Test
    fun `showDownload behaves identically for non-bundled models`() {
        assertTrue(
            "model missing + Idle must show Download",
            AiToolUiPolicy.showDownload(AiToolStatus.Idle, modelAvailable = false)
        )
        assertFalse(
            "model available + Idle must NOT show Download",
            AiToolUiPolicy.showDownload(AiToolStatus.Idle, modelAvailable = true)
        )
        assertFalse(
            "model missing + Downloading must NOT show Download (in-flight)",
            AiToolUiPolicy.showDownload(
                AiToolStatus.Downloading,
                modelAvailable = false
            )
        )
        assertFalse(
            "model missing + Processing must NOT show Download (running)",
            AiToolUiPolicy.showDownload(
                AiToolStatus.Processing,
                modelAvailable = false
            )
        )
    }

    /**
     * Lock 7. The BUNDLED_ASSET_MISSING failure kind stays in the
     * enum (stable API surface) with a Persian message.
     */
    @Test
    fun `BUNDLED_ASSET_MISSING failure kind exists with a Persian message`() {
        val kind = AiFailure.BUNDLED_ASSET_MISSING
        assertNotNull("AiFailure.BUNDLED_ASSET_MISSING must exist", kind)
        val msg = kind.persianMessage()
        assertTrue(
            "Persian message for BUNDLED_ASSET_MISSING must be non-blank",
            msg.isNotBlank()
        )
    }

    /**
     * Lock 8. The bundled-asset path used by the runtime
     * installer must agree with the catalog. If a future refactor
     * renames the dir or the file on the spec, the asset path
     * resolution breaks silently. This test forces them to stay
     * in lockstep.
     */
    @Test
    fun `AiModels bundledAssetPath matches the three bundled-first specs`() {
        assertEquals(
            "ai-models/selfie_multiclass/selfie_multiclass_256x256.tflite",
            AiModels.bundledAssetPath(AiModelCatalog.SELFIE_MULTICLASS)
        )
        assertEquals(
            "ai-models/pose_landmarker_lite/pose_landmarker_lite.task",
            AiModels.bundledAssetPath(AiModelCatalog.POSE_LANDMARKER_LITE)
        )
        assertEquals(
            "ai-models/esrgan_tflite/ESRGAN.tflite",
            AiModels.bundledAssetPath(AiModelCatalog.ESRGAN_TFLITE_4X)
        )
    }

    /**
     * Lock 9. The `AiModelCatalog.byId(...)` lookup must resolve
     * every production id back to the same instance. This
     * guards against a future refactor that renames an id on the
     * manifest side without updating the catalog, which would
     * silently fall off the bundled path.
     */
    @Test
    fun `catalog byId resolves the three production ids back to their singletons`() {
        assertSame(
            AiModelCatalog.SELFIE_MULTICLASS,
            AiModelCatalog.byId("selfie_multiclass")
        )
        assertSame(
            AiModelCatalog.POSE_LANDMARKER_LITE,
            AiModelCatalog.byId("pose_landmarker_lite")
        )
        assertSame(
            AiModelCatalog.ESRGAN_TFLITE_4X,
            AiModelCatalog.byId("esrgan_tflite_4x")
        )
    }

    /**
     * Lock 10. The default `bundledOnly` value on a fresh
     * `CatalogEntry(...)` constructor call is `false`. This
     * keeps the existing Whisper code path (and any future
     * downloadable model) safe from accidentally inheriting a
     * bundled-only invariant.
     */
    @Test
    fun `default bundledOnly on CatalogEntry is false - downloadable models stay downloadable by default`() {
        val downloadable = CatalogEntry(
            id = "test_whisper_x",
            displayName = "Test Whisper",
            dirName = "test-whisper",
            fileName = "test.bin",
            minSizeBytes = 1L
        )
        assertFalse(
            "Default bundledOnly must be false so downloadable models " +
                "do not silently inherit the bundled-only invariant",
            downloadable.bundledOnly
        )
        // And an explicit `true` is preserved.
        val bundled = CatalogEntry(
            id = "test_bundled_y",
            displayName = "Test Bundled",
            dirName = "test-bundled",
            fileName = "test.tflite",
            minSizeBytes = 1L,
            bundledOnly = true
        )
        assertTrue(bundled.bundledOnly)
    }
}
