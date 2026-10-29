package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lockfile integrity test: parses the REAL `models.lock.json` and
 * asserts every entry the four repaired tools depend on carries a
 * genuine HTTPS URL, a 64-hex SHA-256, and a positive size.
 *
 * This is the fail-closed contract: a placeholder URL/SHA/size can
 * never reach the installer, because the registry parser rejects
 * it and this test rejects a lockfile that the parser would
 * reject.
 */
class ModelLockfileTest {

    private fun lockfile(): ModelRegistry {
        // JVM unit tests run with the app module dir as CWD.
        val candidates = listOf(
            File("ai-models/models.lock.json"),
            File("app/ai-models/models.lock.json")
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("models.lock.json not found (tried ${candidates.map { it.path }})")
        return ModelRegistry.parse(file.readText())
    }

    @Test
    fun `lockfile parses without validation errors`() {
        val registry = lockfile()
        assertTrue(registry.all().isNotEmpty())
    }

    @Test
    fun `selfie multiclass entry is real and matches the bundled asset`() {
        val spec = lockfile().byId("selfie_multiclass")!!
        assertTrue(spec.url.startsWith("https://"))
        assertTrue("sha was ${spec.expectedSha256}", spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue("size was ${spec.declaredSizeBytes}", spec.declaredSizeBytes > 10_000_000L)
        assertEquals("selfie_multiclass_256x256.tflite", spec.fileName)
    }

    @Test
    fun `pose landmarker entry is real`() {
        val spec = lockfile().byId("pose_landmarker_lite")!!
        assertTrue(spec.url.startsWith("https://"))
        assertTrue(spec.expectedSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(spec.declaredSizeBytes > 4_000_000L)
        assertEquals("pose_landmarker_lite.task", spec.fileName)
    }

    @Test
    fun `esrgan entry pins the verified tensorflow artifact`() {
        val spec = lockfile().byId("esrgan_tflite_4x")!!
        assertEquals(
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite",
            spec.url
        )
        assertEquals(
            "1a380d3744103e11ef343534aaff54815cae40769dcd00c023652a7e5bc47f4b",
            spec.expectedSha256
        )
        assertEquals(4993712L, spec.declaredSizeBytes)
    }

    @Test
    fun `no entry carries a placeholder url sha or size`() {
        val registry = lockfile()
        registry.all().forEach { spec ->
            assertTrue(
                "${spec.id} url looks like a placeholder: ${spec.url}",
                spec.url.startsWith("https://") && !spec.url.contains("PLACEHOLDER", ignoreCase = true)
                    && !spec.url.contains("example.", ignoreCase = true)
            )
            assertTrue(
                "${spec.id} sha looks like a placeholder",
                !spec.expectedSha256.contains("TBD", ignoreCase = true) &&
                    !spec.expectedSha256.all { it == '0' }
            )
            assertTrue("${spec.id} size is ${spec.declaredSizeBytes}", spec.declaredSizeBytes > 0)
        }
    }

    @Test
    fun `catalog ids all resolve in the lockfile`() {
        val registry = lockfile()
        listOf(
            AiModelCatalog.SELFIE_MULTICLASS,
            AiModelCatalog.POSE_LANDMARKER_LITE,
            AiModelCatalog.ESRGAN_TFLITE_4X
        ).forEach { entry ->
            val spec = registry.byId(entry.id)
            assertTrue("lockfile missing ${entry.id}", spec != null)
            assertEquals(entry.fileName, spec!!.fileName)
            assertEquals(entry.dirName, spec.dirName)
        }
    }

    @Test
    fun `rnnoise native snapshot is pinned with hash and size`() {
        val text = lockText()
        assertTrue(text.contains("rnnoise"))
        assertTrue(text.contains("c44365dacfb0425916e9f4cfa204a79d4a014b957c68464229fc54dfa4f39e68"))
        assertTrue(text.contains("f0b73e38c029660bc6e010b583b406f6470f8a1b"))
    }

    private fun lockText(): String {
        val candidates = listOf(
            File("ai-models/models.lock.json"),
            File("app/ai-models/models.lock.json")
        )
        return candidates.first { it.exists() }.readText()
    }
}
