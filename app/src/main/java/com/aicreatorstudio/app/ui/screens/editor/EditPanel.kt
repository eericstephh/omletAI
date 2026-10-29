package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.CropPreset
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.ai.AiModelStatus
import com.aicreatorstudio.app.ai.SubtitleGenerationState
import com.aicreatorstudio.app.ai.WhisperModelSpec
import com.aicreatorstudio.app.model.VideoClip

/**
 * Square icon-and-label button used in the bottom toolbar.
 *
 * Doubles as the visual primitive for both the toolbar items and any
 * inline sub-panel grids (e.g. the animation picker).
 *
 * @param isSelected highlights the button with the primary colour
 *   when its associated sub-panel is currently open
 * @param isDestructive recolours the icon/label with the error
 *   palette (for Delete)
 */
@Composable
fun ToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isDestructive: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    when {
                        isDestructive -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    }
                )
                .then(
                    when {
                        isDestructive -> Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                            RoundedCornerShape(16.dp)
                        )
                        isSelected -> Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            RoundedCornerShape(16.dp)
                        )
                        else -> Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    isDestructive -> MaterialTheme.colorScheme.error
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp),
            color = when {
                isDestructive -> MaterialTheme.colorScheme.error
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * 3-column grid of [ClipAnimation] thumbnails. Tapping one applies the
 * animation to [selectedClip] via [onAnimationSelected]. The currently
 * selected animation is highlighted.
 */
@Composable
fun AnimationPicker(
    selectedClip: VideoClip?,
    onAnimationSelected: (ClipAnimation) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Animations",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(200.dp)
        ) {
            items(ClipAnimation.entries) { animation ->
                MotionThumbnail(
                    animation = animation,
                    isSelected = selectedClip?.animation == animation,
                    onClick = { onAnimationSelected(animation) },
                    modifier = Modifier.padding(4.dp)
                )
            }
        }
    }
}

/**
 * 3-column grid of [CropPreset] chips. Tapping one applies the crop
 * preset to [selectedClip] via [onCropSelected].
 */
@Composable
fun CropPicker(
    selectedClip: VideoClip?,
    onCropSelected: (CropPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Crop Presets",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.height(200.dp)
        ) {
            items(CropPreset.entries) { preset ->
                CropPresetChip(
                    preset = preset,
                    isSelected = selectedClip?.cropPreset == preset,
                    onClick = { onCropSelected(preset) }
                )
            }
        }
    }
}

