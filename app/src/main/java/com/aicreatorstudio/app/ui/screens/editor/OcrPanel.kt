package com.aicreatorstudio.app.ui.screens.editor

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ai.AiToolOptions
import com.aicreatorstudio.app.ai.AiToolState
import com.aicreatorstudio.app.ai.AiToolStatus
import com.aicreatorstudio.app.ai.ocr.OcrAnalysisResult
import com.aicreatorstudio.app.ai.ocr.OcrLanguagePacks
import com.aicreatorstudio.app.ai.ocr.TesseractOcrEngine
import com.aicreatorstudio.app.model.OcrPanelResult
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSGreen
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.StatusWarning
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point so the OCR panel (a composable cannot
 * constructor-inject) can reach the app-scoped
 * [TesseractOcrEngine].
 *
 * 2026-10-20 — the panel needs the engine's REAL install state
 * (`installedOcrLanguages()`) so the language pills stop claiming
 * that a pack which is not on disk is "ready". The engine is a
 * `@Singleton`, so this entry point returns the very same instance
 * the AI-tool registry runs.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface OcrEngineEntryPoint {
    fun ocrEngine(): TesseractOcrEngine
}

/**
 * AI OCR dock sub-panel.
 *
 * Real wiring:
 *  - "دانلود" → [onEnsureModelReady] → engine.ensureModelReady
 *    (downloads + SHA-verifies the fas/ara/eng tessdata packs).
 *  - Language pills → the REAL install state of the fas/ara/eng
 *    tessdata packs (a missing pack is marked "نیاز به دانلود") and the
 *    user's language choice, which is sent to the engine with the
 *    run.
 *  - "استخراج متن" → [onRun] → TesseractOcrEngine.process
 *    (real Tesseract 5 inference on sampled frames).
 *  - Cancel → [onCancel] (coroutine cancel, mid-frame safe).
 *  - Result card → the best frame's text, editable; Copy → system
 *    clipboard; Apply → [onApply] persists onto the clip.
 *  - Reset → [onReset] clears staged + committed results.
 */
