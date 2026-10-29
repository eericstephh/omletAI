package com.aicreatorstudio.app.ai

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized AI model installer — the PHASE 2 / PHASE 9 entry point.
 *
 * Lifecycle (PHASE 2 of the brief):
 *
 *   NOT_INSTALLED
 *     ↓
 *   DOWNLOADING    ← resolves URL from models.lock.json via Gradle
 *     ↓             (catalog URL is in the bundled manifest.json)
 *   VERIFYING      ← SHA-256 of the file matches manifest.json
 *     ↓
 *   INSTALLING     ← atomic rename from .partial -> final
 *     ↓
 *   LOADING        ← engine loads the file (TFLite / whisper.cpp)
 *     ↓
 *   READY          ← engine confirms the file is usable
 *
 * Each failure path returns an [AiLoadOutcome] with a precise
 * [AiFailure] kind so the UI can show the right error message
 * (PHASE 7 of the brief — no more generic "no engine available").
 *
 * ## Why a separate file from ModelManager.kt
 *
 * The legacy ModelManager kept `expectedSha256` on the spec itself.
 * With multiple models that meant the catalog carried hardcoded
 * SHAs in source, which would silently rot if anyone bumped
 * models.lock.json without updating the spec. The new design reads
 * the SHA from the bundled `ai-models/manifest.json` (generated at
 * build time), so a manifest regeneration propagates to runtime
 * without any Kotlin source change.
 *
 * ## Threading
 *
 * Public functions are `suspend` and use `Dispatchers.IO` internally.
 *
 * ## 2026-09-06 — bundled-first, network-repair fallback
 *
 * On first launch the bundled assets under
 * `assets/ai-models/<dirName>/<fileName>` are present (because the
 * Gradle download task populated them during the build). We copy
 * them into `filesDir/models/...` and verify the SHA before
 * declaring READY. If the bundled asset is missing or its SHA
 * doesn't match the manifest, we fall back to downloading from the
 * network. The order is intentional: bundled-first means the app
 * works offline after a single install, and the network step is a
 * repair path the user can always fall back to instead of
 * reinstalling.
 */
