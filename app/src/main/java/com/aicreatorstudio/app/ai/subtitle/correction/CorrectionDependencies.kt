package com.aicreatorstudio.app.ai.subtitle.correction

import com.aicreatorstudio.app.ai.groq.GroqApiKeyStore
import com.aicreatorstudio.app.ai.groq.GroqChatClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two collaborators [PersianSubtitleCorrector] needs, expressed as
 * interfaces.
 *
 * ## Why interfaces rather than the concrete classes
 *
 * `GroqApiKeyStore` needs an Android `Context` (Keystore + prefs) and
 * `GroqChatClient` needs a socket. This project has no Robolectric and
 * no mocking framework — the established convention is an interface
 * plus a `Default…` implementation bound in `di/AppBindings.kt`, with a
 * hand-written fake in the test.
 *
 * That seam is what makes the required behaviours testable **without a
 * paid API call**: rate limiting, timeouts, cancellation, malformed
 * replies and a failed batch leaving the original text intact are all
 * asserted against a fake client, not against Groq.
 */

/**
 * One chat-completion round trip.
 *
 * Implemented by [GroqChatClient]; faked in tests.
 */
interface ChatCompletionClient {
    suspend fun complete(request: GroqChatClient.Request): GroqChatClient.Outcome
}

/**
 * Key, endpoint, model and disclosure state for the correction stage.
 *
 * Deliberately narrow: the corrector cannot delete a key, cannot read
 * the transcription model, and cannot touch the transcription
 * endpoint. Those belong to [GroqApiKeyStore] and to the audio path.
 */
interface CorrectionCredentials {

    /** The decrypted API key, or null when absent/unreadable. */
    fun apiKey(): String?

    /** The stored transcription endpoint (the chat URL is derived from it). */
    fun endpoint(): String

    /** The stored chat model, already resolved against the catalog. */
    fun storedModelId(): String

    /** Persist the user's chat-model choice. */
    fun saveModelId(modelId: String)

    /** True once the user has seen the "text leaves the device" notice. */
    fun hasAcknowledgedDisclosure(): Boolean

    /** Record that the notice was shown and understood. */
    fun acknowledgeDisclosure()
}

/**
 * The production implementation, backed by the Keystore-encrypted
 * [GroqApiKeyStore]. No new secret storage: the correction stage reuses
 * the exact key the transcription stage already uses.
 */
@Singleton
class DefaultCorrectionCredentials @Inject constructor(
    private val store: GroqApiKeyStore
) : CorrectionCredentials {

    override fun apiKey(): String? =
        store.load()?.trim()?.takeIf { it.isNotEmpty() }

    override fun endpoint(): String = store.endpoint()

    override fun storedModelId(): String = store.correctionModelId()

    override fun saveModelId(modelId: String) = store.saveCorrectionModelId(modelId)

    override fun hasAcknowledgedDisclosure(): Boolean =
        store.hasAcknowledgedCorrectionDisclosure()

    override fun acknowledgeDisclosure() = store.acknowledgeCorrectionDisclosure()
}