@Composable
fun OcrPanel(
    selectedClip: com.aicreatorstudio.app.model.VideoClip?,
    aiState: AiToolState,
    ocrResult: OcrPanelResult?,
    committedResult: com.aicreatorstudio.app.ai.ocr.OcrClipResult?,
    onRun: (AiToolOptions) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onApply: (text: String, timestampMs: Long, lineCount: Int, confidence: Float) -> Unit,
    modelStatusText: String = "",
    modelAvailable: Boolean = false,
    onEnsureModelReady: () -> Unit = {},
    /**
     * 2026-10-20 — the Tesseract language codes ("fas", "ara", "eng")
     * that are installed on this device. `null` = ask the engine
     * (through [OcrEngineEntryPoint]); an explicit list wins, which
     * keeps the panel usable without the Hilt graph.
     */
    installedLanguages: List<String>? = null
) {
    val clip = selectedClip
    var editedText by remember { mutableStateOf("") }
    var selectedFrameTs by remember { mutableStateOf(-1L) }
    var applied by remember { mutableStateOf(false) }

    // ---- Language pack state (2026-10-20) ----
    //
    // The three language pills used to be hard-coded to "ready", so
    // the panel claimed that Persian, Arabic AND English were
    // available even when only the Persian pack had been
    // downloaded. The truth comes from the engine, which knows which
    // *.traineddata files are really on disk.
    val context = LocalContext.current
    val engine: TesseractOcrEngine? = remember(context.applicationContext) {
        runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                OcrEngineEntryPoint::class.java
            ).ocrEngine()
        }.getOrNull()
    }
    // Re-read the install state whenever the model status changes (a
    // pack was just downloaded).
    val installedPacks: List<String> = remember(
        modelStatusText,
        modelAvailable,
        installedLanguages,
        engine
    ) {
        installedLanguages ?: engine?.installedOcrLanguages() ?: emptyList()
    }
    // `null` = the user has not picked yet -> every installed pack is
    // used. A non-null set is the user's explicit choice.
    var languageChoice by remember { mutableStateOf<Set<String>?>(null) }
    val defaultPacks: Set<String> =
        if (installedPacks.isEmpty()) OcrLanguagePacks.ALL_CODES.toSet()
        else installedPacks.toSet()
    val selectedPacks: Set<String> = (languageChoice ?: defaultPacks)
        .filter { installedPacks.isEmpty() || it in installedPacks }
        .toSet()
    // False = the engine could not be reached, so the install state
    // is UNKNOWN. An unknown pack is never announced as ready and
    // never announced as missing either.
    val installStateKnown: Boolean = installedLanguages != null || engine != null
    val missingPacks: List<String> =
        OcrLanguagePacks.ALL_CODES.filter { it !in installedPacks }
    val toggleLanguage: (String) -> Unit = toggle@{ code ->
        if (installStateKnown && code !in installedPacks) return@toggle
        val current = selectedPacks
        if (code in current) {
            // Keep at least one language selected: an empty set would
            // leave the Run button with nothing to send.
            if (current.size > 1) languageChoice = current - code
        } else {
            languageChoice = current + code
        }
    }

    // When a new OCR result lands, load the best frame's text into
    // the editor.
    //
    // 2026-10-20 — TWO FIXES:
    //
    //  1. The key was `ocrResult` — the WHOLE staged object. Pressing
    //     "Apply" mutates that object (`appliedText` / `appliedTimestampMs`),
    //     which produces a NEW, non-equal instance and re-fired this
    //     effect. The user's edited text was therefore wiped back to
    //     the raw OCR output a moment after they corrected it, and the
    //     button flipped from "اعمال شد ✓" back to "اعمال".
    //     Keying on `ocrResult?.frames` fixes it: the frame list only
    //     changes when a NEW run lands.
    //  2. The text is only auto-filled when the user has not edited
    //     it, so a re-run never destroys manual corrections.
    LaunchedEffect(ocrResult?.frames) {
        val best = ocrResult?.frames?.maxByOrNull { it.lines.size }
        if (best != null) {
            if (editedText.isBlank()) {
                editedText = best.lines.joinToString("\n") { it.text }
            }
            selectedFrameTs = best.timestampMs
            applied = false
        }
    }
    // Show the committed text when the panel opens with a prior result.
    LaunchedEffect(committedResult) {
        val committed = committedResult ?: return@LaunchedEffect
        if (editedText.isBlank() && ocrResult == null) {
            editedText = committed.text
            selectedFrameTs = committed.timestampMs
            applied = true
        }
    }

    // NOTE: no root verticalScroll here — the host SubPanelContent
    // already wraps every panel in a scrollable Column, and a nested
    // scrollable inside it is measured with infinite height and
    // crashes (IllegalStateException in checkScrollableContainerConstraints).
    Column(modifier = Modifier.fillMaxWidth()) {

        // ---- Header ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                iOSBlue.copy(alpha = 0.6f),
                                iOSPurple.copy(alpha = 0.6f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.DocumentScanner,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "AI OCR",
                style = MaterialTheme.typography.labelSmall,
                color = iOSBlueBright,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                    .clickable(enabled = clip != null) { onReset() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "بازنشانی",
                        tint = LabelSecondary,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val installedLabel = if (installedPacks.isEmpty()) {
            "—"
        } else {
            installedPacks.joinToString(", ")
        }
        Text(
            // 2026-10-20 — the old line named the three languages
            // unconditionally. It now lists the packs that are really
            // installed, so the header can never promise a language
            // the device does not have.
            text = "موتور: Tesseract 5 (آفلاین) · زبان‌های نصب‌شده: $installedLabel · RTL",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(10.dp))

        if (clip == null) {
            EmptyClipHint(text = "برای استخراج متن، ابتدا یک کلیپ را انتخاب کنید.")
            return@Column
        }

        // ---- Model state ----
        if (!modelAvailable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "بسته زبانی فارسی نصب نیست.",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (modelStatusText.isNotBlank()) {
                        Text(
                            text = modelStatusText,
                            color = LabelSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(iOSBlue)
                        .clickable { onEnsureModelReady() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "دانلود",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ---- Language pack status + selection ----
        //
        // 2026-10-20 — the three pills were hard-coded to "ready", so
        // the panel claimed every language was available even when
        // only the Persian pack was installed. They now show the
        // REAL install state, mark a missing pack as
        // "نیاز به دانلود" and double as the user's language selection — the
        // chosen set is sent to the engine with the run.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LanguagePill(
                label = "فارسی",
                ready = OcrLanguagePacks.FAS in installedPacks,
                selected = OcrLanguagePacks.FAS in selectedPacks,
                missing = installStateKnown && OcrLanguagePacks.FAS !in installedPacks,
                onClick = { toggleLanguage(OcrLanguagePacks.FAS) }
            )
            LanguagePill(
                label = "عربی",
                ready = OcrLanguagePacks.ARA in installedPacks,
                selected = OcrLanguagePacks.ARA in selectedPacks,
                missing = installStateKnown && OcrLanguagePacks.ARA !in installedPacks,
                onClick = { toggleLanguage(OcrLanguagePacks.ARA) }
            )
            LanguagePill(
                label = "English",
                ready = OcrLanguagePacks.ENG in installedPacks,
                selected = OcrLanguagePacks.ENG in selectedPacks,
                missing = installStateKnown && OcrLanguagePacks.ENG !in installedPacks,
                onClick = { toggleLanguage(OcrLanguagePacks.ENG) }
            )
        }
        Spacer(Modifier.height(6.dp))
        // The combination that will actually be loaded — the user must see
        // it before the run.
        Text(
            text = "زبان‌های انتخاب‌شده: " + selectedPacks.joinToString("+"),
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        // A pack that is missing can be downloaded from here: the
        // pills say "نیاز به دانلود", so the action belongs next to them.
        if (installStateKnown && missingPacks.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onEnsureModelReady() }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "دانلود",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        AiToolProgress(aiState)
        Spacer(Modifier.height(12.dp))

        AiToolRunButton(
            label = "استخراج متن",
            isProcessing = aiState.status == AiToolStatus.Processing,
            enabled = com.aicreatorstudio.app.ai.AiToolUiPolicy.generateEnabled(
                aiState.status, modelAvailable
            ) && selectedPacks.isNotEmpty(),
            onRun = {
                onRun(
                    AiToolOptions(
                        // 2026-10-20 — the language selection travels
                        // in the EXPLICIT `ocrLanguages` set, which is
                        // what the engine prefers.
                        //
                        // `strength` still carries the bit mask as a
                        // fallback for older call sites; it used to be
                        // a 0..1 ratio that the engine mapped to a
                        // fixed combination, and the panel always sent
                        // 1f, so the documented mapping was dead code
                        // and the user could not choose the languages.
                        ocrLanguages = selectedPacks,
                        strength = OcrLanguagePacks.maskOf(selectedPacks).toFloat(),
                        commitToClip = false
                    )
                )
            },
            onCancel = onCancel
        )
        Spacer(Modifier.height(12.dp))

        // ---- Result ----
        if (ocrResult != null && ocrResult.frames.isNotEmpty()) {
            val frame = ocrResult.frames.firstOrNull { it.timestampMs == selectedFrameTs }
                ?: ocrResult.frames.maxByOrNull { it.lines.size }
            if (frame != null) {
                // Frame selector (frames that yielded text).
                if (ocrResult.frames.size > 1) {
                    Text(
                        text = "فریم‌های دارای متن: ${ocrResult.frames.size}",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ocrResult.frames.take(6).forEach { f ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (f.timestampMs == frame.timestampMs) iOSBlue.copy(alpha = 0.2f)
                                        else Color.White.copy(alpha = 0.04f)
                                    )
                                    .border(
                                        1.dp,
                                        if (f.timestampMs == frame.timestampMs) iOSBlueBright else HairlineSubtle,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { selectedFrameTs = f.timestampMs }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = formatTs(f.timestampMs),
                                    color = if (f.timestampMs == frame.timestampMs) iOSBlueBright else LabelSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Editable transcript with per-line confidence bars.
                Text(
                    text = "متن استخراج‌شده (قابل ویرایش)",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        textDirection = TextDirection.Content
                    )
                )
                Spacer(Modifier.height(6.dp))
                frame.lines.take(5).forEach { line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${line.confidencePercent}٪",
                            color = if (line.confidence > 0.7f) iOSGreen else LabelSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(38.dp)
                        )
                        Text(
                            text = line.text,
                            color = LabelSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Copy + Apply row.
                val clipboard = LocalClipboardManager.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable { clipboard.setText(AnnotatedString(editedText)) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = null,
                                tint = LabelPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "کپی",
                                color = LabelPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(
                                if (applied) Brush.horizontalGradient(
                                    listOf(iOSGreen.copy(alpha = 0.8f), iOSGreen)
                                )
                                else Brush.horizontalGradient(
                                    listOf(iOSBlue, iOSBlueBright, iOSPurple.copy(alpha = 0.85f))
                                )
                            )
                            .clickable {
                                if (editedText.isNotBlank()) {
                                    val meanConf = if (frame.lines.isEmpty()) 0f
                                    else frame.lines.map { it.confidence }.average().toFloat()
                                    onApply(editedText, frame.timestampMs, frame.lines.size, meanConf)
                                    applied = true
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (applied) "اعمال شد ✓" else "اعمال",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (applied) {
                    Text(
                        text = "متن روی کلیپ ذخیره شد.",
                        color = iOSGreen,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        } else if (aiState.status == AiToolStatus.Completed) {
            Text(
                text = "متنی در این کلیپ پیدا نشد.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        } else if (committedResult != null) {
            // Previously committed result (panel reopened).
            Text(
                text = "نتیجه ذخیره‌شده (${formatTs(committedResult.timestampMs)}):",
                color = LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = committedResult.text,
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * One language chip.
 *
 * 2026-10-20 — the chip used to be a passive "ready / not ready"
 * badge whose state was hard-coded by the caller. It is now the
 * user's language selection:
 *
 *  - [ready] — the pack is really installed (green dot).
 *  - [missing] — the pack is NOT installed: the dot turns amber and
 *    the chip says "نیاز به دانلود" (an existing theme token is reused — no
 *    new colour is invented).
 *  - [selected] — the language is part of the combination that will be
 *    sent to the engine (accent border + fill).
 */
@Composable
private fun LanguagePill(
    label: String,
    ready: Boolean,
    selected: Boolean,
    missing: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (selected) iOSBlue.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(
                1.dp,
                if (selected) iOSBlueBright else HairlineSubtle,
                RoundedCornerShape(percent = 50)
            )
            .clickable(enabled = !missing) { onClick() }
            // 2026-10-20 — the chip is now a control, so it needs a role and
            // a state for screen readers: "selected" / "not selected".
            .semantics {
                role = Role.Checkbox
                stateDescription = if (selected) "انتخاب‌شده" else "انتخاب نشده"
            }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        missing -> StatusWarning
                        ready -> iOSGreen
                        else -> LabelSecondary
                    }
                )
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            color = if (missing) LabelSecondary else LabelPrimary,
            style = MaterialTheme.typography.labelSmall
        )
        if (missing) {
            Spacer(Modifier.width(4.dp))
            Text(
                // 2026-10-20 — the honest label for a pack that is not on
                // disk. The pill must never claim "ready".
                text = "نیاز به دانلود",
                color = StatusWarning,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun formatTs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