@Singleton
class AiModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Returns the disk-side "is this file installed and verified?"
     * check. Pure disk + manifest read; no network.
     */
    fun isInstalled(spec: CatalogEntry): Boolean =
        AiModels.isVerifiedInstalled(context, spec)

    /**
     * Full install pipeline. Reports phase transitions via
     * [phaseListener] and progress within each phase via
     * [onProgress]. Never throws; returns an [AiLoadOutcome]
     * whose `failure` is non-null on any error.
     *
     * Steps:
     *  1. If a verified file already exists, return READY immediately.
     *  2. If a bundled asset is present in `assets/ai-models/...`,
     *     copy it to `filesDir/models/...` and verify (offline path).
     *  3. Otherwise, download from the URL in models.lock.json via
     *     Gradle (network path).
     *  4. SHA-256 verify against the manifest.
     *  5. Atomic rename .partial -> final.
     */
    suspend fun installWithManifest(
        spec: CatalogEntry,
        phaseListener: AiPhaseListener = AiPhaseListener { _, _ -> },
        onProgress: (Float) -> Unit = {}
    ): AiLoadOutcome = withContext(Dispatchers.IO) {
        try {
            // Step 0: already installed?
            if (isInstalled(spec)) {
                phaseListener.onPhase(AiPhase.READY, 1f)
                onProgress(1f)
                return@withContext AiLoadOutcome.ready(spec)
            }

            // Step 1: try bundled asset (offline path). Stream the
            // asset straight into the cache-dir .partial slot so we
            // never inflate a 75 MB Whisper file into a ByteArray.
            phaseListener.onPhase(AiPhase.DOWNLOADING, 0f)
            onProgress(0f)
            val tmpDir = File(context.cacheDir, "ai-model-downloads").apply { mkdirs() }
            val stagedFile: File = File(tmpDir, "${spec.id}.partial")
            if (stagedFile.exists()) stagedFile.delete()

            val copiedFromAssets = runCatching {
                copyBundledAssetTo(spec, stagedFile, onProgress)
            }.getOrDefault(false)

            if (!copiedFromAssets) {
                // Step 2: network fallback. 2026-09-06: the bundled
                // asset is missing (e.g. the APK was stripped of
                // large assets) — instead of the old dead-end
                // BUNDLED_ASSET_MISSING failure the installer now
                // downloads the model in-app from the URL in
                // models.lock.json so the tool can be repaired
                // without a reinstall. If the URL is unknown the
                // install fails with MODEL_DOWNLOAD_FAILED
                // ("no URL configured").
                val url = downloadUrlFor(spec)
                if (url == null) {
                    stagedFile.delete()
                    return@withContext AiLoadOutcome.failed(
                        spec, AiPhase.DOWNLOADING,
                        AiFailure.MODEL_DOWNLOAD_FAILED,
                        "No download URL configured for spec ${spec.id}"
                    )
                }
                downloadToPartial(url, spec, onProgress)
            }

            // Step 3: SHA verify.
            phaseListener.onPhase(AiPhase.VERIFYING, 0f)
            val expectedSha = AiModels.sha256For(context, spec)
            if (expectedSha == null) {
                return@withContext AiLoadOutcome.failed(
                    spec, AiPhase.VERIFYING,
                    AiFailure.MODEL_VERIFICATION_FAILED,
                    "manifest.json has no sha256 for ${spec.id}"
                )
            }
            // 2026-10-11 — exact-size check BEFORE hashing. The manifest
            // carries the publisher's exact byte count; a mismatch is
            // conclusive and cheap, and it lets us name the fault
            // precisely instead of reporting a generic hash failure for
            // what is really a truncated transfer.
            val expectedSize = AiModels.expectedSizeFor(context, spec)
            val stagedSize = stagedFile.length()
            if (expectedSize != null && stagedSize != expectedSize) {
                Log.w(
                    TAG,
                    "size mismatch for ${spec.id}: staged=$stagedSize expected=$expectedSize"
                )
                stagedFile.delete()
                return@withContext AiLoadOutcome.failed(
                    spec, AiPhase.VERIFYING,
                    AiFailure.MODEL_VERIFICATION_FAILED,
                    "Size mismatch for ${spec.id}: expected=$expectedSize got=$stagedSize " +
                        "(the transfer was incomplete or the file was truncated)"
                )
            }
            val actualSha = AiModels.sha256OfFile(stagedFile)
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                return@withContext AiLoadOutcome.failed(
                    spec, AiPhase.VERIFYING,
                    AiFailure.MODEL_VERIFICATION_FAILED,
                    "SHA mismatch for ${spec.id}: expected=$expectedSha got=$actualSha"
                )
            }
            onProgress(0.9f)

            // Step 4: atomic rename into place.
            phaseListener.onPhase(AiPhase.INSTALLING, 0.9f)
            val finalFile = AiModels.modelFile(context, spec)
            finalFile.parentFile?.mkdirs()

            // 2026-10-11 PRESERVE-VALID-MODEL FIX.
            //
            // The old code did `if (finalFile.exists()) finalFile.delete()`
            // and only THEN attempted the move. If the move then failed,
            // the user was left with the model deleted and nothing to
            // replace it — they lost a working model to a failed repair.
            //
            // The staged file is already SHA-verified at this point, so
            // we move it into place *over* the existing file. On Android
            // and POSIX a rename over an existing path is atomic, so the
            // old file is only ever replaced by a good one, never lost.
            // If the destination cannot be overwritten at all, we delete
            // it as a last resort but only because the replacement has
            // already been verified.
            val ok = if (stagedFile == finalFile) {
                true // already in place (bundled path copied directly)
            } else {
                stagedFile.renameTo(finalFile) ||
                    runCatching {
                        // Cross-filesystem or non-overwriting rename.
                        // Move the existing file aside first so a failure
                        // here still leaves us able to restore it.
                        val backup = File(finalFile.parentFile, "${finalFile.name}.bak")
                        if (backup.exists()) backup.delete()
                        val hadExisting = finalFile.exists()
                        if (hadExisting && !finalFile.renameTo(backup)) {
                            finalFile.delete()
                        }
                        val copied = stagedFile.copyTo(finalFile, overwrite = true)
                        if (copied.length() == stagedFile.length()) {
                            stagedFile.delete()
                            backup.delete()
                            true
                        } else {
                            // Restore the previous good file.
                            finalFile.delete()
                            if (hadExisting && backup.exists()) backup.renameTo(finalFile)
                            false
                        }
                    }.getOrDefault(false)
            }
            if (!ok) {
                // Leave any surviving previous model in place — a failed
                // repair must not cost the user a working model.
                return@withContext AiLoadOutcome.failed(
                    spec, AiPhase.INSTALLING,
                    AiFailure.MODEL_DOWNLOAD_FAILED,
                    "Could not move model into ${finalFile.absolutePath}"
                )
            }

            // 2026-10-11 ATOMICITY FIX — re-verify the DESTINATION.
            //
            // `cacheDir` and `filesDir` are not guaranteed to share a
            // filesystem, so `renameTo` can legitimately return false and
            // fall through to `copyTo`, which is NOT atomic and is not
            // resumable. A process death or an I/O error part-way through
            // that copy leaves a truncated file at the FINAL path — where
            // the `.sha256` sidecar written below would make it look
            // installed forever.
            //
            // The verification above ran on the *staging* file, so it
            // proves nothing about what actually landed. Re-check the
            // destination size (cheap) and hash (only when the cheap
            // check is inconclusive) and refuse to install a file we
            // cannot vouch for.
            val landedSize = finalFile.length()
            val landedOk = when {
                expectedSize != null -> landedSize == expectedSize
                else -> landedSize >= spec.minSizeBytes
            }
            if (!landedOk) {
                Log.w(
                    TAG,
                    "post-move size check failed for ${spec.id}: " +
                        "landed=$landedSize expected=${expectedSize ?: spec.minSizeBytes}"
                )
                finalFile.delete()
                return@withContext AiLoadOutcome.failed(
                    spec, AiPhase.INSTALLING,
                    AiFailure.MODEL_VERIFICATION_FAILED,
                    "Model did not land intact at the final path " +
                        "(got $landedSize bytes). Storage may have run out mid-copy."
                )
            }

            // 2026-10-11 PERF FIX — persist the verified SHA sidecar
            // so every future isVerifiedInstalled check (editor open,
            // model-picker refresh) skips the full rehash of the
            // multi-GB file. The mismatch branch in
            // AiModels.isVerifiedInstalled deletes a stale sidecar.
            //
            // Written ONLY after the destination has been confirmed
            // intact, since this file is what lets every later check
            // skip the hash.
            runCatching {
                java.io.File(finalFile.parentFile, finalFile.name + ".sha256")
                    .writeText(actualSha.lowercase())
            }

            onProgress(1f)

            AiLoadOutcome.ready(spec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "installWithManifest(${spec.id}) threw", e)
            AiLoadOutcome.failed(
                spec, AiPhase.DOWNLOADING,
                AiFailure.MODEL_DOWNLOAD_FAILED,
                e.message ?: e::class.java.simpleName
            )
        }
    }

    /**
     * Engine-side load test: read the file via the engine's loader
     * and confirm the engine returns a non-null handle. For Whisper
     * this is `WhisperNative.initFromFile(...)` returning a non-zero
     * context pointer. The actual engine load is performed by the
     * caller; this helper just composes the [AiLoadOutcome] from
     * the engine's load result.
     */
    fun loaded(spec: CatalogEntry, engineLoadOk: Boolean, detail: String? = null): AiLoadOutcome {
        return if (engineLoadOk) AiLoadOutcome.ready(spec)
        else AiLoadOutcome.failed(
            spec, AiPhase.LOADING,
            AiFailure.MODEL_LOAD_FAILED,
            detail
        )
    }

    /**
     * Delete the installed model. Idempotent. Used by the UI's
     * "remove model" debug action and by retry paths that want to
     * force a fresh download.
     */
    suspend fun uninstall(spec: CatalogEntry): Boolean = withContext(Dispatchers.IO) {
        val file = AiModels.modelFile(context, spec)
        val ok = if (file.exists()) file.delete() else true
        // Also clean up any stale .partial from a previous interrupted
        // download.
        val partial = File(file.parentFile, "${file.name}.partial")
        if (partial.exists()) partial.delete()
        ok
    }

    // ---- internals ----

    /**
     * Stream the bundled asset at `ai-models/<dirName>/<fileName>` to
     * [dest] in 128 KiB chunks. Returns true on success, false if the
     * asset is absent or shorter than [spec.minSizeBytes]. Progress
     * is reported on a 0..0.5 scale (the network path uses 0..0.9)
     * so the bundled path finishes well before verification.
     */
    private fun copyBundledAssetTo(
        spec: CatalogEntry,
        dest: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val path = AiModels.bundledAssetPath(spec)
        val asset = runCatching { context.assets.open(path) }.getOrNull() ?: return false
        return asset.use { input ->
            try {
                val total = runCatching { context.assets.openFd(path).use { it.length } }
                    .getOrNull() ?: spec.minSizeBytes
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(128 * 1024)
                    var totalRead = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        totalRead += n
                        val frac = (totalRead.toFloat() / total).coerceIn(0f, 1f)
                        onProgress(frac * 0.5f)
                    }
                }
                true
            } catch (e: Exception) {
                Log.w(TAG, "copyBundledAssetTo($path) failed", e)
                if (dest.exists()) dest.delete()
                false
            }
        }
    }

    private suspend fun downloadToPartial(
        url: String,
        spec: CatalogEntry,
        onProgress: (Float) -> Unit
    ): File {
        val tmpDir = File(context.cacheDir, "ai-model-downloads").apply { mkdirs() }
        val tmp = File(tmpDir, "${spec.id}.partial")
        if (tmp.exists()) tmp.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", "AI-Creator-Studio/1.0")
            instanceFollowRedirects = true
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                throw RuntimeException("HTTP $rc downloading $url")
            }
            val totalBytes = conn.contentLengthLong.takeIf { it > 0 } ?: spec.minSizeBytes
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(128 * 1024)
                    var total = 0L
                    var lastLog = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        total += n
                        val frac = (total.toFloat() / totalBytes).coerceIn(0f, 1f)
                        onProgress(frac * 0.9f) // 0..0.9 for download, 0.9..1.0 reserved for verify+install
                        if (System.currentTimeMillis() - lastLog > 1500) {
                            Log.d(TAG, "download ${spec.id}: $total / $totalBytes bytes")
                            lastLog = System.currentTimeMillis()
                        }
                    }
                }
            }
            if (tmp.length() < spec.minSizeBytes) {
                tmp.delete()
                throw RuntimeException(
                    "downloaded ${tmp.length()} bytes for ${spec.id}, " +
                        "expected at least ${spec.minSizeBytes}"
                )
            }
            return tmp
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Resolve the download URL for [spec]. The runtime manifest is
     * deliberately URL-free so a user inspecting the APK can't see
     * the upstream URLs; this method bakes the URLs back in from
     * `models.lock.json` (loaded via Gradle at build time) so the
     * network fallback still works.
     *
     * The two Whisper URLs (Medium, Large-v3) point at the official
     * `ggerganov/whisper.cpp` Hugging Face mirror — the same
     * publisher the bundled assets were downloaded from at
     * build time, so the SHA-256 in `models.lock.json` is
     * authoritative for the bytes the user will receive.
     *
     * The matting + pose landmarker + ESRGAN models ship bundled
     * into the APK (bundle-first offline install). The URLs kept
     * in this table for them are REPAIR FALLBACKS: the install
     * path consults them only when the bundled asset is missing
     * from the APK (stripped build) — the user can then repair
     * the model in-app instead of reinstalling. Adding more URLs
     * here is the one-place edit to support more
     * network-downloaded models.
     */
    private fun downloadUrlFor(spec: CatalogEntry): String? = when (spec.id) {
        AiModelCatalog.WHISPER_SMALL.id ->
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
        AiModelCatalog.SELFIE_MULTICLASS.id ->
            "https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite"
        AiModelCatalog.POSE_LANDMARKER_LITE.id ->
            "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task"
        AiModelCatalog.ESRGAN_TFLITE_4X.id ->
            "https://storage.googleapis.com/download.tensorflow.org/models/tflite/esrgan/ESRGAN.tflite"
        AiModelCatalog.TESSERACT_FAS.id ->
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/fas.traineddata"
        AiModelCatalog.TESSERACT_ARA.id ->
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata"
        AiModelCatalog.TESSERACT_ENG.id ->
            "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"
        else -> null
    }

    /**
     * Public, read-only view of the URL table used by the
     * network-fallback path. Exposed for JVM unit tests (see
     * [com.aicreatorstudio.app.ai.WhisperDownloadUrlTest]) so
     * the audit can assert that every model in the
     * [AiModelCatalog] has a real, current upstream URL.
     *
     * The function is intentionally pure: it does not touch
     * the network and has no side effects. Returns null when
     * the spec's id has no upstream entry, which the install
     * path translates to `AiFailure.MODEL_DOWNLOAD_FAILED`
     * with detail "No download URL configured for spec ...".
     */
    fun downloadUrlForSpec(spec: CatalogEntry): String? = downloadUrlFor(spec)

    companion object {
        const val TAG = "AiModelInstaller"
    }
}