package com.aicreatorstudio.app.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Single source of truth for every AI model the app can install.
 *
 * Reads `assets/ai-models/models.lock.json` at runtime and exposes
 * a [Spec] per entry. The lockfile is the authoritative catalog —
 * URLs, SHA-256s, sizes, and filenames are all declared there and
 * never in Kotlin source. Adding or upgrading a model is a one-file
 * edit (`models.lock.json`) and a Gradle re-run; no code change.
 *
 * Why this is the right shape (PHASE A of the brief):
 *
 *  - The legacy `AiModelSpec` (in `ModelManager.kt`) hardcoded
 *    `expectedSha256` inside the data class, which led to the
 *    `TBD_PLACEHOLDER_REPLACE_BEFORE_SHIP` problem in
 *    `MediaPipeMattingEngine.MATTING_SPEC`. The lockfile is the only
 *    place a SHA can live now, and the registry rejects any entry
 *    that has a missing/blank SHA — fail-closed.
 *  - The legacy `AiModelInstaller.downloadUrlFor(...)` switch was a
 *    parallel catalog of network URLs, hardcoded in Kotlin. The
 *    registry reads the URL from the lockfile, so a model swap is
 *    one edit.
 *  - Engines ask the registry for a [Spec] by `id` and pass that
 *    spec to [ModelInstaller.install]. The installer never invents
 *    a URL or SHA on its own.
 *
 * Threading: the registry is stateless after the first `load()`. It
 * is safe to call from any thread. The first call to [load] is
 * synchronized so two concurrent callers don't both parse the
 * lockfile.
 */
class ModelRegistry private constructor(
    private val byId: Map<String, Spec>
) {

    /** One installable model, sourced from `models.lock.json`. */
    data class Spec(
        /** Stable id (e.g. `"whisper-medium"` / `"whisper-large-v3"`) — see
         *  PHASE B1.6; the legacy `whisper-tiny` / `whisper-small` ids were
         *  removed from the lockfile and from the runtime catalog on
         *  2026-09-04. */
        val id: String,
        /** User-visible label, e.g. "Whisper Tiny". */
        val displayName: String,
        /** Subdirectory under `filesDir/models/`. */
        val dirName: String,
        /** File name inside that directory. */
        val fileName: String,
        /** Minimum acceptable byte count for a valid download. */
        val minSizeBytes: Long,
        /** Lockfile-pinned SHA-256. Never null when produced by [load]. */
        val expectedSha256: String,
        /** HTTPS URL for the model file. */
        val url: String,
        /** Lockfile size in bytes (the publisher's declared size). */
        val declaredSizeBytes: Long,
        /** Lockfile `language` (BCP-47). Null for non-STT models. */
        val language: String?,
        /** Lockfile `subtitle` (user-visible tagline). */
        val subtitle: String?,
        /** Lockfile `whisperThreads` (cpu threads for whisper.cpp). Null otherwise. */
        val whisperThreads: Int?,
        /** Lockfile `minRamMb` (free-RAM gate). Null = no gate. */
        val minRamMb: Long?
    )

    /** Lookup by id. Returns null for unknown ids. */
    fun byId(id: String): Spec? = byId[id]

    /** All specs in lockfile order. */
    fun all(): List<Spec> = byId.values.toList()

    companion object {
        /** Path of the lockfile inside the APK assets. */
        const val LOCKFILE_ASSET_PATH: String = "ai-models/models.lock.json"

        /**
         * Load the registry from the bundled assets. Returns a
         * [Result] so a malformed lockfile surfaces a real exception
         * to the caller — never a silent empty registry.
         */
        fun fromAssets(context: Context): Result<ModelRegistry> = runCatching {
            val text = context.assets.open(LOCKFILE_ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
            parse(text)
        }

        /**
         * Parse a lockfile string. Public so JVM unit tests can
         * construct a registry from a fixture without touching the
         * APK assets. Throws [IllegalArgumentException] on any
         * validation failure (missing SHA, missing URL, bad size).
         */
        fun parse(json: String): ModelRegistry {
            val root = JSONObject(json)
            val models = root.optJSONArray("models")
                ?: throw IllegalArgumentException("models.lock.json has no 'models' array")
            val out = LinkedHashMap<String, Spec>(models.length())
            for (i in 0 until models.length()) {
                val entry = models.getJSONObject(i)
                val spec = parseEntry(entry)
                require(spec.id !in out) {
                    "models.lock.json has duplicate id '${spec.id}'"
                }
                out[spec.id] = spec
            }
            return ModelRegistry(out)
        }

        private fun parseEntry(o: JSONObject): Spec {
            val id = o.getNonBlankString("id")
            val displayName = o.optString("displayName", id)
            val dirName = o.getNonBlankString("dirName")
            val fileName = o.getNonBlankString("fileName")
            val url = o.getNonBlankString("url")
            val sha = o.getNonBlankString("sha256")
            val size = o.getLong("sizeBytes")
            val minSize = o.optLong("minSizeBytes", size).coerceAtLeast(1L)
            require(sha.matches(HEX_64)) {
                "models.lock.json entry '$id' has invalid sha256: '$sha'"
            }
            require(url.startsWith("https://") || url.startsWith("http://")) {
                "models.lock.json entry '$id' has non-http(s) url: '$url'"
            }
            require(size > 0L) {
                "models.lock.json entry '$id' has non-positive sizeBytes: $size"
            }
            return Spec(
                id = id,
                displayName = displayName,
                dirName = dirName,
                fileName = fileName,
                minSizeBytes = minSize,
                expectedSha256 = sha.lowercase(),
                url = url,
                declaredSizeBytes = size,
                language = o.optStringOrNull("language"),
                subtitle = o.optStringOrNull("subtitle"),
                whisperThreads = if (o.has("whisperThreads")) o.getInt("whisperThreads") else null,
                minRamMb = if (o.has("minRamMb")) o.getLong("minRamMb") else null
            )
        }

        private fun JSONObject.getNonBlankString(name: String): String {
            if (!has(name) || isNull(name)) {
                throw IllegalArgumentException("missing required field '$name'")
            }
            val v = getString(name)
            require(v.isNotBlank()) { "required field '$name' is blank" }
            return v
        }

        private fun JSONObject.getLong(name: String): Long {
            if (!has(name) || isNull(name)) {
                throw IllegalArgumentException("missing required field '$name'")
            }
            return getLong(name)
        }

        private fun JSONObject.optStringOrNull(name: String): String? {
            if (!has(name) || isNull(name)) return null
            val v = optString(name)
            return v.takeIf { it.isNotBlank() }
        }

        private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    }
}

/**
 * On-disk path the installer uses for a given [ModelRegistry.Spec].
 * Centralized here so the installer + the registry + the
 * `MattingSidecar` modelSha256OrNull all agree on the layout.
 *
 *   `filesDir/models/<dirName>/<fileName>`
 *
 * The `partial` is at the same path with `.partial` appended; the
 * installer cleans that up on every failure.
 */
fun modelFileFor(
    filesDir: File,
    spec: ModelRegistry.Spec
): File = File(filesDir, "models/${spec.dirName}/${spec.fileName}")

fun modelPartialFileFor(
    filesDir: File,
    spec: ModelRegistry.Spec
): File = File(filesDir, "models/${spec.dirName}/${spec.fileName}.partial")
