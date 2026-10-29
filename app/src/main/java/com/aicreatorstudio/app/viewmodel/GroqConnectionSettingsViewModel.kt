package com.aicreatorstudio.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicreatorstudio.app.ai.groq.GroqConnectionTester
import com.aicreatorstudio.app.ai.groq.GroqApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 2026-10-10 — «اتصال هوش مصنوعی»: the user's own Groq API key.
 *
 * Save → encrypt at rest ([GroqApiKeyStore], AES-256/GCM +
 * Android Keystore) → optional live test via the quota-free
 * models endpoint ([GroqConnectionTester]).
 *
 * The plaintext key exists only in this ViewModel's transient
 * draft state and inside the tester call; it is never persisted
 * unencrypted and never logged.
 */
@HiltViewModel
class GroqConnectionSettingsViewModel @Inject constructor(
    private val apiKeyStore: GroqApiKeyStore,
    private val tester: GroqConnectionTester
) : ViewModel() {

    /** What the connection test reported, if it has run. */
    data class TestResult(
        val success: Boolean,
        val message: String
    )

    data class State(
        /** True when a key is already stored on this device. */
        val hasKey: Boolean = false,
        /** Masked form of the stored key, e.g. `••••••••1234`. */
        val maskedKey: String? = null,
        /** Whatever the user has typed in the field (never persisted). */
        val keyDraft: String = "",
        val isSaving: Boolean = false,
        val isTesting: Boolean = false,
        val testResult: TestResult? = null,
        /** Inline field-level error (blank/invalid input). */
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refreshStoredKey()
    }

    private fun refreshStoredKey() {
        val has = apiKeyStore.hasKey()
        _state.update {
            it.copy(
                hasKey = has,
                maskedKey = if (has) apiKeyStore.masked() else null
            )
        }
    }

    fun onKeyChanged(value: String) {
        _state.update { it.copy(keyDraft = value, errorMessage = null, testResult = null) }
    }

    /**
     * Persist the typed key (encrypted), then run a live connection
     * test so the user gets immediate confirmation.
     */
    fun saveKey() {
        val key = _state.value.keyDraft.trim()
        if (key.isBlank()) {
            _state.update { it.copy(errorMessage = "کلید API را وارد کنید.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, errorMessage = null, testResult = null) }
            val saved = withContext(Dispatchers.IO) { apiKeyStore.save(key) }
            if (!saved) {
                _state.update {
                    it.copy(isSaving = false, errorMessage = "ذخیرهٔ کلید ناموفق بود. دوباره تلاش کنید.")
                }
                return@launch
            }
            _state.update {
                it.copy(keyDraft = "", isSaving = false, hasKey = true, maskedKey = apiKeyStore.masked())
            }
            runTest()
        }
    }

    /** Live-test the already-stored key (no re-entry needed). */
    fun testConnection() {
        if (_state.value.isTesting) return
        runTest()
    }

    private fun runTest() {
        val key = apiKeyStore.load()
        if (key.isNullOrBlank()) {
            _state.update {
                it.copy(testResult = TestResult(success = false, message = "کلیدی ذخیره نشده است."))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testResult = null) }
            val verdict = withContext(Dispatchers.IO) { tester.testConnection(key) }
            _state.update {
                it.copy(
                    isTesting = false,
                    testResult = TestResult(
                        success = verdict is GroqConnectionTester.Verdict.Success,
                        message = verdict.message
                    )
                )
            }
        }
    }

    /** Remove the stored key. Idempotent. */
    fun clearKey() {
        apiKeyStore.clear()
        _state.update {
            State(hasKey = false, maskedKey = null, keyDraft = "", testResult = null)
        }
    }
}
