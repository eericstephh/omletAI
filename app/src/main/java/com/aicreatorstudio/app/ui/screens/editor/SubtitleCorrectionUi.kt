package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.runtime.staticCompositionLocalOf
import com.aicreatorstudio.app.ai.groq.GroqChatModels
import com.aicreatorstudio.app.ai.subtitle.correction.AiCorrectionState

/**
 * Everything the "Persian Subtitle AI Correction" row in the Subs card
 * needs, bundled into one value.
 *
 * ## Why this file exists at all
 *
 * `EditorScreenBody`'s sub-panel sheet lives inside an
 * `AnimatedVisibility` content lambda whose generated method is already
 * at the JVM's 64 KB per-method ceiling. Adding even ONE argument to
 * the `SubPanelRow(...)` call there fails the build with
 * `MethodTooLargeException`, so the correction actions cannot ride
 * down the existing parameter chain.
 *
 * [LocalSubtitleCorrectionActions] carries them instead: the provider
 * sits in `EditorScreen` (a small composable, nowhere near the limit),
 * and the card reads them at the bottom of the tree. The call site that
 * is too large gains exactly zero bytes.
 *
 * This is the idiomatic Compose answer to "an ambient action needed by
 * a deep descendant" — not a workaround invented for this build.
 */

/**
 * The four actions the correction row invokes.
 *
 * Defaults are no-ops so a card composed without a provider renders an
 * inert row instead of crashing.
 */
data class SubtitleCorrectionActions(
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSelectModel: (String) -> Unit = {},
    val onAcknowledge: () -> Unit = {}
)

/**
 * Ambient provider for [SubtitleCorrectionActions].
 *
 * `static` because the value is stable for the lifetime of the
 * provider: it is created once with `remember(viewModel)` and never
 * changes, so reading it must not subscribe to recomposition.
 */
internal val LocalSubtitleCorrectionActions =
    staticCompositionLocalOf { SubtitleCorrectionActions() }

/**
 * The correction row's complete input: state + model + actions.
 *
 * Passed to `AISubtitleCard` as ONE parameter for the same
 * method-size reason as above — `SubPanelContent` is a large `when`
 * and seven extra arguments are seven chances to overflow it.
 */
data class SubtitleCorrectionUi(
    val state: AiCorrectionState = AiCorrectionState.Idle,
    val modelId: String = GroqChatModels.DEFAULT,
    val disclosureAcknowledged: Boolean = false,
    val actions: SubtitleCorrectionActions = SubtitleCorrectionActions()
)
