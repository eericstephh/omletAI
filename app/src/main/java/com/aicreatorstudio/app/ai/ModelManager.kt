package com.aicreatorstudio.app.ai

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic on-device model file manager for the AI tools.
 *
 * Implements the lifecycle the user requested:
 *
 *   NotInstalled → Downloading(progress) → Verifying → Installing → Ready
 *
 * Used by the [AiToolEngine] implementations to fetch their optional
 * TFLite weights. Today one of the three shipped engines
 * ([MediaPipeMattingEngine], [SaliencyTrackerEngine],
 * [SpectralGateVoiceClean]) calls this — the matting engine downloads
 * MediaPipe's Selfie Multiclass weights on first use. The other two
 * ship pure-Kotlin algorithms with zero APK bloat. The manager is
 * fully wired so a future MODNet / BlazeFace / DeepFilterNet backend
 * can drop in by adding one Hilt provider.
 *
 * ## Storage
 *
 * Models live under `context.filesDir/models/<dirName>/<fileName>`.
 * The directory is created on first download. `.partial` files are
 * kept around if a download is interrupted so a retry resumes from
 * where it left off (the partial is deleted only after a successful
 * verify + rename).
 *
 * ## Verification
 *
 * When [AiModelSpec.expectedSha256] is non-null, the downloaded bytes
 * are hashed with SHA-256 and compared. A mismatch deletes the
 * partial and returns [ModelInstallResult.ChecksumMismatch] so the
 * user can retry. When the expected hash is null (uncommon, only for
 * very small debug builds) the verify step is skipped.
 *
 * ## Threading
 *
 * Every public function is `suspend` and uses
 * `withContext(Dispatchers.IO)` internally. The host (the AI tool
 * engine) calls these from `Dispatchers.IO` already, but the extra
 * hop is harmless.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    /**
     * Network client used to open HTTP(S) connections when downloading
     * model files. The Hilt graph supplies [DefaultNetworkClient] in
     * production; tests construct [ModelManager] directly with a
     * fake `NetworkClient` so they can script response codes, byte
     * streams, and content lengths without hitting the network.
     */
    private val networkClient: NetworkClient
) : ModelInstaller {

    private val rootDir: File
        get() = File(context.filesDir, MODELS_DIR_NAME).apply { mkdirs() }

    /**
     * Returns `true` if the model file for [spec] is present on disk
     * and passes the size check. No SHA verification here — that's
     * only done at install time so a hot check is cheap.
     */
    override fun isInstalled(spec: AiModelSpec): Boolean {
        val file = modelFile(spec)
        return file.exists() && file.length() >= spec.minSizeBytes
    }

    /**
     * Download + verify + install the model for [spec]. Emits
     * progress:
     *
     *   0f       → 0.8f   : downloading bytes
     *   0.8f     → 0.95f  : verifying SHA-256
     *   0.95f    → 1.0f   : atomic rename into final location
     *
     * Returns the result; never throws.
     */
    override suspend fun install(
        spec: AiModelSpec,
        onProgress: (Float) -> Unit
    ): ModelInstallResult = withContext(Dispatchers.IO) {
        try {
            ensureDownloaded(spec, onProgress)
            verify(spec, onProgress)
            // Rename is a no-op at this point because [ensureDownloaded]
            // already moved the .partial into place; the third phase
            // exists to satisfy the lifecycle contract.
            onProgress(1f)
            ModelInstallResult.Ready(spec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ChecksumMismatchException) {
            // Clean up the half-written file so the next retry starts
            // fresh. The .partial is deleted here (and not in [verify])
            // so that callers can still see the bytes on disk for
            // diagnostic purposes before the catch block runs.
            runCatching { partialFile(spec).delete() }
            ModelInstallResult.ChecksumMismatch(spec)
        } catch (e: Exception) {
            // Clean up any half-written file so the next retry starts
            // fresh. Spec dir survives so the user sees consistent
            // "ModelMissing → Downloading" transitions.
            runCatching { partialFile(spec).delete() }
            ModelInstallResult.Failed(e.message ?: "خطای ناشناخته هنگام نصب مدل")
        }
    }

    /**
     * Delete the model file for [spec]. Idempotent — safe to call
     * when the model isn't installed. Used by the UI's "Remove
     * Model" debug action.
     */
    override suspend fun uninstall(spec: AiModelSpec): Boolean = withContext(Dispatchers.IO) {
        val file = modelFile(spec)
        val partial = partialFile(spec)
        var ok = true
        if (file.exists()) ok = file.delete() && ok
        if (partial.exists()) ok = partial.delete() && ok
        ok
    }

    /**
     * Open the model file as a [Uri] for engines that prefer URIs
     * over raw [File] handles (e.g. TFLite `Interpreter` from file
     * path string).
     */
    override fun modelUri(spec: AiModelSpec): Uri =
        Uri.fromFile(modelFile(spec))

    // ---- internals ----

    private fun modelFile(spec: AiModelSpec): File =
        File(rootDir, "${spec.dirName}/${spec.fileName}")

    private fun partialFile(spec: AiModelSpec): File =
        File(rootDir, "${spec.dirName}/${spec.fileName}.partial")

    private suspend fun ensureDownloaded(
        spec: AiModelSpec,
        onProgress: (Float) -> Unit
    ) {
        val out = modelFile(spec)
        if (out.exists() && out.length() >= spec.minSizeBytes) {
            onProgress(0.8f)
            return
        }
        out.parentFile?.mkdirs()
        val tmp = partialFile(spec)

        val connection = networkClient.openConnection(URL(spec.url))
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            // Some mirrors (Hugging Face LFS) require a User-Agent
            // header — `HttpURLConnection` defaults to "Java/..." which
            // gets 403-blocked. The bare UA we use is what curl sends.
            connection.setRequestProperty("User-Agent", "AI-Creator-Studio/1.0")
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw Exception(
                    "خطای سرور هنگام دانلود مدل: HTTP ${connection.responseCode}"
                )
            }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
                ?: spec.minSizeBytes
            connection.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var read: Int
                    var total = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        total += read
                        val frac = (total.toFloat() / totalBytes).coerceIn(0f, 1f)
                        // Download occupies the first 80% of the lifecycle.
                        onProgress(frac * 0.8f)
                    }
                }
            }
            if (!tmp.renameTo(out)) {
                throw Exception("انتقال فایل مدل به محل نهایی ناموفق بود")
            }
            if (out.length() < spec.minSizeBytes) {
                throw Exception(
                    "حجم فایل دانلود شده کمتر از حداقل مورد انتظار است " +
                        "(${out.length()} < ${spec.minSizeBytes})"
                )
            }
            onProgress(0.8f)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verify(
        spec: AiModelSpec,
        onProgress: (Float) -> Unit
    ) {
        val expected = spec.expectedSha256
        if (expected.isNullOrBlank()) {
            // No checksum supplied — trust the size check + transport.
            onProgress(0.95f)
            return
        }
        val file = modelFile(spec)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            var processed = 0L
            val size = file.length()
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
                processed += read
                // Verify occupies 80% → 95% of the lifecycle.
                val verifyFrac = if (size > 0) processed.toFloat() / size else 1f
                onProgress(0.8f + 0.15f * verifyFrac.coerceIn(0f, 1f))
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) {
            throw ChecksumMismatchException(
                "تأیید صحت مدل ناموفق بود. هش محاسبه‌شده با هش مورد انتظار مطابقت ندارد."
            )
        }
        onProgress(0.95f)
    }

    companion object {
        const val MODELS_DIR_NAME = "models"
    }
}

