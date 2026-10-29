package com.aicreatorstudio.app.ai

import com.aicreatorstudio.app.ai.UnifiedModelInstaller.DefaultHttpFetcher
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * End-to-end real model install test.
 *
 * Phase A's contract is DOWNLOAD → VERIFY → INSTALL → READY, with
 * **real bytes** and a **real SHA-256 verification**. This test
 * stands up a tiny local HTTP server on `127.0.0.1:<random>`, serves
 * a real bundled model file from disk, and points the production
 * [UnifiedModelInstaller] (using the production [DefaultHttpFetcher])
 * at it.
 *
 * ## 2026-10-16 — the target model is now DERIVED, not hard-coded
 *
 * This test used to hard-code `whisper-medium` /
 * `ai-models/whisper-ggml-medium/ggml-medium.bin`. That premise died
 * when the model catalog was re-vetted: `whisper-medium` was removed
 * from `models.lock.json` and its 1.5 GB asset was deleted from the
 * tree. The removal is asserted elsewhere —
 * `WhisperModelSelectionAuditTest` requires
 * `AiModelCatalog.byId("whisper-medium") == null`, and
 * `WhisperNativeSymbolsTest` already recorded on 2026-10-11 that
 * "`whisper-ggml-medium` was removed". The only remaining Whisper
 * entry, `whisper-small`, is `"bundled": false` (downloaded on first
 * use), so **no Whisper model is bundled** any more.
 *
 * The correction that fixed `WhisperNativeSymbolsTest` missed this
 * file, which left a permanent red test. Rather than name another
 * model that can rot the same way, the test now selects its fixture
 * from `models.lock.json`:
 *
 *   1. read the lockfile entries and keep the ones flagged
 *      `bundled != false` (the ones the APK is required to ship);
 *   2. resolve each asset under `assets/ai-models/<dirName>/<fileName>`
 *      and keep only those whose bytes are actually on disk at the
 *      lockfile's declared size;
 *   3. run the pipeline against the SMALLEST such model (fast).
 *
 * The assertion that at least one bundled fixture exists is real, not
 * a skip: the offline AI tools (Background Remove, Auto Frame, AI
 * Upscale) MUST ship their models inside the APK, which
 * `BundledAssetIntegrityTest` enforces independently. Byte-for-byte
 * agreement with the lockfile is likewise covered there, so this
 * test does not duplicate it — it filters to a trustworthy fixture and
 * then exercises the installer.
 *
 * Every original assertion is preserved: real bytes are served,
 * the installer must download every byte, SHA-256 the file on disk,
 * confirm it matches the lockfile, atomically rename `*.partial`,
 * write the `.sha256` sidecar, and return
 * `InstallOutcome.Ready(source = Downloaded)`.
 *
 * Why this is the right gate: the only fakes are the network
 * transport (a 40-line socket server) and the lockfile URL
 * (rewritten to point at the server). Everything else — registry
 * parsing, installer state machine, SHA computation, atomic rename,
 * sidecar write, filesDir layout — is the production code path.
 *
 * Note on the bundled-asset fast path: [UnifiedModelInstaller]
 * prefers a bundled asset when the APK carries one. [TestContext] is
 * a [android.content.ContextWrapper] over `null`, so
 * `context.assets` is unavailable, `copyBundledAsset` returns
 * `BundledResult.Miss`, and the installer correctly falls through to
 * the download path this test is about.
 */
class RealModelInstallTest {

