package com.aicreatorstudio.app.ai.groq

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure on-device storage for the user's Groq API key.
 *
 * ## Design
 *
 * The key is encrypted at rest with AES-256/GCM using a key that
 * lives inside the **Android Keystore** (hardware-backed on most
 * devices; the key material never leaves the TEE/StrongBox and is
 * not extractable). The ciphertext (IV + GCM tag + payload) is the
 * only thing persisted, in a private `SharedPreferences` file.
 * There is no plaintext path and no hardcoded key anywhere - the
 * value enters the device only through the settings UI.
 *
 * ## Threat model
 *
 * Defeats: another app reading private prefs, backups (the file is
 * under `noBackupFilesDir`-style exclusion? - no, but the
 * ciphertext is useless without the keystore key bound to this
 * app's UID + device), accidental log leakage.
 * Does not defeat: root + a debugger attached to the running
 * process (true of every client-side secret).
 *
 * ## Failure semantics (2026-10-20 - IMPORTANT)
 *
 * A **decrypt failure is NOT "the user has no key"**. The Keystore
 * can fail transiently (the TEE hiccups, the device is locked, a
 * biometric enrolment change invalidates the session, the key is
 * temporarily unavailable right after a reboot). The old code
 * treated every decrypt error as "stale blob" and DELETED it, so a
 * single hiccup destroyed a perfectly good API key - permanently,
 * with the UI only saying "key required" and no explanation. The
 * stored blob is now PRESERVED on any decrypt failure so a later
 * attempt can still succeed; only a blob that is *structurally*
 * impossible to decrypt (not base64, or shorter than IV + GCM tag)
 * is dropped, because such a blob can never yield a key again.
 *
 * The blob is removed ONLY on an explicit user action:
 * [clear] (the user pressed the delete button) or [save] (the user
 * typed a new key, which overwrites the old blob).
 *
 * ## Performance (2026-10-20)
 *
 * The decrypted key is cached in memory ([cachedKey]) so that
 * [hasKey] - which is called from `UserGroqEngine.isAvailable` (a
 * property getter), from `SubtitleGenerator.resolveCloudEngine()`
 * and from `EditorViewModel.setToolPanel`, i.e. on EVERY tool-panel
 * switch on the MAIN THREAD - is a pure memory read instead of a
 * full Keystore AES-256/GCM decrypt (a TEE round trip). The cache
 * is refreshed by [save] and dropped by [clear].
 */
@Singleton
class GroqApiKeyStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * 2026-10-20 - in-memory cache of the DECRYPTED key.
     *
     * WHY: decrypting is a TEE round trip (a few ms on a slow TEE).
     * [hasKey] used to call [load] directly, so an "is a key
     * available?" check did real crypto work - on the main thread,
     * on every panel switch. Now the value is decrypted at most
     * once per store instance and read from memory afterwards.
     *
     * SECURITY NOTE: the plaintext key now lives in the app process
     * heap for the lifetime of the process. That is the same trust
     * boundary the plaintext already had while it was being used for
     * a request; the key AT REST is still Keystore-encrypted and no
     * plaintext is ever written to disk.
     */
    @Volatile
    private var cachedKey: String? = null

    /**
     * True when [cachedKey] authoritatively reflects what is stored
     * (either the decrypted key, or the proven fact that no key is
     * stored). False when the store must (re)read and decrypt.
     */
    @Volatile
    private var cacheValid = false

    /**
     * Timestamp of the last FAILED decrypt. Used to throttle retries:
     * after a transient Keystore failure the blob is kept, but we do
     * not hammer the TEE on every single availability check.
     */
    @Volatile
    private var lastDecryptFailureAt = 0L

    /** Persist [apiKey] encrypted. Returns false when crypto failed. */
    fun save(apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(iv.size + ct.size)
            iv.copyInto(blob)
            ct.copyInto(blob, iv.size)
            prefs.edit()
                .putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
                .apply()
            // 2026-10-20 - the stored value is now known, so refresh the
            // in-memory cache instead of dropping it: a later load()
            // must not decrypt again just to learn the key we already
            // hold in plaintext.
            cachedKey = apiKey
            cacheValid = true
            lastDecryptFailureAt = 0L
            true
        } catch (t: Throwable) {
            // The old blob (and the cache) are untouched: a failed
            // save leaves the previously stored key intact.
            Log.w(TAG, "API key encryption failed", t)
            false
        }
    }

    /**
     * Decrypt and return the stored key, or null when absent/unreadable.
     *
     * 2026-10-20 - the stored blob is NEVER deleted because of a
     * decrypt error. Only a structurally impossible blob (not
     * base64 / truncated below IV + GCM tag) is dropped, because it
     * can never decrypt again. Every other failure is treated as
     * transient: the blob survives and the next call retries.
     */
    fun load(): String? {
        // Cheap path: the answer is already in memory.
        if (cacheValid) return cachedKey

        val blobB64 = prefs.getString(KEY_BLOB, null)
        if (blobB64 == null) {
            // No blob at all - provably "no key stored". Cache the
            // negative result so hasKey() stays a pure memory read.
            cachedKey = null
            cacheValid = true
            return null
        }

        // A decrypt failed recently (transient Keystore/TEE trouble).
        // Do not hammer the TEE on every availability check; the blob
        // is preserved and retried after the cooldown.
        val now = System.currentTimeMillis()
        if (lastDecryptFailureAt != 0L && now - lastDecryptFailureAt < DECRYPT_RETRY_COOLDOWN_MS) {
            return null
        }

        // ---- structurally impossible blobs: the only case we drop ----
        val blob = try {
            Base64.decode(blobB64, Base64.NO_WRAP)
        } catch (t: IllegalArgumentException) {
            Log.w(TAG, "Stored API key blob is not valid base64 - dropping it", t)
            dropStoredBlob()
            return null
        }
        if (blob.size < MIN_BLOB_BYTES) {
            // Needs at least IV + GCM tag; anything shorter can never
            // produce a plaintext - the safe (and only) case for deletion.
            Log.w(TAG, "Stored API key blob is truncated (${blob.size} bytes) - dropping it")
            dropStoredBlob()
            return null
        }

        return try {
            val iv = blob.copyOf(IV_LEN)
            val ct = blob.copyOfRange(IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val key = String(cipher.doFinal(ct), Charsets.UTF_8)
            cachedKey = key
            cacheValid = true
            lastDecryptFailureAt = 0L
            key
        } catch (t: Throwable) {
            // 2026-10-20 - PRESERVE the blob.
            // WHY: this is usually a TRANSIENT Keystore failure (TEE
            // hiccup, locked device, biometric enrolment change, key
            // temporarily unavailable after boot). The previous code
            // deleted the blob here, so ONE hiccup destroyed the
            // user's key permanently and the UI just said "key
            // required" with no explanation. Keeping the ciphertext
            // means a later attempt can still succeed.
            lastDecryptFailureAt = now
            Log.w(
                TAG,
                "API key decrypt failed - the stored blob is PRESERVED and will be retried",
                t
            )
            null
        }
    }

    /**
     * True when a readable key exists.
     *
     * 2026-10-20 - this is a CHEAP, NON-CRYPTO check in the steady
     * state: it reads the in-memory cache and only touches the
     * Keystore on the very first call (cold cache). A blank key
     * still counts as "no key".
     */
    fun hasKey(): Boolean = !load().isNullOrBlank()

    /** Masked form for the UI, e.g. `gsk_****1234`. */
    fun masked(): String? {
        val key = load() ?: return null
        val tail = key.takeLast(4)
        return "••••••••$tail"
    }

    /** Remove the stored key. Idempotent. */
    fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
        // 2026-10-20 - an explicit user action is the ONE case where
        // the blob really goes away; drop the cache with it.
        cachedKey = null
        cacheValid = true
        lastDecryptFailureAt = 0L
    }

    // ----------------------------------------------------------------
    // Non-secret endpoint config (URL / model id)
    // ----------------------------------------------------------------
    // The endpoint URL and model id are NOT secrets - they are stored
    // as plain strings in the same private prefs file. Only the API
    // key goes through the Keystore cipher.

    /**
     * Persist the user's transcription endpoint. Blank input falls
     * back to the default Groq endpoint. Returns the normalized URL
     * actually stored, or null when [url] is malformed.
     *
     * 2026-10-20 - the stored endpoint is the SINGLE source of truth
     * for "which server do we talk to":
     * - the connection test derives its list-models URL from it
     *   ([GroqEndpoints.modelsUrl], used by [GroqConnectionTester]),
     * - the transcription request must pass it as
     *   `GroqWhisperClient.RequestParams.endpoint`.
     *
     * TODO(2026-10-20): the transcription path
     * (`UserGroqEngine.generateTranscription`) does not pass the
     * endpoint yet - it must add `endpoint = apiKeyStore.endpoint()`
     * to the `RequestParams` constructor call, otherwise "test
     * connection" and the real request could still disagree.
     */
    fun saveEndpoint(url: String): String? {
        val normalized = GroqEndpoints.normalize(url) ?: return null
        prefs.edit().putString(KEY_ENDPOINT, normalized).apply()
        return normalized
    }

    /**
     * The stored endpoint, or the default Groq endpoint when unset.
     *
     * 2026-10-20 - this is what the connection test and the
     * transcription request must BOTH use, so that the tested server
     * and the used server can never disagree.
     */
    fun endpoint(): String =
        prefs.getString(KEY_ENDPOINT, null) ?: GroqWhisperClient.ENDPOINT

    /** Persist the model id (free text, user-supplied). */
    fun saveModelId(modelId: String) {
        prefs.edit().putString(KEY_MODEL_ID, modelId.trim()).apply()
    }

    /**
     * The stored model id, or [GroqWhisperClient.DEFAULT_MODEL]
     * (`whisper-large-v3-turbo`) when unset. A stored value that is
     * blank or not a known Groq Whisper model (2026-10-10 validation)
     * is ignored so a stale/corrupted pref can never send an unknown
     * model id to the API - the pinned turbo default is used instead.
     * Delegates to the pure [GroqWhisperClient.resolveModelId].
     */
    fun modelId(): String = GroqWhisperClient.resolveModelId(
        prefs.getString(KEY_MODEL_ID, null)
    )

    // ---- crypto internals ----

    /**
     * Remove the stored blob because it can NEVER decrypt (not
     * base64 / truncated). Also resets the in-memory cache.
     */
    private fun dropStoredBlob() {
        prefs.edit().remove(KEY_BLOB).apply()
        cachedKey = null
        cacheValid = true
        lastDecryptFailureAt = 0L
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    companion object {
        private const val TAG = "GroqApiKeyStore"
        private const val PREFS_FILE = "groq_api_secure"
        private const val KEY_BLOB = "api_key_v1"
        private const val KEY_ENDPOINT = "endpoint_url"
        private const val KEY_MODEL_ID = "model_id"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "aicreatorstudio_groq_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8

        /** IV + GCM tag - the smallest blob that could ever decrypt. */
        private const val MIN_BLOB_BYTES = IV_LEN + GCM_TAG_BYTES

        /**
         * Cooldown between two decrypt attempts after a failure, so a
         * broken/locked Keystore is not hammered on every UI poll.
         */
        private const val DECRYPT_RETRY_COOLDOWN_MS = 5_000L
    }
}

