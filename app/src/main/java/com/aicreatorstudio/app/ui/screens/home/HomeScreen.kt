package com.aicreatorstudio.app.ui.screens.home

import android.app.Activity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aicreatorstudio.app.ads.TapsellAdManager
import com.aicreatorstudio.app.ads.TapsellAdsEntryPoint
import com.aicreatorstudio.app.model.HOME_TOUR_STEPS
import com.aicreatorstudio.app.model.HomeUiState
import com.aicreatorstudio.app.model.TutorialTarget
import com.aicreatorstudio.app.model.clampTutorialIndex
import com.aicreatorstudio.app.model.isLastTutorialIndex
import com.aicreatorstudio.app.model.shouldShowHomeTour
import com.aicreatorstudio.app.model.shouldShowOnboarding
import com.aicreatorstudio.app.model.tutorialTargetItemIndex
import com.aicreatorstudio.app.ui.components.EmptyState
import com.aicreatorstudio.app.ui.components.GlassChrome
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.ProjectCard
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.theme.accentBright
import com.aicreatorstudio.app.ui.theme.accentContainer
import com.aicreatorstudio.app.ui.theme.accentDeep
import com.aicreatorstudio.app.ui.theme.accentPrimary
import com.aicreatorstudio.app.ui.theme.glowBrand
import com.aicreatorstudio.app.ui.theme.AICreatorStudioTheme
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.AccentCyan
import com.aicreatorstudio.app.ui.theme.AccentDeep
import com.aicreatorstudio.app.ui.theme.AccentPrimary
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.GlowBrand
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.LabelTertiary
import com.aicreatorstudio.app.ui.theme.StatusError
import com.aicreatorstudio.app.ui.theme.SurfaceCard
import com.aicreatorstudio.app.ui.theme.SurfaceCardHigh
import com.aicreatorstudio.app.ui.theme.cardEdgeBrush
import com.aicreatorstudio.app.ui.theme.ctaBrush
import com.aicreatorstudio.app.ui.theme.iOSShapes
import com.aicreatorstudio.app.ui.tutorial.LocalTutorialAnchors
import com.aicreatorstudio.app.ui.tutorial.OnboardingOverlay
import com.aicreatorstudio.app.ui.tutorial.TutorialAnchors
import com.aicreatorstudio.app.ui.tutorial.TutorialSpotlight
import com.aicreatorstudio.app.ui.tutorial.tutorialAnchor
import com.aicreatorstudio.app.util.TelegramChannel
import com.aicreatorstudio.app.viewmodel.HomeViewModel
import com.aicreatorstudio.app.viewmodel.TutorialViewModel
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
    viewModel: HomeViewModel = hiltViewModel(),
    tutorialViewModel: TutorialViewModel = hiltViewModel()
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

    // ---- 2026-11-01 TUTORIAL -------------------------------------------------
    // Two stages, chained: the first-run onboarding covers the screen, and
    // the moment it completes the spotlight tour runs over the real UI.
    // Both decisions are pure reads of one StateFlow, so finishing either
    // stage dismisses it with no navigation event and no extra flag.
    val tutorialProgress by tutorialViewModel.progress.collectAsState()
    val showOnboarding = shouldShowOnboarding(tutorialProgress)
    val showTour = !showOnboarding && shouldShowHomeTour(tutorialProgress)

    // Hoisted out of HomeScreenContent so the tour can scroll the list to
    // the element it is about to highlight — a step whose target is below
    // the fold would otherwise draw a ring around nothing.
    val listState = rememberLazyListState()
    val tutorialAnchors = remember { TutorialAnchors() }
    var tourStepIndex by remember { mutableStateOf(0) }
    val tourIndex = clampTutorialIndex(tourStepIndex, HOME_TOUR_STEPS.size)
    val tourStep = HOME_TOUR_STEPS.getOrNull(tourIndex)

    // Restart at the first step every time the tour (re)opens, including a
    // replay from Settings.
    LaunchedEffect(showTour) {
        if (showTour) tourStepIndex = 0
    }
    LaunchedEffect(showTour, tourIndex) {
        if (showTour && tourStep != null) {
            listState.animateScrollToItem(tutorialTargetItemIndex(tourStep.target))
        }
    }
    // -------------------------------------------------------------------------

    CompositionLocalProvider(LocalTutorialAnchors provides tutorialAnchors) {
        // The overlay is a sibling of the content, NOT inside it: it must
        // start at the composition root so the anchor bounds (recorded in
        // root coordinates) line up with the scrim's own canvas.
        Box(modifier = Modifier.fillMaxSize()) {
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
                onTelegramClick = { TelegramChannel.open(context) },
                listState = listState
            )

            when {
                showOnboarding -> OnboardingOverlay(
                    aiIcons = AI_CAPABILITIES.map { it.icon },
                    onFinish = { tutorialViewModel.completeOnboarding() },
                    onSkip = { tutorialViewModel.skipAllTutorials() }
                )

                showTour && tourStep != null -> TutorialSpotlight(
                    step = tourStep,
                    index = tourIndex,
                    total = HOME_TOUR_STEPS.size,
                    targetRect = tutorialAnchors.rectOf(tourStep.target),
                    onNext = {
                        if (isLastTutorialIndex(tourIndex, HOME_TOUR_STEPS.size)) {
                            tutorialViewModel.completeHomeTour()
                        } else {
                            tourStepIndex = tourIndex + 1
                        }
                    },
                    onPrevious = { if (tourIndex > 0) tourStepIndex = tourIndex - 1 },
                    onDismiss = { tutorialViewModel.completeHomeTour() }
                )
            }
        }
    }

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
    onTelegramClick: () -> Unit = {},
    /**
     * Hoisted so [HomeScreen] can scroll to a tour step's element. The
     * tour's item indices are pinned by `tutorialTargetItemIndex` in
     * `model/TutorialContent.kt`; changing the order of the items below
     * means changing that mapping too.
     */
    listState: LazyListState = rememberLazyListState()
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
                state = listState,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 16.dp,
                    bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 2026-11-01 TUTORIAL — each of these four items is also a
                // tour anchor. The anchors are applied by WRAPPING the item
                // content, never by re-signaturing HomeHeader / ActionButtons /
                // AiCapabilitySection: where an item sits is a property of the
                // list, not of what the item draws.
                item(key = "header") {
                    Box(Modifier.tutorialAnchor(TutorialTarget.HEADER_SETTINGS)) {
                        HomeHeader(
                            hasUnreadUpdates = uiState.hasUnreadUpdates,
                            onNotificationsClick = onNotificationsClick,
                            onTelegramClick = onTelegramClick,
                            onSettingsClick = onSettingsClick
                        )
                    }
                }

                item(key = "actions") {
                    Box(Modifier.tutorialAnchor(TutorialTarget.CREATE_PROJECT)) {
                        ActionButtons(
                            onCreateProjectClick = onCreateProjectClick,
                            onImportVideoClick = onImportVideoClick
                        )
                    }
                }

                item(key = "features") {
                    // 2026-10-20 — the capability list is now the
                    // complete, accurate AI catalogue (12 tools, one
                    // distinct icon each) and every tile is a real
                    // action: it opens the matching tool in the editor.
                    Box(Modifier.tutorialAnchor(TutorialTarget.AI_CAPABILITIES)) {
                        AiCapabilitySection(
                            onCapabilityClick = onAiCapabilityClick,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                item(key = "projects-title") {
                    Box(Modifier.tutorialAnchor(TutorialTarget.PROJECTS)) {
                        SectionHeader(
                            title = t("پروژه‌های من"),
                            count = uiState.projects.size
                        )
                    }
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

/**
 * "Dark Studio 2" backdrop — still static and cheap (no blur, no particles),
 * but with real depth instead of one flat wash:
 *
 *  - a base vertical gradient that dips slightly cooler in the middle, so
 *    the screen is not one unbroken value;
 *  - a brand glow anchored top-END (in RTL that is the upper-right, where
 *    the eye lands first and where the brand lockup sits);
 *  - a faint cyan counter-glow bottom-start, so the bottom of a long list
 *    does not die into pure black.
 *
 * Both glows are drawn in a single `drawBehind` with known `size` — a
 * `Brush.radialGradient` needs pixel geometry, and `drawBehind` is the one
 * place that has it without a `BoxWithConstraints` re-layout.
 */
@Composable
private fun StudioBackdrop(modifier: Modifier = Modifier) {
    // Read in composition — a DrawScope block is not a composable context.
    val brandGlow = glowBrand()
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    colors = listOf(CinematicBlack, Color(0xFF10131C), CinematicBlack),
                    startY = 0f,
                    endY = 1600f
                )
            )
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(brandGlow.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.05f),
                        radius = size.maxDimension * 0.85f
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(AccentCyan.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(size.width * 0.08f, size.height * 0.9f),
                        radius = size.maxDimension * 0.7f
                    )
                )
            }
    )
}

