package com.aicreatorstudio.app.ui.tutorial

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.ONBOARDING_PAGES
import com.aicreatorstudio.app.model.TutorialArt
import com.aicreatorstudio.app.model.TutorialPage
import com.aicreatorstudio.app.model.clampTutorialIndex
import com.aicreatorstudio.app.model.isLastTutorialIndex
import com.aicreatorstudio.app.model.toPersianDigits
import com.aicreatorstudio.app.ui.components.iOSPillButton
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.theme.accentBright
import com.aicreatorstudio.app.ui.theme.accentDeep
import com.aicreatorstudio.app.ui.theme.accentPrimary
import com.aicreatorstudio.app.ui.theme.glowBrand
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.AccentDeep
import com.aicreatorstudio.app.ui.theme.AccentPrimary
import com.aicreatorstudio.app.ui.theme.CinematicBlack
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.LabelTertiary

/**
 * The first-run onboarding: four full-screen pages over the Home screen.
 *
 * ## Why an overlay and not a navigation destination
 *
 * It has to be dismissible *into* the Home screen it is explaining, and
 * the guided tour that follows it needs the real Home screen already
 * composed so its anchors have measured bounds. A separate nav
 * destination would mean either a flash of Home before the overlay or a
 * second composition of it, and "back" would have to be special-cased to
 * avoid returning to the splash.
 *
 * As an overlay it is a single `if` in `HomeScreen`: the content beneath
 * is live, the state is one `StateFlow` read, and completing the tutorial
 * is a state write rather than a navigation event.
 *
 * ## The AI page shows the real tools
 *
 * [aiIcons] is supplied by the caller from the actual
 * `AI_CAPABILITIES` catalogue, so this screen cannot advertise a tool the
 * app does not ship. That is also why the page's copy carries no number:
 * the icon count is the truth, and a hard-coded "12 tools" would be a
 * second source of truth that goes stale.
 */
@Composable
fun OnboardingOverlay(
    pages: List<TutorialPage> = ONBOARDING_PAGES,
    aiIcons: List<ImageVector> = emptyList(),
    onFinish: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    var rawIndex by remember { mutableStateOf(0) }
    // Clamped on read, not on write: a restored index from process death
    // (or a shortened page list after an app update) can never address a
    // page that is not there.
    val index = clampTutorialIndex(rawIndex, pages.size)
    val page = pages.getOrNull(index) ?: return

    val isLast = isLastTutorialIndex(index, pages.size)

    // Back means "previous page" — on the first page it means "skip",
    // because there is nowhere to go back to and swallowing the gesture
    // would leave the user stuck.
    BackHandler {
        if (index > 0) rawIndex = index - 1 else onSkip()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(CinematicBlack, CinematicSurface, CinematicBlack)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Crossfade(
                targetState = page,
                animationSpec = tween(durationMillis = 220),
                label = "onboarding-page"
            ) { current ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TutorialArtBadge(
                        art = current.art,
                        aiIcons = aiIcons
                    )
                    Spacer(modifier = Modifier.height(36.dp))
                    Text(
                        text = t(current.title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = LabelPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = t(current.body),
                        style = MaterialTheme.typography.bodyLarge,
                        color = LabelSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            PageDots(count = pages.size, selected = index)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = t("${toPersianDigits(index + 1)} از ${toPersianDigits(pages.size)}"),
                style = MaterialTheme.typography.labelSmall,
                color = LabelTertiary
            )

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 460.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = t("رد کردن"),
                        style = MaterialTheme.typography.labelLarge,
                        color = LabelTertiary
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                iOSPillButton(
                    label = if (isLast) t("شروع کنید") else t("بعدی"),
                    onClick = {
                        if (isLast) onFinish() else rawIndex = index + 1
                    },
                    modifier = Modifier.widthIn(min = 148.dp)
                )
            }
        }
    }
}

/**
 * The illustration at the top of a page: one large glyph, except on
 * [TutorialArt.AI_TOOLS] where it becomes a small grid of the real tool
 * icons — the point of that page is "there are many of these", which one
 * glyph cannot say.
 */
@Composable
private fun TutorialArtBadge(
    art: TutorialArt,
    aiIcons: List<ImageVector>
) {
    Box(
        modifier = Modifier
            .size(148.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        accentPrimary().copy(alpha = 0.34f),
                        accentDeep().copy(alpha = 0.10f),
                        Color.Transparent
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (art == TutorialArt.AI_TOOLS && aiIcons.isNotEmpty()) {
            // Up to six tools, three per row — the same rhythm as the
            // Home grid, so the tutorial previews the real screen.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                aiIcons.take(6).chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = accentBright(),
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Icon(
                imageVector = artGlyph(art),
                contentDescription = null,
                tint = accentBright(),
                modifier = Modifier.size(64.dp)
            )
        }
    }
}

private fun artGlyph(art: TutorialArt): ImageVector = when (art) {
    TutorialArt.WELCOME -> Icons.Filled.Movie
    TutorialArt.TRANSCRIBE -> Icons.Filled.Subtitles
    TutorialArt.AI_TOOLS -> Icons.Filled.Movie
    TutorialArt.READY -> Icons.Filled.RocketLaunch
}

/** Page indicator: a pill for the current page, dots for the rest. */
@Composable
private fun PageDots(count: Int, selected: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { i ->
            val active = i == selected
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 22.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (active) accentBright() else LabelTertiary.copy(alpha = 0.35f)
                    )
            )
        }
    }
}