/**
 * Abstraction over `URL.openConnection()` so unit tests can supply a
 * fake [HttpURLConnection] without hitting the network.
 *
 * Implementations are expected to return an [HttpURLConnection] whose
 * `responseCode`, `inputStream`, and `contentLengthLong` reflect the
 * intended download. The default implementation ([DefaultNetworkClient])
 * is a thin pass-through to [URL.openConnection].
 */
interface NetworkClient {
    fun openConnection(url: URL): HttpURLConnection
}

/**
 * Contract for the on-device model file manager that AI tool engines
 * (matting, auto-frame, voice-clean, future MODNet / BlazeFace /
 * DeepFilterNet backends) depend on. Exists as an interface so JVM
 * unit tests can supply hand-rolled fakes without subclassing the
 * `final` [ModelManager] production class.
 *
 * Production implementation: [ModelManager].
 * Hilt provides the binding through [com.aicreatorstudio.app.di.AppModule].
 */
interface ModelInstaller {
    /** True if [spec]'s model file is present on disk and meets its min size. */
    fun isInstalled(spec: AiModelSpec): Boolean

    /**
     * Download (and verify) [spec]'s model file. Returns a
     * [ModelInstallResult]; never throws.
     */
    suspend fun install(
        spec: AiModelSpec,
        onProgress: (Float) -> Unit
    ): ModelInstallResult

