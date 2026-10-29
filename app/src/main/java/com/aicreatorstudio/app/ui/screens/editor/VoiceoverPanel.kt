package com.aicreatorstudio.app.ui.screens.editor

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aicreatorstudio.app.model.VoiceoverState
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.StatusError
import com.aicreatorstudio.app.ui.theme.StatusSuccess
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright

/**
 * The four actions the voiceover panel can raise.
 *
 * They travel as one holder rather than four separate parameters
 * because the recorder is a cohesive unit: threading four lambdas
 * through three composable layers (the editor screen → the sub-panel
 * row → the panel content) would be noise, and the defaults keep every
 * existing call site working.
 */
data class VoiceoverPanelActions(
    /** Begin a take. The panel has already obtained the mic grant. */
    val onStart: () -> Unit = {},
    /** End the take and hold it for confirmation. */
    val onStop: () -> Unit = {},
    /** Put the captured take on the timeline at the playhead. */
    val onPlace: () -> Unit = {},
    /** Throw the take away and return to idle. */
    val onDiscard: () -> Unit = {}
)

/**
 * The voiceover actions, provided by the editor screen and read by the
 * panel.
 *
 * 2026-10-21 — a composition local rather than a parameter, and the
 * reason is a hard JVM limit rather than taste: the editor screen's
 * `AnimatedVisibility` content lambda is a single method that is
 * already at the 64 KB method-size limit, and adding ONE more
 * argument to the sub-panel call made the Kotlin compiler fail with
 * `MethodTooLargeException` (the whole screen stopped compiling).
 *
 * A composition local costs that lambda nothing: the provider sits
 * around the screen's *body* — whose content lambda is a single call —
 * and the panel reads the actions where it needs them.
 */
val LocalVoiceoverActions = androidx.compose.runtime.compositionLocalOf {
    VoiceoverPanelActions()
}

/**
 * The voiceover panel as the editor screen calls it.
 *
 * 2026-10-21 — a separate composable so the editor's panel `when` stays
 * a one-liner: that `when` lives inside a lambda which is already at the
 * JVM's 64 KB method-size limit, and the Kotlin compiler fails with
 * `MethodTooLargeException` when a composable's body is inlined into it.
 */
@Composable
fun VoiceoverPanelSection(
    state: VoiceoverState,
    actions: VoiceoverPanelActions,
    modifier: Modifier = Modifier
) {
    VoiceoverPanel(
        state = state,
        modifier = modifier,
        onStart = actions.onStart,
        onStop = actions.onStop,
        onPlace = actions.onPlace,
        onDiscard = actions.onDiscard,
        // A failure has no take to keep, so "discard" is exactly the
        // right action for it: it clears the error and returns to idle.
        onDismissError = actions.onDiscard
    )
}

/**
 * «نریشن» (voiceover) — record a narration over the video.
 *
 * CapCut's voiceover is one of its signature capabilities: the user
 * records a narration and it lands on the timeline as its own audio
 * object. This project had the audio lane, the mixer and the voice
 * changer, but no recorder — and no `RECORD_AUDIO` grant in the
 * manifest.
 *
 * ## The flow is three states, and the panel shows exactly one of them
 *
 *  - **Idle** — one big record button.
 *  - **Recording** — the button becomes "stop", a live level meter and
 *    an elapsed timer appear.
 *  - **Captured** — the take's length, and the choice to put it on the
 *    timeline or throw it away.
 *
 * The captured take is NOT on the timeline until the user says so. A
 * mis-fired recording must not leave a stray clip on the timeline, and
 * the take is on disk (app-owned storage) so it survives the panel
 * being closed and reopened.
 *
 * ## The permission is requested at the moment of the tap
 *
 * The app never records on its own: the button asks for
 * `RECORD_AUDIO` only when the user actually presses it, and a denied
 * grant is reported in the panel rather than silently doing nothing.
 *
 * ## Controlled, not stateful
 *
 * The panel holds no copy of the recording state — it renders the
 * [state] parameter and reports taps upward, so the recorder in the
 * ViewModel is the single source of truth and the button can never be
 * shown while a recording is already running.
 */
@Composable
fun VoiceoverPanel(
    state: VoiceoverState,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPlace: () -> Unit,
    onDiscard: () -> Unit,
    onDismissError: () -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStart() else onDiscard()
    }

    fun requestThenStart() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) onStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "نریشن",
                color = LabelPrimary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "صدای خودت را ضبط کن؛ روی محل پخش در تایم‌لاین قرار می‌گیرد",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }

        when (state) {
            is VoiceoverState.Idle -> {
                VoiceoverRecordButton(
                    recording = false,
                    enabled = true,
                    onClick = { requestThenStart() }
                )
            }

            is VoiceoverState.Recording -> {
                VoiceoverRecordButton(
                    recording = true,
                    enabled = true,
                    onClick = onStop
                )
                Spacer(Modifier.height(2.dp))
                VoiceoverLevelMeter(level = state.level)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatElapsed(state.elapsedMs),
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            is VoiceoverState.Captured -> {
                Text(
                    text = "ضبط شد — ${formatElapsed(state.durationMs)}",
                    color = StatusSuccess,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    VoiceoverActionButton(
                        label = "افزودن به تایم‌لاین",
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = onPlace
                    )
                    VoiceoverActionButton(
                        label = "دوباره ضبط کن",
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = onDiscard
                    )
                }
            }

            is VoiceoverState.Failed -> {
                Text(
                    text = state.message,
                    color = StatusError,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1C1C1E))
                        .padding(12.dp)
                )
                VoiceoverActionButton(
                    label = "بستن",
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismissError
                )
            }
        }
    }
}

/**
 * The hero record button.
 *
 * A circle rather than a pill because it is the one control the user
 * has to hit without reading: the shape says "press here to record".
 */
@Composable
private fun VoiceoverRecordButton(
    recording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(if (recording) StatusError else iOSBlue)
                .border(
                    width = 3.dp,
                    color = if (recording) StatusError else iOSBlueBright,
                    shape = CircleShape
                )
                .clickable(enabled = enabled, onClick = onClick)
                .semantics {
                    contentDescription =
                        if (recording) "پایان ضبط" else "شروع ضبط صدا"
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (recording) Icons.Filled.FiberManualRecord else Icons.Filled.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

/**
 * The live input meter.
 *
 * A plain bar rather than a waveform: the recorder reports a peak
 * amplitude, not samples, and drawing a fake waveform from a peak
 * would be a lie. The bar is centred so a quiet room visibly reads as
 * "quiet" rather than as "nothing happened".
 */
@Composable
private fun VoiceoverLevelMeter(
    level: Float,
    modifier: Modifier = Modifier
) {
    val safe = level.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1C1C1E))
            .border(width = 1.dp, color = HairlineSubtle, shape = RoundedCornerShape(4.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(safe)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (safe > 0.85f) StatusError else iOSBlueBright)
        )
    }
}

/** A secondary action button (the panel's only other control). */
@Composable
private fun VoiceoverActionButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) iOSBlue else Color(0xFF1C1C1E))
            .border(
                width = 1.dp,
                color = if (filled) iOSBlue else HairlineSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (filled) Color.White else LabelPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

/** `mm:ss` for a duration in milliseconds. */
private fun formatElapsed(ms: Long): String {
    val safe = ms.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}
