package com.aicreatorstudio.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.model.AppReleaseNote
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.screens.editor.toFontFamily
import com.aicreatorstudio.app.ui.theme.AICreatorStudioTheme
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.LabelToolbar
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSRed
import com.aicreatorstudio.app.ui.theme.iOSShapes

// ---------------------------------------------------------------------------
// Palette — the sheet sits on the Home screen's purple gradient, so its
// surfaces are the same family as that gradient rather than the editor's
// neutral greys. Kept file-private: they are this sheet's identity, not
// theme tokens, and nothing else should reach for them.
// ---------------------------------------------------------------------------

private val SheetSurface = Color(0xFF14101F)
private val ReleaseCardSurface = Color(0x14FFFFFF)
private val CurrentReleaseCardSurface = Color(0x1F8B5CF6)

/**
 * The "تغییرات و آپدیت‌های جدید" bottom sheet behind the Home screen's
 * notification bell.
 *
 * ## Design notes
 *
 * - **RTL.** The whole sheet is Persian, so its content is wrapped in a
 *   `LayoutDirection.Rtl` boundary. The app forces LTR chrome
 *   (`NavGraph.kt`) precisely so that RTL is opted into per-surface rather
 *   than inherited; this is one such surface. Under RTL the version chips
 *   lead from the right, the date trails on the left, and each bullet dot
 *   sits to the right of its text — all without a single manual alignment.
 * - **Vazirmatn.** Persian text is rendered in the app's bundled Vazirmatn
 *   family rather than the theme's `FontFamily.Default`, because the default
 *   resolves to Roboto, whose Persian glyphs come from whatever fallback the
 *   device happens to have. Vazirmatn is shipped in the APK, so the sheet
 *   looks the same on every device.
 * - **The newest card is highlighted.** [AppReleaseNote.isCurrent] drives an
 *   accent border, a tinted surface and a "جدید" chip, so the thing the user
 *   was notified about is the first thing they see.
 *
 * ## Why the chrome goes through `t(...)`
 *
 * Titles, chips and the button are UI copy and are resolved through
 * [t] so they follow the in-app language like the rest of the app. The
 * feature bullets deliberately do **not**: they are the changelog's authored
 * content, stored in [com.aicreatorstudio.app.data.AppChangelogProvider], and
 * translating data at render time is how a changelog ends up half in one
 * language. Localising the release notes means localising the data.
 *
 * @param releases newest first, as authored. Empty is handled by hiding the
 *   list rather than by drawing an empty card.
 * @param currentVersionName shown in the header as "نسخه جاری …".
 * @param onDismiss dismissed without acknowledging (back press, scrim tap,
 *   swipe down). Leaves the unread badge alone.
 * @param onGotIt the "متوجه شدم" button: acknowledge and close.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateSheet(
    releases: List<AppReleaseNote>,
    currentVersionName: String,
    onDismiss: () -> Unit,
    onGotIt: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Not @Composable, so a plain remember is enough — and it avoids
    // re-resolving the font family on every recomposition.
    val persianFamily = remember { PersianFont.VAZIR.toFontFamily() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetSurface,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        // Replaces the default grey pill with the same handle the editor's
        // sheets use, so every sheet in the app reads as one component.
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(HairlineSubtle)
                )
            }
        },
        modifier = modifier
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Bounded height, which is what lets the list below use
                    // `weight(1f)` to claim the leftover space and scroll
                    // inside it while the footer stays put. Without a bound
                    // the list would be measured against the sheet's own
                    // unconstrained height and simply overflow.
                    .heightIn(max = 620.dp)
            ) {
                LazyColumn(
                    // `fill = false` so a short changelog does not stretch the
                    // sheet to 620dp; the list shrinks to its content and the
                    // sheet hugs it.
                    modifier = Modifier.weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "header") {
                        SheetHeader(
                            currentVersionName = currentVersionName,
                            persianFamily = persianFamily
                        )
                    }
                    items(releases, key = { it.versionCode }) { release ->
                        ReleaseCard(release = release, persianFamily = persianFamily)
                    }
                }

                SheetFooter(
                    onGotIt = onGotIt,
                    persianFamily = persianFamily
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun SheetHeader(
    currentVersionName: String,
    persianFamily: FontFamily
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Bell medallion — the same glyph as the TopBar button, so the sheet
        // visually answers the thing that was tapped.
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(iOSBlue, iOSBlueBright)))
                .border(1.dp, iOSBlueBright.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(23.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = t("تغییرات و قابلیت‌های جدید"),
                color = LabelPrimary,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = persianFamily),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                VersionChip(
                    label = "${t("نسخه جاری")} $currentVersionName",
                    persianFamily = persianFamily,
                    accent = true
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One release
// ---------------------------------------------------------------------------

@Composable
private fun ReleaseCard(
    release: AppReleaseNote,
    persianFamily: FontFamily
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (release.isCurrent) CurrentReleaseCardSurface else ReleaseCardSurface
            )
            .border(
                width = 1.dp,
                color = if (release.isCurrent) iOSBlueBright.copy(alpha = 0.38f)
                else HairlineSubtle,
                shape = RoundedCornerShape(18.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VersionChip(
                label = "${t("نسخه")} ${release.versionName}",
                persianFamily = persianFamily,
                accent = release.isCurrent
            )
            if (release.isCurrent) {
                Spacer(Modifier.width(6.dp))
                VersionChip(
                    label = t("جدید"),
                    persianFamily = persianFamily,
                    accent = true,
                    filled = true
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = release.releaseDate,
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = persianFamily)
            )
        }

        release.features.forEach { feature ->
            FeatureBullet(text = feature, persianFamily = persianFamily)
        }
    }
}

/**
 * A single bullet. The dot is a sibling of the text rather than a `•`
 * character in the string, so it keeps its own colour and its own optical
 * alignment regardless of the text's line height or the chosen font.
 *
 * `Alignment.Top` plus the dot's own top padding aligns the dot with the
 * first line's x-height instead of the top of the line box; under RTL the
 * `Row` lays the dot out on the right, which is where a Persian bullet
 * belongs.
 */
