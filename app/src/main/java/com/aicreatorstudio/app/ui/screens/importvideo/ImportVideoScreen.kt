package com.aicreatorstudio.app.ui.screens.importvideo

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicreatorstudio.app.model.ImportVideoUiState
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.StatusError
import com.aicreatorstudio.app.ui.theme.StatusErrorContainer
import com.aicreatorstudio.app.ui.theme.StatusSuccess
import com.aicreatorstudio.app.ui.theme.ctaBrush
import com.aicreatorstudio.app.ui.theme.iOSShapes
import com.aicreatorstudio.app.util.Formatters
import com.aicreatorstudio.app.viewmodel.ImportVideoViewModel

/**
 * Lets the user pick a video from device storage, shows its metadata, and
 * saves it as a new project. No trimming, timeline, or AI analysis here —
 * this screen only imports and displays file info.
 *
 * Presentation now matches the rest of the app (dark studio chrome + tokens)
 * instead of the default Material scaffold, and the copy is Persian with the
 * same `t()` fallback used on Home.
 */
@Composable
fun ImportVideoScreen(
    onBackClick: () -> Unit,
    onProjectSaved: (String) -> Unit,
    viewModel: ImportVideoViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Modern picker: no runtime permission needed, works on API 21+ via the
    // Android Photo Picker / its Google Play Services backport.
    // 2026-10-05 — MULTI-select (up to 10): a Reels batch lands as one
    // multi-clip draft in a single pick. The first valid video creates the
    // project, the rest are appended (see ImportVideoViewModel.onVideosPicked).
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10),
        onResult = viewModel::onVideosPicked
    )

    // Fallback for devices where the photo picker isn't available.
    val legacyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = viewModel::onVideoPicked
    )

    val requiredPermission = legacyStoragePermission()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                viewModel.onPermissionGranted()
                legacyPickerLauncher.launch("video/*")
            } else {
                val activity = context.findActivity()
                val permanentlyDenied = activity != null &&
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, requiredPermission)
                viewModel.onPermissionDenied(permanentlyDenied)
            }
        }
    )

    fun launchPicker() {
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)) {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(context, requiredPermission) ==
            PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            legacyPickerLauncher.launch("video/*")
        } else {
            permissionLauncher.launch(requiredPermission)
        }
    }

    LaunchedEffect(uiState.savedProjectId) {
        uiState.savedProjectId?.let { projectId ->
            onProjectSaved(projectId)
        }
    }

    ImportVideoScreenContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onSelectVideoClick = ::launchPicker,
        onOpenSettingsClick = { context.openAppSettings() },
        onChooseDifferentClick = { viewModel.clearSelection(); launchPicker() },
        onSaveClick = viewModel::saveToProject,
        onDismissError = viewModel::consumeError
    )
}

@Composable
private fun ImportVideoScreenContent(
    uiState: ImportVideoUiState,
    onBackClick: () -> Unit,
    onSelectVideoClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onChooseDifferentClick: () -> Unit,
    onSaveClick: () -> Unit,
    onDismissError: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CinematicBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(title = t("افزودن ویدیو"), onBackClick = onBackClick)

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        uiState.permissionRequired -> PermissionNeeded(
                            permanentlyDenied = uiState.permissionPermanentlyDenied,
                            onGrantClick = onSelectVideoClick,
                            onOpenSettingsClick = onOpenSettingsClick
                        )

                        uiState.isLoadingMetadata -> LoadingState()

                        uiState.selectedClip != null -> SelectedVideoState(
                            clip = uiState.selectedClip,
                            saved = uiState.savedProjectId != null,
                            onChooseDifferentClick = onChooseDifferentClick,
                            onSaveClick = onSaveClick
                        )

                        else -> InitialState(onSelectVideoClick = onSelectVideoClick)
                    }

                    if (uiState.errorMessage != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        ErrorCard(message = uiState.errorMessage, onDismiss = onDismissError)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScreenTopBar(title: String, onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(CinematicSurface)
                .border(1.dp, BorderSubtle, RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button, onClickLabel = t("بازگشت"), onClick = onBackClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = LabelPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = LabelPrimary
        )
    }
}

@Composable
private fun InitialState(onSelectVideoClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconTile(icon = Icons.Filled.VideoFile, tint = AccentBright)
        Text(
            text = t("یک ویدیو از دستگاه خود انتخاب کنید"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = LabelPrimary
        )
        Text(
            text = t("نام فایل، مدت، رزولوشن و حجم آن را پیش از افزودن به پروژه نشان می‌دهیم."),
            style = MaterialTheme.typography.bodyMedium,
            color = LabelSecondary
        )
        PrimaryPillButton(label = t("انتخاب ویدیو"), onClick = onSelectVideoClick)
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CircularProgressIndicator(color = AccentBright)
        Text(
            text = t("در حال خواندن اطلاعات ویدیو…"),
            style = MaterialTheme.typography.bodyLarge,
            color = LabelSecondary
        )
    }
}

@Composable
private fun SelectedVideoState(
    clip: VideoClip,
    saved: Boolean,
    onChooseDifferentClick: () -> Unit,
    onSaveClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CinematicSurface)
                .border(1.dp, BorderSubtle, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(
                text = clip.fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = LabelPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(modifier = Modifier.height(12.dp))
            InfoRow(label = t("مدت"), value = Formatters.duration(clip.durationMs))
            InfoRow(label = t("رزولوشن"), value = Formatters.resolution(clip.width, clip.height))
            InfoRow(label = t("حجم فایل"), value = Formatters.fileSize(clip.fileSizeBytes))
        }

        if (saved) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = StatusSuccess
                )
                Text(
                    text = t("در پروژه جدید ذخیره شد"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = LabelPrimary
                )
            }
        } else {
            SecondaryPillButton(
                label = t("انتخاب ویدیوی دیگر"),
                onClick = onChooseDifferentClick
            )
            PrimaryPillButton(
                label = t("ذخیره در پروژه"),
                onClick = onSaveClick
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = LabelSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = LabelPrimary
        )
    }
}

@Composable
private fun PermissionNeeded(
    permanentlyDenied: Boolean,
    onGrantClick: () -> Unit,
    onOpenSettingsClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        IconTile(icon = Icons.Filled.FolderOff, tint = StatusError)
        Text(
            text = t("دسترسی به حافظه لازم است"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = LabelPrimary
        )
        Text(
            text = if (permanentlyDenied) {
                t("دسترسی به ویدیوها رد شد. برای افزودن ویدیو آن را از تنظیمات برنامه فعال کنید.")
            } else {
                t("املت برای افزودن ویدیو به دسترسی شما نیاز دارد.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = LabelSecondary
        )
        PrimaryPillButton(
            label = if (permanentlyDenied) t("باز کردن تنظیمات") else t("اجازه دادن"),
            onClick = if (permanentlyDenied) onOpenSettingsClick else onGrantClick
        )
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(StatusErrorContainer)
            .border(1.dp, StatusError.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = StatusError
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = LabelPrimary,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button, onClickLabel = t("بستن"), onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                tint = LabelSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(32.dp)
        )
    }
}

@Composable
private fun PrimaryPillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(iOSShapes.capsule)
            .background(ctaBrush())
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SecondaryPillButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(iOSShapes.capsule)
            .background(CinematicSurface)
            .border(1.dp, BorderSubtle, iOSShapes.capsule)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = LabelPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** API 33+ uses the scoped video permission; older APIs fall back to broad storage read. */
private fun legacyStoragePermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
    }
    startActivity(intent)
}
