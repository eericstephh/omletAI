package com.aicreatorstudio.app.ai.groq

/**
 * 2026-09-13 FILENAME FIX — pure (JVM-testable) resolution of the
 * multipart `file` part's filename + Content-Type for Groq uploads.
 *
 * ## Why this exists
 *
 * Groq detects the audio format from the FILENAME EXTENSION in the
 * multipart Content-Disposition — NOT from the bytes. A part named
 * `filename="audio"` (no extension, which the client used to send)
 * is rejected with 400:
 *
 * ```
 * file must be one of the following types:
 * [flac mp3 mp4 mpeg mpga m4a ogg opus wav webm]
 * ```
 *
 * even when the underlying file is a perfectly valid mp3/m4a/wav
 * (the same file works in the Playground, which uploads it under
 * its real name).
 *
 * ## Resolution order (each step is REAL — no guessing)
 *
 * 1. The display name already ends with a Groq-supported
 *    extension → keep it verbatim (sanitized).
 * 2. The content resolver's MIME type maps to a supported
 *    extension (audio/mpeg → mp3, audio/mp4 → m4a, …) → base name
 *    + that extension.
 * 3. The Uri/file-path suffix is a supported extension → use it.
 * 4. The first bytes of the file (magic numbers) identify the
 *    container (ID3/0xFFEx → mp3, RIFF/WAVE → wav, fLaC → flac,
 *    OggS → ogg, EBML → webm, ftyp → mp4) → base name + it.
 * 5. Nothing identifiable → `null` → the caller shows an honest
 *    error and NEVER sends the request (no blind upload).
 *
 * The part's Content-Type is derived from the FINAL extension so
 * it is always truthful (audio/mpeg for mp3, video/mp4 for mp4,
 * audio/mp4 for m4a, audio/wav for wav, …) — never a bare
 * `application/octet-stream`.
 */
object GroqUploadTypes {

    /** Groq's documented accepted extensions (docs, verified 2026-09-13). */
    val SUPPORTED_EXTENSIONS: Set<String> = setOf(
        "flac", "mp3", "mp4", "mpeg", "mpga", "m4a", "ogg", "opus", "wav", "webm"
    )

    /** Sanitized display-name fallback when nothing is known. */
    private const val FALLBACK_BASE = "audio"

    // ----------------------------------------------------------------
    // extension helpers
    // ----------------------------------------------------------------

    /** Extension of [name], lowercase without the dot; null when none. */
    fun extensionOf(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }

    /** True when [name] ends with one of Groq's supported extensions. */
    fun hasSupportedExtension(name: String?): Boolean =
        extensionOf(name) in SUPPORTED_EXTENSIONS

    // ----------------------------------------------------------------
    // mime <-> extension maps
    // ----------------------------------------------------------------

