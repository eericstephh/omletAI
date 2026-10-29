package com.aicreatorstudio.app.ai

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Centralized AI model catalog — the runtime companion to
 * `app/ai-models/models.lock.json`.
 *
 * Every AI tool (Whisper subtitles, MediaPipe matting, MediaPipe pose
 * landmarker, …) reads its [CatalogEntry] from this object. The URL
 * and SHA-256 are NOT hardcoded in this file; they are read from
 * `assets/ai-models/manifest.json` (generated at build time by the
 * Gradle download task from `models.lock.json`). That keeps the
 * runtime and the build system in lock-step: a model swap requires
 * changing `models.lock.json` once, and every consumer picks it up.
 *
 * ## Lifecycle contract (PHASE 2 of the brief)
 *
 *   NOT_INSTALLED → DOWNLOADING → VERIFYING → INSTALLING → LOADING → READY
 *                    ↓                ↓            ↓             ↓
 *               FAILED(msg)    CHECKSUM_      FAILED(msg)   FAILED(msg)
 *                              MISMATCH
 *
 * No consumer reports READY until:
 *   1. The model file exists at `filesDir/models/<dirName>/<fileName>`.
 *   2. Its SHA-256 matches the value in `manifest.json`.
 *   3. The engine successfully loads the file (e.g. TFLite Interpreter,
 *      or whisper.cpp `whisper_init_from_file_with_params`).
 *
 * ## File paths
 *
 * All on-device model files live at:
 *   `filesDir/models/<dirName>/<fileName>`
 *
 * The previous code path used `filesDir/<dirName>/<fileName>` (no
 * "models/" prefix), which caused the centralized ModelManager to
 * never see Whisper's downloads and vice-versa. The path mismatch
 * is the root cause of the "Engine reports Ready but ModelManager
 * reports Not Installed" race that previously bricked the UI.
 */
object AiModels {

    /** Internal "models/" prefix under `filesDir`. Centralized here. */
    private const val MODELS_DIR = "models"

    fun modelFile(context: Context, spec: CatalogEntry): File =
        File(context.filesDir, "$MODELS_DIR/${spec.dirName}/${spec.fileName}")

    /**
     * Read the SHA-256 from the bundled manifest.json that the
     * Gradle download task generates from models.lock.json. Returns
     * null if the manifest is missing or doesn't carry the spec's id.
     *
     * The expected-SHA-256 lives in the manifest, not on the spec,
     * so a single source of truth (manifest.json) drives every
     * install. Specs that haven't been downloaded yet never carry
     * baked-in SHAs that might drift.
     */
    fun sha256For(context: Context, spec: CatalogEntry): String? =
        readManifestSha256(context, spec.id)

    /**
     * Read the **exact** expected byte size from the bundled
     * `manifest.json`, when the manifest carries one.
     *
     * 2026-10-11 — the runtime `CatalogEntry.minSizeBytes` is a floor
     * (480,000,000 for whisper-small) that sits 7.2 MB *below* the real
     * artefact (487,601,967). That gap means a download truncated by up
     * to 7 MB still passes the size check. Reading the exact size from
     * the same manifest that supplies the SHA closes that hole and, more
     * importantly, lets the engine tell a **wrong-size** file apart from
     * a **wrong-bytes** file when native init fails.
     *
     * Returns null when the manifest has no size for [spec] — callers
     * must treat that as "unknown", never as "correct".
     */
    fun expectedSizeFor(context: Context, spec: CatalogEntry): Long? =
        readManifestSize(context, spec.id)

