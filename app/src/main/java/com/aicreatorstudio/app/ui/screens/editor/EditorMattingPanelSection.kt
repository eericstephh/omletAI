package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.model.MattingBackgroundMode
import com.aicreatorstudio.app.model.VideoClip

/**
 * The [AiMattingPanel] branch of the editor's tool-`when`, extracted
 * into its own composable.
 *
 * ## Why this exists
 *
 * The editor's tool-`when` block compiles into one Compose `invoke`
 * method — and the **`SubPanelRow` call inside the bottom sheet's
 * `AnimatedVisibility`** sits exactly at the JVM's 64 KB byte-code
 * limit: on 2026-10-21 one extra argument there failed the whole
 * screen with `MethodTooLargeException` (this file's editor header
 * comment on `LocalVoiceoverActions`), and the 2026-10-25 expansion /
 * feather / reverse callbacks did exactly that a second time.
 *
 * So the three matting knobs travel through
 * [LocalMattingPanelActions] instead of growing that call, and this
 * branch was lifted out into its own composable while it was being
 * rewired.
 *
 * The branch keeps no state of its own and resolves nothing —
 * everything it needs travels through the parameters and the local.
 */
@Composable
internal fun EditorMattingPanelSection(
    selectedClip: VideoClip?,
    mattingState: AiToolState,
    mattingModelAvailable: Boolean,
    onRunAiTool: (String, AiToolOptions) -> Unit,
    onCancelAiTool: (String) -> Unit,
    onResetAiTool: (String) -> Unit,
    onEnsureMattingModelReady: () -> Unit,
    onPickBackgroundMedia: () -> Unit,
    onMattingBackgroundModeChanged: (MattingBackgroundMode) -> Unit
) {
    val knobActions = LocalMattingPanelActions.current
    AiMattingPanel(
        selectedClip = selectedClip,
        settings = selectedClip?.matting
            ?: com.aicreatorstudio.app.model.MattingSettings.Identity,
        aiState = mattingState,
        engineModelStatusText = mattingState.message ?: "",
        // Live engine availability (not derived from AiToolState): a
        // model already on disk reports available on first open, so
        // Generate is enabled without a redundant re-download.
        engineIsAvailable = mattingModelAvailable,
        onRun = { opts -> onRunAiTool("matting", opts) },
        onCancel = { onCancelAiTool("matting") },
        onPickBackground = onPickBackgroundMedia,
        onBackgroundModeChanged = onMattingBackgroundModeChanged,
        onExpansionChanged = knobActions.onExpansionChanged,
        onFeatherChanged = knobActions.onFeatherChanged,
        onReverseChanged = knobActions.onReverseChanged,
        onReset = { onResetAiTool("matting") },
        onEnsureModelReady = onEnsureMattingModelReady
    )
}

/**
 * The three composition-time mask knobs of the Background Remove
 * panel. Provided via composition (not `SubPanelRow` parameters) for
 * the same 64 KB method limit that forced the CompositionLocal shape
 * for `LocalVoiceoverActions` — see [EditorMattingPanelSection].
 */
data class MattingPanelActions(
    val onExpansionChanged: (Float) -> Unit,
    val onFeatherChanged: (Float) -> Unit,
    val onReverseChanged: (Boolean) -> Unit
)

/** No-op default; the real provider sits in `EditorScreen`. */
val LocalMattingPanelActions = staticCompositionLocalOf {
    MattingPanelActions(
        onExpansionChanged = {},
        onFeatherChanged = {},
        onReverseChanged = {}
    )
}
