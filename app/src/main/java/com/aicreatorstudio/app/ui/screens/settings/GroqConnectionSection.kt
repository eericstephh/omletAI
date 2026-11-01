package com.aicreatorstudio.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.viewmodel.GroqConnectionSettingsViewModel

/** Groq's API-key management console — the guide's «ورود به کنسول» target. */
private const val GROQ_CONSOLE_KEYS_URL = "https://console.groq.com/keys"

/**
 * 2026-11-01 — one numbered step of the «راهنمای دریافت کلید» guide.
 * `num` is the pre-rendered Persian digit so the list is pure data.
 */
private data class GroqGuideStep(val num: String, val text: String)

private val GROQ_GUIDE_STEPS = listOf(
    GroqGuideStep(
        "۱",
        "تحریم‌شکن را روشن کنید — سرویس Groq به IP ایران پاسخ نمی‌دهد."
    ),
    GroqGuideStep(
        "۲",
        "روی دکمهٔ «ورود به کنسول Groq» در پایین همین راهنما بزنید تا صفحهٔ ساخت کلید باز شود (آدرس: console.groq.com/keys)."
    ),
    GroqGuideStep(
        "۳",
        "با ایمیل یا حساب گوگل ثبت‌نام کنید — ساخت حساب و کلید رایگان است."
    ),
    GroqGuideStep(
        "۴",
        "روی دکمهٔ «Create API Key» بزنید و یک نام دلخواه وارد کنید (مثلاً omelet)."
    ),
    GroqGuideStep(
        "۵",
        "کلید نمایش‌داده‌شده (با gsk_ شروع می‌شود) را کپی کنید — توجه: فقط همین یک بار نمایش داده می‌شود!"
    ),
    GroqGuideStep(
        "۶",
        "به همین صفحه برگردید، کلید را در فیلد بالا بچسبانید و «ذخیره و تست اتصال» را بزنید."
    )
)

/**
 * 2026-10-10 — «اتصال هوش مصنوعی»: save + test the user's own
 * Groq API key.
 *
 * States:
 * - no key: password field + «ذخیره و تست اتصال».
 * - key saved: masked key + status + «تست مجدد» / «حذف کلید»
 *   (destructive → confirmation dialog).
 * - test result: icon + Persian message (success or failure).
 */
@Composable
fun GroqConnectionSection(
    viewModel: GroqConnectionSettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = "کلید API شخصی Groq",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "زیرنویس ابری با کلید خودتان — بدون حساب و سهمیه روزانه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- 2026-11-01 — collapsible «how to get a key» guide ----
            var showGuide by rememberSaveable { mutableStateOf(false) }

            TextButton(
                onClick = { showGuide = !showGuide },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.HelpOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (showGuide) "بستن راهنما"
                    else "کلید ندارم — چطور کلید Groq بگیرم؟",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = showGuide) {
                val context = LocalContext.current
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    GROQ_GUIDE_STEPS.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = step.num,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = step.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(GROQ_CONSOLE_KEYS_URL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("ورود به کنسول Groq (ساخت کلید)")
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (!state.hasKey) {
                // ---- no key yet: entry field + save & test ----
                OutlinedTextField(
                    value = state.keyDraft,
                    onValueChange = viewModel::onKeyChanged,
                    label = { Text("کلید API") },
                    placeholder = { Text("gsk_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                )

                state.errorMessage?.let { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = viewModel::saveKey,
                    enabled = !state.isSaving && state.keyDraft.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("ذخیره و تست اتصال")
                    }
                }
            } else {
                // ---- key saved: masked + test / remove ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = state.maskedKey ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    IconButton(
                        onClick = { showClearConfirm = true },
                        enabled = !state.isTesting
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف کلید",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                OutlinedButton(
                    onClick = viewModel::testConnection,
                    enabled = !state.isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("در حال تست…")
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تست اتصال")
                    }
                }
            }

            // ---- test result (both modes) ----
            state.testResult?.let { result ->
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (result.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.success) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            Text(
                text = "کلید شما فقط روی همین دستگاه و به‌صورت رمزنگاری‌شده (Keystore) ذخیره می‌شود و مستقیماً به سرور Groq ارسال می‌گردد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // ---- destructive confirmation ----
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("حذف کلید API؟") },
            text = { Text("کلید ذخیره‌شده حذف می‌شود و زیرنویس ابری تا زمان وارد کردن دوبارهٔ کلید، در دسترس نخواهد بود.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearKey()
                    }
                ) {
                    Text("حذف", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("انصراف")
                }
            }
        )
    }
}
