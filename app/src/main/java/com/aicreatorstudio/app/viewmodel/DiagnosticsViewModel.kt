package com.aicreatorstudio.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicreatorstudio.app.logging.DiagnosticsSummary
import com.aicreatorstudio.app.logging.LogReportBuilder
import com.aicreatorstudio.app.logging.OmeletLogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 2026-10-12 — drives the «گزارش‌های فنی» section of Settings.
 *
 * Holds the retained diagnostic events, a filter (all vs problems
 * only), and the latest-problem summary. Exporting the report is pure
 * text assembly ([LogReportBuilder]) so the caller can hand it to
 * `Intent.ACTION_SEND` using the same shape the existing crash-report
 * dialog already uses.
 *
 * Deliberately has **no** upload path: nothing here performs I/O other
 * than reading the local log files.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val store: OmeletLogStore
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsSummary())
    val state: StateFlow<DiagnosticsSummary> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the retained events from disk. */
    fun refresh() {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { store.readAll() }
            _state.value = DiagnosticsSummary.summarize(records, _state.value.problemsOnly)
        }
    }

    /** Toggles between "all events" and "problems only". */
    fun setProblemsOnly(problemsOnly: Boolean) {
        _state.value = DiagnosticsSummary.summarize(_state.value.records, problemsOnly)
    }

    /** Deletes every retained log file, then re-reads. */
    fun clearLogs() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clearAll() }
            val records = withContext(Dispatchers.IO) { store.readAll() }
            _state.value = DiagnosticsSummary.summarize(records, _state.value.problemsOnly)
        }
    }

    /**
     * Builds the shareable report body. Returns a header-only report
     * when nothing is retained, never null.
     */
    fun buildReport(): String {
        val records = _state.value.records
        return LogReportBuilder.build(
            records = records,
            deviceSummary = store.deviceSummary(),
            generatedAtMillis = System.currentTimeMillis(),
            // 2026-10-20 — the device's encoder capabilities. The AI
            // pipeline's encoder / color-format / resolution choice is
            // device-specific, so the report has to carry it: without
            // it, "the AI feature does not work on this phone" is not
            // actionable.
            mediaSummary = store.mediaSummary()
        )
    }
}
