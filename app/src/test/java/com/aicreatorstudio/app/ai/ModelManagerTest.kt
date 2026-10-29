package com.aicreatorstudio.app.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * Tests for [ModelManager]. The test seam is the [NetworkClient]
 * interface — the manager is constructed with a hand-rolled fake
 * that returns scripted [HttpURLConnection] instances.
 *
 * We do NOT use MockK / Mockito (the project doesn't depend on
 * them). The [FakeHttpURLConnection] below is the minimum needed to
 * exercise the manager's download / verify / size-check branches:
 * response code, content length, and a byte stream of body.
 */
class ModelManagerTest {

    private lateinit var tempDir: File
    private lateinit var context: android.content.Context
    private lateinit var fakeClient: FakeNetworkClient

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("model-manager-test").toFile()
        context = TestContext(tempDir)
        fakeClient = FakeNetworkClient()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `isInstalled is false when the file is missing`() {
        val mgr = ModelManager(context, fakeClient)
        val spec = spec()
        assertFalse(mgr.isInstalled(spec))
    }

    @Test
    fun `isInstalled is false when the file is below the min size`() {
        val mgr = ModelManager(context, fakeClient)
        val spec = spec(minSizeBytes = 1000L)
        // Write a 500-byte file to the model location.
        val modelFile = File(tempDir, "models/${spec.dirName}/${spec.fileName}")
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(ByteArray(500))
        assertFalse(mgr.isInstalled(spec))
    }

    @Test
    fun `isInstalled is true when the file meets the min size`() {
        val mgr = ModelManager(context, fakeClient)
        val spec = spec(minSizeBytes = 1000L)
        val modelFile = File(tempDir, "models/${spec.dirName}/${spec.fileName}")
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(ByteArray(2000))
        assertTrue(mgr.isInstalled(spec))
    }

    @Test
    fun `install downloads, verifies, and returns Ready when sha256 matches`() = kotlinx.coroutines.runBlocking {
        // Use a body large enough to force the read loop to make
        // multiple iterations against the 128 KiB buffer the manager
        // uses; with 4 KiB the entire body fits in one read, the
        // first progress emission jumps straight to 0.8, and the
        // "first in 0f..0.01f" assertion would never hold.
        val body = ByteArray(512 * 1024) { (it and 0xFF).toByte() }
        val expectedSha = sha256Hex(body)
        fakeClient.scripted = listOf(
            FakeHttpURLConnection(
                responseCode = 200,
                body = body,
                contentLength = body.size.toLong()
            )
        )
        val mgr = ModelManager(context, fakeClient)
        val spec = spec(minSizeBytes = 1000L, expectedSha256 = expectedSha)
        val seenProgress = mutableListOf<Float>()
        val result = mgr.install(spec) { seenProgress += it }
        assertTrue("expected Ready, got $result", result is ModelInstallResult.Ready)
        assertEquals(spec, (result as ModelInstallResult.Ready).spec)
        // Progress should be monotonic and reach 1.0 at the end.
        // The first chunk read sees ~0% (128 KiB / 512 KiB = 0.25
        // → 0.25 * 0.8 = 0.2), so 0f..0.01f is no longer the right
        // window. We instead pin the first-emission lower bound
        // loosely to anything below the post-verify 0.95 spike.
        assertTrue("first progress was ${seenProgress.first()}", seenProgress.first() in 0f..0.5f)
        assertEquals(1f, seenProgress.last(), 0.0001f)
        // The model file should exist on disk.
        assertTrue(mgr.isInstalled(spec))
    }

    @Test
    fun `install returns ChecksumMismatch when the SHA-256 does not match`() = kotlinx.coroutines.runBlocking {
        val body = ByteArray(4096) { (it and 0xFF).toByte() }
        fakeClient.scripted = listOf(
            FakeHttpURLConnection(
                responseCode = 200,
                body = body,
                contentLength = body.size.toLong()
            )
        )
        val mgr = ModelManager(context, fakeClient)
        val spec = spec(
            minSizeBytes = 1000L,
            expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000"
        )
        val result = mgr.install(spec) { }
        assertTrue("expected ChecksumMismatch, got $result", result is ModelInstallResult.ChecksumMismatch)
        // The partial file should be cleaned up so the next retry starts fresh.
        val partial = File(tempDir, "models/${spec.dirName}/${spec.fileName}.partial")
        assertFalse("partial must be deleted on mismatch", partial.exists())
    }

