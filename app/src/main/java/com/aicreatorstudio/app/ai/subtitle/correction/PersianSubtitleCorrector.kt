package com.aicreatorstudio.app.ai.subtitle.correction

import com.aicreatorstudio.app.ai.groq.GroqChatClient
import com.aicreatorstudio.app.ai.groq.GroqEndpoints
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates one Persian subtitle-correction run.
 *
 * ## What it does NOT do
 *
 * It does not read the project, does not write the project, and does
 * not decide whether the result is acceptable. It takes a list of
 * (id, text) pairs, asks the model, validates the replies, and hands
 * back corrected text keyed by id. The ViewModel owns the editing
 * state; the UI owns the accept/reject decision. That separation is
 * what makes "a failed correction leaves the subtitles untouched" a
 * structural fact rather than a promise.
 *
 * ## Failure policy (per batch, not per run)
 *
 * - **MALFORMED / UNKNOWN** — the batch is recorded as failed and the
 *   run CONTINUES. One chatty reply must not cost the user the other
 *   eleven batches; the failed overlays simply keep their text and
 *   the preview says so.
 * - **AUTH / MODEL_UNAVAILABLE / RATE_LIMITED** — the run ABORTS
 *   immediately. These are systemic: every remaining request would
 *   fail the same way and burn quota to prove it.
 * - **NETWORK** — aborts too. Retrying a dead socket per batch turns
 *   a 3-second failure into a 3-minute one.
 *
 * No automatic retries anywhere: the brief forbids retry loops that
 * consume quota, so a retry is always an explicit user action.
 */
