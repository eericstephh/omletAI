package com.aicreatorstudio.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.ToolPanel
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.AccentCyan
import com.aicreatorstudio.app.ui.theme.AccentSecondary
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicSurface
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.StatusInfo
import com.aicreatorstudio.app.ui.theme.StatusSuccess
import com.aicreatorstudio.app.ui.theme.StatusWarning

/**
 * The home screen's AI capability catalogue.
 *
 * ## Why a catalogue and not a hard-coded list
 *
 * The previous home screen advertised six "smart capabilities", but:
 *
 *  - it was missing eight of the twelve AI features the app actually
 *    ships (OCR, silence cut, smart reframe, motion tracking, voice
 *    enhancement, beat sync, smart highlights, …), and
 *  - it listed "متن و پاراگراف", which is not an AI feature at all, and
 *  - three icons were reused for different tools (`AutoAwesome` for
 *    both upscale and highlights, `GraphicEq` for both voice tools,
 *    `CropFree` for both framing tools), so the icons did not
 *    distinguish anything.
 *
 * This file is the single list of what the app can do with AI. Every
 * entry maps to a [ToolPanel] the editor can open, so a row is a real
 * action and not an advertisement.
 *
 * ## Icon rules
 *
 * One distinct, literal icon per capability — a scanner for OCR, a
 * person for background removal, a portrait frame for auto-frame, an
 * HD badge for upscaling, scissors for cutting silence. No generic
 * "AI sparkle" for everything: the sparkle is reserved for the one
 * feature it actually describes (smart highlights, which ranks the
 * best moments).
 *
 * ## Description rules
 *
 * The descriptions are written to fit **one line** (they are
 * translated, and a translated line is longer than the Persian one).
 * Twelve rows at two description lines each would take ~940dp — more
 * than a whole screen — and push the user's own project list out of
 * view. The title carries the meaning; the description only has to
 * make the capability concrete.
 */
data class AiCapability(
    /** Stable id — also the route value used to deep-link the tool. */
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    /** Semantic accent token — never a bespoke colour. */
    val accent: Color,
    /** The editor panel this capability opens. */
    val panel: ToolPanel
)

/**
 * Every AI capability the app ships, in the order a user would look
 * for them: content first (subtitles, text, highlights), then picture
 * (framing, background, tracking, resolution), then sound, then
 * structure (silence, beats).
 */
val AI_CAPABILITIES: List<AiCapability> = listOf(
    AiCapability(
        id = "subtitles",
        title = "زیرنویس هوشمند",
        description = "زیرنویس فارسی با Whisper، کاملاً آفلاین",
        icon = Icons.Filled.Subtitles,
        accent = StatusSuccess,
        panel = ToolPanel.SUBTITLES
    ),
    AiCapability(
        id = "ocr",
        title = "متن‌یابی هوشمند",
        description = "استخراج متن فارسی، عربی و انگلیسی از تصویر",
        icon = Icons.Filled.DocumentScanner,
        accent = StatusInfo,
        panel = ToolPanel.AI_OCR
    ),
    AiCapability(
        id = "highlights",
        title = "هایلایت هوشمند",
        description = "پیدا کردن بهترین لحظه‌های ویدیو",
        icon = Icons.Filled.AutoAwesome,
        accent = AccentBright,
        panel = ToolPanel.AI_HIGHLIGHTS
    ),
    AiCapability(
        id = "matting",
        title = "حذف پس‌زمینه",
        description = "جداسازی افراد و جایگزینی پس‌زمینه",
        icon = Icons.Filled.Person,
        accent = AccentSecondary,
        panel = ToolPanel.AI_MATTING
    ),
    AiCapability(
        id = "autoframe",
        title = "قاب‌بندی خودکار",
        description = "قاب ۹:۱۶ که سوژه را در کادر نگه می‌دارد",
        icon = Icons.Filled.CropPortrait,
        accent = AccentCyan,
        panel = ToolPanel.AI_AUTOFRAME
    ),
    AiCapability(
        id = "reframe",
        title = "ری‌فریم هوشمند",
        description = "تغییر قاب ویدیو با پیش‌نمایش و تأیید",
        icon = Icons.Filled.CropFree,
        accent = StatusInfo,
        panel = ToolPanel.AI_REFRAME
    ),
    AiCapability(
        id = "motion_track",
        title = "ردیابی حرکت",
        description = "چسباندن متن و استیکر به سوژهٔ در حال حرکت",
        icon = Icons.Filled.MyLocation,
        accent = AccentBright,
        panel = ToolPanel.AI_MOTION_TRACK
    ),
    AiCapability(
        id = "upscale",
        title = "افزایش کیفیت",
        description = "ابره‌دقت ۲× و ۴× با شبکهٔ عصبی، آفلاین",
        icon = Icons.Filled.Hd,
        accent = AccentSecondary,
        panel = ToolPanel.UPSCALER
    ),
    AiCapability(
        id = "voice_clean",
        title = "پاک‌سازی صدا",
        description = "حذف نویز و صدای اضافهٔ پس‌زمینه",
        icon = Icons.Filled.GraphicEq,
        accent = StatusWarning,
        panel = ToolPanel.AI_VOICE_CLEAN
    ),
    AiCapability(
        id = "voice_enhance",
        title = "بهبود صدا",
        description = "متعادل‌سازی بلندی و گرم کردن صدای خام",
        icon = Icons.Filled.VolumeUp,
        accent = AccentCyan,
        panel = ToolPanel.AI_VOICE_ENHANCE
    ),
    AiCapability(
        id = "silence_cut",
        title = "برش سکوت",
        description = "حذف خودکار سکوت‌ها با تشخیص گفتار",
        icon = Icons.Filled.ContentCut,
        accent = StatusWarning,
        panel = ToolPanel.AI_SILENCE_CUT
    ),
    AiCapability(
        id = "beat_sync",
        title = "سینک ضرب",
        description = "پیشنهاد برش‌های هماهنگ با ریتم موزیک",
        icon = Icons.Filled.MusicNote,
        accent = AccentBright,
        panel = ToolPanel.AI_BEAT_SYNC
    )
)

