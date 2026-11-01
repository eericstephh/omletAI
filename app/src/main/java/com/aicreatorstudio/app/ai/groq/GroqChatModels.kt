package com.aicreatorstudio.app.ai.groq

/**
 * The Groq **chat** models this app is willing to send subtitle text
 * to, plus the pure resolver that keeps an unknown/stale stored id off
 * the wire.
 *
 * ## Why a closed catalog instead of free text
 *
 * The audio side ([GroqWhisperClient.resolveModelId]) pins its model
 * list for the same reason: an id that the account cannot reach turns
 * into a 404/400 at request time, and the user reads it as "the
 * feature is broken" rather than "that model is not on your plan".
 * Groq's chat lineup changes and **not every model stays free**, so
 * the app never promises one — it offers the four ids it knows and
 * says so.
 *
 * [DEFAULT] is the brief's preferred model. When it is unavailable on
 * the user's account the request fails with an actionable message and
 * the picker in the Subs card lets them switch to another entry — the
 * id is stored, not hardcoded at the call site.
 *
 * ## Pure, JVM-testable
 *
 * No Android, no network. `GroqChatModelsTest` pins the resolver.
 */
object GroqChatModels {

    /** The brief's preferred model. Configurable, never hardcoded per-call. */
    const val DEFAULT: String = "openai/gpt-oss-20b"

    /**
     * One selectable model.
     *
     * @property id the exact `model` value sent to Groq.
     * @property label short name for the picker chip.
     * @property hint Persian one-liner describing the trade-off.
     */
    data class ChatModel(val id: String, val label: String, val hint: String)

    /**
     * The picker's contents, best-first. Every id is an
     * OpenAI-compatible chat-completions model on Groq — none of them
     * is an audio-transcription model, which matters: the correction
     * stage must never be pointed at the transcription endpoint.
     */
    val CATALOG: List<ChatModel> = listOf(
        ChatModel(DEFAULT, "GPT-OSS 20B", "پیشنهادی — سبک و سریع"),
        ChatModel("openai/gpt-oss-120b", "GPT-OSS 120B", "دقیق‌تر، کندتر"),
        ChatModel("llama-3.3-70b-versatile", "Llama 3.3 70B", "جایگزین"),
        ChatModel("llama-3.1-8b-instant", "Llama 3.1 8B", "سریع‌ترین")
    )

    /** True when [id] is one of [CATALOG]'s ids. */
    fun isKnown(id: String?): Boolean {
        val trimmed = id?.trim().orEmpty()
        return trimmed.isNotEmpty() && CATALOG.any { it.id == trimmed }
    }

    /**
     * Resolve a possibly stale/blank stored id to something safe to
     * send. An unknown id falls back to [DEFAULT] rather than being
     * forwarded, so a corrupted pref can never produce a 404 that
     * looks like a network fault.
     */
    fun resolveModelId(stored: String?): String {
        val trimmed = stored?.trim()
        return if (!trimmed.isNullOrEmpty() && CATALOG.any { it.id == trimmed }) {
            trimmed
        } else {
            DEFAULT
        }
    }

    /** The catalog entry for [id], or the default entry. */
    fun entryFor(id: String?): ChatModel {
        val resolved = resolveModelId(id)
        return CATALOG.first { it.id == resolved }
    }
}
