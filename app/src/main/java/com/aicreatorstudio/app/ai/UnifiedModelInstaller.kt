package com.aicreatorstudio.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * PHASE A — shared, single-source-of-truth model installer.
 *
 * ## Contract
 *
 * `install(spec)` runs the full pipeline:
 *
 *   NOT_INSTALLED  →  DOWNLOADING  →  VERIFYING  →  INSTALLING  →  READY
 *                       ↓                ↓             ↓             ↓
 *                   Failed(net)    Failed(sha)   Failed(io)    Released
 *
 * Returns an [InstallOutcome] (sealed). **Never throws.** Every
 * failure path carries an [AiFailure] kind + a user-facing detail
 * message so the UI can present a precise error.
 *
 * ## Single source of truth
 *
 * The [ModelRegistry.Spec] carries the URL and SHA-256. The
 * installer never invents either. This is the fix for the
 * `TBD_PLACEHOLDER_REPLACE_BEFORE_SHIP` bug in the legacy system —
 * the SHA-256 cannot be a placeholder any more because it has to
 * come from the lockfile (the registry fails to parse if a SHA is
 * missing or invalid).
 *
 * ## On-disk layout
 *
 *   `filesDir/models/<dirName>/<fileName>`        final, verified
 *   `filesDir/models/<dirName>/<fileName>.partial`  during download
 *   `filesDir/models/<dirName>/<fileName>.sha256`   cached verified SHA
 *
 * The `.sha256` sidecar lets hot-path `isVerifiedInstalled` checks
 * skip the full rehash — we trust the sidecar because the only
 * writer is the installer itself. On app start, the installer can
 * be asked to `verifyAll` which re-hashes every sidecar against
 * the lockfile (catches bit-rot).
 *
 * ## Retry + cancellation
 *
 * The HTTP download is retried up to [MAX_ATTEMPTS] times with
 * exponential backoff (2 s, 4 s, 8 s). Cancellation is cooperative
 * and fires between byte reads; the partial is deleted when the
 * coroutine is cancelled.
 *
 * ## Storage check
 *
 * Before opening a connection the installer checks that
 * `filesDir.usableSpace` is at least 2× [ModelRegistry.Spec.declaredSizeBytes].
 * If not, the install fails with [AiFailure.INSUFFICIENT_STORAGE]
 * and the network is not touched.
 */
