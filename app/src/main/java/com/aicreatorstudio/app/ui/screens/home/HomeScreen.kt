package com.aicreatorstudio.app.ui.screens.home

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicreatorstudio.app.ads.TapsellAdManager
import com.aicreatorstudio.app.ads.TapsellAdsEntryPoint
import com.aicreatorstudio.app.model.HomeUiState
import com.aicreatorstudio.app.ui.components.EmptyState
import com.aicreatorstudio.app.ui.components.GlassChrome
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.ProjectCard
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.theme.AICreatorStudioTheme
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.StatusError
import com.aicreatorstudio.app.ui.theme.SurfaceInset
import com.aicreatorstudio.app.ui.theme.ctaBrush
import com.aicreatorstudio.app.ui.theme.iOSShapes
import com.aicreatorstudio.app.util.TelegramChannel
import com.aicreatorstudio.app.viewmodel.HomeViewModel
import dagger.hilt.android.EntryPointAccessors

/**
 * Telegram's brand blue — the disc behind the channel button.
 *
 * Deliberately NOT a theme token: it is a third party's brand colour,
 * not part of this app's palette, and it must stay that colour.
 */
private val TelegramBrandBlue = Color(0xFF229ED9)

/**
 * Landing screen: primary entry points (new project, import) plus the list
 * of projects the user already has. No editing or AI functionality lives
 * here — this screen only starts those flows via navigation.
 *
 * Copy is Persian by default (the app's default language) and resolved
 * through [t] so it can be translated, which also means the layout is
 * authored for RTL rather than being English text dropped into an RTL frame.
 */
@Composable
fun HomeScreen(
    onCreateProjectClick: () -> Unit = {},
    onImportVideoClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onProjectClick: (String) -> Unit = {},
    onOpenAiTool: (projectId: String, capability: AiCapability) -> Unit = { _, _ -> },
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // 2026-09-16 — once-per-session app-open interstitial. The manager
    // owns the session slot, so rotations (which re-run this effect)
    // can never show it twice.
    val context = LocalContext.current
    val tapsellAds: TapsellAdManager = remember(context.applicationContext) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            TapsellAdsEntryPoint::class.java
        ).tapsellAdManager()
    }
    LaunchedEffect(Unit) {
        (context as? Activity)?.let { tapsellAds.showAppOpenOnce(it) }
    }
    // 2026-10-10 ACCOUNT-FREE — fully usable without an email account.
    // 2026-09-30 — the bell opens the changelog sheet; opening it is also
    // what clears the unread dot.
    var showUpdateSheet by remember { mutableStateOf(false) }
    HomeScreenContent(
        uiState = uiState,
        onCreateProjectClick = onCreateProjectClick,
        onImportVideoClick = onImportVideoClick,
        onSettingsClick = onSettingsClick,
        onProjectClick = onProjectClick,
        // 2026-10-20 — the AI tools live in the editor, so a
        // capability tile needs a project to run on. The list is
        // newest-first, so the most recent project is the sensible
        // default; with no project at all there is nothing to run the
        // tool on, so the user is sent to add a video rather than into
        // an empty editor.
        onAiCapabilityClick = { capability ->
            val projectId = uiState.projects.firstOrNull()?.id
            if (projectId != null) {
                onOpenAiTool(projectId, capability)
            } else {
                onImportVideoClick()
            }
        },
        onDeleteProject = { viewModel.deleteProject(it) },
        onNotificationsClick = {
            viewModel.markUpdatesAsRead()
            showUpdateSheet = true
        },
        // 2026-10-20 — the Telegram channel button in the header.
        onTelegramClick = { TelegramChannel.open(context) }
    )
    if (showUpdateSheet) {
        AppUpdateSheet(
            releases = uiState.releases,
            currentVersionName = uiState.currentVersionName,
            onDismiss = { showUpdateSheet = false },
            onGotIt = {
                viewModel.markUpdatesAsRead()
                showUpdateSheet = false
            }
        )
    }
}