/**
 * The home screen's "AI capabilities" section.
 *
 * 2026-10-27 — user-directed layout change: the capabilities sit in a
 * **3-per-row grid of compact tiles** (icon tile, one-line title, a
 * two-line description at most). The earlier one-capability-per-row
 * layout is gone, and so is its rationale — described below only
 * because it was the reason the code looked the way it did: a grid of
 * tiles had been tried and rejected for swallowing the descriptions,
 * which is why the tiles cap the description at two ellipsised lines
 * instead of letting it wrap raggedly at a third of the screen width.
 *
 * Four rows of three, in the order a user would look for them
 * (content first, then picture, then sound, then structure):
 *
 * ```
 * ┌────────┐ ┌────────┐ ┌────────┐
 * │ [icon] │ │ [icon] │ │ [icon] │
 * │ زیرنویس │ │ متن‌یابی │ │ هایلایت │
 * │ هوشمند │ │ هوشمند │ │ هوشمند │
 * └────────┘ └────────┘ └────────┘
 * ```
 *
 * Each tile is one tap target and opens the matching tool inside the
 * editor; [Role.Button] + the title as the click label keeps the
 * screen readable for assistive tech.
 */
@Composable
fun AiCapabilitySection(
    onCapabilityClick: (AiCapability) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = t("قابلیت‌های هوش مصنوعی"),
            color = LabelPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Rows of exactly three tiles; the 12 items divide evenly.
        // IntrinsicSize.Min stretches the shorter tiles in a row to
        // match the tallest one, so no row is ragged at the bottom.
        AI_CAPABILITIES.chunked(3).forEach { rowCapabilities ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowCapabilities.forEach { capability ->
                    AiCapabilityTile(
                        capability = capability,
                        onClick = { onCapabilityClick(capability) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

/**
 * One capability, one tile of the three-per-row grid.
 *
 * The tile is a single surface card with the icon on top so the
 * capability is identifiable by shape as well as by colour — the
 * twelve icons are deliberately all different (see
 * [AI_CAPABILITIES]), so a user who has learned "the scissors one is
 * cut silence" can find it again without reading. The title is the
 * label (kept to one line — every title fits); the description is
 * capped at two lines with an ellipsis rather than wrapped raggedly, because at a third of the screen width a full Persian
 * sentence becomes three uneven lines of noise.
 */
@Composable
private fun AiCapabilityTile(
    capability: AiCapability,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CinematicSurface)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(
                role = Role.Button,
                onClickLabel = capability.title,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // The icon tile: a clean shape on a quiet tint of the
        // capability's accent.
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(capability.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = capability.icon,
                contentDescription = null,
                tint = capability.accent,
                modifier = Modifier.size(18.dp)
            )
        }

        Text(
            text = t(capability.title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = LabelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = t(capability.description),
            style = MaterialTheme.typography.bodySmall,
            color = LabelSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
