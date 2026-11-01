package com.aicreatorstudio.app.ai.subtitle.correction

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict validation of the model's reply.
 *
 * ## The rule this file enforces
 *
 * **Model output is untrusted data.** A language model asked for JSON
 * will, eventually, return a prose apology, a markdown fence, a
 * duplicated id, an id it invented, or an empty array — and every one
 * of those must be rejected *before* it can reach a caption, not
 * "cleaned up" into something plausible.
 *
 * ## What is accepted
 *
 * The chat-completion envelope's `choices[0].message.content` must be
 * a single JSON object `{"segments":[{"id":..,"text":..}, ...]}` in
 * which:
 *
 *  1. the id set is EXACTLY the expected set — no missing, no extra;
 *  2. no id appears twice;
 *  3. `id` and `text` are both strings;
 *  4. no `text` is blank.
 *
 * Anything else fails the whole batch. A batch failure leaves those
 * overlays with their ORIGINAL text, which is the safe direction.
 *
 * ## Why the whole batch, not the good rows
 *
 * Partially applying a reply means the user reviews a caption list
 * that silently mixes corrected and uncorrected segments with no way
 * to tell them apart. Rejecting the batch keeps the preview honest:
 * every row shown is a row the model actually returned in full.
 *
 * Pure + JVM-testable (org.json only).
 */
object CorrectionResponseParser {

    /** The accepted shape. */
    sealed interface Result {
        data class Ok(val segments: List<CorrectedSegment>) : Result

        /** [reason] is a short, non-sensitive diagnostic for the log/UI. */
        data class Malformed(val reason: String) : Result
    }

    /**
     * Parse a raw chat-completions HTTP body.
     *
     * @param expectedIds the ids the request carried, in order. The
     *   reply must cover exactly this set.
     */
    fun parseChatCompletion(body: String, expectedIds: List<String>): Result {
        val content = extractContent(body)
            ?: return Result.Malformed("پاسخ سرور ساختار chat-completion نداشت")
        return parseSegments(content, expectedIds)
    }

    /**
     * Pull `choices[0].message.content` out of [body], or null when
     * the envelope is not the documented shape.
     */
    fun extractContent(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val root = JSONObject(trimmed)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val first = choices.optJSONObject(0) ?: return null
            val message = first.optJSONObject("message") ?: return null
            val content = message.optString("content", "")
            content.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validate the model's content against [expectedIds].
     *
     * A leading/trailing markdown fence is tolerated because it is a
     * transport artefact, not commentary — but nothing else outside
     * the JSON object is: `JSONObject` throws on trailing prose, and
     * that throw is reported as malformed.
     */
    fun parseSegments(content: String, expectedIds: List<String>): Result {
        if (expectedIds.isEmpty()) return Result.Malformed("درخواست هیچ بخشی نداشت")
        if (expectedIds.size != expectedIds.toSet().size) {
            // Defensive: the caller built a batch with duplicate ids.
            // That is a programming error, and applying a reply to it
            // would be ambiguous.
            return Result.Malformed("شناسه‌های درخواست تکراری بودند")
        }
        val json = stripFence(content.trim())
        if (json.isEmpty()) return Result.Malformed("پاسخ خالی بود")

        val array: JSONArray = try {
            val root = JSONObject(json)
            root.optJSONArray("segments")
                ?: return Result.Malformed("پاسخ کلید segments نداشت")
        } catch (_: Exception) {
            return Result.Malformed("پاسخ JSON معتبر نبود")
        }

        if (array.length() == 0) return Result.Malformed("پاسخ هیچ بخشی نداشت")
        if (array.length() != expectedIds.size) {
            return Result.Malformed(
                "تعداد بخش‌های پاسخ (${array.length()}) با درخواست (${expectedIds.size}) نمی‌خواند"
            )
        }

        val expected = expectedIds.toSet()
        val seen = HashSet<String>(array.length())
        val out = ArrayList<CorrectedSegment>(array.length())

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: return Result.Malformed("عضو ${i + 1} پاسخ یک شیء JSON نبود")
            val id = item.opt("id")
            if (id !is String || id.isBlank()) {
                return Result.Malformed("عضو ${i + 1} شناسهٔ متنی نداشت")
            }
            if (id !in expected) {
                return Result.Malformed("شناسهٔ ناشناخته در پاسخ: $id")
            }
            if (!seen.add(id)) {
                return Result.Malformed("شناسهٔ تکراری در پاسخ: $id")
            }
            val rawText = item.opt("text")
            if (rawText !is String) {
                return Result.Malformed("متن عضو $id رشته نبود")
            }
            val text = normalizeWhitespace(rawText)
            if (text.isBlank()) {
                return Result.Malformed("متن عضو $id خالی بود")
            }
            out.add(CorrectedSegment(id, text))
        }

        if (seen.size != expected.size) {
            val missing = expected - seen
            return Result.Malformed("این شناسه‌ها در پاسخ نبودند: ${missing.joinToString(", ")}")
        }
        return Result.Ok(out)
    }

    /**
     * Remove ONE leading/trailing markdown code fence.
     *
     * Models habitually wrap JSON in ```json … ```. That is a
     * formatting artefact of the transport, not commentary, so it is
     * unwrapped rather than treated as a failure. Prose around the
     * object is still fatal — `JSONObject` refuses it.
     */
    internal fun stripFence(content: String): String {
        var s = content.trim()
        if (!s.startsWith("```")) return s
        s = s.removePrefix("```")
        // Drop the language tag line ("json", "JSON", nothing).
        val newline = s.indexOf('\n')
        if (newline >= 0) s = s.substring(newline + 1)
        s = s.trim()
        if (s.endsWith("```")) s = s.removeSuffix("```")
        return s.trim()
    }

    /**
     * Collapse line breaks and tabs to single spaces.
     *
     * A caption is one line; a stray newline from the model would
     * either render as a broken two-line caption or be silently
     * dropped by the renderer. This is whitespace normalisation, not
     * content change — ZWNJ (U+200C) and every Persian letter are
     * untouched, because Java's `\s` does not match them.
     */
    internal fun normalizeWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text.trim()) {
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                pendingSpace = true
            } else {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