@Singleton
class PersianSubtitleCorrector @Inject constructor(
    private val chatClient: ChatCompletionClient,
    private val credentials: CorrectionCredentials
) {

    /** True when a Groq key is stored (the precondition for any run). */
    fun isAvailable(): Boolean = credentials.apiKey() != null

    /** The chat model the next run will use. */
    fun modelId(): String = credentials.storedModelId()

    /** Persist the user's model choice. */
    fun setModelId(modelId: String) {
        credentials.saveModelId(modelId)
    }

    /** True once the user has seen the "text leaves the device" notice. */
    fun hasAcknowledgedDisclosure(): Boolean =
        credentials.hasAcknowledgedDisclosure()

    fun acknowledgeDisclosure() {
        credentials.acknowledgeDisclosure()
    }

    /**
     * Run the correction over [segments].
     *
     * @param onProgress called after each batch with
     *   `(completedBatches, totalBatches)`; also called once up front
     *   with `(0, total)` so the UI can render a real fraction before
     *   the first reply arrives.
     */
    suspend fun correct(
        segments: List<CorrectionInputSegment>,
        modelId: String = modelId(),
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): CorrectionRunResult {
        val resolvedModel = com.aicreatorstudio.app.ai.groq.GroqChatModels.resolveModelId(modelId)

        val apiKey = credentials.apiKey()
            ?: return CorrectionRunResult(
                segments = emptyList(),
                failureMessage = MISSING_KEY_MESSAGE,
                failureKind = CorrectionFailureKind.AUTH
            )

        val batches = SubtitleCorrectionBatcher.plan(segments)
        if (batches.isEmpty()) {
            return CorrectionRunResult(
                segments = emptyList(),
                failureMessage = "زیرنویسی برای اصلاح وجود ندارد",
                failureKind = CorrectionFailureKind.MALFORMED,
                totalBatches = 0
            )
        }

        val endpoint = GroqEndpoints.chatUrl(credentials.endpoint())
        val corrected = ArrayList<CorrectedSegment>(segments.size)
        val failed = ArrayList<Int>()
        var failureMessage: String? = null
        var failureKind: CorrectionFailureKind? = null

        onProgress(0, batches.size)

        for (batch in batches) {
            currentCoroutineContext().ensureActive()

            val outcome = chatClient.complete(
                GroqChatClient.Request(
                    apiKey = apiKey,
                    model = resolvedModel,
                    systemPrompt = CorrectionPrompt.SYSTEM,
                    userPrompt = CorrectionPrompt.userMessage(batch),
                    endpoint = endpoint
                )
            )

            when (outcome) {
                is GroqChatClient.Outcome.Success -> {
                    val parsed = CorrectionResponseParser.parseSegments(
                        content = outcome.content,
                        expectedIds = batch.segments.map { it.id }
                    )
                    when (parsed) {
                        is CorrectionResponseParser.Result.Ok ->
                            corrected.addAll(parsed.segments)

                        is CorrectionResponseParser.Result.Malformed -> {
                            failed.add(batch.index)
                            if (failureMessage == null) {
                                failureMessage = "پاسخ مدل برای بخش ${batch.index + 1} معتبر نبود: ${parsed.reason}"
                                failureKind = CorrectionFailureKind.MALFORMED
                            }
                        }
                    }
                }

                is GroqChatClient.Outcome.ApiError -> {
                    val kind = classify(outcome.httpCode, outcome.message)
                    failed.add(batch.index)
                    if (failureMessage == null) {
                        failureMessage = outcome.message
                        failureKind = kind
                    }
                    if (isSystemic(kind)) {
                        // Record the untouched batches so the UI can
                        // say "only the first N were reviewed".
                        for (rest in batch.index + 1 until batches.size) failed.add(rest)
                        break
                    }
                }

                is GroqChatClient.Outcome.NetworkError -> {
                    failed.add(batch.index)
                    if (failureMessage == null) {
                        failureMessage = outcome.message
                        failureKind = CorrectionFailureKind.NETWORK
                    }
                    for (rest in batch.index + 1 until batches.size) failed.add(rest)
                    break
                }

                is GroqChatClient.Outcome.Cancelled -> {
                    return CorrectionRunResult(
                        segments = emptyList(),
                        failedBatches = batches.map { it.index },
                        failureMessage = CANCELLED_MESSAGE,
                        failureKind = null,
                        cancelled = true,
                        totalBatches = batches.size
                    )
                }
            }

            onProgress(batch.index + 1, batches.size)
        }

        return CorrectionRunResult(
            segments = corrected,
            failedBatches = failed.distinct().sorted(),
            failureMessage = failureMessage,
            failureKind = failureKind,
            cancelled = false,
            totalBatches = batches.size
        )
    }

    /** Map an HTTP status to the failure taxonomy. */
    internal fun classify(httpCode: Int, message: String): CorrectionFailureKind = when {
        httpCode == 401 || httpCode == 403 -> CorrectionFailureKind.AUTH
        httpCode == 404 -> CorrectionFailureKind.MODEL_UNAVAILABLE
        httpCode == 429 -> CorrectionFailureKind.RATE_LIMITED
        httpCode in 500..599 -> CorrectionFailureKind.SERVER
        httpCode == 400 && message.contains("مدل") -> CorrectionFailureKind.MODEL_UNAVAILABLE
        httpCode == 0 -> CorrectionFailureKind.NETWORK
        else -> CorrectionFailureKind.UNKNOWN
    }

    /**
     * True for failures where every remaining request would fail
     * identically, so continuing only wastes quota.
     */
    internal fun isSystemic(kind: CorrectionFailureKind): Boolean = when (kind) {
        CorrectionFailureKind.AUTH,
        CorrectionFailureKind.MODEL_UNAVAILABLE,
        CorrectionFailureKind.RATE_LIMITED,
        CorrectionFailureKind.NETWORK -> true

        // A single malformed reply is transient — the next batch may
        // be fine, and the user paid for it already.
        CorrectionFailureKind.MALFORMED,
        CorrectionFailureKind.SERVER,
        CorrectionFailureKind.UNKNOWN -> false
    }

    companion object {
        /** Shown when the stage runs without a saved key. */
        const val MISSING_KEY_MESSAGE: String =
            "برای اصلاح هوشمند، کلید Groq خود را در «تنظیمات ← اتصال هوش مصنوعی» وارد کنید."

        /** Shown when the user cancels mid-run. */
        const val CANCELLED_MESSAGE: String = "اصلاح زیرنویس لغو شد"
    }
}
