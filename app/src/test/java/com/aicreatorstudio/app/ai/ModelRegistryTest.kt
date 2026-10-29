package com.aicreatorstudio.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Tests for [ModelRegistry] — the single source of truth for every
 * AI model the app installs.
 *
 * Two layers of coverage:
 *
 *  1. **Unit tests** on a hand-rolled fixture lockfile: validation
 *     rules (SHA length, URL prefix, non-zero size, no duplicates,
 *     blank fields).
 *  2. **Real-world test** that reads the project's actual
 *     `app/ai-models/models.lock.json` from the host filesystem and
 *     proves every entry has a valid 64-hex SHA. This is the gate
 *     against the `TBD_PLACEHOLDER_REPLACE_BEFORE_SHIP` bug that
 *     previously lived in `MediaPipeMattingEngine.MATTING_SPEC`.
 */
class ModelRegistryTest {

    // ---- Unit tests against a fixture ----

    @Test
    fun `parses a minimal valid entry`() {
        val json = """
        {
          "models": [
            {
              "id": "demo",
              "displayName": "Demo",
              "dirName": "demo",
              "fileName": "demo.bin",
              "minSizeBytes": 100,
              "sizeBytes": 1024,
              "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
              "url": "https://example.com/demo.bin"
            }
          ]
        }
        """.trimIndent()
        val reg = ModelRegistry.parse(json)
        val s = reg.byId("demo")
        assertNotNull(s)
        assertEquals("demo", s!!.id)
        assertEquals("demo.bin", s.fileName)
        assertEquals(100L, s.minSizeBytes)
        assertEquals(1024L, s.declaredSizeBytes)
        assertEquals(
            "0000000000000000000000000000000000000000000000000000000000000000",
            s.expectedSha256
        )
        assertEquals("https://example.com/demo.bin", s.url)
    }