    /** Android MIME types → a Groq-supported extension. */
    private val MIME_TO_EXTENSION = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",
        "audio/mpga" to "mpga",
        "video/mpeg" to "mpeg",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "video/mp4" to "mp4",
        "application/mp4" to "mp4",
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/opus" to "opus",
        "audio/webm" to "webm",
        "video/webm" to "webm"
        // NOTE: aac / amr / 3gp / wma are deliberately ABSENT —
        // Groq does not accept them; those sources must be
        // transcoded (see SubtitleAudioExtractor's WAV fallback).
    )

    /** Groq-supported extension → the truthful file-part Content-Type. */
    private val EXTENSION_TO_MIME = mapOf(
        "flac" to "audio/flac",
        "mp3" to "audio/mpeg",
        "mpga" to "audio/mpeg",
        "mpeg" to "video/mpeg",
        "mp4" to "video/mp4",
        "m4a" to "audio/mp4",
        "ogg" to "audio/ogg",
        "opus" to "audio/opus",
        "wav" to "audio/wav",
        "webm" to "video/webm"
    )

    /** Map a content-resolver MIME type to a supported extension; null when unmappable. */
    fun extensionForMime(mimeType: String?): String? =
        mimeType?.lowercase()?.substringBefore(';')?.trim()
            ?.let { MIME_TO_EXTENSION[it] }

    /** The truthful file-part Content-Type for a supported extension; null otherwise. */
    fun mimeTypeForExtension(extension: String?): String? =
        extension?.lowercase()?.let { EXTENSION_TO_MIME[it] }

    // ----------------------------------------------------------------
    // magic-number sniffing (real container detection, last resort)
    // ----------------------------------------------------------------

    /**
     * Identify the container from the first bytes of the file.
     * Returns a Groq-supported extension, or null when the head
     * matches nothing (including formats Groq does not accept,
     * like AMR `#!AMR` — an honest "unknown", never a fake type).
     */
    fun sniffExtension(head: ByteArray): String? {
        if (head.size < 12) return null
        fun b(i: Int): Int = head[i].toInt() and 0xFF
        fun at(offset: Int, vararg chars: Char): Boolean {
            for (i in chars.indices) {
                if (b(offset + i) != chars[i].code) return false
            }
            return true
        }
        return when {
            // MP3 with an ID3v2 tag.
            at(0, 'I', 'D', '3') -> "mp3"
            // MPEG audio frame sync: 0xFF followed by 111xxxxx.
            b(0) == 0xFF && (b(1) and 0xE0) == 0xE0 -> "mp3"
            // FLAC.
            at(0, 'f', 'L', 'a', 'C') -> "flac"
            // Ogg (Vorbis/Opus both live in an OggS container).
            at(0, 'O', 'g', 'g', 'S') -> "ogg"
            // EBML — Matroska/WebM family.
            b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> "webm"
            // RIFF....WAVE.
            at(0, 'R', 'I', 'F', 'F') && at(8, 'W', 'A', 'V', 'E') -> "wav"
            // MP4/M4A: "ftyp" box at offset 4.
            at(4, 'f', 't', 'y', 'p') -> "mp4"
            else -> null
        }
    }

    // ----------------------------------------------------------------
    // final filename resolution
    // ----------------------------------------------------------------

    /**
     * Resolve the filename the multipart `file` part must carry.
     *
     * @param rawName the best display name available (content
     *   resolver, file name, …). May be null or extension-less.
     * @param mimeType the source's MIME type (content resolver /
     *   extractor), when known.
     * @param pathSuffixExtension the source path's extension, when
     *   the name itself carried none.
     * @param sniffedExtension the magic-byte result, when the
     *   earlier steps had nothing.
     * @return a sanitized filename guaranteed to end with a
     *   Groq-supported extension, or null when the format cannot
     *   be honestly determined (the caller must NOT upload then).
     */
    fun resolveUploadFileName(
        rawName: String?,
        mimeType: String?,
        pathSuffixExtension: String? = null,
        sniffedExtension: String? = null
    ): String? {
        // 1. The name already carries a supported extension.
        if (hasSupportedExtension(rawName)) return sanitizeFileName(rawName)

        // 2-4. Derive the extension from mime / path / magic bytes.
        val ext = extensionForMime(mimeType)
            ?: pathSuffixExtension?.lowercase()?.takeIf { it in SUPPORTED_EXTENSIONS }
            ?: sniffedExtension?.lowercase()?.takeIf { it in SUPPORTED_EXTENSIONS }
            ?: return null

        val base = sanitizeFileName(rawName?.substringBeforeLast('.'))
            ?: FALLBACK_BASE
        return "$base.$ext"
    }

    /**
     * Strip everything that must never enter a Content-Disposition
     * header: path separators (injection), quotes (header
     * splitting), CR/LF (smuggling). Null when nothing survives.
     */
    fun sanitizeFileName(name: String?): String? =
        name
            ?.replace('\\', '_')
            ?.replace('/', '_')
            ?.replace("\"", "")
            ?.replace("\r", "")
            ?.replace("\n", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