@Composable
private fun FeatureBullet(
    text: String,
    persianFamily: FontFamily
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(iOSBlueBright)
        )
        Text(
            text = text,
            color = LabelToolbar,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = persianFamily,
                lineHeight = 23.sp
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

// ---------------------------------------------------------------------------
// Small pieces
// ---------------------------------------------------------------------------

@Composable
private fun VersionChip(
    label: String,
    persianFamily: FontFamily,
    accent: Boolean,
    filled: Boolean = false
) {
    val tint = if (accent) iOSBlueBright else LabelSecondary
    Box(
        modifier = Modifier
            .clip(iOSShapes.capsule)
            .background(if (filled) tint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, tint.copy(alpha = if (accent) 0.45f else 0.25f), iOSShapes.capsule)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = persianFamily),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SheetFooter(
    onGotIt: () -> Unit,
    persianFamily: FontFamily
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 28.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(iOSShapes.capsule)
                .background(Brush.horizontalGradient(listOf(iOSBlue, iOSBlueBright)))
                .border(1.dp, iOSBlueBright.copy(alpha = 0.5f), iOSShapes.capsule)
                .clickable { onGotIt() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = t("متوجه شدم"),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = persianFamily),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF0C0714)
@Composable
private fun AppUpdateSheetContentPreview() {
    // The sheet's own content, previewed without the ModalBottomSheet
    // wrapper: `ModalBottomSheet` hosts itself in a separate window, so a
    // preview of the real thing renders the scrim and nothing else.
    AICreatorStudioTheme {
        val persianFamily = remember { PersianFont.VAZIR.toFontFamily() }
        val sample = listOf(
            AppReleaseNote(
                versionCode = 5,
                versionName = "1.1",
                releaseDate = "۱۰ آبان ۱۴۰۵",
                isCurrent = true,
                features = listOf(
                    "اصلاح هوشمند املای زیرنویس فارسی با هوش مصنوعی، همراه با پیش‌نمایش و تأیید پیش از اعمال",
                    "آموزش اولیه و تور راهنمای صفحهٔ اصلی برای آشنایی سریع با محیط و قابلیت‌ها",
                    "۱۲ ابزار هوش مصنوعی در ویرایشگر: زیرنویس هوشمند، حذف پس‌زمینه، متن‌یابی و افزایش کیفیت"
                )
            )
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SheetSurface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SheetHeader(currentVersionName = "1.1", persianFamily = persianFamily)
                sample.forEach { ReleaseCard(it, persianFamily) }
                SheetFooter(onGotIt = {}, persianFamily = persianFamily)
            }
        }
    }
}