    @Test
    fun `rejects an entry whose SHA is a placeholder`() {
        val json = """
        {
          "models": [
            {
              "id": "bad",
              "dirName": "bad",
              "fileName": "bad.bin",
              "minSizeBytes": 1,
              "sizeBytes": 1024,
              "sha256": "TBD_PLACEHOLDER_REPLACE_BEFORE_SHIP",
              "url": "https://example.com/bad.bin"
            }
          ]
        }
        """.trimIndent()
        try {
            ModelRegistry.parse(json)
            fail("expected IllegalArgumentException for placeholder SHA")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention sha256: ${e.message}",
                e.message!!.contains("sha256")
            )
        }
    }

    @Test
    fun `rejects an entry with a SHA that is the wrong length`() {
        val json = """
        {
          "models": [
            {
              "id": "bad",
              "dirName": "bad",
              "fileName": "bad.bin",
              "minSizeBytes": 1,
              "sizeBytes": 1024,
              "sha256": "abcd",
              "url": "https://example.com/bad.bin"
            }
          ]
        }
        """.trimIndent()
        try {
            ModelRegistry.parse(json)
            fail("expected IllegalArgumentException for short SHA")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention sha256: ${e.message}",
                e.message!!.contains("sha256")
            )
        }
    }

    @Test
    fun `rejects an entry whose URL is not http(s)`() {
        val json = """
        {
          "models": [
            {
              "id": "bad",
              "dirName": "bad",
              "fileName": "bad.bin",
              "minSizeBytes": 1,
              "sizeBytes": 1024,
              "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
              "url": "ftp://example.com/bad.bin"
            }
          ]
        }
        """.trimIndent()
        try {
            ModelRegistry.parse(json)
            fail("expected IllegalArgumentException for non-http(s) URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention url: ${e.message}",
                e.message!!.contains("url")
            )
        }
    }

    @Test
    fun `rejects an entry whose size is non-positive`() {
        val json = """
        {
          "models": [
            {
              "id": "bad",
              "dirName": "bad",
              "fileName": "bad.bin",
              "minSizeBytes": 1,
              "sizeBytes": 0,
              "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
              "url": "https://example.com/bad.bin"
            }
          ]
        }
        """.trimIndent()
        try {
            ModelRegistry.parse(json)
            fail("expected IllegalArgumentException for size 0")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention size: ${e.message}",
                e.message!!.contains("size")
            )
        }
    }

    @Test
    fun `rejects duplicate ids`() {
        val json = """
        {
          "models": [
            {
              "id": "dup",
              "dirName": "a",
              "fileName": "a.bin",
              "minSizeBytes": 1,
              "sizeBytes": 1,
              "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
              "url": "https://example.com/a.bin"
            },
            {
              "id": "dup",
              "dirName": "b",
              "fileName": "b.bin",
              "minSizeBytes": 1,
              "sizeBytes": 1,
              "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
              "url": "https://example.com/b.bin"
            }
          ]
        }
        """.trimIndent()
        try {
            ModelRegistry.parse(json)
            fail("expected IllegalArgumentException for duplicate id")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "message should mention duplicate: ${e.message}",
                e.message!!.contains("duplicate")
            )
        }
    }

    @Test
    fun `byId returns null for unknown ids`() {
        val json = """{"models": []}"""
        val reg = ModelRegistry.parse(json)
        assertNull(reg.byId("nope"))
        assertEquals(emptyList<ModelRegistry.Spec>(), reg.all())
    }

    // ---- Real-world test against the on-disk lockfile ----
    //
    // This is the gate that prevents a "TBD" SHA from ever being
    // merged. It reads `app/ai-models/models.lock.json` directly
    // from the working tree and fails the test if any entry has a
    // malformed SHA, a missing URL, or a non-positive size.

    @Test
    fun `the on-disk models dot lock dot json is well-formed and every SHA is real`() {
        // Walk up from the JVM working directory until we find the
        // project root (which contains `app/ai-models/models.lock.json`).
        // Gradle sets the working directory to the module dir for
        // unit tests, so a hard-coded `app/ai-models/...` path would
        // resolve to the wrong location.
        var dir: File? = File(System.getProperty("user.dir"))
        var lockfile: File? = null
        var assetRoot: File? = null
        while (dir != null) {
            val candidate = File(dir, "app/ai-models/models.lock.json")
            if (candidate.exists()) {
                lockfile = candidate
                assetRoot = File(dir, "app/src/main/assets/ai-models")
                break
            }
            dir = dir.parentFile
        }
        assertNotNull(
            "lockfile not found anywhere above ${System.getProperty("user.dir")}; " +
                "expected app/ai-models/models.lock.json",
            lockfile
        )
        val reg = ModelRegistry.parse(lockfile!!.readText())
        val specs = reg.all()
        assertTrue("lockfile has no models", specs.isNotEmpty())
        for (s in specs) {
            assertTrue(
                "spec ${s.id} SHA is not 64 hex: '${s.expectedSha256}'",
                s.expectedSha256.matches(Regex("^[0-9a-f]{64}$"))
            )
            assertTrue(
                "spec ${s.id} URL is not http(s): '${s.url}'",
                s.url.startsWith("https://") || s.url.startsWith("http://")
            )
            assertTrue(
                "spec ${s.id} sizeBytes is not positive: ${s.declaredSizeBytes}",
                s.declaredSizeBytes > 0L
            )
            // If a real, downloaded file exists in
            // app/src/main/assets/, recompute its SHA and confirm
            // the lockfile's pinned value matches. This is the
            // strongest "no fake SHA" gate.
            val assetFile = File(assetRoot, "${s.dirName}/${s.fileName}")
            if (assetRoot != null && assetFile.exists()) {
                val actual = sha256Hex(assetFile)
                assertEquals(
                    "asset file SHA for ${s.id} does not match lockfile",
                    s.expectedSha256, actual
                )
            }
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