/**
 * Endpoint URL normalization for the API-test forms. Pure + JVM
 * testable: trims, defaults to the Groq endpoint when blank, and
 * rejects anything that is not a well-formed http(s) URL.
 *
 * ## Empty-path rule
 *
 * A bare domain like `https://api.groq.com` would 404 on POST
 * (verified live), so the transcription PATH must be present -
 * [normalize] rejects empty/"-"-only paths. ([allowEmptyPath]
 * stays as a parameter for custom test endpoints; the Groq
 * default is strict.)
 */
object GroqEndpoints {

    /**
     * Normalize [raw]; null when malformed (caller shows an error).
     * When [allowEmptyPath] is true a bare domain is accepted
     * (custom endpoints); otherwise the URL must carry a path
     * (Groq-style transcription endpoints).
     */
    fun normalize(raw: String, allowEmptyPath: Boolean = false): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return GroqWhisperClient.ENDPOINT
        return try {
            val u = URI(trimmed)
            val scheme = u.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") return null
            if (u.host.isNullOrBlank()) return null
            if (!allowEmptyPath) {
                // POSTing to the bare domain (empty or "/" path) is the
                // #1 real-world 404 for provider APIs: the transcription
                // path must be present.
                val path = u.path ?: ""
                if (path.isBlank() || path == "/") return null
            }
            trimmed
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Derive the OpenAI-compatible **list-models** URL from a
     * transcription endpoint.
     *
     * 2026-10-20 - WHY: the connection test and the transcription
     * request must hit the SAME server. When the test hard-coded
     * `https://api.groq.com/openai/v1/models` while the request used
     * the (user-configurable) endpoint, "test connection" could pass
     * against Groq while the real transcription went to a completely
     * different server - the two could never disagree about whether
     * the key works. Now the test derives its URL from the SAME
     * stored endpoint the transcription uses.
     *
     * Rules:
     * - `.../v1/audio/transcriptions` -> `.../v1/models`
     * - `.../v1/transcriptions`         -> `.../v1/models`
     * - any other path                  -> `<path>/models`
     * - unparsable URL / bad scheme     -> [GroqConnectionTester.MODELS_ENDPOINT]
     *
     * Pure + JVM testable (no Android, no network).
     */
    fun modelsUrl(transcriptionEndpoint: String): String {
        val raw = transcriptionEndpoint.trim()
        if (raw.isBlank()) return GroqConnectionTester.MODELS_ENDPOINT
        return try {
            val u = URI(raw)
            val scheme = u.scheme?.lowercase()
            if (scheme != "https" && scheme != "http") return GroqConnectionTester.MODELS_ENDPOINT
            val host = u.host
            if (host.isNullOrBlank()) return GroqConnectionTester.MODELS_ENDPOINT
            val port = if (u.port > 0) ":${u.port}" else ""
            var path = u.path ?: ""
            while (path.endsWith("/")) path = path.dropLast(1)
            for (suffix in TRANSCRIPTION_SUFFIXES) {
                if (path.endsWith(suffix)) {
                    path = path.dropLast(suffix.length)
                    break
                }
            }
            if (path.isBlank()) path = "/v1"
            "$scheme://$host$port$path/models"
        } catch (_: Exception) {
            GroqConnectionTester.MODELS_ENDPOINT
        }
    }

    /** Path suffixes that identify a transcription endpoint. */
    private val TRANSCRIPTION_SUFFIXES = listOf("/audio/transcriptions", "/transcriptions")
}