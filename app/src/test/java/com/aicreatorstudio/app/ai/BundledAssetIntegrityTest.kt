package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Byte-exact audit of every **bundled** AI model asset.
 *
 * ## The bug this pins (2026-10-15)
 *
 * A device report showed Background Remove failing twice with
 * `HTTP 403 downloading …` on `storage.googleapis.com`. The model it
 * needs (`selfie_multiclass`) was not in the APK at all: the
 * lockfile entry had drifted to `"bundled": false` while its own
 * comment — and the KDoc in `AiModelInstaller` — still claimed it
 * shipped inside the APK. Nothing verified the drift, because the
 * existing lockfile tests only checked that every entry *had* a
 * URL, never that the entries the offline tools depend on were
 * actually packaged with the bytes the lockfile pins.
 *
 * That left an offline AI tool one unreachable host away from
 * unusable. On the target network that host answers 403.
 *
 * ## What this test asserts
 *
 *  1. Every lock entry whose `bundled` flag is not `false` has its
 *     binary on disk, byte-for-byte: exact size, exact SHA-256.
 *  2. The models behind the offline tools (Background Remove, Auto
 *     Frame, AI Upscale) must be **bundled** — an offline feature
 *     may not depend on a download that a network can refuse.
 *  3. Every entry, bundled or not, keeps a real HTTPS URL so the
 *     in-app repair path still exists when an asset is stripped.
 *
 * Runs on the JVM: it only reads files from the repository, hashes
 * them, and parses JSON. No network, no Android APIs.
 */
class BundledAssetIntegrityTest {

