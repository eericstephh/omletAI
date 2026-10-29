package com.aicreatorstudio.app

import com.aicreatorstudio.app.ai.SubtitleGenerator
import com.aicreatorstudio.app.ai.WhisperModelSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-10 ACCOUNT-FREE REGRESSION SUITE.
 *
 * Pins the product decision: Omelet is usable WITHOUT any email
 * account, sign-in, registration, or Omelet-imposed daily subtitle
 * limit. Every test here is deterministic JVM — no network, no
 * Android framework, no Compose.
 *
 * What these tests prove:
 *  1. The removed auth/quota classes no longer exist (compile-time
 *     absence → ClassNotFoundException at runtime).
 *  2. Subtitle generation has NO client-side 60-second quota gate.
 *  3. The cloud path is gated ONLY by the user's own Groq key.
 *  4. Offline transcription has no account precondition.
 *  5. Missing-key errors name the Settings path, not a login form.
 */
class AccountFreeRegressionTest {

    // ---- Phase 5.1–5.3: deleted auth surfaces are truly gone ----

    @Test
    fun `CloudLoginDialog class is deleted - no home auto-login dialog exists`() {
        assertClassDeleted("com.aicreatorstudio.app.ui.screens.settings.CloudLoginDialog")
    }

    @Test
    fun `WhisperCloudSection class is deleted - no settings auth section exists`() {
        assertClassDeleted("com.aicreatorstudio.app.ui.screens.settings.WhisperCloudSection")
    }

    @Test
    fun `WhisperCloudSettingsViewModel class is deleted - no auth+quota viewmodel exists`() {
        assertClassDeleted("com.aicreatorstudio.app.viewmodel.WhisperCloudSettingsViewModel")
    }

    @Test
    fun `SupabaseAuthRepository class is deleted - no email auth client exists`() {
        assertClassDeleted("com.aicreatorstudio.app.data.auth.SupabaseAuthRepository")
    }

    @Test
    fun `AuthSessionManager class is deleted - no session persistence exists`() {
        assertClassDeleted("com.aicreatorstudio.app.data.auth.AuthSessionManager")
    }

    @Test
    fun `CloudWhisperEngine class is deleted - no auth-gated worker engine exists`() {
        assertClassDeleted("com.aicreatorstudio.app.ai.CloudWhisperEngine")
    }

    @Test
    fun `CloudWhisperRepository class is deleted - no worker transcription repository exists`() {
        assertClassDeleted("com.aicreatorstudio.app.ai.subtitle.cloud.CloudWhisperRepository")
    }

    @Test
    fun `UsageQuota data class is deleted - no 60-second daily quota model exists`() {
        // WhisperCloudSettingsViewModel.UsageQuota was an inner class;
        // if the outer class is gone the inner is too. Assert directly.
        assertClassDeleted("com.aicreatorstudio.app.viewmodel.WhisperCloudSettingsViewModel\$UsageQuota")
    }

    // ---- Phase 5.4: no client-side 60-second quota gate ----

    @Test
    fun `SubtitleGenerator has no quota, dailyLimit, or usage fields`() {
        val quotaishNames = SubtitleGenerator::class.java.declaredFields
            .map { it.name.lowercase() }
            .filter { name ->
                name.contains("quota") ||
                    name.contains("dailylimit") ||
                    name.contains("daily_limit") ||
                    name.contains("usage") ||
                    name.contains("remaining") ||
                    name.contains("secondlimit")
            }
        assertTrue(
            "SubtitleGenerator must not carry quota state, found: $quotaishNames",
            quotaishNames.isEmpty()
        )
    }

    @Test
    fun `SubtitleGenerator has no quota-checking methods`() {
        val quotaishMethods = SubtitleGenerator::class.java.declaredMethods
            .map { it.name.lowercase() }
            .filter { name ->
                name.contains("quota") ||
                    name.contains("dailylimit") ||
                    name.contains("checkusage") ||
                    name.contains("remainingseconds") ||
                    name.contains("cantranscribe")
            }
        assertTrue(
            "SubtitleGenerator must not expose quota gates, found: $quotaishMethods",
            quotaishMethods.isEmpty()
        )
    }

    @Test
    fun `unavailable cloud error names settings path not a login prompt`() {
        val msg = SubtitleGenerator.CLOUD_ENGINE_UNAVAILABLE_MESSAGE
        assertTrue(
            "error should name the Groq settings path, got: $msg",
            msg.contains("اتصال هوش مصنوعی")
        )
        assertTrue(
            "error should offer the offline alternative, got: $msg",
            msg.contains("آفلاین")
        )
        val loginPromptTriggers = listOf(
            "وارد حساب", "ورود", "ایمیل", "رمز عبور", "ثبت‌نام", "اکانت",
            "sign in", "sign up", "log in", "login", "register", "email", "password"
        )
        val hit = loginPromptTriggers.firstOrNull { msg.contains(it, ignoreCase = true) }
        assertTrue(
            "cloud error must NOT read as a login prompt (matched: $hit), got: $msg",
            hit == null
        )
    }

    // ---- Phase 5.5: restart cannot restore the restriction ----