@Composable
private fun HomeScreenContent(
    uiState: HomeUiState,
    onCreateProjectClick: () -> Unit,
    onImportVideoClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onProjectClick: (String) -> Unit,
    onAiCapabilityClick: (AiCapability) -> Unit = {},
    onDeleteProject: (String) -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onTelegramClick: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CinematicBlack)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        // Calm studio backdrop: a single static gradient with one faint
        // brand glow top-end. No drifting blobs, no particles — the content
        // carries the screen.
        StudioBackdrop(modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item(key = "header") {
                    HomeHeader(
                        hasUnreadUpdates = uiState.hasUnreadUpdates,
                        onNotificationsClick = onNotificationsClick,
                        onTelegramClick = onTelegramClick,
                        onSettingsClick = onSettingsClick
                    )
                }

                item(key = "actions") {
                    ActionButtons(
                        onCreateProjectClick = onCreateProjectClick,
                        onImportVideoClick = onImportVideoClick
                    )
                }

                item(key = "features") {
                    // 2026-10-20 — the capability list is now the
                    // complete, accurate AI catalogue (12 tools, one
                    // distinct icon each) and every tile is a real
                    // action: it opens the matching tool in the editor.
                    AiCapabilitySection(
                        onCapabilityClick = onAiCapabilityClick,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                item(key = "projects-title") {
                    SectionHeader(
                        title = t("پروژه‌های من"),
                        count = uiState.projects.size
                    )
                }

                if (uiState.projects.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = t("هنوز پروژه‌ای نساخته‌اید"),
                            message = t("برای شروع، یک پروژه تازه بسازید یا ویدیویی اضافه کنید."),
                            actionLabel = t("پروژه جدید"),
                            onAction = onCreateProjectClick
                        )
                    }
                } else {
                    items(uiState.projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            onClick = { onProjectClick(project.id) },
                            onDelete = { showDeleteDialog = project.id }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(t("حذف پروژه")) },
            text = { Text(t("این پروژه برای همیشه حذف می‌شود و قابل بازگردانی نیست.")) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog?.let { onDeleteProject(it) }
                    showDeleteDialog = null
                }) {
                    Text(t("حذف"), color = StatusError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(t("انصراف"))
                }
            }
        )
    }
}

@Composable
private fun StudioBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(CinematicBlack, SurfaceInset, CinematicBlack),
                startY = 0f,
                endY = 1400f
            )
        )
    ) {
        // One faint brand glow, deliberately static and low-alpha.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentBright.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        radius = 900f
                    )
                )
        )
    }
}

@Composable
private fun HomeHeader(
    hasUnreadUpdates: Boolean,
    onNotificationsClick: () -> Unit,
    onTelegramClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    GlassChrome(
        shape = RoundedCornerShape(20.dp),
        material = GlassMaterial.Regular,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = t("استودیو ویرایش ویدیو"),
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    // 2026-10-20 — the header carries THREE 44dp
                    // controls now (bell, Telegram, settings), which
                    // leaves the brand column narrower. One line with
                    // an ellipsis keeps the header a fixed height
                    // instead of wrapping on a narrow phone.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = t("املت"),
                        color = LabelPrimary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "AI",
                        color = AccentBright,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            BadgedBox(
                badge = {
                    if (hasUnreadUpdates) {
                        Badge(containerColor = StatusError)
                    }
                }
            ) {
                RoundGlassIconButton(
                    icon = Icons.Outlined.Notifications,
                    contentDescription = t("تغییرات و آپدیت‌های جدید"),
                    onClick = onNotificationsClick
                )
            }
            Spacer(Modifier.width(8.dp))
            TelegramIconButton(onClick = onTelegramClick)
            Spacer(Modifier.width(8.dp))
            RoundGlassIconButton(
                icon = Icons.Filled.Settings,
                contentDescription = t("تنظیمات"),
                onClick = onSettingsClick
            )
        }
    }
}

/** 44dp circular glass control — the header's one repeated action shape. */
@Composable
private fun RoundGlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(CinematicSurface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LabelPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The Telegram channel button.
 *
 * Same 44dp circle as the header's other controls, but filled with
 * Telegram's brand blue so it is recognisable at a glance — the
 * channel is a *destination*, not a setting, and it should not read
 * as one more toggle.
 *
 * The glyph is Material's `Send` paper plane — the same shape as the
 * Telegram mark — drawn in white on the blue disc. The **non**
 * auto-mirrored variant is used on purpose: a brand mark must not
 * flip when the app is in Persian.
 */
@Composable
private fun TelegramIconButton(onClick: () -> Unit) {
    val label = t("کانال تلگرام")
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(TelegramBrandBlue)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Send,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = LabelPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(CinematicSurface)
                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "$count",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun ActionButtons(
    onCreateProjectClick: () -> Unit,
    onImportVideoClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Primary — CTA gradient (AA-safe white text).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(iOSShapes.capsule)
                .background(ctaBrush())
                .clickable(
                    role = Role.Button,
                    onClickLabel = t("پروژه جدید"),
                    onClick = onCreateProjectClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = t("پروژه جدید"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Secondary — outlined pill, subordinate to the primary action.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(iOSShapes.capsule)
                .background(CinematicSurface)
                .border(1.dp, BorderSubtle, iOSShapes.capsule)
                .clickable(
                    role = Role.Button,
                    onClickLabel = t("افزودن ویدیو"),
                    onClick = onImportVideoClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.VideoLibrary,
                    contentDescription = null,
                    tint = LabelPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = t("افزودن ویدیو"),
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    AICreatorStudioTheme {
        HomeScreenContent(
            uiState = HomeUiState(),
            onCreateProjectClick = {},
            onImportVideoClick = {},
            onSettingsClick = {},
            onProjectClick = {}
        )
    }
}