@Composable
private fun HomeHeader(
    hasUnreadUpdates: Boolean,
    onNotificationsClick: () -> Unit,
    onTelegramClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    GlassChrome(
        shape = RoundedCornerShape(24.dp),
        material = GlassMaterial.Thick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = t("استودیو ویرایش ویدیو"),
                    color = LabelTertiary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp,
                    // 2026-10-20 — the header carries THREE 44dp
                    // controls now (bell, Telegram, settings), which
                    // leaves the brand column narrower. One line with
                    // an ellipsis keeps the header a fixed height
                    // instead of wrapping on a narrow phone.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = t("املت"),
                        color = LabelPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    // Brand chip — the "AI" is the product's differentiator,
                    // so it reads as a badge rather than trailing type. The
                    // container is the theme's own primaryContainer value;
                    // accentBright() on it is the tested 6:1-on-dark pairing.
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(7.dp))
                            .background(accentContainer())
                            .border(1.dp, accentPrimary().copy(alpha = 0.45f), RoundedCornerShape(7.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "AI",
                            color = accentBright(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
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

/**
 * 44dp circular control — the header's one repeated action shape.
 *
 * Depth comes from the card ladder (a `SurfaceCardHigh` well inside the
 * header glass) plus a top-lit edge, and the press is a restrained 0.92
 * scale — the same spring the pills use, so every control on the screen
 * answers a touch identically.
 */
@Composable
private fun RoundGlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "header-icon-scale"
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
            .clip(RoundedCornerShape(percent = 50))
            .background(SurfaceCardHigh)
            .border(1.dp, cardEdgeBrush(), RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick
            ),
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
                    .background(SurfaceCardHigh)
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
        // Primary — CTA gradient (AA-safe white text), lifted off the
        // backdrop by a violet-tinted shadow and a top-lit edge, so the
        // single most important action is also the most physical one.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = iOSShapes.capsule,
                    ambientColor = Color.Transparent,
                    spotColor = accentDeep().copy(alpha = 0.55f)
                )
                .clip(iOSShapes.capsule)
                .background(ctaBrush())
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.30f), Color.Transparent)
                    ),
                    shape = iOSShapes.capsule
                )
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

        // Secondary — a card-well pill, subordinate to the primary action:
        // same height for rhythm, quieter fill, brand-tinted glyph so the
        // pair reads as one family.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(iOSShapes.capsule)
                .background(SurfaceCard)
                .border(1.dp, cardEdgeBrush(), iOSShapes.capsule)
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
                    tint = accentBright(),
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
