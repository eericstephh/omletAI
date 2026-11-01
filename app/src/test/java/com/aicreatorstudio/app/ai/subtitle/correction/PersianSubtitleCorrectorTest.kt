package com.aicreatorstudio.app.ai.subtitle.correction

import com.aicreatorstudio.app.ai.groq.GroqChatClient
import com.aicreatorstudio.app.ai.groq.GroqChatModels
import com.aicreatorstudio.app.ai.groq.GroqEndpoints
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of one correction run, against a FAKE client.
 *
 * No network, no key, no cost: the failure taxonomy, the abort policy,
 * the per-batch tolerance, cancellation and the absence of automatic
 * retries are all properties of the orchestrator and are asserted here
 * directly. The real HTTP layer is exercised only by the app.
 */
class PersianSubtitleCorrectorTest {

    // ---- fakes ----

    private class FakeClient(
        private val handler: suspend (GroqChatClient.Request) -> GroqChatClient.Outcome
    ) : ChatCompletionClient {
        val requests = mutableListOf<GroqChatClient.Request>()

        override suspend fun complete(request: GroqChatClient.Request): GroqChatClient.Outcome {
            requests += request
            return handler(request)
        }
    }

    private class FakeCredentials(
        var key: String? = "gsk_test",
        var model: String = GroqChatModels.DEFAULT,
        var endpoint: String = GroqEndpoints.DEFAULT_CHAT_URL,
        var acknowledged: Boolean = false
    ) : CorrectionCredentials {
        override fun apiKey(): String? = key
        override fun endpoint(): String = endpoint
        override fun storedModelId(): String = model
        override fun saveModelId(modelId: String) {
            model = modelId
        }

        override fun hasAcknowledgedDisclosure(): Boolean = acknowledged
        override fun acknowledgeDisclosure() {
            acknowledged = true
        }
    }

    /** Every `"id":"…"` the request's segment array carries, in order. */
    private fun idsOf(request: GroqChatClient.Request): List<String> =
        Regex("\"id\":\"([^\"]+)\"").findAll(request.userPrompt)
            .map { it.groupValues[1] }
            .toList()

    /** A well-formed reply echoing the request's ids. */
    private fun reply(
        request: GroqChatClient.Request,
        transform: (String) -> String = { it }
    ): GroqChatClient.Outcome.Success {
        val array = JSONArray()
        idsOf(request).forEach { id ->
            array.put(JSONObject().put("id", id).put("text", transform(id)))
        }
        return GroqChatClient.Outcome.Success(
            JSONObject().put("segments", array).toString()
        )
    }

    private fun segments(count: Int) =
        (1..count).map { CorrectionInputSegment("s$it", "متن شماره $it") }

    // ---- happy path ----

    @Test
    fun `successful run returns corrected text keyed by id`() = runTest {
        val client = FakeClient { reply(it) { id -> "اصلاح‌شده $id" } }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(3))

