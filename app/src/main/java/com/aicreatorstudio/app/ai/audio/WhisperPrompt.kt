package com.aicreatorstudio.app.ai.audio

/**
 * Builds `whisper_full_params.initial_prompt` for each window.
 *
 * ## What the initial prompt is (and is not)
 *
 * whisper.cpp prepends the prompt to the decoder's token context.
 * It is **not** an instruction channel: the decoder treats it as
 * text it "has already written", so it imitates its *shape* —
 * script, spacing, punctuation, register, vocabulary. Anything
 * that looks like an instruction ("do not split words", "rules:
 * …") is therefore either ignored or, worse, *imitated*: on a
 * low-confidence window the model happily emits a sentence that
 * echoes the prompt instead of the audio. That is one of the ways
 * a subtitle ends up with fluent Persian nobody said.
 *
 * So the primer here is plain natural Persian — no headings, no
 * commands — chosen to establish three things the model needs:
 *
 *  - **spaces between words** (unspaced Persian is the classic
 *    failure of the small checkpoints),
 *  - **ZWNJ compounds** (`درباره‌ی`, `همین‌طور`), which the model
 *    otherwise writes with a full space,
 *  - **the register of spoken tech content**, which biases the
 *    vocabulary toward everyday words rather than literary ones.
 *
 * ## Cross-window continuity
 *
 * whisper.cpp's own loop feeds `prompt_past` from the previous
 * window automatically. This engine calls `whisper_full` once per
 * window with `no_context = true`, so that continuity is LOST —
 * every window is decoded as if the video just started, and a
 * proper noun or a topic word established at 00:10 is forgotten
 * by 00:40. [carryOverFrom] restores it by appending the tail of
 * the previous window's accepted text, which is the same
 * mechanism, just under our control (and gated on confidence so
 * a garbage window cannot poison the next one).
 */
internal object WhisperPrompt {

    /**
     * Natural Persian primer. Deliberately short (~45 tokens): the
     * prompt competes with the audio for the decoder's attention,
     * and every extra token is another phrase the model can echo.
     */
    const val PRIMER: String =
        "سلام، به این ویدیو خوش آمدید. امروز درباره‌ی آپدیت جدید و " +
            "امکانات تازه صحبت می‌کنیم. اگر سؤالی داشتید در بخش نظرات " +
            "بنویسید. بله، دقیقاً همین‌طور است. حالا برویم سراغ ادامه‌ی کار."

    /** Tail length of the previous window reused as context. */
    const val MAX_CARRY_CHARS: Int = 140

    /**
     * A window's text is only reused as context when the decoder
     * was reasonably sure of it. Below this the tail is dropped:
     * feeding a hallucination forward is how one bad window turns
     * into five.
     */
    const val MIN_CARRY_CONFIDENCE: Float = 0.55f

    /** Assembles primer + carried context + user dictionary. */
    fun build(dictionary: List<String>, carryOver: String = ""): String {
        val sb = StringBuilder(PRIMER)
        val carry = carryOver.trim()
        if (carry.isNotEmpty()) {
            sb.append('\n').append(carry)
        }
        val dict = dictionary.map { it.trim() }.filter { it.isNotBlank() }
        if (dict.isNotEmpty()) {
            sb.append('\n').append(dict.joinToString("، "))
        }
        return sb.toString()
    }

    /**
     * Tail of [windowText] suitable for the next window's prompt.
     *
     * The cut is snapped to a word boundary — a prompt that starts
     * mid-word teaches the model that mid-word breaks are normal,
     * which is the opposite of what the primer is for.
     */
    fun carryOverFrom(
        windowText: String,
        meanConfidence: Float?,
        maxChars: Int = MAX_CARRY_CHARS
    ): String {
        if (meanConfidence != null && meanConfidence < MIN_CARRY_CONFIDENCE) return ""
        val text = windowText.trim()
        if (text.isEmpty()) return ""
        if (text.length <= maxChars) return text
        val tail = text.takeLast(maxChars)
        // Drop the first (probably partial) word unless the cut
        // already landed on whitespace.
        val firstSpace = tail.indexOfFirst { it.isWhitespace() }
        if (firstSpace < 0) return ""
        return tail.substring(firstSpace + 1).trim()
    }

    /** Mean of the per-word probabilities whisper reported, or null. */
    fun meanConfidence(probabilities: List<Float>): Float? {
        val valid = probabilities.filter { it > 0f }
        if (valid.isEmpty()) return null
        return valid.average().toFloat()
    }
}
