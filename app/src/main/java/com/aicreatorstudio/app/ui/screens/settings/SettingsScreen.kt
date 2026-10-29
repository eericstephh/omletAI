package com.aicreatorstudio.app.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.HdrStrong
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicreatorstudio.app.R
import com.aicreatorstudio.app.settings.AppSettings
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.settings.ThemeMode
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            // 2026-10-17 PHASE 3 — this screen was still stock Material
            // chrome with an English title ("Settings" / "Back") in a
            // Persian-default app, the same defect Phase 1 fixed on the
            // Import screen. Title + back label are now localized through
            // `t()` and the bar sits on the app's CinematicBlack surface
            // instead of the default elevated surface, so it matches the
            // black header band every other screen uses.
            TopAppBar(
                title = { Text(t("تنظیمات")) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = t("بازگشت"),
                            tint = LabelPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CinematicBlack,
                    titleContentColor = LabelPrimary,
                    navigationIconContentColor = LabelPrimary
                )
            )
        }
    ) { paddingValues ->
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 2026-09-09 — APP LANGUAGE picker, at the top so it is
            // the first thing a user whose UI is in the wrong language
            // will find. Selecting a chip persists the tag, then
            // recreates the host Activity so every resource
            // re-resolves with the new locale immediately.
            item {
                AppLanguageSection(
                    savedTag = viewModel.savedAppLanguageTag(),
                    onLanguageSelected = { tag ->
                        viewModel.updateAppLanguage(tag)
                        activity?.recreate()
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 2026-09-06 — AI MODELS / DOWNLOADS. Every model the app
            // can use with its real install state, a one-tap Download
            // button, and a live progress bar. This is the central
            // download surface of the app.
            item {
                val modelRows by viewModel.modelRows.collectAsState()
                SettingsSection(title = t("مدل‌های هوش مصنوعی و دانلودها")) {
                    if (modelRows.isEmpty()) {
                        Text(
                            text = t("در حال بررسی…"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        modelRows.forEach { row ->
                            ModelDownloadItem(
                                row = row,
                                onDownload = { viewModel.downloadModel(row.id) }
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 2026-10-10 — «اتصال هوش مصنوعی»: the user's own Groq
            // API key (AES-256/GCM + Android Keystore at rest) with
            // a REAL connection test against the quota-free models
            // endpoint. Preferred cloud path — no account, no daily
            // quota. Falls back to the Worker section below when no
            // key is saved.
            item {
                val groqVm: com.aicreatorstudio.app.viewmodel.GroqConnectionSettingsViewModel =
                    hiltViewModel()
                SettingsSection(title = t("اتصال هوش مصنوعی")) {
                    GroqConnectionSection(
                        viewModel = groqVm,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 2026-10-10 ACCOUNT-FREE — the "سرویس ابری Whisper" section
            // (Supabase email login + 60-second daily quota) was removed.
            // Cloud transcription now uses only the user's own Groq API
            // key (section above) — no account, no quota.

            item {
                SettingsSection(title = t("ظاهر")) {
                    ThemeModeSelector(
                        selectedMode = settings.themeMode,
                        onModeSelected = viewModel::updateThemeMode
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 2026-09-06 — trimmed: the low-value Video Editing and
            // Preview sections (Snap to Grid / Grid size / Transition
            // duration / Unlimited Undo / Frame numbers / Preview FPS)
            // were removed; Export keeps quality + hardware accel.
            item {
                SettingsSection(title = t("خروجی")) {
                    ExportQualitySelector(
                        currentQuality = settings.defaultExportQuality,
                        onQualitySelected = viewModel::updateExportQuality
                    )

                    SettingsToggleItem(
                        icon = Icons.Filled.HdrStrong,
                        title = t("شتاب سخت‌افزاری"),
                        subtitle = t("برای خروجی سریع‌تر از GPU استفاده کن (ممکن است باتری بیشتری مصرف کند)."),
                        checked = settings.hardwareAcceleration,
                        onCheckedChange = viewModel::updateHardwareAcceleration
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            // 2026-10-12 — «گزارش‌های فنی»: local, account-free
            // diagnostics. Views the sanitized events the centralized
            // logger retains, filters to problems, and exports a report
            // through the native share sheet after an explicit warning.
            // Nothing here uploads or makes a network call.
            item {
                val diagnosticsVm: com.aicreatorstudio.app.viewmodel.DiagnosticsViewModel =
                    hiltViewModel()
                DiagnosticsSection(viewModel = diagnosticsVm)
            }

            item { Spacer(modifier = Modifier.height(8.dp)) }

            item {
                SettingsSection(title = t("عمومی")) {
                    SettingsToggleItem(
                        icon = Icons.Filled.Save,
                        title = t("ذخیره خودکار"),
                        subtitle = t("تغییرات هر ۳۰ ثانیه به‌طور خودکار ذخیره شوند."),
                        checked = settings.autoSaveEnabled,
                        onCheckedChange = viewModel::updateAutoSave
                    )

                    SettingsToggleItem(
                        icon = Icons.Filled.Smartphone,
                        title = t("بازخورد لمسی"),
                        subtitle = t("لرزش هنگام تعامل با تایم‌لاین"),
                        checked = settings.hapticFeedback,
                        onCheckedChange = viewModel::updateHapticFeedback
                    )

                    DefaultFontSelector(
                        currentFont = settings.defaultTextFont,
                        onFontSelected = viewModel::updateTextFont
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

/**
 * 2026-09-09 — app-language picker row. Each option is a chip with
 * its NATIVE name (فارسی / English / العربية) so users can always
 * find their language regardless of the current UI language.
 * Selecting a chip persists the tag and applies the locale via
 * [com.aicreatorstudio.app.AICreatorStudioApp.applyLanguage], which
 * recreates activities so every resource re-resolves immediately.
 * The empty tag follows the device language; the FIRST-LAUNCH
 * default (nothing saved) is Persian.
 */
@Composable
private fun AppLanguageSection(
    savedTag: String?,
    onLanguageSelected: (String) -> Unit
) {
    // First launch: nothing saved yet → Persian is already active via
    // the Activity-level locale override; highlight it.
    val effective = savedTag ?: "fa"
    SettingsSection(title = stringResource(id = R.string.settings_language_title)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                com.aicreatorstudio.app.settings.AppLanguage.SUPPORTED
                    .forEach { (tag, nativeName) ->
                        val isSelected = effective == tag
                        Text(
                            text = nativeName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold
                            else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { onLanguageSelected(tag) }
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.settings_language_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 2026-09-06 — one row of the AI-models download list: name, real
 * size, install state, a Download button (hidden while installed),
 * and a live progress bar with percentage while downloading.
 */
@Composable
private fun ModelDownloadItem(
    row: com.aicreatorstudio.app.viewmodel.SettingsViewModel.ModelDownloadRow,
    onDownload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CloudDownload,
                contentDescription = null,
                tint = if (row.installed) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (row.installed) {
                        Text(
                            text = t("نصب شده"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "${row.description} · ${row.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!row.installed && !row.downloading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            RoundedCornerShape(50)
                        )
                        .clickable { onDownload() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = t("دانلود"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        // Live progress bar while downloading.
        if (row.downloading) {
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { row.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                // 2026-10-17 PHASE 3 — the label was a bare Persian
                // literal, so English/Arabic users saw Persian chrome
                // here. `t()` has no interpolation, so the translated
                // label is concatenated with the (language-neutral)
                // percentage.
                text = t("در حال دانلود…") + " ${(row.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
        )
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun ThemeModeSelector(
    selectedMode: ThemeMode,
    onModeSelected: (ThemeMode) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        ThemeMode.entries.forEach { mode ->
            val icon = when (mode) {
                ThemeMode.DARK -> Icons.Filled.DarkMode
                ThemeMode.LIGHT -> Icons.Filled.LightMode
                ThemeMode.SYSTEM -> Icons.Filled.Brightness6
            }
            // 2026-10-17 PHASE 3 — these three labels were the last
            // English chrome left on the Settings screen, in a
            // Persian-default app. `label` is also reused as the icon's
            // contentDescription, so localizing it fixes the accessible
            // name too.
            val label = when (mode) {
                ThemeMode.DARK -> t("حالت تیره")
                ThemeMode.LIGHT -> t("حالت روشن")
                ThemeMode.SYSTEM -> t("سیستم")
            }
            val isSelected = mode == selectedMode
            val containerColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                label = "themeChipColor"
            )
            val borderColor by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                label = "themeBorderColor"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(containerColor)
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                    .clickable { onModeSelected(mode) },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ExportQualitySelector(
    currentQuality: String,
    onQualitySelected: (String) -> Unit
) {
    val qualities = listOf("480p", "720p", "1080p", "1440p (2K)", "2160p (4K)")
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = t("کیفیت پیش‌فرض خروجی"),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = currentQuality,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 2026-10-17 PHASE 3 — same class of defect as the font row below;
        // five chips happen to fit at the default font scale, but nothing
        // stops a larger system font from squeezing the last one. Scroll
        // container added for consistency and to stay safe under scaling.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            qualities.forEach { quality ->
                FilterChip(
                    selected = quality == currentQuality,
                    onClick = { onQualitySelected(quality) },
                    label = {
                        Text(
                            text = quality.replace(" (2K)", "").replace(" (4K)", ""),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@Composable
private fun DefaultFontSelector(
    currentFont: String,
    onFontSelected: (String) -> Unit
) {
    val fonts = listOf("Vazirmatn", "Sahel", "Shabnam", "Samim", "Lalezar")
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = t("فونت پیش‌فرض متن"), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = currentFont,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 2026-10-17 PHASE 3 — this row had no scroll container, so the
        // fifth chip was measured against the leftover width: "Lalezar"
        // collapsed to ~126 px and wrapped to one glyph per line (7 lines
        // tall, per a live uiautomator dump on emulator-5554), i.e. a
        // broken-looking control. Every chip row in the subtitle panel and
        // the language picker above already use `horizontalScroll`; this
        // row now matches that convention.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            fonts.forEach { font ->
                FilterChip(
                    selected = font == currentFont,
                    onClick = { onFontSelected(font) },
                    label = {
                        Text(
                            text = font,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}