    private fun lockFile(): File {
        val candidates = listOf(
            File("ai-models/models.lock.json"),
            File("app/ai-models/models.lock.json")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("models.lock.json not found (tried ${candidates.map { it.path }})")
    }

    private fun assetRoot(): File {
        val candidates = listOf(
            File("src/main/assets/ai-models"),
            File("app/src/main/assets/ai-models")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("assets/ai-models not found (tried ${candidates.map { it.path }})")
    }

    private fun entries(): List<org.json.JSONObject> {
        val root = org.json.JSONObject(lockFile().readText())
        val models = root.getJSONArray("models")
        return (0 until models.length()).map { models.getJSONObject(it) }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Lock 1 — every non-download-only entry's asset is present with
     * the pinned bytes. This is the check that was missing: a model
     * whose asset was deleted from the tree without touching the
     * lockfile used to pass every test and then 403 on the device.
     */
    @Test
    fun `every bundled model asset exists with the pinned bytes`() {
        val checked = mutableListOf<String>()
        entries().forEach { entry ->
            val id = entry.getString("id")
            val bundled = entry.optBoolean("bundled", true)
            if (!bundled) return@forEach

            val dirName = entry.getString("dirName")
            val fileName = entry.getString("fileName")
            val file = File(assetRoot(), "$dirName/$fileName")
            assertTrue(
                "$id is marked bundled but $dirName/$fileName is not on disk",
                file.isFile
            )
            assertEquals(
                "$id asset size does not match the lockfile",
                entry.getLong("sizeBytes"),
                file.length()
            )
            assertEquals(
                "$id asset bytes do not match the pinned sha256 " +
                    "(a wrong or truncated file in the tree would be " +
                    "bundled and fail the runtime verification)",
                entry.getString("sha256").lowercase(),
                sha256Of(file)
            )
            checked.add(id)
        }
        assertTrue(
            "no bundled entries were checked — the lockfile shape changed",
            checked.isNotEmpty()
        )
    }

    /**
     * Lock 2 — the models behind the offline tools MUST be bundled.
     * Background Remove lost its asset to a `"bundled": false` edit
     * and degraded to a single HTTP download from a host that
     * answers 403 on the target network; these three may not drift
     * back to download-only.
     */
    @Test
    fun `the offline tool models are bundled not download-only`() {
        val offlineToolModels = mapOf(
            "selfie_multiclass" to "Background Remove (matting)",
            "pose_landmarker_lite" to "Auto Frame",
            "esrgan_tflite_4x" to "AI Upscale",
            "blaze_face_short_range" to "Auto Frame face fallback"
        )
        offlineToolModels.forEach { (id, tool) ->
            val entry = entries().firstOrNull { it.getString("id") == id }
            assertTrue("$id missing from models.lock.json", entry != null)
            assertTrue(
                "$id powers $tool and must ship inside the APK, but the " +
                    "lockfile marks it download-only",
                entry!!.optBoolean("bundled", true)
            )
        }
    }

    /**
     * Lock 3 — every entry keeps a real HTTPS repair URL, including
     * the bundled ones. The bundled asset is the offline fast path;
     * the URL is what saves a user whose APK was stripped or whose
     * file got corrupted.
     */
    @Test
    fun `every entry keeps a real https repair url`() {
        entries().forEach { entry ->
            val url = entry.getString("url")
            assertTrue(
                "${entry.getString("id")} has no usable repair URL: $url",
                url.startsWith("https://") &&
                    !url.contains("example.", ignoreCase = true)
            )
        }
    }

    /**
     * Lock 4 — the bytes this test just validated for selfie are
     * the ones the runtime manifest pins. If the two files
     * disagree, the installer would copy the asset and then fail
     * its own SHA check, looking exactly like corruption.
     */
    @Test
    fun `lockfile and runtime manifest agree on every bundled model`() {
        val manifestFile = File(assetRoot(), "manifest.json")
        assertTrue("manifest.json missing", manifestFile.isFile)
        val manifest = org.json.JSONObject(manifestFile.readText())
        val manifestModels = manifest.getJSONArray("models")

        entries().forEach { entry ->
            if (!entry.optBoolean("bundled", true)) return@forEach
            val id = entry.getString("id")
            val pinned = (0 until manifestModels.length())
                .map { manifestModels.getJSONObject(it) }
                .firstOrNull { it.optString("id") == id }
            assertTrue("manifest.json has no entry for bundled model $id", pinned != null)
            assertEquals("$id sha mismatch between lock and manifest",
                entry.getString("sha256").lowercase(),
                pinned!!.getString("sha256").lowercase())
            assertEquals("$id size mismatch between lock and manifest",
                entry.getLong("sizeBytes"),
                pinned.getLong("sizeBytes"))
            assertEquals("$id file name mismatch between lock and manifest",
                entry.getString("fileName"),
                pinned.getString("fileName"))
            assertEquals("$id dir name mismatch between lock and manifest",
                entry.getString("dirName"),
                pinned.getString("dirName"))
        }
    }

    /**
     * Lock 5 — the lockfile exists in TWO places and they must stay
     * semantically identical: `app/ai-models/models.lock.json` is
     * what the Gradle download task reads, and
     * `app/src/main/assets/ai-models/models.lock.json` is the copy
     * that ships inside the APK. Nothing synchronises them, and
     * they already carry different file dates — an edit to one
     * alone is exactly how the `bundled: false` drift survived a
     * build. The `_comment`/metadata fields are exempt; every
     * semantic field an entry carries must agree.
     */
    @Test
    fun `the two lockfile copies agree on every entry`() {
        val assetCopy = File(assetRoot(), "models.lock.json")
        assertTrue("the assets copy of models.lock.json is missing", assetCopy.isFile)
        val gradleCopy = org.json.JSONObject(lockFile().readText())
        val shippedCopy = org.json.JSONObject(assetCopy.readText())

        val gradleModels = gradleCopy.getJSONArray("models")
        val shippedModels = shippedCopy.getJSONArray("models")
        assertEquals(
            "the two lockfile copies have different entry counts",
            gradleModels.length(),
            shippedModels.length()
        )
        val semanticFields = listOf(
            "id", "url", "sha256", "sizeBytes", "dirName", "fileName", "bundled", "ready"
        )
        for (i in 0 until gradleModels.length()) {
            val a = gradleModels.getJSONObject(i)
            val b = shippedModels.getJSONObject(i)
            semanticFields.forEach { field ->
                assertEquals(
                    "lockfile copies disagree on ${a.optString("id")}.$field — the " +
                        "Gradle copy (app/ai-models/) and the APK copy " +
                        "(app/src/main/assets/ai-models/) must be edited together",
                    a.opt(field),
                    b.opt(field)
                )
            }
        }
    }
}
