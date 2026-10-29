package com.aicreatorstudio.app.ui.screens.settings

import android.content.Intent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.aicreatorstudio.app.logging.LogLevel
import com.aicreatorstudio.app.logging.LogRecord
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.viewmodel.DiagnosticsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 2026-10-12 — «گزارش‌های فنی» / «عیب‌یابی» — the compact Persian
 * diagnostics section inside Settings.
 *
 * Scope is deliberately small: view recent events, filter to problems,
 * see the latest error, export a sanitized report through Android's
 * native share sheet, and clear the logs after confirmation. It reuses
 * [SettingsSection] / [SettingsToggleItem]-style rows so the Settings
 * screen's look is not redesigned.
 *
 * There is no upload and no remote dashboard — the share sheet is the
 * only egress, and it is user-initiated after an explicit warning.
 */
@Composable
fun DiagnosticsSection(
    viewModel: DiagnosticsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }
    var showShareConfirm by remember { mutableStateOf(false) }

    // 2026-10-13 — the panel is a snapshot of the log files taken when
    // the ViewModel was constructed, so anything logged after that (the
    // failures this panel exists to show) stayed invisible until the
    // process restarted. Re-read on every resume: the user leaves
    // Settings, triggers a failure in the editor, comes back, and the
    // record is there.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsSection(title = t("گزارش‌های فنی")) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ---- Summary row ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t("رویدادهای ثبت‌شده"),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "کل: ${state.totalCount} · خطا: ${state.errorCount} · هشدار: ${state.warnCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- Latest problem summary ----
            state.latestProblem?.let { latest ->
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            (if (latest.level == LogLevel.ERROR) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.tertiary).copy(alpha = 0.10f)
                        )
                        .padding(10.dp)
                ) {
                    Text(
                        text = t("آخرین خطا"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (latest.level == LogLevel.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = summaryLine(latest),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Problems-only toggle ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setProblemsOnly(!state.problemsOnly) }
                    .padding(vertical = 4.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = t("فقط خطاها و هشدارها"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = state.problemsOnly,
                    onCheckedChange = { viewModel.setProblemsOnly(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // ---- Event list ----
            if (state.visibleRecords.isEmpty()) {
                Text(
                    text = if (state.problemsOnly) t("موردی برای نمایش نیست")
                    else t("هنوز رویدادی ثبت نشده است"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            RoundedCornerShape(10.dp)
                        )
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Newest first reads better in a short scrollable box.
                    state.visibleRecords.asReversed().forEach { record ->
                        Text(
                            text = summaryLine(record),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = colorFor(record.level)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---- Actions ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                DiagnosticsActionButton(
                    icon = Icons.Filled.Share,
                    label = t("اشتراک‌گذاری گزارش"),
                    modifier = Modifier.weight(1f),
                    onClick = { showShareConfirm = true }
                )
                DiagnosticsActionButton(
                    icon = Icons.Filled.DeleteSweep,
                    label = t("پاک کردن"),
                    modifier = Modifier.weight(1f),
                    onClick = { showClearConfirm = true }
                )
            }
        }
    }

    // ---- Share confirmation: explains what leaves the device ----
    if (showShareConfirm) {
        AlertDialog(
            onDismissRequest = { showShareConfirm = false },
            title = { Text(t("اشتراک‌گذاری گزارش فنی")) },
            text = {
                Text(
                    t(
                        "این گزارش فقط رویدادهای ثبت‌شده و اطلاعات فنی پایهٔ دستگاه " +
                            "(نسخهٔ برنامه، نسخهٔ اندروید، معماری پردازنده) را در بر می‌گیرد. " +
                            "اطلاعات حساس، مسیر فایل‌ها و کلیدهای دسترسی پیش از ذخیره حذف شده‌اند. " +
                            "هیچ چیزی به‌صورت خودکار ارسال نمی‌شود."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showShareConfirm = false
                    val report = viewModel.buildReport()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Omelet Diagnostic Report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(
                        Intent.createChooser(send, "اشتراک‌گذاری گزارش فنی")
                    )
                }) {
                    Text(t("اشتراک‌گذاری"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showShareConfirm = false }) { Text(t("انصراف")) }
            }
        )
    }

    // ---- Clear confirmation: destructive ----
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(t("پاک کردن گزارش‌ها")) },
            text = {
                Text(
                    t(
                        "همهٔ رویدادهای فنی پاک می‌شوند. این کار فقط فایل‌های گزارش " +
                            "را حذف می‌کند و به پروژه‌ها یا ویدیوهای خروجی شما آسیبی نمی‌رساند."
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clearLogs()
                }) {
                    Text(t("پاک کردن"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(t("انصراف")) }
            }
        )
    }
}

@Composable
private fun DiagnosticsActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private val EVENT_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

/** One compact, monospace-friendly line for the event list. */
private fun summaryLine(record: LogRecord): String {
    val time = EVENT_TIME_FORMAT.format(Date(record.timestampMillis))
    val head = "$time ${record.level.name} ${record.component}/${record.event}"
    val body = record.message.ifBlank { record.throwable ?: "" }
    return if (body.isBlank()) head else "$head — $body"
}

@Composable
private fun colorFor(level: LogLevel) = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
}