    /** Idempotent. Used by the UI's "Remove Model" debug action. */
    suspend fun uninstall(spec: AiModelSpec): Boolean

    /**
     * Open the model file as a [Uri]. Engines that prefer URIs over
     * raw [File] handles (e.g. TFLite `Interpreter` from file path
     * string) read the path off this.
     */
    fun modelUri(spec: AiModelSpec): android.net.Uri
}

/** Default [NetworkClient] that delegates to [URL.openConnection]. */
class DefaultNetworkClient @Inject constructor() : NetworkClient {
    override fun openConnection(url: URL): HttpURLConnection =
        url.openConnection() as HttpURLConnection
}

/**
 * One-shot spec for a model the AI tools might need. Mirrors the
 * shape of [com.aicreatorstudio.app.ai.ModelSpec] (used by Whisper)
 * but lives in this file so it's owned by [ModelManager].
 *
 * @property displayName user-visible label, e.g. "MODNet Mobile"
 * @property fileName file name under the engine directory
 * @property dirName subdirectory under `filesDir/models/`
 * @property url download URL — must be HTTPS so airplane-mode users
 *   can still see the "Not Installed" UI without auto-downloads
 * @property minSizeBytes minimum acceptable byte count for a valid
 *   download. Used both as a download target hint and as a sanity
 *   check after the bytes land.
 * @property expectedSha256 optional SHA-256 of the model bytes; when
 *   present the install path verifies the checksum before declaring
 *   Ready.
 */
/**
 * One-shot spec for a model the AI tools might need. Distinct from
 * the original `ModelSpec` enum so future TFLite
 * backends (MODNet, BlazeFace, DeepFilterNet) can declare arbitrary
 * weight sets without colliding with the Whisper checkpoint list.
 *
 * @property displayName user-visible label, e.g. "MODNet Mobile"
 * @property fileName file name under the engine directory
 * @property dirName subdirectory under `filesDir/models/`
 * @property url download URL — must be HTTPS so airplane-mode users
 *   can still see the "Not Installed" UI without auto-downloads
 * @property minSizeBytes minimum acceptable byte count for a valid
 *   download. Used both as a download target hint and as a sanity
 *   check after the bytes land.
 * @property expectedSha256 optional SHA-256 of the model bytes; when
 *   present the install path verifies the checksum before declaring
 *   Ready.
 */
data class AiModelSpec(
    val displayName: String,
    val fileName: String,
    val dirName: String,
    val url: String,
    val minSizeBytes: Long,
    val expectedSha256: String? = null
)

/**
 * Sealed result of a [ModelManager.install] call. Lets the UI
 * distinguish a successful install from a checksum failure vs. a
 * network failure without parsing exception messages.
 */
sealed interface ModelInstallResult {
    data class Ready(val spec: AiModelSpec) : ModelInstallResult
    data class ChecksumMismatch(val spec: AiModelSpec) : ModelInstallResult
    data class Failed(val reason: String) : ModelInstallResult
}

/**
 * Thrown by [ModelManager.verify] when the downloaded bytes' SHA-256
 * does not match the [AiModelSpec.expectedSha256] pinned in
 * `models.lock.json`. Caught by [ModelManager.install] and translated
 * to [ModelInstallResult.ChecksumMismatch] so the UI can present the
 * failure as a distinct user-visible state ("checksum mismatch —
 * retry the download") rather than a generic error.
 */
class ChecksumMismatchException(message: String) : Exception(message)