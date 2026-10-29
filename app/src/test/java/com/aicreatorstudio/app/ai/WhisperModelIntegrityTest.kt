package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-11 Whisper compatibility investigation — §5/§6 tests for the
 * model-integrity contract.
 *
 * ## Why this reads files instead of calling `AiModels`
 *
 * `AiModels.expectedSizeFor` / `isVerifiedInstalled` read
 * `context.assets`, which needs a real Android `Context`. A JVM unit
 * test cannot provide one without Robolectric, and this project's test
 * stack deliberately excludes Robolectric and all mock frameworks.
 *
 * Rather than mock the asset stream and then assert on the mock — which
 * would prove nothing about the shipped data — these tests parse the
 * **actual** `manifest.json` and `models.lock.json` from disk, exactly
 * as `ModelLockfileTest` does for the lockfile. A wrong size in the
 * manifest is a real defect and is caught here; a wrong size returned
 * by a stub would not be.
 *
 * The Android-side behaviour (that `expectedSizeFor` returns the value
 * from this manifest) is a one-line delegation and is covered by the
 * `isVerifiedInstalled` logic being straightforward; it is NOT
 * overclaimed here as verified on a device.
 */
class WhisperModelIntegrityTest {

    // ---- Manifest: the exact-size source of truth ----

    private fun manifestModels(): List<Pair<String, Long>> {
        val file = locate("app/src/main/assets/ai-models/manifest.json")
            ?: error("manifest.json not found from cwd ${File(".").absolutePath}")
        val text = file.readText()
        val obj = org.json.JSONObject(text)
        val arr = obj.getJSONArray("models")
        return (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            e.getString("id") to e.optLong("sizeBytes", -1L)
        }
    }

    private fun lockfileSizeFor(id: String): Long? {
        val file = locate("app/ai-models/models.lock.json")
            ?: error("models.lock.json not found from cwd ${File(".").absolutePath}")
        val registry = ModelRegistry.parse(file.readText())
        return registry.byId(id)?.declaredSizeBytes
    }

    @Test
    fun `manifest declares whisper-small with an exact size`() {
        val models = manifestModels()
        val entry = models.firstOrNull { it.first == "whisper-small" }
        assertNotNull(
            "whisper-small must be present in manifest.json. Found ids: " +
                models.map { it.first },
            entry
        )
        val size = entry!!.second
        assertTrue(
            "whisper-small must carry a positive exact sizeBytes — the " +
                "engine's truncated-vs-corrupt diagnosis depends on it. Got: $size",
            size > 0L
        )
        // 2026-10-11 audit: the real ggml-small.bin is 487,601,967 B.
        // Pin it so a silent upstream swap cannot quietly invalidate the
        // gate that every other check is calibrated against.
        assertEquals(487_601_967L, size)
    }

    @Test
    fun `manifest exact size is meaningfully above the minSizeBytes floor`() {
        // The whole reason the exact-size gate exists: the runtime uses
        // `CatalogEntry.minSizeBytes` as a floor, and for whisper-small
        // that floor (480,000,000) sits below the real artefact
        // (487,601,967). If these two ever coincide, this test tells us
        // the floor was corrected and the gate could be simplified —
        // it must never be *worse* than the floor.
        val spec = AiModelCatalog.WHISPER_SMALL
        val exact = manifestModels().first { it.first == "whisper-small" }.second

        assertTrue(
            "manifest size ($exact) must be >= minSizeBytes (${spec.minSizeBytes}); " +
                "otherwise the floor is stricter than reality and legit " +
                "installs would be rejected",
            exact >= spec.minSizeBytes
        )
        val gap = exact - spec.minSizeBytes
        assertTrue(
            "Expected a small positive gap between the floor and the exact " +
                "size (the band a truncated download can hide in). Got: $gap",
            gap > 0L
        )
    }

    @Test
    fun `every manifest entry that has a lockfile counterpart agrees on size`() {
        // Two sources of truth for the same artefact is a bug factory;
        // if they disagree, one of them is wrong and the installer will
        // fight the engine. Assert agreement for every id present in both.
        var compared = 0
        for ((id, manifestSize) in manifestModels()) {
            if (manifestSize <= 0L) continue
            val locked = lockfileSizeFor(id) ?: continue
            assertEquals(
                "size disagreement for '$id' between manifest.json and " +
                    "models.lock.json — these must be generated from the " +
                    "same source",
                locked, manifestSize
            )
            compared++
        }
        assertTrue(
            "expected to compare at least one entry across both files",
            compared >= 1
        )
    }

    // ---- Catalog contract that the diagnosis depends on ----

    @Test
    fun `whisper-small is not bundled so a download failure is user-visible`() {
        // The audit established that no Whisper model ships in the APK —
        // `bundledOnly` defaults to false for Whisper and the lockfile
        // entry is download-only. Every user therefore downloads
        // ~487 MB before first use, which is what makes a truncated
        // download the leading failure hypothesis. Pin the fact.
        val spec = AiModelCatalog.WHISPER_SMALL
        assertTrue(
            "whisper-small must NOT be bundled: if it became a bundled " +
                "asset this test, and the leading truncated-download " +
                "hypothesis, must both be revisited",
            !spec.bundledOnly
        )
        // Cross-check against the lockfile, which is where the Gradle
        // download task reads the real bundling flag from.
        val lock = locate("app/ai-models/models.lock.json")
            ?: error("models.lock.json not found")
        val entries = org.json.JSONObject(lock.readText()).getJSONArray("models")
        val whisper = (0 until entries.length())
            .map { entries.getJSONObject(it) }
            .firstOrNull { it.getString("id") == "whisper-small" }
        assertNotNull("whisper-small must be present in models.lock.json", whisper)
        assertTrue(
            "whisper-small must be declared bundled:false in the lockfile so " +
                "the Gradle task does not place 487 MB inside the APK",
            !whisper!!.optBoolean("bundled", true)
        )
    }

    @Test
    fun `whisper-small minSizeBytes is a floor not an exact size`() {
        // Guard against someone "fixing" the gap by setting minSizeBytes
        // to the exact byte count: that is fine, but it must then equal
        // the manifest value. This test documents which of the two states
        // the code is in.
        val spec = AiModelCatalog.WHISPER_SMALL
        val exact = manifestModels().first { it.first == "whisper-small" }.second
        assertTrue(
            "minSizeBytes (${spec.minSizeBytes}) must never exceed the real " +
                "artefact size ($exact)",
            spec.minSizeBytes <= exact
        )
    }

    @Test
    fun `whisper model spec enum keeps only the supported models`() {
        // The audit found the enum reduced to SMALL + CLOUD_API. Asserting
        // the set pins the supported-configuration answer the report must
        // give: there is no longer a Medium or Large offline option, so
        // "which models can run offline" has exactly one answer.
        val names = WhisperModelSpec.entries.map { it.name }.toSet()
        assertEquals(
            "the offline model surface changed — update the compatibility " +
                "report's supported-configuration section",
            setOf("SMALL", "CLOUD_API"),
            names
        )
    }

    // ---- helper ----

    private fun locate(relative: String): File? {
        var dir: File? = File(".").absoluteFile
        repeat(8) {
            if (dir?.resolve("settings.gradle.kts")?.isFile == true) {
                val f = dir.resolve(relative)
                if (f.isFile) return f
            }
            dir = dir?.parentFile
        }
        // Fallback: relative to cwd (some runners cwd into app/).
        val direct = File(relative)
        return if (direct.isFile) direct else null
    }
}