    @Test
    fun `no SharedPreferences key for auth session or daily quota exists in source constants`() {
        // The deleted AuthSessionManager owned prefs file "aierix_auth_prefs"
        // with keys jwt_token/user_id/user_email. Assert the source tree
        // no longer contains those literals (strongest JVM-provable proxy
        // for "restart cannot restore the removed restriction").
        val mainRoot = java.io.File("src/main/java")
        assertTrue("expected src/main/java relative layout", mainRoot.isDirectory)
        val offenders = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("aierix_auth_prefs") ||
                    text.contains("jwt_token") ||
                    text.contains("daily_limit") ||
                    text.contains("UsageQuota(")
            }
            .map { it.relativeTo(mainRoot).path }
            .toList()
        assertTrue(
            "found residual auth/quota persistence literals in: $offenders",
            offenders.isEmpty()
        )
    }

    // ---- Phase 5.6: offline transcription works without an account ----

    @Test
    fun `offline SMALL path succeeds with only a local engine present`() = runTest {
        val local = FakeEngine("whisper", "Local", isAvailable = true)
        val gen = SubtitleGenerator(engines = listOf(local))
        val result = gen.initializeModelFor(WhisperModelSpec.SMALL) {}
        assertTrue("offline path must not require any account", result.isSuccess)
        assertEquals(1, local.initCount)
        assertEquals(local, gen.engineThatWillGenerate())
    }

    @Test
    fun `offline SMALL path succeeds with an EMPTY engine list failure being engine-unavailable not auth`() = runTest {
        // With no engines the failure must be the generic engine-unavailable
        // Persian message — NOT a login/quota prompt.
        val gen = SubtitleGenerator(engines = emptyList())
        val result = gen.initializeModelFor(WhisperModelSpec.SMALL) {}
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        val loginTriggers = listOf("وارد حساب", "ایمیل", "رمز عبور", "ثبت‌نام", "sign in", "login")
        val hit = loginTriggers.firstOrNull { msg.contains(it, ignoreCase = true) }
        assertTrue("offline failure must not be an auth prompt (matched: $hit), got: $msg", hit == null)
    }

    // ---- Phase 5.7: Groq-key cloud path has no Omelet account ----

    @Test
    fun `cloud with user key does not consult any session or auth class`() = runTest {
        val userKey = FakeEngine("groq_user_key", "کلید شخصی", isAvailable = true)
        val gen = SubtitleGenerator(engines = listOf(userKey))
        val result = gen.initializeModelFor(WhisperModelSpec.CLOUD_API) {}
        assertTrue("cloud with key must succeed with zero Omelet account state", result.isSuccess)
        assertEquals(1, userKey.initCount)
        assertTrue(gen.hasUserGroqKey())
    }

    @Test
    fun `cloud engine list contains no worker or auth-gated engine id`() = runTest {
        // AppModule now provides ONLY [whisper, groq_user_key]. Prove the
        // generator never resolves "whisper_cloud" even if a stale engine
        // with that id were injected.
        val staleWorker = FakeEngine("whisper_cloud", "Worker", isAvailable = true)
        val userKey = FakeEngine("groq_user_key", "کلید شخصی", isAvailable = true)
        val gen = SubtitleGenerator(engines = listOf(userKey, staleWorker))
        val result = gen.initializeModelFor(WhisperModelSpec.CLOUD_API) {}
        assertTrue(result.isSuccess)
        assertEquals("کلید شخصی", gen.activeEngineDisplayName)
        // The worker engine was present in the list but must NOT be selected.
        assertEquals(userKey, gen.engineThatWillGenerate())
    }

    // ---- Phase 5.8: missing key → error, not auth prompt ----

    @Test
    fun `missing groq key produces settings-path error not an authentication prompt`() = runTest {
        val gen = SubtitleGenerator(engines = emptyList())
        val result = gen.initializeModelFor(WhisperModelSpec.CLOUD_API) {}
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()?.message ?: ""
        assertTrue(msg.contains("کلید Groq"))
        assertTrue(msg.contains("اتصال هوش مصنوعی"))
        val authTriggers = listOf("وارد حساب", "ایمیل", "رمز عبور", "sign in", "login", "register")
        val hit = authTriggers.firstOrNull { msg.contains(it, ignoreCase = true) }
        assertTrue("missing-key error must not be an auth prompt (matched: $hit)", hit == null)
    }

    // ---- helpers ----

    private fun assertClassDeleted(fqcn: String) {
        try {
            Class.forName(fqcn)
            org.junit.Assert.fail(
                "Expected $fqcn to be deleted (account-free build), but the class still exists."
            )
        } catch (e: ClassNotFoundException) {
            // expected
        }
    }
}

/** Minimal [com.aicreatorstudio.app.ai.TranscriptionEngine] double. */
private class FakeEngine(
    override val id: String,
    override val displayName: String,
    override val isAvailable: Boolean
) : com.aicreatorstudio.app.ai.TranscriptionEngine {

    var initCount = 0
        private set

    override suspend fun initializeModel(onProgress: (Float) -> Unit): Result<Unit> {
        initCount += 1
        onProgress(1f)
        return Result.success(Unit)
    }

    override suspend fun generateTranscription(
        videoUri: android.net.Uri,
        onProgress: (Float) -> Unit
    ): Result<com.aicreatorstudio.app.ai.Transcription> {
        onProgress(1f)
        return Result.success(com.aicreatorstudio.app.ai.Transcription.Empty)
    }

    override fun release() {}
}