    @Test
    fun `install a real bundled model end-to-end through the production installer`() {
        // 1. Locate the lockfile and the asset root. We walk up from
        //    the working dir because Gradle sets the JVM's user.dir
        //    to the module directory, not the project root.
        val lockfile = locateLockfile()
        val assetRoot = locateAssetRoot()

        // 2. Derive a real, bundled fixture from the lockfile itself.
        val entries = JSONObject(lockfile.readText()).getJSONArray("models")
        val bundledFixtures = (0 until entries.length())
            .map { entries.getJSONObject(it) }
            .filter { it.optBoolean("bundled", true) }
            .mapNotNull { entry ->
                val file = File(assetRoot, "${entry.getString("dirName")}/${entry.getString("fileName")}")
                if (file.isFile && file.length() == entry.getLong("sizeBytes")) {
                    Triple(entry.getString("id"), file, entry.getLong("sizeBytes"))
                } else {
                    null
                }
            }
        assertTrue(
            "No bundled model asset is present on disk with the lockfile's " +
                "declared size, so the install pipeline cannot be exercised " +
                "with real bytes. Looked under ${assetRoot.absolutePath}. " +
                "The offline AI tools must ship their models inside the APK — " +
                "see BundledAssetIntegrityTest.",
            bundledFixtures.isNotEmpty()
        )
        // Smallest fixture keeps the test fast (the pipeline is
        // size-independent).
        val (fixtureId, bundledModel, _) = bundledFixtures.minBy { it.third }

        // Compute the SHA from the actual on-disk bytes — this is
        // the value the lockfile MUST match. We assert that next.
        val expectedSha = sha256Hex(bundledModel)
        val expectedSize = bundledModel.length()
        assertTrue("bundled model is unexpectedly empty", expectedSize > 0L)

        // Also load the lockfile and verify its pinned SHA matches
        // the bundled file. This is the precondition for a real
        // install test: the lockfile is honest.
        val registry = ModelRegistry.parse(lockfile.readText())
        val spec = registry.byId(fixtureId)
        assertNotNull("$fixtureId spec not in lockfile", spec)
        assertEquals(
            "bundled model SHA does NOT match the lockfile — the lockfile is lying",
            expectedSha, spec!!.expectedSha256
        )
        assertEquals(
            "bundled model size does NOT match the lockfile",
            expectedSize, spec.declaredSizeBytes
        )
        // Guards against the fixture silently drifting to a
        // download-only entry (whose asset is never on disk).
        assertTrue(
            "$fixtureId is declared bundled, so its asset must be on disk",
            bundledModel.isFile
        )

        // 3. Stand up a local HTTP server on a free port that
        //    serves the bundled model bytes.
        //
        //    ## 2026-10-16 — the request head MUST be read before
        //    ## the response is written
        //
        //    An earlier revision of this stub drained the request
        //    with `while (input.available() > 0) { read() }`.
        //    `available()` is 0 whenever the server thread wins the
        //    accept race, so the stub skipped the drain, wrote 229
        //    KB, and closed the socket while the client was still
        //    writing its request. The client saw a reset and
        //    `HttpURLConnection.responseCode` came back -1, which
        //    this test reported as `HTTP -1 downloading ...`. A
        //    blocking read up to the blank line — what a real HTTP
        //    server does — removes the race.
        val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 10_000
        val port = server.localPort
        val serverThread = Thread {
            try {
                // Serve every connection until the listener times
                // out. One GET is expected, but a retry must not hit
                // a listener that has already stopped accepting.
                while (!server.isClosed) {
                    val socket: Socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    socket.use { s ->
                        s.soTimeout = 5_000
                        val input = s.getInputStream()
                        val output = s.getOutputStream()
                        // Block until the request head is complete
                        // (terminated by CRLF CRLF) so the client is
                        // never mid-write when we close.
                        val head = ByteArray(1024)
                        var filled = 0
                        while (filled < head.size) {
                            val n = input.read(head, filled, 1)
                            if (n <= 0) break
                            filled++
                            if (filled >= 4 &&
                                head[filled - 4] == '\r'.code.toByte() &&
                                head[filled - 3] == '\n'.code.toByte() &&
                                head[filled - 2] == '\r'.code.toByte() &&
                                head[filled - 1] == '\n'.code.toByte()
                            ) {
                                break
                            }
                        }
                        val header = "HTTP/1.0 200 OK\r\n" +
                            "Content-Length: $expectedSize\r\n" +
                            "Content-Type: application/octet-stream\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        // Stream the real model bytes — not a fake,
                        // not a stub. The installer must read every
                        // one and SHA them.
                        Files.newInputStream(bundledModel.toPath()).use { src ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                            }
                        }
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                // Surface the error through a flag so the test
                // thread can see it.
                serverError = e
            } finally {
                runCatching { server.close() }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        // 4. Build a spec whose URL points at the local server.
        val localSpec = spec.copy(url = "http://127.0.0.1:$port/${spec.fileName}")

        // Use a fresh temp filesDir so we can verify the on-disk
        // final file independently of the test runner's state.
        val tempDir = Files.createTempDirectory("real-model-install-test").toFile()
        val context = TestContext(tempDir)
        val installer = UnifiedModelInstaller(context, DefaultHttpFetcher())

        // 5. Run the production install pipeline. This must
        //    produce Ready (Downloaded) on the very first attempt.
        val outcome = runBlocking { installer.install(localSpec) }
        assertTrue(
            "the local HTTP stub threw instead of serving the model: $serverError",
            serverError == null
        )
        assertTrue(
            "expected InstallOutcome.Ready, got $outcome (serverError=$serverError)",
            outcome is InstallOutcome.Ready
        )
        val ready = outcome as InstallOutcome.Ready
        assertEquals(InstallSource.Downloaded, ready.source)

        // 6. Verify the on-disk artifacts independently.
        val finalFile = File(tempDir, "models/${localSpec.dirName}/${localSpec.fileName}")
        assertTrue("final file missing at ${finalFile.absolutePath}", finalFile.exists())
        assertEquals(
            "final file size does not match bundled source",
            expectedSize, finalFile.length()
        )
        val onDiskSha = sha256Hex(finalFile)
        assertEquals(
            "final file SHA does not match the lockfile's pinned hash",
            expectedSha, onDiskSha
        )
        val sidecar = File(tempDir, "models/${localSpec.dirName}/${localSpec.fileName}.sha256")
        assertTrue("sidecar missing at ${sidecar.absolutePath}", sidecar.exists())
        assertEquals(
            "sidecar content does not match the verified SHA",
            expectedSha, sidecar.readText().trim().lowercase()
        )
        val partial = File(tempDir, "models/${localSpec.dirName}/${localSpec.fileName}.partial")
        assertTrue("partial should be cleaned up after success", !partial.exists())

        // 7. Cleanup.
        tempDir.deleteRecursively()
    }

    @Volatile private var serverError: Exception? = null

    /**
     * The canonical lockfile (`app/ai-models/models.lock.json`),
     * discovered by walking up from the working dir. Its twin under
     * `assets/` is asserted equivalent by `BundledAssetIntegrityTest`.
     */
    private fun locateLockfile(): File {
        File("ai-models/models.lock.json").let { if (it.isFile) return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "app/ai-models/models.lock.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("models.lock.json not found above ${System.getProperty("user.dir")}")
    }

    /** The bundled-asset root (`app/src/main/assets/ai-models`). */
    private fun locateAssetRoot(): File {
        File("src/main/assets/ai-models").let { if (it.isDirectory) return it }
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            val candidate = File(dir, "app/src/main/assets/ai-models")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("assets/ai-models not found above ${System.getProperty("user.dir")}")
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
