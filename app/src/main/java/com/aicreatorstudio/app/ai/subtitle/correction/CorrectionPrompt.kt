package com.aicreatorstudio.app.ai.subtitle.correction

/**
 * The system prompt and user-message builder for the Persian
 * subtitle-correction stage.
 *
 * ## Why the prompt is a constant, not inline at the call site
 *
 * The behavioural rules below ARE the feature. They are the only
 * thing standing between "correct my typos" and "rewrite my
 * subtitles", and a paraphrase slipped into a request builder is
 * invisible to review. Kept here so a test can pin the rules that
 * must never disappear (see `CorrectionPromptTest`).
 *
 * ## Why it is in English while the subtitles are Persian
 *
 * The rules describe *constraints on the model*, not content the
 * user reads. Instruction-following is measurably more reliable in
 * the model's dominant instruction language, and every example the
 * rules need to name (half-space, colloquial forms, homophones) is
 * still named in Persian. The SUBTITLES stay Persian; only the
 * instructions are English.
 */
object CorrectionPrompt {

    /**
     * The dedicated proofreader system prompt.
     *
     * Rule 10 ("return only the structured output") is what makes
     * [CorrectionResponseParser] able to reject a chatty reply
     * instead of trying to salvage it.
     */
    val SYSTEM: String = """
        You are an expert Persian subtitle proofreader specializing in correcting automatic speech recognition output.
        Your task is to correct transcription spelling and Persian orthography while preserving the original spoken content.

        Rules:
        1. Correct obvious spelling mistakes using the surrounding context.
        2. Apply consistent Persian orthography, including appropriate half-spaces (نیم‌فاصله, U+200C) in compound words and in the prefixes می‌ and suffixes‌ها، ‌های، ‌تر، ‌ترین.
        3. Preserve colloquial Persian. Do not convert spoken colloquial forms into formal Persian.
        4. Do not summarize, rewrite, paraphrase, or improve the speaker's style.
        5. Do not add information or remove words merely to make a sentence sound more natural.
        6. Preserve names, technical terms, English words, numbers, and the original order of speech.
        7. Correct homophones only when the context strongly supports a particular spelling.
        8. If a correction is uncertain, preserve the original word.
        9. Never invent words that are not supported by the original transcription.
        10. Return only the requested structured output. Do not include explanations or commentary.

        Output contract:
        - Reply with a single JSON object and nothing else: {"segments":[{"id":"<id>","text":"<corrected text>"}]}
        - Include EVERY id you were given, exactly once, in the same order.
        - Do not invent ids. Do not drop ids. Do not merge or split segments.
        - Each "text" value is the corrected text of exactly that one segment.
    """.trimIndent()

    /**
     * Build the user message for [batch].
     *
     * The segments are sent as a JSON array so the ids are
     * unambiguous and the model has a shape to echo. Neighbouring
     * text is sent separately and explicitly marked read-only —
     * otherwise a model that is asked to "correct everything it
     * sees" starts returning corrections for the context too.
     */
    fun userMessage(batch: CorrectionBatch): String {
        val sb = StringBuilder()
        val before = batch.contextBefore?.trim()
        if (!before.isNullOrEmpty()) {
            sb.append("Context BEFORE (reference only — do NOT correct, do NOT return):\n")
            sb.append(before).append("\n\n")
        }
        sb.append("Segments to correct (JSON array, keep these ids):\n")
        sb.append(segmentsJson(batch.segments)).append("\n")
        val after = batch.contextAfter?.trim()
        if (!after.isNullOrEmpty()) {
            sb.append("\nContext AFTER (reference only — do NOT correct, do NOT return):\n")
            sb.append(after).append("\n")
        }
        sb.append("\nReturn ONLY {\"segments\":[{\"id\":...,\"text\":...}]} with exactly ")
        sb.append(batch.segments.size).append(" entr")
        sb.append(if (batch.segments.size == 1) "y" else "ies").append(".")
        return sb.toString()
    }

    /**
     * Encode [segments] as a JSON array. Hand-rolled rather than
     * `org.json` so the exact bytes are predictable and the builder
     * stays dependency-free (the same discipline
     * `GroqMultipart` follows).
     */
    internal fun segmentsJson(segments: List<CorrectionInputSegment>): String =
        segments.joinToString(separator = ",", prefix = "[", postfix = "]") { s ->
            "{\"id\":${quote(s.id)},\"text\":${quote(s.text)}}"
        }

    /**
     * Minimal JSON string encoder: quotes, backslashes and control
     * characters. Persian text needs no other escaping, and escaping
     * more than JSON requires would corrupt ZWNJ.
     */
    internal fun quote(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // ZWNJ / RLM / LRM are legitimate content and MUST be
                // emitted as raw UTF-8, never as \u escapes that a
                // model may then reproduce literally.
                else -> if (ch.code < 0x20) {
                    sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