@Composable
private fun CropPresetChip(
    preset: CropPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .border(
                width = if (isSelected) 1.5.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else Color(0x00000000),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = preset.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * AI subtitle generation card. The Generate button calls
 * [onGenerateSubtitles] with the currently-selected font and
 * Whisper model. The Export SRT button calls [onExportSrt] after
 * AI generation has produced transcript data. The Import SRT
 * button (PHASE B4) calls [onImportSrt] to launch the SAF
 * document picker for an external `.srt` file. [srtImportState]
 * is rendered as a status line so the user sees the real
 * import pipeline progress (Reading / Parsing / Validating /
 * Importing / Completed / Error) — no fake progress.
 */
@Composable
fun AISubtitleCard(
    selectedClip: VideoClip?,
    state: SubtitleGenerationState,
    hasGeneratedSubtitles: Boolean,
    activeTranscriptionEngine: String?,
    selectedModel: WhisperModelSpec,
    /**
     * 2026-10-10 — true when a saved Groq API key makes the
     * user-key cloud engine usable. When false and the cloud row
     * is selected, a Persian warning + "switch to offline" button
     * is shown so the user is never left guessing why Generate
     * would fail.
     */
    hasGroqApiKey: Boolean = false,
    aiModelStatuses: Map<WhisperModelSpec, AiModelStatus>,
    // 2026-09-06 — per-model download progress (0..1); a row renders
    // its progress bar when its id is present in this map.
    aiModelDownloadProgress: Map<String, Float> = emptyMap(),
    onDownloadModel: (WhisperModelSpec) -> Unit = {},
    selectedTextOverlay: TextOverlay?,
    onSelectModel: (WhisperModelSpec) -> Unit,
    // 2026-09-06 — language picker: seed value + change callback.
    initialLanguage: com.aicreatorstudio.app.ai.SubtitleLanguage =
        com.aicreatorstudio.app.ai.SubtitleLanguage.PERSIAN,
    onSelectLanguage: (com.aicreatorstudio.app.ai.SubtitleLanguage) -> Unit = {},
    // 2026-09-09 — accuracy mode picker + custom dictionary editor.
    initialAccuracyMode: com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode =
        com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode.BALANCED,
    onSelectAccuracyMode: (com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode) -> Unit = {},
    // 2026-09-15 — Premiere-style words-per-caption picker.
    // 0 = "خودکار" (off; readability rules alone), else 1..12 words.
    initialWordsPerCaption: Int = 0,
    onSelectWordsPerCaption: (Int) -> Unit = {},
    savedDictionaryText: String = "",
    onSaveDictionary: (String) -> Unit = {},
    onGenerateSubtitles: (PersianFont) -> Unit,
    // 2026-10-05 — AUTO-CAPTION ONE-TAP: pick a template chip, hit the
    // button — generation runs and every new caption is styled with that
    // template (persisted via the Studio path). Default no-op keeps
    // existing call sites compiling.
    onAutoCaption: (templateId: String?) -> Unit = {},
    onCancel: () -> Unit,
    onExportSrt: () -> Unit,
    onImportSrt: () -> Unit = {},
    srtImportState: com.aicreatorstudio.app.ai.SrtImportState =
        com.aicreatorstudio.app.ai.SrtImportState.Idle,
    // 2026-09-07 AUTO-DISMISS — the Completed/Error status line now
    // self-clears via the LaunchedEffect below (the callback used to
    // be declared but never invoked, so the status persisted forever).
    onConsumeSrtImportState: () -> Unit = {},
    onUpdateTextOverlay: (String, String) -> Unit,
    onDeleteTextOverlay: (String) -> Unit,
    onDeleteAllAiSubtitles: () -> Unit,
    onClearSelection: () -> Unit,
    // PHASE B5 — production subtitle styling callbacks.
    onApplySubtitleStyle: (String, com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = { _, _ -> },
    onApplySubtitleStyleToAll: (com.aicreatorstudio.app.model.SubtitleStyle) -> Unit = {},
    hasSubtitles: Boolean = false,
    /** 2026-09-11 — what the next Generate will transcribe: the
     *  tapped audio file's name, or the video clip's. Null hides
     *  the chip. */
    generationSourceLabel: String? = null,
    modifier: Modifier = Modifier
) {
    var selectedFont by remember { mutableStateOf(PersianFont.VAZIR) }
    // 2026-09-06 — spoken-language selection, seeded from the uiState.
    // 2026-09-07 KEY FIX — keyed on initialLanguage so an external
    // change of the uiState language while the card is composed keeps
    // the chip row in sync instead of diverging silently.
    var selectedLanguage by remember(initialLanguage) { mutableStateOf(initialLanguage) }
    val downloadProgressMap = aiModelDownloadProgress
    // PHASE B3 — regenerate-replace confirmation. The user must
    // confirm before the second run overwrites the first set of
    // AI subtitles; user-added Text overlays are not affected
    // either way (per `EditorViewModel.generateSubtitles`), so
    // the dialog just tells them how many will be replaced.
    var showRegenerateDialog by remember { mutableStateOf(false) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    // Inline editor state for the selected AI subtitle. Initialised
    // from the selected overlay's text; the user can edit, then Save
    // or Cancel. Saving calls `onUpdateTextOverlay(id, newText)`
    // which uses the brief's invariant `overlay.copy(text = ...)`
    // — so id, start/end, words, font, karaoke are all preserved.
    var editBuffer by remember(selectedTextOverlay?.id, selectedTextOverlay?.text) {
        mutableStateOf(selectedTextOverlay?.text ?: "")
    }
    // PHASE B5 — collapsible "Style" panel below the inline
    // editor. Visible whenever the user has any subtitle
    // (AI or SRT) on the timeline; the panel itself runs
    // even if no overlay is currently selected (it falls
    // back to SubtitleStyle.Default so the user can preview
    // a preset before selecting an overlay to apply it to).
    var showStylePanel by rememberSaveable { mutableStateOf(false) }
    // 2026-09-06 — quick style chips beside the Generate button.
    var showQuickStyles by rememberSaveable { mutableStateOf(false) }

    // 2026-09-07 AUTO-DISMISS — Completed/Error import status lines
    // self-dismiss after 4 s (matching the doc contract); Idle and
    // the transient progress phases never trigger the callback.
    LaunchedEffect(srtImportState) {
        val st = srtImportState
        if (st is com.aicreatorstudio.app.ai.SrtImportState.Completed ||
            st is com.aicreatorstudio.app.ai.SrtImportState.Error
        ) {
            kotlinx.coroutines.delay(4_000L)
            onConsumeSrtImportState()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 2026-09-11 — TRANSCRIPTION SOURCE chip: shows exactly
            // what the next Generate run will transcribe — the
            // tapped AUDIO file (selectedAudioClipId) or the video
            // clip. Import already pairs an audio file to every
            // video; tapping it on the VIDEO AUDIO lane routes
            // generation to it exclusively.
            generationSourceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                ) {
                    Text(
                        text = "منبع رونویسی",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            // 2026-10-05 — AUTO-CAPTION ONE-TAP. The primary path: pick a
            // template chip and hit the button; generation runs through
            // the standard gated state machine and every new caption is
            // styled with the chosen template (persisted via the Studio
            // path, so the choice survives regenerations).
            var autoTemplateId by rememberSaveable {
                mutableStateOf(
                    com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.ALL
                        .firstOrNull()?.id ?: ""
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    .padding(10.dp)
            ) {
                Text(
                    text = "Auto-Caption یک‌لمسی",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "تمپلیت را انتخاب کن، دکمه را بزن — زیرنویس با همین استایل ساخته می‌شود",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    com.aicreatorstudio.app.model.BuiltInSubtitleTemplates.ALL
                        .take(8)
                        .forEach { template ->
                            val isSelected = autoTemplateId == template.id
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
                                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.06f)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { autoTemplateId = template.id }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = template.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onAutoCaption(autoTemplateId.takeIf { it.isNotBlank() }) },
                    enabled = state.isTerminal,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("در حال ساخت…")
                    } else {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("ساخت زیرنویس خودکار با این استایل")
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = "زیرنویس فارسی با هوش مصنوعی",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    // 2026-10-10 — show the engine the user SELECTED
                    // (not the post-hoc activeTranscriptionEngine,
                    // which is only set after a successful run and
                    // used to be inferred from key existence).
                    Text(
                        text = "موتور انتخابی: ${selectedModel.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
                // PHASE B3 — Generate button label depends on whether
                // a previous run produced any AI subtitles. First
                // time: "Generate". After success: "Generate again"
                // (and the click goes through the confirm dialog).
                val buttonLabel = if (hasGeneratedSubtitles) "Generate again" else "Generate"
                val isRunning = state.isRunning
                Button(
                    onClick = {
                        if (hasGeneratedSubtitles) {
                            showRegenerateDialog = true
                        } else {
                            onGenerateSubtitles(selectedFont)
                        }
                    },
                    enabled = state.isTerminal,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(buttonLabel)
                    }
                }
                // 2026-09-06 — quick "subtitle text style" toggle,
                // right beside the Generate (sub) button. Expands the
                // preset chip row below without scrolling to the
                // full style panel at the bottom of the card.
                IconButton(
                    onClick = { showQuickStyles = !showQuickStyles },
                    enabled = state.isTerminal,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Palette,
                        contentDescription = "Subtitle style",
                        tint = if (showQuickStyles) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 2026-09-06 — quick Persian-subtitle style presets.
            // One tap paints the preset onto the selected subtitle
            // (when one is selected) or onto every subtitle on the
            // timeline. Chips render a live mini-preview of each
            // style so the look is obvious before tapping.
            if (showQuickStyles) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "استایل تکست زیرنویس فارسی",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    val styleTarget = selectedTextOverlay
                    val activeStyle = styleTarget?.subtitleStyle
                    SubtitleStyle.AllPresets.forEach { (name, preset) ->
                        SubtitleQuickStyleChip(
                            name = name,
                            style = preset,
                            isSelected = activeStyle == preset,
                            enabled = styleTarget != null || hasSubtitles,
                            onClick = {
                                val target = selectedTextOverlay
                                if (target != null) {
                                    onApplySubtitleStyle(target.id, preset)
                                } else {
                                    onApplySubtitleStyleToAll(preset)
                                }
                            }
                        )
                    }
                }
                if (selectedTextOverlay == null && !hasSubtitles) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "ابتدا زیرنویس بسازید یا یکی را انتخاب کنید",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // ----- AI Model picker with install status pills -----
            // PHASE B3 — each radio row now has a pill that tells
            // the user the real install status of that model. The
            // pill text is locked by AiModelStatusTest.
            // 2026-10-10 — section header is Persian; the cloud row
            // is key-aware (hasGroqApiKey) so it never shows
            // "Cloud" ready when no key is saved.
            Spacer(Modifier.height(14.dp))
            Text(
                text = "موتور رونویسی",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WhisperModelSpec.entries.forEach { spec ->
                    val isSelected = spec == selectedModel
                    val status = aiModelStatuses[spec] ?: AiModelStatus.NOT_INSTALLED
                    // RAM is INFORMATIONAL ONLY. Every row stays
                    // visible, selectable, and downloadable
                    // regardless of the device's free RAM; the
                    // row shows the documented RAM requirement
                    // as information (see
                    // modelSizeAndRamHint). Only a real native
                    // load failure blocks inference, and that
                    // surfaces as a clear error — never a
                    // disabled row, never a silent model switch.
                    val enableRow = state.isTerminal
                    ModelPickerRow(
                        spec = spec,
                        isSelected = isSelected,
                        isEnabled = enableRow,
                        status = status,
                        hasGroqApiKey = hasGroqApiKey,
                        downloadProgress = downloadProgressMap[spec.aiSpec().id],
                        onDownload = { onDownloadModel(spec) },
                        onClick = { onSelectModel(spec) }
                    )
                }
            }

            // 2026-10-10 — KEY MISSING WARNING: when cloud is the
            // selected engine but no Groq key is saved, explain the
            // exact problem in Persian AND offer a one-tap switch
            // to the offline engine (deliberate mode change, never
            // silent).
            if (selectedModel.isCloud && !hasGroqApiKey) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "کلید Groq تنظیم نشده است",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "برای زیرنویس ابری ابتدا کلید را در «تنظیمات ← اتصال هوش مصنوعی» وارد کنید، یا موتور آفلاین (Whisper Small) را انتخاب کنید.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onSelectModel(WhisperModelSpec.SMALL) },
                        enabled = state.isTerminal,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("انتخاب موتور آفلاین")
                    }
                }
            }

            // 2026-09-06 — subtitle SPOKEN LANGUAGE picker. The
            // multilingual Whisper checkpoints (Medium / Large-v3)
            // natively support every entry; the selection is the
            // language hint passed to whisper.cpp so detection is
            // instant and word timings stay stable.
            Spacer(Modifier.height(10.dp))
            Text(
                text = "زبان گفتار (Speech language)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                com.aicreatorstudio.app.ai.SubtitleLanguage.entries.forEach { lang ->
                    val isSelected = lang == selectedLanguage
                    Text(
                        text = lang.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(enabled = state.isTerminal) {
                                selectedLanguage = lang
                                onSelectLanguage(lang)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // 2026-09-09 — ACCURACY MODE (real decoder parameters:
            // beam size + temperature fallback). Fast = greedy for
            // clean audio on weak devices; Balanced = beam 5 default;
            // Accurate = beam 8 + wide fallback for hard audio.
            var selectedAccuracy by remember(initialAccuracyMode) {
                mutableStateOf(initialAccuracyMode)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "دقت رونویسی (Accuracy)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                com.aicreatorstudio.app.ai.WhisperEngine.AccuracyMode.entries
                    .forEach { mode ->
                        val isSelected = mode == selectedAccuracy
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable(enabled = state.isTerminal) {
                                    selectedAccuracy = mode
                                    onSelectAccuracyMode(mode)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
            }

            // 2026-09-15 — WORDS PER CAPTION (Premiere-style). When
            // set (1..12), every generated caption carries at most
            // this many words — the segmenter chunks the transcript
            // in speech order at this fixed size before the
            // readability caps run. "خودکار" (0) turns the cap off
            // and lets chars/duration/CPS alone shape the captions.
            // Applied on the NEXT Generate run; existing captions
            // are untouched.
            var selectedWordsPerCaption by remember(initialWordsPerCaption) {
                mutableStateOf(initialWordsPerCaption)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "تعداد کلمه در هر زیرنویس (Words per caption)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                // "خودکار" first (the default), then 1..12 — the
                // same chip pattern as the accuracy/language rows.
                val captionWordOptions = listOf(
                    0 to "خودکار",
                    1 to "۱", 2 to "۲", 3 to "۳", 4 to "۴", 5 to "۵",
                    6 to "۶", 7 to "۷", 8 to "۸", 9 to "۹", 10 to "۱۰",
                    11 to "۱۱", 12 to "۱۲"
                )
                captionWordOptions.forEach { (count, label) ->
                    val isSelected = count == selectedWordsPerCaption
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(enabled = state.isTerminal) {
                                selectedWordsPerCaption = count
                                onSelectWordsPerCaption(count)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // 2026-09-09 — CUSTOM DICTIONARY: brand names / people /
            // jargon injected into the decoder's initial prompt so the
            // model emits the correct spelling instead of guessing.
            var showDictionaryEditor by remember { mutableStateOf(false) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDictionaryEditor = !showDictionaryEditor },
                enabled = state.isTerminal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showDictionaryEditor) "بستن دیکشنری سفارشی" else "دیکشنری سفارشی (اسامی/برندها)")
            }
            if (showDictionaryEditor) {
                var dictBuffer by remember(savedDictionaryText) {
                    mutableStateOf(savedDictionaryText)
                }
                Column {
                    OutlinedTextField(
                        value = dictBuffer,
                        onValueChange = { dictBuffer = it },
                        placeholder = { Text("هر واژه در یک خط:\nChatGPT\nAICreator\nEric") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 90.dp, max = 160.dp),
                        minLines = 3,
                        maxLines = 6
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                    ) {
                        Button(
                            onClick = { onSaveDictionary(dictBuffer) },
                            enabled = state.isTerminal
                        ) {
                            Text("ذخیره")
                        }
                        OutlinedButton(
                            onClick = { dictBuffer = "" ; onSaveDictionary("") },
                            enabled = state.isTerminal && dictBuffer.isNotEmpty()
                        ) {
                            Text("خالی کردن")
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Subtitle font",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                PersianFont.entries.forEach { font ->
                    val isSelected = font == selectedFont
                    Text(
                        text = font.displayName,
                        fontFamily = font.toFontFamily(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(enabled = state.isTerminal) { selectedFont = font }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Export SRT — enabled as soon as the recognizer has produced
            // at least one subtitle overlay. The actual save uses the
            // Android "Save as" file picker (ACTION_CREATE_DOCUMENT) so
            // the user picks the destination and we never write to a
            // hardcoded path.
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onExportSrt,
                enabled = hasGeneratedSubtitles && state.isTerminal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Export SRT")
            }

            // PHASE B4 — Import SRT. Always available (does not
            // require prior AI generation) and never blocks the
            // AI subtitle pipeline; the SAF picker can be opened
            // regardless of the subtitle state. The button is
            // disabled only while an import is already in
            // flight so two rapid taps can't double-fire the
            // picker.
            val isImporting = srtImportState !is com.aicreatorstudio.app.ai.SrtImportState.Idle &&
                srtImportState !is com.aicreatorstudio.app.ai.SrtImportState.Completed &&
                srtImportState !is com.aicreatorstudio.app.ai.SrtImportState.Error
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onImportSrt,
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Import SRT")
            }
            // PHASE B4 — live status of the SRT import pipeline.
            // Each state maps to a real, observable phase (no fake
            // progress) and is dismissed either by the user
            // (Completed/Error disappear after a short delay) or
            // by the next import click.
            srtImportState.let { st ->
                when (st) {
                    is com.aicreatorstudio.app.ai.SrtImportState.Reading ->
                        Text(
                            "Reading SRT…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    is com.aicreatorstudio.app.ai.SrtImportState.Parsing ->
                        Text(
                            "Parsing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    is com.aicreatorstudio.app.ai.SrtImportState.Validating -> {
                        val issueCount = st.issues.size
                        Text(
                            if (issueCount == 0) "Validating…"
                            else "Validating… ($issueCount parser issue${if (issueCount == 1) "" else "s"})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    is com.aicreatorstudio.app.ai.SrtImportState.Importing ->
                        Text(
                            "Adding to timeline…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    is com.aicreatorstudio.app.ai.SrtImportState.Completed -> {
                        val parts = mutableListOf<String>()
                        parts += "Imported ${st.overlayCount} subtitle${if (st.overlayCount == 1) "" else "s"}"
                        if (st.skippedDuplicates > 0) {
                            parts += "${st.skippedDuplicates} duplicate${if (st.skippedDuplicates == 1) "" else "s"} skipped"
                        }
                        if (st.issues.isNotEmpty()) {
                            parts += "${st.issues.size} parser issue${if (st.issues.size == 1) "" else "s"}"
                        }
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    is com.aicreatorstudio.app.ai.SrtImportState.Error ->
                        Text(
                            "Import failed: ${st.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    is com.aicreatorstudio.app.ai.SrtImportState.Idle -> Unit
                }
            }

            // PHASE B3 — "Delete all AI subtitles" affordance.
            // Only enabled when there is something to delete. Goes
            // through a confirm dialog so an accidental tap does
            // not wipe the run. User-added Text overlays are not
            // affected either way; the dialog spells that out.
            if (hasGeneratedSubtitles) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDeleteAllDialog = true },
                    enabled = state.isTerminal,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Delete all AI subtitles")
                }
            }

            // PHASE B5 — production subtitle styling. The
            // entry point is a single "Style" button that
            // expands the panel inline (same pattern as the
            // editor above). The panel renders whenever at
            // least one subtitle exists on the timeline; the
            // "Apply to all" entry shows in that case so the
            // user can paint a preset across every subtitle.
            if (hasSubtitles || selectedTextOverlay != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showStylePanel = !showStylePanel },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (showStylePanel) "Hide subtitle style" else "Style subtitles")
                }
                if (showStylePanel) {
                    SubtitleStylePanel(
                        initialStyle = selectedTextOverlay?.subtitleStyle,
                        onApplyToSelected = { newStyle ->
                            selectedTextOverlay?.let {
                                onApplySubtitleStyle(it.id, newStyle)
                            }
                        },
                        onApplyToAll = { newStyle ->
                            onApplySubtitleStyleToAll(newStyle)
                        },
                        showApplyToAll = hasSubtitles
                    )
                }
            }

            // PHASE B3 — State machine label + real progress
            // indicator. The label is the state machine's
            // `userMessage` (a Persian string the ViewModel owns).
            // The progress bar is only shown when the state has a
            // real 0..1 fraction. Cancelling and Error states
            // surface the corresponding text without any
            // progress bar (the brief forbids fake progress).
            // 2026-09-12 CLOUD — ProcessingByWhisper shows an
            // INDETERMINATE bar: the model runs server-side, so
            // no real client-side percentage exists.
            if (state != SubtitleGenerationState.Idle) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.userMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = if (state is SubtitleGenerationState.Error)
                        FontWeight.SemiBold else FontWeight.Normal
                )
                val fraction = state.currentProgressFraction
                if (fraction != null) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (state.isIndeterminate) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // PHASE B3 — Cancel button while a generation is in
            // flight. The cancellation is window-boundary-honest
            // (the Whisper pipeline finishes the current 30-s
            // window before honouring the cancel), so the button
            // stays available until the state actually drops back
            // to Idle.
            if (state.isRunning) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel")
                }
            }

            if (selectedClip == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    // 2026-10-10 — Persian (the app is Persian-first); the
                    // previous English hint sat inside an otherwise-Persian
                    // subtitle tool. Matches resolveSubtitleSourceWindow's
                    // "first clip" fallback.
                    text = "بدون انتخاب کلیپ — اولین ویدیوی پروژه استفاده می‌شود",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }

    // ----- Regenerate confirmation dialog -----
    if (showRegenerateDialog) {
        AlertDialog(
            onDismissRequest = { showRegenerateDialog = false },
            title = { Text("Generate again?") },
            text = {
                Text(
                    "This will replace the existing AI-generated subtitles " +
                        "with a fresh Whisper pass. " +
                        "Unrelated Text clips will be kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateDialog = false
                    onGenerateSubtitles(selectedFont)
                }) { Text("Replace") }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ----- Delete-all confirmation dialog -----
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Delete all AI subtitles?") },
            text = {
                Text(
                    "All AI-generated subtitle clips will be removed. " +
                        "Unrelated Text clips will be kept."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAllDialog = false
                    onDeleteAllAiSubtitles()
                }) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * Single radio row in the model picker. Owns the
 * install-status pill that locks the "Ready / Download /
 * Downloading… / Failed" labels (see AiModelStatusTest).
 *
 * Every row is always visible and selectable. LOCAL rows are
 * downloadable; the CLOUD row never renders download affordances
 * (2026-09-13 CLOUD FIX — status is CLOUD_READY and the size
 * hint is cloud-specific, never Small's file size).
 * RAM is shown as information only (see [modelSizeAndRamHint]);
 * it never disables the row.
 */
@Composable
private fun ModelPickerRow(
    spec: WhisperModelSpec,
    isSelected: Boolean,
    isEnabled: Boolean,
    status: AiModelStatus,
    /**
     * 2026-10-10 — when false, the CLOUD row's pill reflects the
     * missing Groq key instead of always showing CLOUD_READY.
     */
    hasGroqApiKey: Boolean = true,
    downloadProgress: Float?,
    onDownload: () -> Unit,
    onClick: () -> Unit
) {
    val cloudNeedsKey = spec.isCloud && !hasGroqApiKey
    val pillColor = when {
        cloudNeedsKey -> MaterialTheme.colorScheme.error
        status == AiModelStatus.INSTALLED -> MaterialTheme.colorScheme.tertiary
        status == AiModelStatus.DOWNLOADING -> MaterialTheme.colorScheme.secondary
        status == AiModelStatus.FAILED -> MaterialTheme.colorScheme.error
        status == AiModelStatus.CLOUD_READY -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.RadioButton(
                selected = isSelected,
                onClick = null,
                enabled = isEnabled,
                colors = androidx.compose.material3.RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.onPrimary,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = spec.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    // Install-status pill. Background tints with
                    // the status; text is the locked pillLabel
                    // string. Keeps the row compact.
                    // 2026-10-10 — the CLOUD row shows a UI-local
                    // «کلید لازم» pill when no Groq key is saved,
                    // so it never claims CLOUD_READY falsely.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(pillColor)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (cloudNeedsKey) "کلید لازم" else status.pillLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Text(
                    text = spec.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Show the model file size + RAM requirement on
                // every LOCAL row so the user can tell Medium
                // (1.5 GB) from Small (488 MB) at a glance.
                // INFORMATIONAL ONLY — it never disables the row.
                // 2026-09-13 CLOUD FIX — the cloud row shows a
                // cloud-specific hint instead; spec.aiSpec() is
                // meaningless for it (there is no local file, and
                // the pre-fix code rendered Small's "≈ 466 MB
                // دانلود" on the cloud row).
                Text(
                    text = if (spec.isCloud) {
                        if (hasGroqApiKey) {
                            "پردازش ابری (Groq) — نیاز به اینترنت و کلید API · بدون دانلود مدل"
                        } else {
                            "پردازش ابری (Groq) — کلید تنظیم نشده · در تنظیمات کلید را وارد کنید"
                        }
                    } else {
                        modelSizeAndRamHint(spec)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }
            // 2026-09-06 — explicit Download button for rows that are
            // not yet installed. Generate on a missing model would
            // download implicitly; this makes the download FIRST-CLASS
            // so the user can pre-fetch the model without starting a
            // generation run.
            // 2026-09-13 CLOUD FIX — the cloud row NEVER shows a
            // download button: there is no file to download (the
            // model runs on Groq's cloud). Pre-fix, this row's
            // status was derived from the Small catalog mapping
            // and rendered a "دانلود" button that actually
            // downloaded 488 MB of Whisper Small.
            if (!spec.isCloud &&
                status != AiModelStatus.INSTALLED &&
                status != AiModelStatus.DOWNLOADING
            ) {
                Spacer(Modifier.width(8.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                            RoundedCornerShape(50)
                        )
                        .clickable(enabled = isEnabled) { onDownload() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "دانلود",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        // 2026-09-06 — REAL download progress bar for this row while
        // its model is being fetched (0..1 from the installer).
        if (status == AiModelStatus.DOWNLOADING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { downloadProgress ?: 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${((downloadProgress ?: 0f) * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

/**
 * Format the model file size + RAM requirement as a single
 * short string, e.g. "≈ 1.4 GB دانلود · Requires approximately
 * 4 GB RAM". The RAM text comes straight from
 * [WhisperModelSpec.ramRequirementText] (pinned by JVM tests)
 * and is INFORMATIONAL ONLY — the picker never disables a row
 * based on RAM.
 */
private fun modelSizeAndRamHint(spec: com.aicreatorstudio.app.ai.WhisperModelSpec): String {
    val entry = spec.aiSpec()
    val sizeMb = entry.minSizeBytes / (1024L * 1024L)
    val sizeLabel = if (sizeMb >= 1024) {
        "≈ %.1f GB".format(sizeMb / 1024.0)
    } else {
        "≈ $sizeMb MB"
    }
    return "$sizeLabel دانلود · ${spec.ramRequirementText()}"
}

/**
 * 2026-09-06 — one chip in the quick subtitle-style row beside the
 * Generate (sub) button. Renders a live mini preview of the style:
 * the chip background is the preset's background (rounded pill when
 * enabled), and the label is drawn with the preset's own text color,
 * weight, and stroke — so the look is obvious before tapping.
 */
@Composable
private fun SubtitleQuickStyleChip(
    name: String,
    style: SubtitleStyle,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val chipBackground = if (style.backgroundEnabled) {
        Color(style.backgroundColorArgb).copy(alpha = style.backgroundOpacity)
    } else {
        Color.Black.copy(alpha = 0.35f)
    }
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline
    val alpha = if (enabled) 1f else 0.45f
    // Stroke preview width scaled down to chip size (the real
    // subtitle renders at strokeWidthSp on the canvas).
    val strokePreview = with(LocalDensity.current) { style.strokeWidthSp.sp.toPx() * 0.6f }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(style.backgroundCornerRadiusDp.dp.coerceIn(6.dp, 20.dp)))
            .background(chipBackground)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        Text(
            text = name,
            fontSize = style.fontSizeSp.sp * 0.42f,
            textAlign = TextAlign.Center,
            color = Color(style.textColorArgb),
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            style = if (style.strokeEnabled) {
                TextStyle(
                    drawStyle = Stroke(
                        width = strokePreview,
                        join = StrokeJoin.Round
                    )
                )
            } else {
                TextStyle.Default
            }
        )
    }
}