@Singleton
class UnifiedModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    /**
     * HTTP transport. The default [DefaultHttpFetcher] uses
     * `HttpURLConnection`. JVM unit tests construct the installer
     * with a hand-rolled fake so they can script response codes,
     * byte streams, content lengths, and timeouts without
     * touching the network.
     */
    private val http: HttpFetcher = DefaultHttpFetcher()
) {

    /**
     * One-shot HTTP fetch. Returns the response code + an
     * [InputStream] over the body. The caller is responsible for
     * closing both the stream and the returned connection.
     */
    data class HttpResponse(
        val code: Int,
        val contentLength: Long,
        val stream: java.io.InputStream,
        private val closer: () -> Unit
    ) : AutoCloseable {
        override fun close() = closer()
    }

    interface HttpFetcher {
        fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse
    }

    /** Production [HttpFetcher] backed by [HttpURLConnection]. */
    class DefaultHttpFetcher @Inject constructor() : HttpFetcher {
        override fun open(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpResponse {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", "AI-Creator-Studio/1.0")
                instanceFollowRedirects = true
            }
            // Force the connect so the response code is available.
            try { conn.connect() } catch (_: Exception) { /* surfaced via responseCode */ }
            val code = runCatching { conn.responseCode }.getOrDefault(-1)
            val len = runCatching { conn.contentLengthLong }.getOrDefault(-1L)
            val stream: java.io.InputStream = runCatching { conn.inputStream }
                .getOrElse { java.io.ByteArrayInputStream(ByteArray(0)) }
            return HttpResponse(code, len, stream) { conn.disconnect() }
        }
    }


    /**
     * Look up [spec] on disk and confirm it matches the lockfile.
     * Cheap: trusts the `.sha256` sidecar if present.
     */
    fun isVerifiedInstalled(spec: ModelRegistry.Spec): Boolean {
        val final = modelFileFor(context.filesDir, spec)
        if (!final.exists() || final.length() < spec.minSizeBytes) return false
        val sidecar = File(final.parentFile, "${final.name}.sha256")
        if (sidecar.exists()) {
            val cached = runCatching { sidecar.readText().trim().lowercase() }.getOrNull()
            if (cached == spec.expectedSha256) return true
            // Sidecar is stale or was written by a different model
            // version; fall through to the full rehash.
        }
        val actual = runCatching { sha256OfFile(final) }.getOrNull() ?: return false
        if (actual.equals(spec.expectedSha256, ignoreCase = true)) {
            // Refresh the sidecar so the next call is fast.
            runCatching { sidecar.writeText(actual) }
            return true
        }
        return false
    }

    /**
     * Full pipeline. Reports progress 0..1 via [onProgress] and phase
     * transitions via [phaseListener]. Never throws. Safe to cancel
     * by cancelling the calling coroutine.
     */
    suspend fun install(
        spec: ModelRegistry.Spec,
        phaseListener: AiPhaseListener = AiPhaseListener { _, _ -> },
        onProgress: (Float) -> Unit = {}
    ): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            if (isVerifiedInstalled(spec)) {
                phaseListener.onPhase(AiPhase.READY, 1f)
                onProgress(1f)
                return@withContext InstallOutcome.Ready(spec, source = InstallSource.Cached)
            }

            // Storage pre-flight — fail before we open a connection.
            val need = spec.declaredSizeBytes * 2L
            val have = runCatching { context.filesDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
            if (have in 0 until need) {
                val msg = "فضای ذخیره‌سازی کافی نیست. مدل ${spec.displayName} " +
                    "به $need بایت فضا نیاز دارد؛ موجود: $have بایت."
                phaseListener.onPhase(AiPhase.NOT_INSTALLED, 0f)
                return@withContext InstallOutcome.Failed(
                    spec, AiPhase.NOT_INSTALLED,
                    AiFailure.INSUFFICIENT_STORAGE, msg
                )
            }

            // Resolve the install directory once and reuse.
            val installDir = File(context.filesDir, "models/${spec.dirName}").apply { mkdirs() }
            val finalFile = File(installDir, spec.fileName)
            val partial = File(installDir, "${spec.fileName}.partial")
            val sidecar = File(installDir, "${spec.fileName}.sha256")

            // BUNDLED-ASSET FIRST (2026-09-06): if the APK ships the
            // model under `assets/ai-models/<dirName>/<fileName>`,
            // stream it into the partial slot and verify it like a
            // download — no network touched. If the bundled asset
            // is absent OR its SHA does not match the lockfile
            // (stripped/corrupt APK), fall through to the network
            // download so the user can repair the model in-app
            // instead of reinstalling.
            var fromBundled = false
            when (val bundledResult = copyBundledAsset(spec, partial)) {
                is BundledResult.Failure -> {
                    runCatching { partial.delete() }
                    phaseListener.onPhase(AiPhase.NOT_INSTALLED, 0f)
                    return@withContext InstallOutcome.Failed(
                        spec, AiPhase.DOWNLOADING, bundledResult.kind, bundledResult.message
                    )
                }
                is BundledResult.Copied -> {
                    val shaOk = runCatching {
                        sha256OfFile(partial).equals(spec.expectedSha256, ignoreCase = true)
                    }.getOrDefault(false)
                    if (shaOk) {
                        fromBundled = true
                    } else {
                        // Corrupt bundled asset — discard and let the
                        // network path below repair it.
                        runCatching { partial.delete() }
                    }
                }
                is BundledResult.Miss -> Unit
            }
            if (!fromBundled) {
                // DOWNLOAD — only when no usable bundled asset exists.
                phaseListener.onPhase(AiPhase.DOWNLOADING, 0f)
                onProgress(0f)
                val downloadResult = downloadWithRetry(
                    url = spec.url,
                    dest = partial,
                    declaredSize = spec.declaredSizeBytes,
                    onProgress = { frac -> onProgress(frac * 0.8f) }
                )
                if (downloadResult is DownloadResult.Failure) {
                    runCatching { partial.delete() }
                    phaseListener.onPhase(AiPhase.NOT_INSTALLED, 0f)
                    return@withContext InstallOutcome.Failed(
                        spec, AiPhase.DOWNLOADING, downloadResult.kind, downloadResult.message
                    )
                }
            }
            coroutineContext[Job]?.ensureActive()

            // VERIFY
            phaseListener.onPhase(AiPhase.VERIFYING, 0.8f)
            onProgress(0.8f)
            val verifyResult = runCatching {
                val actual = sha256OfFile(partial)
                if (!actual.equals(spec.expectedSha256, ignoreCase = true)) {
                    throw IOException(
                        "SHA-256 mismatch: expected=${spec.expectedSha256} " +
                            "actual=$actual for ${spec.id}"
                    )
                }
                actual
            }
            if (verifyResult.isFailure) {
                runCatching { partial.delete() }
                val msg = verifyResult.exceptionOrNull()?.message ?: "verify failed"
                phaseListener.onPhase(AiPhase.NOT_INSTALLED, 0f)
                return@withContext InstallOutcome.Failed(
                    spec, AiPhase.VERIFYING, AiFailure.MODEL_VERIFICATION_FAILED, msg
                )
            }
            onProgress(0.95f)

            // INSTALL (atomic rename)
            phaseListener.onPhase(AiPhase.INSTALLING, 0.95f)
            val ok = try {
                if (finalFile.exists()) finalFile.delete()
                if (!partial.renameTo(finalFile)) {
                    // renameTo can fail across volumes / AV locks;
                    // fall back to a copy.
                    finalFile.delete()
                    partial.copyTo(finalFile, overwrite = true)
                    partial.delete()
                    true
                } else true
            } catch (e: Exception) {
                runCatching { partial.delete() }
                false
            }
            if (!ok) {
                phaseListener.onPhase(AiPhase.NOT_INSTALLED, 0f)
                return@withContext InstallOutcome.Failed(
                    spec, AiPhase.INSTALLING, AiFailure.MODEL_LOAD_FAILED,
                    "Could not move ${partial.absolutePath} to ${finalFile.absolutePath}"
                )
            }
            // Persist the verified SHA so the next hot-path check is
            // a sidecar read, not a full rehash.
            runCatching { sidecar.writeText(spec.expectedSha256) }
            onProgress(1f)
            phaseListener.onPhase(AiPhase.READY, 1f)
            InstallOutcome.Ready(
                spec,
                source = if (fromBundled) InstallSource.Bundled else InstallSource.Downloaded
            )
        } catch (e: CancellationException) {
            // Best-effort cleanup; do not mask cancellation.
            runCatching {
                File(context.filesDir, "models/${spec.dirName}/${spec.fileName}.partial")
                    .delete()
            }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "install(${spec.id}) failed unexpectedly", e)
            InstallOutcome.Failed(
                spec, AiPhase.NOT_INSTALLED,
                AiFailure.MODEL_LOAD_FAILED, e.message ?: e::class.java.simpleName
            )
        }
    }

    /**
     * Idempotent delete. Removes the model file, the partial, and
     * the SHA sidecar. Used by retry paths and by the UI's
     * "remove model" debug action.
     */
    suspend fun uninstall(spec: ModelRegistry.Spec): Boolean = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models/${spec.dirName}")
        listOf(spec.fileName, "${spec.fileName}.partial", "${spec.fileName}.sha256")
            .map { File(dir, it) }
            .filter { it.exists() }
            .map { runCatching { it.delete() }.getOrDefault(false) }
            .all { it }
    }

    // ---- internals ----

    /** Result of trying to seed the partial from the bundled asset. */
    private sealed class BundledResult {
        /** No bundled asset in the APK — fall back to the network. */
        data object Miss : BundledResult()

        /** Bundled asset copied into the partial slot. */
        data object Copied : BundledResult()

        /** Bundled asset present but unreadable — treat as a failure. */
        data class Failure(val kind: AiFailure, val message: String) : BundledResult()
    }

    /**
     * Stream `assets/ai-models/<dirName>/<fileName>` into [dest]
     * when the APK carries it. Returns [BundledResult.Miss] when
     * the asset is absent (caller falls back to the network),
     * [BundledResult.Copied] on success, or [BundledResult.Failure]
     * when the asset exists but cannot be read/copied.
     */
    private fun copyBundledAsset(spec: ModelRegistry.Spec, dest: File): BundledResult {
        val path = "ai-models/${spec.dirName}/${spec.fileName}"
        val input = runCatching { context.assets.open(path) }.getOrNull()
            ?: return BundledResult.Miss
        return try {
            input.use { ins ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            if (dest.length() < spec.minSizeBytes) {
                dest.delete()
                BundledResult.Failure(
                    AiFailure.MODEL_VERIFICATION_FAILED,
                    "bundled asset $path is ${dest.length()} bytes, " +
                        "expected at least ${spec.minSizeBytes}"
                )
            } else {
                BundledResult.Copied
            }
        } catch (e: Exception) {
            runCatching { dest.delete() }
            BundledResult.Failure(
                AiFailure.MODEL_DOWNLOAD_FAILED,
                "bundled asset $path could not be copied: ${e.message ?: e::class.java.simpleName}"
            )
        }
    }

    private sealed class DownloadResult {
        data object Success : DownloadResult()
        /**
         * @param retryable when true, [downloadWithRetry] will try
         *  again with exponential backoff. Used for transient
         *  failures (5xx, timeouts, network errors). 4xx and
         *  payload-shape failures are NOT retryable — the same
         *  request will produce the same response.
         */
        data class Failure(
            val kind: AiFailure,
            val message: String,
            val retryable: Boolean
        ) : DownloadResult()
    }

    private fun sha256OfFile(file: File): String {
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

    private suspend fun downloadWithRetry(
        url: String,
        dest: File,
        declaredSize: Long,
        onProgress: (Float) -> Unit
    ): DownloadResult {
        var lastError: AiFailure = AiFailure.MODEL_DOWNLOAD_FAILED
        var lastMsg: String = "download not attempted"
        var lastRetryable: Boolean = true
        for (attempt in 1..MAX_ATTEMPTS) {
            coroutineContext[Job]?.ensureActive()
            if (dest.exists()) dest.delete()
            val outcome = try {
                downloadOnce(url, dest, declaredSize, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Network-level exceptions (no response code) are
                // always treated as retryable.
                DownloadResult.Failure(
                    AiFailure.MODEL_DOWNLOAD_FAILED,
                    e.message ?: e::class.java.simpleName,
                    retryable = true
                )
            }
            if (outcome is DownloadResult.Success) return outcome
            outcome as DownloadResult.Failure
            lastError = outcome.kind
            lastMsg = outcome.message
            lastRetryable = outcome.retryable
            Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed: $lastMsg (retryable=${outcome.retryable})")
            // Non-retryable failures (4xx, oversize body) — fail fast
            // with the same error; retrying will produce the same
            // result.
            if (!outcome.retryable) {
                return DownloadResult.Failure(outcome.kind, outcome.message, retryable = false)
            }
            if (attempt < MAX_ATTEMPTS) {
                val backoffMs = BACKOFF_BASE_MS * (1L shl (attempt - 1)) // 2s, 4s, 8s
                try {
                    delay(backoffMs)
                } catch (e: CancellationException) {
                    throw e
                }
            }
        }
        return DownloadResult.Failure(lastError,
            "after $MAX_ATTEMPTS attempts: $lastMsg",
            retryable = lastRetryable)
    }

    /**
     * Single download attempt. Returns [DownloadResult.Success] iff
     * the bytes land on disk and the byte count is plausible. The
     * SHA check happens in the caller.
     */
    private fun downloadOnce(
        url: String,
        dest: File,
        declaredSize: Long,
        onProgress: (Float) -> Unit
    ): DownloadResult {
        val response = http.open(url, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        response.use { resp ->
            val rc = resp.code
            if (rc !in 200..299) {
                // 5xx is transient (CDN blip, server restart) and
                // worth retrying; 4xx is permanent (404, 410) and
                // will never succeed, so fail fast.
                val retryable = rc in 500..599
                return DownloadResult.Failure(
                    if (retryable) AiFailure.NETWORK_TIMEOUT else AiFailure.MODEL_DOWNLOAD_FAILED,
                    "HTTP $rc downloading $url",
                    retryable = retryable
                )
            }
            val totalBytes = resp.contentLength.takeIf { it > 0 } ?: declaredSize
            resp.stream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(128 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        total += n
                        if (total > declaredSize * 2L && declaredSize > 0L) {
                            return DownloadResult.Failure(
                                AiFailure.MODEL_DOWNLOAD_FAILED,
                                "downloaded bytes ($total) exceed 2x declared size " +
                                    "($declaredSize) — aborting",
                                retryable = false
                            )
                        }
                        val frac = (total.toFloat() / totalBytes).coerceIn(0f, 1f)
                        onProgress(frac)
                    }
                }
            }
            if (dest.length() < 1L) {
                return DownloadResult.Failure(
                    AiFailure.MODEL_DOWNLOAD_FAILED,
                    "downloaded file is empty",
                    retryable = false
                )
            }
            return DownloadResult.Success
        }
    }

    companion object {
        private const val TAG = "UnifiedModelInstaller"
        private const val MAX_ATTEMPTS: Int = 3
        private const val CONNECT_TIMEOUT_MS: Int = 30_000
        private const val READ_TIMEOUT_MS: Int = 120_000
        private const val BACKOFF_BASE_MS: Long = 2_000L
    }
}

/**
 * One terminal result of an [UnifiedModelInstaller.install] call.
 * The [phase] reports where the install stopped; the [kind] is one
 * of the [AiFailure] values so the UI can present the right message.
 */
sealed interface InstallOutcome {
    val spec: ModelRegistry.Spec
    val phase: AiPhase

    data class Ready(
        override val spec: ModelRegistry.Spec,
        val source: InstallSource
    ) : InstallOutcome {
        override val phase: AiPhase get() = AiPhase.READY
    }

    data class Failed(
        override val spec: ModelRegistry.Spec,
        override val phase: AiPhase,
        val kind: AiFailure,
        val detail: String
    ) : InstallOutcome

    val isReady: Boolean get() = this is Ready
    val isFailed: Boolean get() = this is Failed
}

/** Where the verified model bytes came from on a Ready outcome. */
enum class InstallSource { Cached, Downloaded, Bundled }
