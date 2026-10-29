package com.aicreatorstudio.app.viewmodel

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.restoreDocumentFrom

/**
 * Decoupled Undo/Redo manager. Owns the history stacks and exposes
 * a minimal API that [EditorViewModel] delegates to.
 *
 * All state reads/writes go through the shared [stateFlow], so the
 * delegate stays in sync with the ViewModel without holding its own
 * reference to the full EditorUiState.
 */
@Stable
class HistoryDelegate(
    private val stateFlow: MutableStateFlow<EditorUiState>,
    private val historyLimit: Int = 20
) {
    private val historyUndo = ArrayDeque<EditorUiState>()
    private val historyRedo = ArrayDeque<EditorUiState>()

    /** Snapshot current state before a mutation. No-op when no project loaded. */
    fun push() {
        val state = stateFlow.value
        if (state.project == null) return
        if (historyUndo.size >= historyLimit) historyUndo.removeFirst()
        historyUndo.addLast(state)
        historyRedo.clear()
        refreshFlags()
    }

    /** Restore the previous snapshot from the undo stack. */
    fun undo() {
        val prev = historyUndo.removeLastOrNull() ?: return
        historyRedo.addLast(stateFlow.value)
        if (historyRedo.size > historyLimit) historyRedo.removeFirst()
        apply(prev)
    }

    /** Re-apply the last undone snapshot from the redo stack. */
    fun redo() {
        val next = historyRedo.removeLastOrNull() ?: return
        historyUndo.addLast(stateFlow.value)
        if (historyUndo.size > historyLimit) historyUndo.removeFirst()
        apply(next)
    }

    fun canUndo(): Boolean = historyUndo.isNotEmpty()
    fun canRedo(): Boolean = historyRedo.isNotEmpty()

    // ── Private helpers ──────────────────────────────────────

    private fun apply(snapshot: EditorUiState) {
        stateFlow.update { current -> restoreDocumentFrom(snapshot, current) }
        refreshFlags()
    }

    private fun refreshFlags() {
        stateFlow.update { it.copy(canUndo = canUndo(), canRedo = canRedo()) }
    }
}