    /**
     * Compute the SHA-256 of a file currently on disk. Used to
     * confirm a freshly-installed model matches the manifest before
     * the engine declares READY.
     */
    fun sha256OfFile(file: File): String {
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

    /**
     * True when the model file for [spec] exists on disk AND its
     * SHA-256 matches the manifest entry. This is the gate the
     * installer uses before declaring READY.
     *
     * 2026-09-06 PERF FIX — sidecar cache. This check runs on EVERY
     * editor open (aiModelStatuses refresh) and the full rehash of
     * the 1.5 GB Medium file took 5-20 seconds of pure disk IO —
     * the "app is heavy / project opens slowly" bug. The installer
     * now writes a `<file>.sha256` sidecar after a successful
     * verified install, and this check trusts it (only the
     * installer writes it, after hashing). A missing/mismatched
     * sidecar falls back to the full hash exactly as before, so
     * integrity is never assumed — only cached.
     */
    fun isVerifiedInstalled(context: Context, spec: CatalogEntry): Boolean {
        val file = modelFile(context, spec)
        if (!file.exists() || file.length() < spec.minSizeBytes) return false

        // 2026-10-11 — exact-size gate BEFORE trusting the sidecar.
        // `minSizeBytes` is a floor that sits ~7 MB below the real
        // whisper-small artefact, so a download truncated in that band
        // used to pass. When the manifest declares an exact size, a
        // mismatch is conclusive and we must not fall through to the
        // cached sidecar (which the installer may have written for a
        // file that was correct at write time and truncated later).
        val expectedSize = readManifestSize(context, spec.id)
        if (expectedSize != null && file.length() != expectedSize) {
            runCatching {
                java.io.File(file.parentFile, file.name + ".sha256").delete()
            }
            return false
        }

        val expected = sha256For(context, spec) ?: return false

        // Fast path — installer-written sidecar.
        val sidecar = java.io.File(file.parentFile, file.name + ".sha256")
        if (sidecar.exists()) {
            val cached = runCatching { sidecar.readText().trim().lowercase() }.getOrNull()
            if (!cached.isNullOrBlank() && cached.length == 64) {
                return cached.equals(expected, ignoreCase = true)
            }
        }

        // Slow path — full hash, then cache it for next time.
        val actual = runCatching { sha256OfFile(file) }.getOrNull() ?: return false
        if (actual.equals(expected, ignoreCase = true)) {
            runCatching { sidecar.writeText(actual.lowercase()) }
            return true
        }
        // Mismatch — clear any stale sidecar so the next check
        // doesn't trust a corrupt model.
        runCatching { sidecar.delete() }
        return false
    }

    /**
     * Bundled-asset path. The Gradle download task places model
     * weights under `app/src/main/assets/ai-models/<dirName>/<fileName>`
     * and packages them into the APK. On first launch the runtime
     * installer copies each bundled asset into `filesDir/models/...`
     * so subsequent loads are fast and the user can replace the
     * file by re-downloading (or so future code can support multiple
     * model variants side-by-side).
     */
    fun bundledAssetPath(spec: CatalogEntry): String =
        "ai-models/${spec.dirName}/${spec.fileName}"

    // ---- Manifest reader ----

    private fun readManifestSha256(context: Context, id: String): String? {
        return runCatching {
            val text = context.assets.open("ai-models/manifest.json")
                .bufferedReader().use { it.readText() }
            val obj = org.json.JSONObject(text)
            val models = obj.optJSONArray("models") ?: return@runCatching null
            for (i in 0 until models.length()) {
                val entry = models.optJSONObject(i) ?: continue
                if (entry.optString("id") == id) {
                    val sha = entry.optString("sha256")
                    return@runCatching sha.takeIf { it.isNotBlank() }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 2026-10-11 — exact expected byte size from the manifest, or null
     * when absent. Written as `sizeBytes` by the Gradle download task,
     * mirroring `models.lock.json`. Null is "unknown", never "correct".
     */
    private fun readManifestSize(context: Context, id: String): Long? {
        return runCatching {
            val text = context.assets.open("ai-models/manifest.json")
                .bufferedReader().use { it.readText() }
            val obj = org.json.JSONObject(text)
            val models = obj.optJSONArray("models") ?: return@runCatching null
            for (i in 0 until models.length()) {
                val entry = models.optJSONObject(i) ?: continue
                if (entry.optString("id") == id) {
                    val size = entry.optLong("sizeBytes", -1L)
                    return@runCatching size.takeIf { it > 0L }
                }
            }
            null
        }.getOrNull()
    }
}

/**
 * Distinct from the legacy `WhisperModelSpec` enum (which is
 * UI-facing) and the legacy `AiModelSpec` data class in
 * `ModelManager.kt` (which carried SHA in source and is consumed
 * by the matting engine). This is the canonical installable-unit
 * type for the new pipeline. The UI-facing [WhisperModelSpec]
 * enum maps onto one of these via `WhisperModelSpec.aiSpec()`.
 *
 * @property id stable string id matching the manifest entry, e.g.
 *   `"whisper-medium"`, `"whisper-large-v3"`, `"selfie_multiclass"`,
 *   `"pose_landmarker_lite"`. The Gradle manifest is keyed by this
 *   id; renaming requires a models.lock.json update.
 * @property displayName user-visible label.
 * @property dirName subdirectory under `filesDir/models/` AND under
 *   `app/src/main/assets/ai-models/`.
 * @property fileName file within that directory.
 * @property minSizeBytes conservative lower bound that rejects
 *   truncated downloads. Matches `models.lock.json sizeBytes` for
 *   real entries.
 * @property language BCP-47 tag for STT engines (Whisper only).
 * @property subtitle short user-visible tagline shown in the AI tool
 *   card.
 * @property whisperThreads number of CPU threads whisper.cpp should
 *   use. Null for non-Whisper specs.
 * @property minRamMb minimum free RAM required to safely load this
 *   model. Null means no specific gate.
 */
data class CatalogEntry(
    val id: String,
    val displayName: String,
    val dirName: String,
    val fileName: String,
    val minSizeBytes: Long,
    val language: String? = null,
    val subtitle: String? = null,
    val whisperThreads: Int? = null,
    val minRamMb: Long? = null,
    /**
     * `true` for models that ship inside the APK. The runtime
     * installer prefers the bundled asset (offline path); if the
     * asset is missing or corrupt it falls back to the URL in
     * `models.lock.json` so the user can repair the model
     * in-app instead of reinstalling.
     *
     * The three production engines — Auto Frame (Pose Landmarker),
     * AI Upscale (ESRGAN), Background Remove (Selfie Multiclass)
     * — ship bundled. Voice Clean is not in this catalog at
     * all (its weights live in compiled C source).
     *
     * Defaults to `false` so pure network-downloadable models
     * (Whisper) keep their original semantics.
     */
    val bundledOnly: Boolean = false
) {
    init {
        require(id.isNotBlank()) { "CatalogEntry.id must be non-blank" }
        require(dirName.isNotBlank()) { "CatalogEntry.dirName must be non-blank" }
        require(fileName.isNotBlank()) { "CatalogEntry.fileName must be non-blank" }
        require(minSizeBytes > 0L) { "CatalogEntry.minSizeBytes must be positive" }
    }
}

/**
 * Specs for every AI model the app ships in v1. Hardcoded
 * (displayName / dirName / fileName) — only the SHAs and sizes
 * are read from the manifest. This way renaming a model is a
 * one-place edit (here), while a security-sensitive swap is a
 * one-place edit (`models.lock.json`).
 *
 * Two Whisper checkpoints (Medium / Large-v3) are selectable
 * and downloadable. PHASE B1.6 (2026-09-04) removed Tiny and
 * Small from the runtime catalog after the real-device Persian
 * transcription-quality audit; their entries are gone from this
 * catalog and from `models.lock.json`. RAM (`minRamMb`) is
 * informational only — it never gates visibility, selection,
 * download, or installation.
 *
 * 2026-09-06: the three production model entries
 * (`SELFIE_MULTICLASS`, `POSE_LANDMARKER_LITE`, `ESRGAN_TFLITE_4X`)
 * ship inside the APK (bundle-first offline install), and the
 * installer falls back to an in-app download when the bundled
 * asset is missing or fails verification. Voice Clean
 * (RNNoise) is not represented here at all because its weights
 * are compiled into `librnnoise.so` (C source, no in-app model).
 */
object AiModelCatalog {

            /**
     * Whisper Small f16 — the speed option. ~488 MB,
     * download-only (bundled: false). ~3x faster than Medium
     * with good Persian accuracy; the 2026-09-08 default.
     * Size + SHA-256 lifted from the official HuggingFace LFS
     * `oid` on the `ggerganov/whisper.cpp` mirror.
     *
     * IMPORTANT: this must be the f16 build. The q5_1 quantized
     * variant was tried (2026-09-08) and produced per-character
     * BPE tokens on Persian — letter-by-letter subtitles.
     */
    val WHISPER_SMALL: CatalogEntry = CatalogEntry(
        id = "whisper-small",
        displayName = "آفلاین — Whisper Small",
        dirName = "whisper-ggml-small",
        fileName = "ggml-small.bin",
        minSizeBytes = 480_000_000L,
        language = "fa",
        subtitle = "پردازش روی دستگاه · بدون اینترنت",
        whisperThreads = 4,
        minRamMb = 2_048L
    )

    /** MediaPipe selfie multiclass segmentation model. ~16 MB. */
    val SELFIE_MULTICLASS: CatalogEntry = CatalogEntry(
        id = "selfie_multiclass",
        displayName = "MediaPipe Selfie Multiclass (256x256)",
        dirName = "selfie_multiclass",
        fileName = "selfie_multiclass_256x256.tflite",
        minSizeBytes = 15_000_000L
    )

    /** MediaPipe pose landmarker lite. ~6 MB. Used by Auto Frame. */
    val POSE_LANDMARKER_LITE: CatalogEntry = CatalogEntry(
        id = "pose_landmarker_lite",
        displayName = "MediaPipe Pose Landmarker (Lite)",
        dirName = "pose_landmarker_lite",
        fileName = "pose_landmarker_lite.task",
        minSizeBytes = 5_000_000L
    )

    /**
     * ESRGAN TFLite 4x — official TensorFlow super-resolution demo
     * model (TFHub captain-pool/esrgan-tf2, dynamic-range
     * quantized, ~5 MB). Backs AI Upscale: the 4x profile runs it
     * directly, the 2x profile runs it on a 0.5x prefiltered frame.
     * 2026-09-06: back to downloadable — the binary still ships
     * inside `app/src/main/assets/ai-models/esrgan_tflite/ESRGAN.tflite`
     * (bundle-first, offline on first use), but if the bundled
     * asset is missing or fails SHA verification the in-app
     * download path is offered as a repair instead of a dead end.
     * Size + SHA-256 verified by real download.
     */
    val ESRGAN_TFLITE_4X: CatalogEntry = CatalogEntry(
        id = "esrgan_tflite_4x",
        displayName = "ESRGAN TFLite (4x)",
        dirName = "esrgan_tflite",
        fileName = "ESRGAN.tflite",
        minSizeBytes = 4_900_000L,
        subtitle = "Real neural 2x/4x video enhancement",
        minRamMb = 2_048L
    )

    /**
     * MediaPipe BlazeFace Short-Range (~230 KB) — the FACE fallback
     * for Auto Frame. 2026-09-06: close-up shots (only the head in
     * frame) defeat the Pose Landmarker; this tiny detector keeps
     * subject tracking alive by locking onto the face. Installed via
     * the same bundled-first + download-repair pipeline.
     */
    val BLAZE_FACE_SHORT_RANGE: CatalogEntry = CatalogEntry(
        id = "blaze_face_short_range",
        displayName = "MediaPipe Blaze Face (Short Range)",
        dirName = "face_detector",
        fileName = "blaze_face_short_range.tflite",
        minSizeBytes = 200_000L
    )

    /**
     * Tesseract tessdata_fast Persian (fas) — the PRIMARY AI OCR
     * language pack. ~422 KB integer LSTM build from the official
     * `tesseract-ocr/tessdata_fast` repo. Downloaded at runtime
     * through the standard models.lock.json pipeline (SHA-256
     * verified before the engine is allowed to load it) because
     * traineddata files are platform-independent data, not ABI-
     * specific binaries.
     */
    val TESSERACT_FAS: CatalogEntry = CatalogEntry(
        id = "tesseract_fas",
        displayName = "Tesseract OCR — Persian (fas)",
        dirName = "tesseract/tessdata",
        fileName = "fas.traineddata",
        minSizeBytes = 400_000L,
        language = "fa",
        subtitle = "OCR فارسی · آفلاین"
    )

    /** Tesseract tessdata_fast Arabic (ara) — AI OCR secondary script. */
    val TESSERACT_ARA: CatalogEntry = CatalogEntry(
        id = "tesseract_ara",
        displayName = "Tesseract OCR — Arabic (ara)",
        dirName = "tesseract/tessdata",
        fileName = "ara.traineddata",
        minSizeBytes = 1_400_000L,
        language = "ar",
        subtitle = "OCR عربی · آفلاین"
    )

    /** Tesseract tessdata_fast English (eng) — AI OCR secondary script. */
    val TESSERACT_ENG: CatalogEntry = CatalogEntry(
        id = "tesseract_eng",
        displayName = "Tesseract OCR — English (eng)",
        dirName = "tesseract/tessdata",
        fileName = "eng.traineddata",
        minSizeBytes = 4_000_000L,
        language = "en",
        subtitle = "OCR انگلیسی · آفلاین"
    )

    /**
     * All specs in display order: Whisper Small, then
     * the vision / upscale models, then the OCR language packs.
     */
    val all: List<CatalogEntry> = listOf(
        WHISPER_SMALL,
        SELFIE_MULTICLASS,
        POSE_LANDMARKER_LITE,
        ESRGAN_TFLITE_4X,
        BLAZE_FACE_SHORT_RANGE,
        TESSERACT_FAS,
        TESSERACT_ARA,
        TESSERACT_ENG
    )

    /**
     * Specs whose model binary ships inside the APK under
     * `app/src/main/assets/ai-models/<dirName>/<fileName>` —
     * the runtime installer copies the bundled asset on first
     * use (offline path). The user only ever needs to download
     * when the bundled asset is missing or corrupt.
     *
     * Computed from the in-memory catalog so a future model
     * added to [all] is honored automatically.
     */
    val bundledOnly: List<CatalogEntry> = all.filter { it.bundledOnly }

    /** Lookup by stable id. */
    fun byId(id: String): CatalogEntry? = all.firstOrNull { it.id == id }
}