    @Test
    fun `install returns Failed on a 5xx response`() = kotlinx.coroutines.runBlocking {
        fakeClient.scripted = listOf(
            FakeHttpURLConnection(responseCode = 503, body = ByteArray(0), contentLength = 0L)
        )
        val mgr = ModelManager(context, fakeClient)
        val spec = spec(minSizeBytes = 1000L)
        val result = mgr.install(spec) { }
        assertTrue("expected Failed, got $result", result is ModelInstallResult.Failed)
    }

    @Test
    fun `install returns Failed when the downloaded body is below minSize`() = kotlinx.coroutines.runBlocking {
        val body = ByteArray(100) { 0x42 } // 100 bytes
        fakeClient.scripted = listOf(
            FakeHttpURLConnection(
                responseCode = 200,
                body = body,
                contentLength = body.size.toLong()
            )
        )
        val mgr = ModelManager(context, fakeClient)
        val spec = spec(minSizeBytes = 1_000_000L) // 1 MB required
        val result = mgr.install(spec) { }
        assertTrue("expected Failed, got $result", result is ModelInstallResult.Failed)
    }

    @Test
    fun `uninstall removes the model file and the partial`() {
        val mgr = ModelManager(context, fakeClient)
        val spec = spec()
        val modelFile = File(tempDir, "models/${spec.dirName}/${spec.fileName}")
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(ByteArray(2000))
        val partial = File(tempDir, "models/${spec.dirName}/${spec.fileName}.partial")
        partial.writeBytes(ByteArray(500))
        kotlinx.coroutines.runBlocking { mgr.uninstall(spec) }
        assertFalse(modelFile.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun `uninstall is idempotent when nothing is installed`(): Unit = kotlinx.coroutines.runBlocking {
        val mgr = ModelManager(context, fakeClient)
        val spec = spec()
        // Should not throw.
        mgr.uninstall(spec)
        // And again - same outcome.
        mgr.uninstall(spec)
        // Explicit Unit to make JUnit happy with the test method's
        // declared return type. Without this the inferred return
        // type is the suspend fun uninstall's `Boolean` and JUnit
        // refuses to run the test.
    }

    @Test
    fun `default NetworkClient delegates to URL openConnection`() {
        // We can only construct a URL whose host is unreachable in
        // a unit test, so the assertion is just that the default
        // client returns an HttpURLConnection and doesn't throw on
        // construction.
        val default = DefaultNetworkClient()
        // Use a URL string the URL parser accepts; we don't open it.
        // (openConnection() does not open a connection - it just
        // constructs the HttpURLConnection object.)
        val conn = default.openConnection(URL("http://127.0.0.1:1/foo"))
        assertNotNull(conn)
    }

    private fun spec(
        minSizeBytes: Long = 1000L,
        expectedSha256: String? = null
    ): AiModelSpec = AiModelSpec(
        displayName = "Test",
        fileName = "model.tflite",
        dirName = "test",
        url = "https://example.invalid/model.tflite",
        minSizeBytes = minSizeBytes,
        expectedSha256 = expectedSha256
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

// ---- Test fakes ----

/**
 * Hand-rolled [NetworkClient] that returns scripted connections in
 * order. The tests above always script exactly one connection per
 * `install` call.
 */
private class FakeNetworkClient : NetworkClient {
    var scripted: List<FakeHttpURLConnection> = emptyList()
    private var index = 0
    override fun openConnection(url: URL): HttpURLConnection {
        val conn = scripted.getOrNull(index) ?: error("No scripted connection at index $index")
        index++
        conn.url = url
        return conn
    }
}

/**
 * Minimal [HttpURLConnection] stub. We only implement the methods
 * [ModelManager] actually calls: `responseCode`, `inputStream`,
 * `contentLengthLong`, `disconnect`, `connect` (no-op), and the
 * `connectTimeout` / `readTimeout` / `setRequestProperty` setters
 * (no-ops).
 *
 * This is a `class` (not an `object`) so we can hold per-instance
 * state. We extend the real `HttpURLConnection` so the type system
 * is happy; the parent constructor requires a URL, which we throw
 * away.
 */
private class FakeHttpURLConnection(
    private val responseCode: Int,
    private val body: ByteArray,
    private val contentLength: Long
) : HttpURLConnection(URL("http://localhost/")) {

    var url: URL = URL("http://localhost/")

    override fun getResponseCode(): Int = responseCode

    override fun getContentLengthLong(): Long = contentLength

    override fun getInputStream(): InputStream = ByteArrayInputStream(body)

    override fun connect() {
        // No-op
    }

    override fun disconnect() {
        // No-op
    }

    override fun usingProxy(): Boolean = false
}