        assertFalse(run.cancelled)
        assertEquals(3, run.segments.size)
        assertEquals(listOf("s1", "s2", "s3"), run.segments.map { it.id })
        assertEquals("اصلاح‌شده s1", run.segments[0].text)
        assertTrue(run.failedBatches.isEmpty())
    }

    @Test
    fun `request uses the resolved model and the derived chat endpoint`() = runTest {
        val creds = FakeCredentials(
            model = "llama-3.3-70b-versatile",
            endpoint = "https://api.groq.com/openai/v1/audio/transcriptions"
        )
        val client = FakeClient { reply(it) }
        val corrector = PersianSubtitleCorrector(client, creds)

        corrector.correct(segments(2))

        assertEquals("llama-3.3-70b-versatile", client.requests[0].model)
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            client.requests[0].endpoint
        )
        // The key travels in the request object only.
        assertEquals("gsk_test", client.requests[0].apiKey)
    }

    @Test
    fun `progress is reported from zero and advances per batch`() = runTest {
        val client = FakeClient { reply(it) }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())
        val ticks = mutableListOf<Pair<Int, Int>>()

        corrector.correct(segments(25)) { done, total -> ticks += done to total }

        // 25 segments at 12 per batch -> 3 batches.
        assertEquals(0 to 3, ticks.first())
        assertEquals(3 to 3, ticks.last())
        assertEquals(listOf(0, 1, 2, 3), ticks.map { it.first })
    }

    // ---- preconditions ----

    @Test
    fun `missing key fails as AUTH without any request`() = runTest {
        val client = FakeClient { reply(it) }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials(key = null))

        val run = corrector.correct(segments(2))

        assertEquals(CorrectionFailureKind.AUTH, run.failureKind)
        assertEquals(PersianSubtitleCorrector.MISSING_KEY_MESSAGE, run.failureMessage)
        assertTrue(client.requests.isEmpty())
        assertFalse(corrector.isAvailable())
    }

    // ---- failure policy ----

    @Test
    fun `rate limit aborts the remaining batches`() = runTest {
        val client = FakeClient {
            GroqChatClient.Outcome.ApiError(429, "محدودیت نرخ Groq.")
        }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(25))

        assertEquals(CorrectionFailureKind.RATE_LIMITED, run.failureKind)
        // ONE request: no retry loop, no hammering.
        assertEquals(1, client.requests.size)
        // Every batch is reported failed so the UI can say how much was
        // left unreviewed.
        assertEquals(3, run.failedBatches.size)
        assertTrue(run.segments.isEmpty())
    }

    @Test
    fun `network error aborts the remaining batches`() = runTest {
        val client = FakeClient { GroqChatClient.Outcome.NetworkError("timeout") }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(25))

        assertEquals(CorrectionFailureKind.NETWORK, run.failureKind)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `auth failure aborts`() = runTest {
        val client = FakeClient { GroqChatClient.Outcome.ApiError(401, "bad key") }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(25))

        assertEquals(CorrectionFailureKind.AUTH, run.failureKind)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `model unavailable is classified and aborts`() = runTest {
        val client = FakeClient { GroqChatClient.Outcome.ApiError(404, "model not found") }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(3))

        assertEquals(CorrectionFailureKind.MODEL_UNAVAILABLE, run.failureKind)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `a malformed reply fails only that batch and the run continues`() = runTest {
        var call = 0
        val client = FakeClient {
            call++
            if (call == 1) {
                GroqChatClient.Outcome.Success("I'm sorry, I cannot do that.")
            } else {
                reply(it)
            }
        }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(25))

        assertEquals(3, client.requests.size)
        assertEquals(listOf(0), run.failedBatches)
        assertEquals(CorrectionFailureKind.MALFORMED, run.failureKind)
        // The other two batches still produced corrections.
        assertEquals(13, run.segments.size)
    }

    @Test
    fun `server error does not abort`() = runTest {
        var call = 0
        val client = FakeClient {
            call++
            if (call == 1) GroqChatClient.Outcome.ApiError(503, "unavailable") else reply(it)
        }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(25))

        assertEquals(3, client.requests.size)
        assertEquals(listOf(0), run.failedBatches)
        assertEquals(13, run.segments.size)
    }

    // ---- retry ----

    @Test
    fun `a failed run is retryable but never retries itself`() = runTest {
        var fail = true
        val client = FakeClient {
            if (fail) GroqChatClient.Outcome.ApiError(429, "slow down") else reply(it)
        }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val first = corrector.correct(segments(2))
        assertEquals(1, client.requests.size)
        assertNotNull(first.failureKind)

        // No time passes and no retry happens on its own.
        assertEquals(1, client.requests.size)

        // An explicit retry (what the UI's "تلاش دوباره" does).
        fail = false
        val second = corrector.correct(segments(2))
        assertEquals(2, client.requests.size)
        assertTrue(second.failureKind == null)
        assertEquals(2, second.segments.size)
    }

    // ---- cancellation ----

    @Test
    fun `a cancelled outcome reports cancellation and yields nothing`() = runTest {
        val client = FakeClient { GroqChatClient.Outcome.Cancelled }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        val run = corrector.correct(segments(3))

        assertTrue(run.cancelled)
        assertTrue(run.segments.isEmpty())
        assertEquals(PersianSubtitleCorrector.CANCELLED_MESSAGE, run.failureMessage)
    }

    @Test
    fun `external cancellation propagates instead of being swallowed`() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = FakeClient {
            started.complete(Unit)
            awaitCancellation()
        }
        val corrector = PersianSubtitleCorrector(client, FakeCredentials())

        var cancelled = false
        val job = launch {
            try {
                corrector.correct(segments(3))
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        started.await()
        job.cancel()
        job.join()

        assertTrue("cancellation must propagate to the caller", cancelled)
    }

    // ---- classification helpers ----

    @Test
    fun `failure taxonomy maps the documented status codes`() {
        val corrector = PersianSubtitleCorrector(
            FakeClient { reply(it) },
            FakeCredentials()
        )
        assertEquals(CorrectionFailureKind.AUTH, corrector.classify(401, ""))
        assertEquals(CorrectionFailureKind.AUTH, corrector.classify(403, ""))
        assertEquals(CorrectionFailureKind.MODEL_UNAVAILABLE, corrector.classify(404, ""))
        assertEquals(CorrectionFailureKind.RATE_LIMITED, corrector.classify(429, ""))
        assertEquals(CorrectionFailureKind.SERVER, corrector.classify(500, ""))
        assertEquals(CorrectionFailureKind.NETWORK, corrector.classify(0, ""))
        // A 400 that names the model is the "you are not entitled to
        // this model" shape Groq uses.
        assertEquals(
            CorrectionFailureKind.MODEL_UNAVAILABLE,
            corrector.classify(400, "این مدل روی حساب شما در دسترس نیست.")
        )
        assertEquals(CorrectionFailureKind.UNKNOWN, corrector.classify(400, "bad request"))
    }

    @Test
    fun `only systemic failures abort the run`() {
        val corrector = PersianSubtitleCorrector(
            FakeClient { reply(it) },
            FakeCredentials()
        )
        assertTrue(corrector.isSystemic(CorrectionFailureKind.AUTH))
        assertTrue(corrector.isSystemic(CorrectionFailureKind.MODEL_UNAVAILABLE))
        assertTrue(corrector.isSystemic(CorrectionFailureKind.RATE_LIMITED))
        assertTrue(corrector.isSystemic(CorrectionFailureKind.NETWORK))
        assertFalse(corrector.isSystemic(CorrectionFailureKind.MALFORMED))
        assertFalse(corrector.isSystemic(CorrectionFailureKind.SERVER))
        assertFalse(corrector.isSystemic(CorrectionFailureKind.UNKNOWN))
    }

    @Test
    fun `model selection is persisted through the credentials seam`() {
        val creds = FakeCredentials()
        val corrector = PersianSubtitleCorrector(FakeClient { reply(it) }, creds)

        corrector.setModelId("openai/gpt-oss-120b")

        assertEquals("openai/gpt-oss-120b", creds.model)
        assertEquals("openai/gpt-oss-120b", corrector.modelId())
    }

    @Test
    fun `disclosure acknowledgement round-trips`() {
        val creds = FakeCredentials()
        val corrector = PersianSubtitleCorrector(FakeClient { reply(it) }, creds)
        assertFalse(corrector.hasAcknowledgedDisclosure())
        corrector.acknowledgeDisclosure()
        assertTrue(corrector.hasAcknowledgedDisclosure())
    }
}
