package com.aicreatorstudio.app.logging

/**
 * 2026-10-12 — privacy gate for everything the logger persists.
 *
 * The logger writes to app-private storage that the user can then hand
 * to a developer through the share sheet, so **redaction is applied
 * before bytes reach disk**, not at report time. This object is pure
 * Kotlin: no Android imports, fully JVM-testable.
 *
 * ## What it removes
 *
 * - Absolute filesystem paths (unix and Windows) → basename only.
 * - `file://` / `content://` URIs → last path segment only.
 * - Long hex strings that look like API keys / hashes.
 * - `Bearer <token>` and `api_key=…` / `token: …` style secrets.
 * - Anything the caller explicitly registers as sensitive.
 *
 * ## What it deliberately does NOT try to do
 *
 * It does not attempt to detect "private media names" by heuristics —
 * the exporters are responsible for not passing full media names in the
 * first place, and the settings/Whisper call sites already only pass
 * counts and sizes. This layer is the safety net, not the policy.
 */
object LogSanitizer {

    /** Placeholder written in place of a redacted value. */
    const val REDACTED: String = "[redacted]"

    /** Max characters kept for a single message/field after sanitizing. */
    const val MAX_FIELD_CHARS: Int = 500

    // ---- Patterns -------------------------------------------------

    private val WINDOWS_PATH = Regex("""[A-Za-z]:[\\/][^\s"',;]+""")
    private val UNIX_PATH = Regex("""/(?:[^\s"',;/]+/)+[^\s"',;/]+""")
    private val FILE_URI = Regex("""(?:file|content)://[^\s"',;]+""")
    private val BEARER = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val KV_SECRET = Regex(
        """(?i)\b(api[_-]?key|apikey|access[_-]?token|auth[_-]?token|token|secret|password|passwd|pwd|authorization)\b\s*[:=]\s*["']?([^\s"',;]+)"""
    )
    private val LONG_HEX = Regex("""\b[A-Fa-f0-9]{32,}\b""")
    private val LONG_BASE64ISH = Regex("""\b[A-Za-z0-9+/]{40,}={0,2}\b""")

    /**
     * Sanitize a free-form string. Idempotent-ish and allocation-light;
     * intended to run on the logging writer's background thread.
     *
     * [extraSecrets] lets a caller register additional literal strings
     * (e.g. a key it just read from disk) that must never be written.
     *
     * [maxChars] overrides [MAX_FIELD_CHARS] for callers that have a
     * legitimate reason to write more — the crash handler passes a
     * larger budget so the **stack frames** (the part that identifies
     * WHERE the crash happened) are not truncated away.
     */
    fun sanitize(
        input: String?,
        extraSecrets: Collection<String> = emptyList(),
        maxChars: Int = MAX_FIELD_CHARS
    ): String {
        val start: String = input ?: return ""
        if (start.isEmpty()) return ""
        var s: String = start

        // 1. Explicit secrets first — highest priority, exact match.
        for (secret in extraSecrets) {
            if (secret.isNotBlank() && s.contains(secret)) {
                s = s.replace(secret, REDACTED)
            }
        }

        // 2. Authorization / bearer headers BEFORE the generic key=value
        //    rule: a line like `Authorization: Bearer sk-…` would
        //    otherwise be reduced to `authorization=<redacted> sk-…`,
        //    leaving the actual token in place.
        s = BEARER.replace(s, "Bearer $REDACTED")

        // 3. key=value / key: value secrets (keep the key, drop the value).
        s = KV_SECRET.replace(s) { m -> m.groupValues[1] + "=" + REDACTED }

        // 4. URIs and paths before the generic path rules (they overlap).
        s = FILE_URI.replace(s) { m -> leafOf(m.value) }
        s = WINDOWS_PATH.replace(s) { m -> leafOf(m.value) }
        s = UNIX_PATH.replace(s) { m -> leafOf(m.value) }

        // 5. Key/hash-looking blobs.
        s = LONG_HEX.replace(s, REDACTED)
        s = LONG_BASE64ISH.replace(s, REDACTED)

        // 6. Bound the size so one runaway message can't bloat a file.
        if (s.length > maxChars) {
            s = s.take(maxChars) + "…"
        }
        return s
    }

    /** Sanitize every value of a metadata map, dropping blank values. */
    fun sanitizeFields(
        fields: Map<String, String>,
        maxChars: Int = MAX_FIELD_CHARS
    ): Map<String, String> {
        if (fields.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(fields.size)
        for ((k, v) in fields) {
            val key = k.trim()
            if (key.isEmpty()) continue
            out[key] = sanitize(v, maxChars = maxChars)
        }
        return out
    }

    /** Basename of a path or URI token — never the full location. */
    private fun leafOf(token: String): String {
        val noQuery = token.substringBefore('?').substringBefore('#')
        val normalized = noQuery.replace('\\', '/').trimEnd('/')
        val leaf = normalized.substringAfterLast('/')
        return if (leaf.isBlank() || leaf == normalized) {
            // e.g. `file:///storage` with no leaf left, or a bare host.
            normalized.substringAfterLast('/').ifBlank { REDACTED }
        } else {
            leaf
        }
    }

    /**
     * Render a throwable as `ClassSimpleName: sanitized message`.
     *
     * The stack trace is intentionally **not** recorded: it is large,
     * mostly framework noise, and the existing [com.aicreatorstudio.app.CrashReporter]
     * already captures full traces for uncaught crashes. Diagnostic
     * events want the failure *identity*, not the frames.
     */
    fun describeThrowable(t: Throwable?): String? {
        if (t == null) return null
        val name = t.javaClass.simpleName.ifBlank { t.javaClass.name }
        val msg = sanitize(t.message)
        return if (msg.isBlank()) name else "$name: $msg"
    }
}
