package com.aicreatorstudio.app.ui.screens.editor.subtitle

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.model.BuiltInSubtitleTemplates
import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.WordRevealMode
import com.aicreatorstudio.app.model.template.BackgroundOverride
import com.aicreatorstudio.app.model.template.withBackgroundOverride
import com.aicreatorstudio.app.model.template.withHighlightOverride
import com.aicreatorstudio.app.model.template.withMotionOverride
import com.aicreatorstudio.app.model.template.withPositionOverride
import com.aicreatorstudio.app.model.template.PositionSafeMargins
import com.aicreatorstudio.app.model.template.withColorOverride
import com.aicreatorstudio.app.model.template.withFontOverride
import com.aicreatorstudio.app.model.template.withShadowOverride
import com.aicreatorstudio.app.model.template.ColorOverride
import com.aicreatorstudio.app.model.template.FontOverride
import com.aicreatorstudio.app.model.template.ShadowOverride
import com.aicreatorstudio.app.model.withTemplate
import com.aicreatorstudio.app.ui.components.EmptyState
import com.aicreatorstudio.app.ui.screens.editor.toFontFamily
import com.aicreatorstudio.app.ui.theme.accentBright
import com.aicreatorstudio.app.ui.theme.accentDeep
import com.aicreatorstudio.app.ui.theme.accentPrimary
import com.aicreatorstudio.app.ui.theme.glowBrand
import com.aicreatorstudio.app.ui.theme.BorderSubtle
import com.aicreatorstudio.app.ui.theme.CinematicElevated
import com.aicreatorstudio.app.ui.theme.Hairline
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.LabelTertiary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSShapes
import com.aicreatorstudio.app.ui.theme.StatusError
import com.aicreatorstudio.app.ui.theme.SurfaceSunken
import com.aicreatorstudio.app.ui.theme.ctaBrush
import kotlin.math.roundToInt

/**
 * 2026-09-14 UPGRADE — «زیرنویس فارسی» studio panel v2
 * (Tools → زیرنویس فارسی).
 *
 * 2026-09-19 PHASE 4 — the template tab serves the JSON catalog
 * (17 entries in 5 categories) instead of the hand-built list;
 * the mapping + grouping live in JsonTemplateMapper so this file
 * stays a thin view. Customization tabs edit the working copy
 * per-axis (font/color/motion/background untouched by each other).
 *
 * Tabbed, CapCut-style control surface:
 *  1. قالب‌ها — JSON catalog grouped by category (one tap = full look)
 *  2. فونت — 11 Persian families + 10 Latin, weight-aware
 *  3. موشن — word-motion engine + entry/exit + reveal mode
 *  4. پس‌زمینه — background kind + active-word extras
 *
 * The WYSIWYG preview at the top renders the CURRENT template
 * exactly as the canvas will: style + stroke + shadow + background
 * kind + the word-motion engine cycling through sample words.
 * A custom combination can be SAVED as a user preset (custom
 * presets flow through the existing repository + apply pipeline).
 */
@Composable
fun SubtitleStudioPanel(
    durationMs: Long,
    playheadMs: Long,
    hasGeneratedSubtitles: Boolean,
    initialTemplate: SubtitleTemplate?,
    onApplyToAll: (SubtitleTemplate) -> Unit,
    onPickTemplate: (SubtitleTemplate) -> Unit,
    onAddSubtitle: (text: String, template: SubtitleTemplate, startMs: Long, endMs: Long) -> Unit,
    onOpenAutoSubtitles: () -> Unit,
    // 2026-10-15 — global subtitle font size (batch resize of every
    // subtitle on the timeline, single undo step). The slider drives
    // the live path (dragStart / live / dragEnd — one history entry
    // per gesture); the +/- chips and the apply button drive the
    // discrete path. All default to no-ops so existing callers are
    // unchanged.
    onBeginGlobalSubtitleResize: () -> Unit = {},
    onGlobalSubtitleSizeLive: (Float) -> Unit = {},
    onEndGlobalSubtitleResize: () -> Unit = {},
    onSetGlobalSubtitleSize: (Float) -> Unit = {},
    onScaleAllSubtitles: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
    // 2026-09-20 — inline text editor for the currently-selected
    // subtitle. When the user taps a subtitle on the timeline, a
    // compact editor bar appears above the tabs so they can fix
    // typos without switching panels.
    selectedTextOverlay: com.aicreatorstudio.app.model.TextOverlay? = null,
    onUpdateTextOverlay: (textId: String, newText: String) -> Unit = { _, _ -> },
    onDeleteTextOverlay: (textId: String) -> Unit = {},
    onClearTextSelection: () -> Unit = {},
    // 2026-10-13 — CapCut-style editing workflow for the selected
    // subtitle. Every callback relays to an `EditorViewModel` action
    // that routes through `model/SubtitleEditOps` + the undo history.
    // Defaults are no-ops so existing callers (and the studio preview)
    // compile and behave unchanged.
    onSplitSubtitle: (textId: String, wordIndex: Int) -> Unit = { _, _ -> },
    onMergeSubtitleWithNext: (textId: String) -> Unit = {},
    onRetimeSubtitle: (textId: String, startMs: Long, endMs: Long) -> Unit = { _, _, _ -> },
    onRetimeSubtitleWord: (textId: String, wordIndex: Int, startMs: Long, endMs: Long) -> Unit =
        { _, _, _, _ -> },
    onResyncSubtitleText: (textId: String) -> Unit = {},
    // 2026-10-12 — user-imported fonts. The strip is rendered inside
    // the font tab; these are the relay callbacks. Defaults (empty list
    // + no-ops) keep every existing caller compiling and show no strip.
    customFonts: List<com.aicreatorstudio.app.model.CustomFont> = emptyList(),
    onImportCustomFont: (uri: String) -> Unit = {},
    onDeleteCustomFont: (fontId: String) -> Unit = {},
    // 2026-09-19 PHASE 4 — JSON catalog source (mapped engine
    // templates) + load status. Defaults preserve the legacy
    // hand-built catalog for any external caller.
    catalogTemplates: List<SubtitleTemplate> = BuiltInSubtitleTemplates.ALL,
    catalogStatus: com.aicreatorstudio.app.model.template.JsonCatalogStatus =
        com.aicreatorstudio.app.model.template.JsonCatalogStatus.READY
) {
    // Catalog default: Karaoke Classic first, else head of catalog.
    // (Not BuiltInSubtitleTemplates.DEFAULT: the picker serves the
    // JSON catalog now; the hand-built default only covers the
    // legacy-default call path.)
    val catalogDefault = catalogTemplates.firstOrNull { it.id == "karaoke_classic" }
        ?: catalogTemplates.firstOrNull()
    // 2026-09-16 BUGFIX — seed from the FULL persisted template
    // (exact tweaks), not just its id; and key the remember on it
    // so the selection refreshes if the default changes while the
    // panel is composed.
    var selectedPreset by remember(initialTemplate) {
        mutableStateOf(
            initialTemplate
                ?: catalogDefault
                ?: BuiltInSubtitleTemplates.DEFAULT
        )
    }
    // 2026-09-19 PHASE 4 — the catalog arrives async (LOADING →
    // READY) AFTER first composition. Re-seed once when it lands,
    // unless the user already picked (no clobber, no flicker: the
    // grid shows a same-metrics skeleton meanwhile).
    var userPicked by remember { mutableStateOf(false) }
    // 2026-09-19 PHASE 6 — per-axis user overrides. The Highlight
    // tab is the first writer: it touches ONLY the Highlight axis
    // here (other axes stay null = base template wins). The working
    // copy below is refreshed through withHighlightOverride so the
    // preview/export pipeline (which reads SubtitleTemplate) and
    // the overrides state can never disagree.
    var overrides by remember {
        mutableStateOf(com.aicreatorstudio.app.model.template.TemplateOverrides.Empty)
    }
    androidx.compose.runtime.LaunchedEffect(initialTemplate, catalogTemplates) {
        if (initialTemplate != null) {
            selectedPreset = initialTemplate
            overrides = com.aicreatorstudio.app.model.template.TemplateOverrides.Empty
            userPicked = false
        } else if (!userPicked) {
            catalogDefault?.let { selectedPreset = it }
        }
    }
    var activeTab by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    var startText by remember { mutableStateOf("") }
    var endText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var applied by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    // 2026-10-16 (Phase 2) — template-picker category filter.
    //
    // The picker renders all 25 catalog templates grouped under 8 category
    // headings. In a 2-column grid that is ~13 rows / roughly 2,600dp of
    // unbroken scrolling inside a panel that already scrolls in its own
    // sheet, with no way to jump to a category and no indication of how
    // many templates sit in each one. This is a pure VIEW filter over the
    // same `SubtitleTemplateTaxonomy.group` result the grid already used:
    // template ids, styles, word timing, active-word highlighting and the
    // preview/export rendering path are all untouched. `null` = «همه».
    var pickerCategory by remember {
        mutableStateOf<com.aicreatorstudio.app.model.template.SubtitleTemplateCategory?>(
            null
        )
    }

    // 2026-09-20 PHASE — compact inline text editor for the
    // currently-selected subtitle. Appears above the tabs when
    // the user taps a subtitle on the timeline. Shows the text
    // in an OutlinedTextField with Save / Delete / Cancel.
    var editTextBuffer by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(selectedTextOverlay) {
        editTextBuffer = selectedTextOverlay?.text ?: ""
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---- Tabs (scrollable for 8 tabs on narrow screens) ----
        // Tabs FIRST: the parent panel scrolls vertically, so the
        // tab bar must appear before any tall content (preview card)
        // to stay visible when the panel opens.
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            listOf("قالب‌ها", "فونت", "رنگ", "سایه", "هایلایت", "پس‌زمینه", "موشن", "موقعیت").forEachIndexed { i, label ->
                Box(
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        // 2026-10-17 PHASE 3 — 8 primary tab chips were ~27 dp
                        // tall (labelSmall + 6 dp vertical padding), far below
                        // the 44 dp floor the editor chrome uses. 40 dp is the
                        // smallest honest step that keeps the row on one line
                        // while making the app's main panel navigation a
                        // reliable touch target.
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (activeTab == i) accentPrimary().copy(alpha = 0.22f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            1.dp,
                            if (activeTab == i) accentBright() else HairlineSubtle,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { activeTab = i }
                        // 2026-10-17 PHASE 3 — the active axis tab was signalled
                        // only by colour/border, which a screen reader cannot
                        // perceive. Expose the selection as semantics so the
                        // eight-axis navigation is comprehensible non-visually.
                        .semantics { selected = activeTab == i }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (activeTab == i) accentBright() else LabelSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (activeTab == i) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }

        // ---- WYSIWYG live preview (full motion) ----
        // Only on the templates tab to avoid pushing content below
        // the fold on other tabs.
        if (activeTab == 0) {
            TemplatePreviewCard(
                template = selectedPreset,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceSunken)
                    .border(1.dp, accentPrimary().copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                    .padding(vertical = 12.dp, horizontal = 14.dp)
            )
        }

        when (activeTab) {
            // ================= TEMPLATES (JSON catalog, grouped) =================
            0 -> {
                // 2026-10-17 PHASE 3 — LOADING and EMPTY used to be the SAME
                // branch: a permanently pulsing skeleton. `JsonCatalogStatus`
                // has three values and the third one (`EMPTY`, the never-crash
                // fallback for a missing/unreadable bundled asset) is not
                // transient — it never resolves. Showing a skeleton forever
                // told the user "still loading" when the catalogue was in fact
                // unavailable. The three states are now distinct.
                if (catalogTemplates.isNotEmpty()) {
                    // 2026-10-16 — browse by the EIGHT product
                    // categories (viral bold, word-by-word karaoke,
                    // podcast/interview, minimal-cinematic, educational,
                    // keyword-emphasis, clean Persian-first, rounded/
                    // outlined) instead of the raw 5-key authoring
                    // grouping, so every requested category is its own
                    // section. Purely presentational: template ids,
                    // styles and the rendered output are untouched.
                    val groups =
                        com.aicreatorstudio.app.model.template.SubtitleTemplateTaxonomy
                            .group(catalogTemplates)
                    // 2026-10-16 (Phase 2) — category navigation. `visibleGroups`
                    // is a VIEW filter over the same grouping: picking a
                    // category hides the other sections but changes no
                    // template, style, timing or rendering behaviour. A chip
                    // can never claim more templates than the grid shows,
                    // because the counts come from the same list.
                    val visibleGroups: List<
                        Pair<
                            com.aicreatorstudio.app.model.template.SubtitleTemplateCategory,
                            List<SubtitleTemplate>
                            >
                        > =
                        if (pickerCategory == null) {
                            groups
                        } else {
                            groups.filter { it.first == pickerCategory }
                                .takeIf { it.isNotEmpty() }
                                ?: groups
                        }
                    // 2026-10-17 PHASE 3 — the header and the category chips used
                    // to disagree: the chips show real counts, but the header
                    // always claimed the FULL catalogue size, so filtering to a
                    // 3-template category still read «قالب‌های آماده فارسی (25)».
                    // It now reports what is actually on screen.
                    val shownCount = visibleGroups.sumOf { it.second.size }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "قالب‌های آماده فارسی ($shownCount)",
                            color = LabelPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // Category chips: «همه» plus one per category, each
                        // with its template count. Horizontal + RTL-aware
                        // (the panel's scroll container mirrors with it).
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            ModeChip(
                                label = "همه (${catalogTemplates.size})",
                                selected = pickerCategory == null,
                                onClick = { pickerCategory = null }
                            )
                            groups.forEach { (category, items) ->
                                ModeChip(
                                    label = "${category.displayNameFa} (${items.size})",
                                    selected = pickerCategory == category,
                                    onClick = { pickerCategory = category }
                                )
                            }
                        }
                        visibleGroups.forEach { (category, items) ->
                            Text(
                                text = category.displayNameFa,
                                color = LabelSecondary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            // Two-per-row grid (chunked Rows, not a nested
                            // lazy grid — the panel itself scrolls). Every
                            // card shows a REAL static Persian preview of
                            // that template (font, colors, background,
                            // active word) plus fa/en names and the
                            // selected ring. One tap applies it live to
                            // generated subtitles (and remembers it as the
                            // default); with no subtitles yet it is just
                            // remembered for future generations.
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items.chunked(2).forEach { row ->
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        row.forEach { t ->
                                            TemplateGridCard(
                                                template = t,
                                                selected = t.id == selectedPreset.id,
                                                onClick = {
                                                    userPicked = true
                                                    // New base → axis overrides restart
                                                    // (a stale highlight must never leak
                                                    // onto another template).
                                                    overrides = com.aicreatorstudio.app.model.template.TemplateOverrides.Empty
                                                    selectedPreset = t
                                                    if (hasGeneratedSubtitles) {
                                                        onApplyToAll(t)
                                                        applied = true
                                                    } else {
                                                        onPickTemplate(t)
                                                        applied = false
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        // Keep pairs aligned on the
                                        // (never-empty) odd tail.
                                        if (row.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (
                    catalogStatus ==
                    com.aicreatorstudio.app.model.template.JsonCatalogStatus.LOADING
                ) {
                    // Stable skeleton while LOADING: same 2-col metrics as the
                    // real grid, zero animation — no jank, no flicker, no crash.
                    TemplateGridSkeleton()
                } else {
                    TemplateCatalogUnavailable()
                }
            }

            // ================= FONT (per-axis override isolation) =================
            1 -> {
                val currentFontOverride = overrides.font
                fun applyFont(next: FontOverride?) {
                    val merged = (overrides.font ?: FontOverride())
                    val updated = if (next == null) null else merged.copy(
                        family = next.family ?: merged.family,
                        weight = next.weight ?: merged.weight,
                        sizeRatio = next.sizeRatio ?: merged.sizeRatio
                    )
                    userPicked = true
                    overrides = overrides.copy(font = updated)
                    selectedPreset = with(
                        com.aicreatorstudio.app.model.template.JsonTemplateMapper
                    ) { selectedPreset.withFontOverride(updated) }
                    applied = false
                }
                Text(
                    text = "فونت فارسی",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // 2026-10-12 — imported fonts FIRST, above the bundled
                // set: a user who just added a font looks for it at the
                // top of the list, not below 21 built-ins.
                com.aicreatorstudio.app.ui.screens.editor.CustomFontStrip(
                    customFonts = customFonts,
                    selectedFontFamily = selectedPreset.style.fontFamily,
                    onPick = { reference ->
                        userPicked = true
                        applied = false
                        // Same override path a bundled family uses, so
                        // the preview, canvas and export all follow.
                        applyFont(FontOverride(family = reference))
                    },
                    onImport = onImportCustomFont,
                    onDelete = onDeleteCustomFont,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    PersianFont.PersianFamilies.forEach { f ->
                        val selected = selectedPreset.style.fontFamily == f.name
                        Box(
                            modifier = Modifier
                                .clip(iOSShapes.capsule)
                                .background(
                                    if (selected) accentPrimary().copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.05f)
                                )
                                .border(
                                    1.dp,
                                    if (selected) accentBright() else HairlineSubtle,
                                    iOSShapes.capsule
                                )
                                .clickable { applyFont(FontOverride(family = f.name)) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = f.displayName,
                                color = if (selected) accentBright() else LabelSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = f.toFontFamily(),
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
                Text(
                    text = "فونت لاتین",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        PersianFont.ROBOTO, PersianFont.OPEN_SANS, PersianFont.MONTSERRAT,
                        PersianFont.POPPINS, PersianFont.INTER, PersianFont.OSWALD,
                        PersianFont.BEBAS_NEUE, PersianFont.ANTON, PersianFont.LOBSTER,
                        PersianFont.PLAYFAIR_DISPLAY
                    ).forEach { f ->
                        val selected = selectedPreset.style.fontFamily == f.name
                        Box(
                            modifier = Modifier
                                .clip(iOSShapes.capsule)
                                .background(
                                    if (selected) accentPrimary().copy(alpha = 0.22f)
                                    else Color.White.copy(alpha = 0.05f)
                                )
                                .border(
                                    1.dp,
                                    if (selected) accentBright() else HairlineSubtle,
                                    iOSShapes.capsule
                                )
                                .clickable { applyFont(FontOverride(family = f.name)) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = f.displayName,
                                color = if (selected) accentBright() else LabelSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = f.toFontFamily()
                            )
                        }
                    }
                }
                // Weight + size
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "نازک" to "regular",
                        "بولد" to "bold"
                    ).forEach { (label, weight) ->
                        ModeChip(
                            label = label,
                            selected = currentFontOverride?.weight == weight ||
                                (!userPicked && selectedPreset.style.bold == (weight == "bold")),
                            onClick = { applyFont(FontOverride(weight = weight)) }
                        )
                    }
                }
                // --------------------------------------------------
                // 2026-10-15 — GLOBAL SUBTITLE SIZE (batch resize).
                //
                // Separate from the per-axis `sizeRatio` override
                // above: the override changes the PRESET's size for
                // whatever gets applied next; this control rewrites
                // the size of every subtitle already on the
                // timeline, in one undo step.
                //
                // The slider is the live-drag path — one history
                // snapshot per gesture (begin on first change),
                // state-only updates while dragging, one persist on
                // release. The +/- chips and the apply button are
                // the discrete path (single push each).
                // --------------------------------------------------
                GlobalSubtitleSizeControl(
                    initialSizeSp = selectedPreset.style.fontSizeSp,
                    onDragStart = onBeginGlobalSubtitleResize,
                    onLiveSize = onGlobalSubtitleSizeLive,
                    onDragEnd = onEndGlobalSubtitleResize,
                    onDiscreteSize = onSetGlobalSubtitleSize,
                    onScale = onScaleAllSubtitles
                )
                // Reset button
                Box(
                    modifier = Modifier
                        .clip(iOSShapes.capsule)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            overrides = overrides.cleared(com.aicreatorstudio.app.model.template.TemplateAxis.FONT)
                            val base = catalogTemplates.firstOrNull { it.id == selectedPreset.id } ?: initialTemplate
                            if (base != null) {
                                selectedPreset = selectedPreset.copy(style = base.style)
                            }
                            applied = false
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(text = "بازنشانی فونت", color = LabelSecondary, style = MaterialTheme.typography.labelSmall) }
                Text(text = "تغییرات فونت فقط محور font را عوض می‌کند؛ رنگ، سایه، هایلایت، پس‌زمینه، موشن و موقعیت دست‌نخورده می‌مانند.", color = LabelSecondary, style = MaterialTheme.typography.labelSmall)
            }

            // ================= COLOR (per-axis override isolation) =================
            2 -> {
                val currentColorOverride = overrides.color
                val effectiveColor = currentColorOverride?.base?.let {
                    with(com.aicreatorstudio.app.model.template.JsonTemplateMapper) {
                        parseColor(it, selectedPreset.style.textColorArgb)
                    }
                } ?: selectedPreset.style.textColorArgb

                fun applyColor(next: ColorOverride?) {
                    val merged = (overrides.color ?: ColorOverride())
                    val updated = if (next == null) null else merged.copy(
                        base = next.base ?: merged.base
                    )
                    userPicked = true
                    overrides = overrides.copy(color = updated)
                    selectedPreset = with(
                        com.aicreatorstudio.app.model.template.JsonTemplateMapper
                    ) { selectedPreset.withColorOverride(updated) }
                    applied = false
                }

                Text(
                    text = "رنگ متن",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // Preset color swatches for common caption colors.
                val colorSwatches = listOf(
                    0xFFFFFFFF.toInt() to "سفید",
                    0xFFFFD60A.toInt() to "طلایی",
                    0xFFFF375F.toInt() to "قرمز",
                    0xFF00E5FF.toInt() to "آبی",
                    0xFF30D158.toInt() to "سبز",
                    0xFFFF9F0A.toInt() to "نارنجی",
                    0xFFBF5AF2.toInt() to "بنفش",
                    0xFFFF453A.toInt() to "کoral"
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    colorSwatches.forEach { (color, label) ->
                        val isSelected = effectiveColor == color
                        Box(
                            modifier = Modifier
                                .clip(iOSShapes.capsule)
                                .background(Color(color))
                                .border(
                                    2.dp,
                                    if (isSelected) accentBright() else Color.Transparent,
                                    iOSShapes.capsule
                                )
                                .clickable {
                                    applyColor(ColorOverride(base = String.format("#%06X", color and 0xFFFFFF)))
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (color == 0xFFFFFFFF.toInt() || color == 0xFFFFD60A.toInt()) Color.Black else Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
                // Hex color input.
                var hexInput by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    label = { Text("کد هگز رنگ (مثلاً #FF0000)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                Box(
                    modifier = Modifier
                        .clip(iOSShapes.capsule)
                        .background(accentPrimary().copy(alpha = 0.18f))
                        .clickable {
                            val hex = hexInput.trim().let { if (it.startsWith("#")) it else "#$it" }
                            if (hex.length == 7) {
                                applyColor(ColorOverride(base = hex))
                                hexInput = ""
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(text = "اعمال رنگ", color = accentBright(), style = MaterialTheme.typography.labelSmall) }
                // Reset button
                Box(
                    modifier = Modifier
                        .clip(iOSShapes.capsule)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            overrides = overrides.cleared(com.aicreatorstudio.app.model.template.TemplateAxis.COLOR)
                            val base = catalogTemplates.firstOrNull { it.id == selectedPreset.id } ?: initialTemplate
                            if (base != null) {
                                selectedPreset = selectedPreset.copy(style = base.style, activeWordColorArgb = base.activeWordColorArgb)
                            }
                            applied = false
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(text = "بازنشانی رنگ", color = LabelSecondary, style = MaterialTheme.typography.labelSmall) }
                Text(text = "تغییرات رنگ فقط محور color را عوض می‌کند؛ فونت، سایه، هایلایت، پس‌زمینه، موشن و موقعیت دست‌نخورده می‌مانند.", color = LabelSecondary, style = MaterialTheme.typography.labelSmall)
            }

            // ================= SHADOW/STROKE (per-axis override isolation) =================
            3 -> {
                val currentShadowOverride = overrides.shadow
                fun applyShadow(next: ShadowOverride?) {
                    val merged = (overrides.shadow ?: ShadowOverride())
                    val updated = if (next == null) null else merged.copy(
                        type = next.type ?: merged.type,
                        color = next.color ?: merged.color,
                        width = next.width ?: merged.width,
                        radius = next.radius ?: merged.radius
                    )
                    userPicked = true
                    overrides = overrides.copy(shadow = updated)
                    selectedPreset = with(
                        com.aicreatorstudio.app.model.template.JsonTemplateMapper
                    ) { selectedPreset.withShadowOverride(updated) }
                    applied = false
                }
                Text(
                    text = "سایه و حاشیه متن",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // Shadow type selector.
                val shadowTypes = listOf(
                    Triple("outline", "حاشیه", "متن با حاشیه رنگی"),
                    Triple("dropshadow", "سایه", "متن با سایه افتاده"),
                    Triple("glow", "درخشش", "نور درخشان دور متن"),
                    Triple("none", "بدون", "بدون سایه و حاشیه")
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shadowTypes.forEach { (type, label, desc) ->
                        val selected = currentShadowOverride?.type == type ||
                            (!userPicked && currentShadowOverride == null && type == "outline")
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) accentPrimary().copy(alpha = 0.18f)
                                    else Color.White.copy(alpha = 0.05f)
                                )
                                .border(
                                    1.dp,
                                    if (selected) accentBright() else HairlineSubtle,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { applyShadow(ShadowOverride(type = type)) }
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Column {
                                Text(
                                    text = label,
                                    color = if (selected) accentBright() else LabelPrimary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = desc,
                                    color = LabelSecondary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                // Glow radius slider (only when type = glow).
                if (currentShadowOverride?.type == "glow") {
                    Text(
                        text = "شعاع درخشش: ${String.format("%.1f", currentShadowOverride.radius ?: 4f)}",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = currentShadowOverride.radius ?: 4f,
                        onValueChange = { applyShadow(ShadowOverride(type = "glow", radius = it)) },
                        valueRange = 1f..20f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Stroke width slider (only when type = outline).
                if (currentShadowOverride?.type == "outline") {
                    Text(
                        text = "ضخامت حاشیه: ${String.format("%.1f", currentShadowOverride.width ?: 2f)}",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = currentShadowOverride.width ?: 2f,
                        onValueChange = { applyShadow(ShadowOverride(type = "outline", width = it)) },
                        valueRange = 0.5f..8f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Reset button
                Box(
                    modifier = Modifier
                        .clip(iOSShapes.capsule)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            overrides = overrides.cleared(com.aicreatorstudio.app.model.template.TemplateAxis.SHADOW)
                            val base = catalogTemplates.firstOrNull { it.id == selectedPreset.id } ?: initialTemplate
                            if (base != null) {
                                selectedPreset = selectedPreset.copy(style = base.style, activeWordGlowRadiusDp = base.activeWordGlowRadiusDp)
                            }
                            applied = false
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(text = "بازنشانی سایه", color = LabelSecondary, style = MaterialTheme.typography.labelSmall) }
                Text(text = "تغییرات سایه فقط محور shadow را عوض می‌کند؛ فونت، رنگ، هایلایت، پس‌زمینه، موشن و موقعیت دست‌نخورده می‌مانند.", color = LabelSecondary, style = MaterialTheme.typography.labelSmall)
            }

            // ================= POSITION (per-axis overrides, PHASE 9) =================
            7 -> {
                val currentPosOverride = overrides.position
                val effectiveX = currentPosOverride?.positionX
                    ?: selectedPreset.style.positionX
                val effectiveY = currentPosOverride?.positionY
                    ?: selectedPreset.style.positionY
                val effectiveScale = currentPosOverride?.scaleRatio ?: 1.0f

                fun applyPosition(next: com.aicreatorstudio.app.model.template.PositionOverride?) {
                    val merged = (overrides.position ?: com.aicreatorstudio.app.model.template.PositionOverride())
                    val updated = if (next == null) null else merged.copy(
                        positionX = next.positionX ?: merged.positionX,
                        positionY = next.positionY ?: merged.positionY,
                        scaleRatio = next.scaleRatio ?: merged.scaleRatio
                    )
                    userPicked = true
                    overrides = overrides.copy(position = updated)
                    selectedPreset = with(
                        com.aicreatorstudio.app.model.template.JsonTemplateMapper
                    ) { selectedPreset.withPositionOverride(updated) }
                    applied = false
                }

                Text(
                    text = "موقعیت زیرنویس در کادر",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        listOf(
                            Triple(0.15f, 0.12f, "بالا چپ"),
                            Triple(0.5f, 0.12f, "بالا"),
                            Triple(0.85f, 0.12f, "بالا راست")
                        ),
                        listOf(
                            Triple(0.15f, 0.5f, "وسط چپ"),
                            Triple(0.5f, 0.5f, "وسط کادر"),
                            Triple(0.85f, 0.5f, "وسط راست")
                        ),
                        listOf(
                            Triple(0.15f, 0.82f, "پایین چپ"),
                            Triple(0.5f, 0.82f, "پایین"),
                            Triple(0.85f, 0.82f, "پایین راست")
                        )
                    ).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            row.forEach { (x, y, label) ->
                                val isSelected =
                                    kotlin.math.abs(effectiveX - x) < 0.01f &&
                                        kotlin.math.abs(effectiveY - y) < 0.01f
                                val inDanger = PositionSafeMargins.isInDangerZone(y)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                isSelected -> accentPrimary().copy(alpha = 0.18f)
                                                inDanger -> StatusError.copy(alpha = 0.08f)
                                                else -> Color.White.copy(alpha = 0.05f)
                                            }
                                        )
                                        .border(
                                            1.dp,
                                            when {
                                                isSelected -> accentBright()
                                                inDanger -> StatusError.copy(alpha = 0.4f)
                                                else -> HairlineSubtle
                                            },
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            applyPosition(
                                                com.aicreatorstudio.app.model.template.PositionOverride(
                                                    positionX = x, positionY = y
                                                )
                                            )
                                        }
                                        .padding(vertical = 12.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = when {
                                            isSelected -> accentBright()
                                            inDanger -> StatusError
                                            else -> LabelSecondary
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
                if (PositionSafeMargins.isInDangerZone(effectiveY)) {
                    Text(
                        text = "این موقعیت ممکن است زیر آیکون‌های ریلز/استوری پنهان شود.",
                        color = StatusError,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(text = "تنظیم دقیق X", color = LabelPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = effectiveX,
                    onValueChange = { applyPosition(com.aicreatorstudio.app.model.template.PositionOverride(positionX = PositionSafeMargins.clampSafeX(it))) },
                    valueRange = PositionSafeMargins.HORIZONTAL..(1f - PositionSafeMargins.HORIZONTAL),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(text = "تنظیم دقیق Y", color = LabelPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = effectiveY,
                    onValueChange = { applyPosition(com.aicreatorstudio.app.model.template.PositionOverride(positionY = PositionSafeMargins.clampSafeY(it))) },
                    valueRange = PositionSafeMargins.TOP..(1f - PositionSafeMargins.BOTTOM),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(text = "مقیاس متن: ${String.format("%.1f", effectiveScale)}x", color = LabelPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = effectiveScale,
                    onValueChange = { raw ->
                        val snapped = if (kotlin.math.abs(raw - 1.0f) < 0.03f) 1.0f else raw
                        applyPosition(com.aicreatorstudio.app.model.template.PositionOverride(scaleRatio = snapped))
                    },
                    valueRange = 0.5f..2.0f,
                    steps = 29,
                    modifier = Modifier.fillMaxWidth()
                )
                // Reset button
                Box(
                    modifier = Modifier
                        .clip(iOSShapes.capsule)
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            overrides = overrides.cleared(com.aicreatorstudio.app.model.template.TemplateAxis.POSITION)
                            val base = catalogTemplates.firstOrNull { it.id == selectedPreset.id } ?: initialTemplate
                            if (base != null) {
                                selectedPreset = selectedPreset.copy(style = base.style)
                            }
                            applied = false
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) { Text(text = "بازنشانی موقعیت", color = LabelSecondary, style = MaterialTheme.typography.labelSmall) }
                Text(text = "تغییرات موقعیت فقط محور position را عوض می‌کند؛ فونت، رنگ، سایه، هایلایت، موشن و پس‌زمینه دست‌نخورده می‌مانند.", color = LabelSecondary, style = MaterialTheme.typography.labelSmall)
            }

            // ================= MOTION (per-axis overrides only) =================
            6 -> {
                val currentMotionOverride = overrides.motion

                fun applyMotion(next: com.aicreatorstudio.app.model.template.MotionOverride?) {
                    val merged = (overrides.motion ?: com.aicreatorstudio.app.model.template.MotionOverride())
                    val updated = if (next == null) null else merged.copy(
                        entryType = next.entryType ?: merged.entryType,
                        exitType = next.exitType ?: merged.exitType,
                        entryDuration = next.entryDuration ?: merged.entryDuration,
                        entryEasing = next.entryEasing ?: merged.entryEasing,
                        exitEasing = next.exitEasing ?: merged.exitEasing
                    )
                    userPicked = true
                    overrides = overrides.copy(motion = updated)
                    selectedPreset = with(
                        com.aicreatorstudio.app.model.template.JsonTemplateMapper
                    ) {
                        selectedPreset.withMotionOverride(updated)
                    }
                    applied = false
                }

                Text(
                    text = "موشن کلمه‌به‌کلمه",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    WordMotionKind.entries.forEach { kind ->
                        val selected = selectedPreset.motionKind == kind
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) accentPrimary().copy(alpha = 0.16f)
                                    else Color.White.copy(alpha = 0.04f)
                                )
                                .clickable {
                                    selectedPreset = selectedPreset.copy(motionKind = kind)
                                    applied = false
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = kind.displayName,
                                color = if (selected) accentBright() else LabelPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
                Text(
                    text = "ورود / خروج",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        "ورود: هیچ" to "none",
                        "ورود: فید" to "fadein",
                        "ورود: پاپ" to "popin",
                        "ورود: اسلاید" to "slideinup",
                        "ورود: گلیچ" to "glitchin"
                    ).forEach { (label, type) ->
                        ModeChip(
                            label = label,
                            selected = currentMotionOverride?.entryType == type,
                            onClick = {
                                applyMotion(
                                    com.aicreatorstudio.app.model.template.MotionOverride(
                                        entryType = type
                                    )
                                )
                            }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        "خروج: هیچ" to "none",
                        "خروج: فید" to "fadeout",
                        "خروج: گلیچ" to "glitchout"
                    ).forEach { (label, type) ->
                        ModeChip(
                            label = label,
                            selected = currentMotionOverride?.exitType == type,
                            onClick = {
                                applyMotion(
                                    com.aicreatorstudio.app.model.template.MotionOverride(
                                        exitType = type
                                    )
                                )
                            }
                        )
                    }
                }
                Text(
                    text = "نوع حرکت (Easing) ورود",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    com.aicreatorstudio.app.ai.EasingFunctions.Easing.entries.forEach { easing ->
                        ModeChip(
                            label = easing.displayName,
                            selected = (currentMotionOverride?.entryEasing
                                ?: selectedPreset.easingIn) == easing.name,
                            onClick = {
                                applyMotion(
                                    com.aicreatorstudio.app.model.template.MotionOverride(
                                        entryEasing = easing.name
                                    )
                                )
                            }
                        )
                    }
                }
                Text(
                    text = "نوع حرکت (Easing) خروج",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    com.aicreatorstudio.app.ai.EasingFunctions.Easing.entries.forEach { easing ->
                        ModeChip(
                            label = easing.displayName,
                            selected = (currentMotionOverride?.exitEasing
                                ?: selectedPreset.easingOut) == easing.name,
                            onClick = {
                                applyMotion(
                                    com.aicreatorstudio.app.model.template.MotionOverride(
                                        exitEasing = easing.name
                                    )
                                )
                            }
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    WordRevealMode.entries.forEach { mode ->
                        ModeChip(
                            label = mode.displayName,
                            selected = selectedPreset.wordRevealMode == mode,
                            onClick = {
                                selectedPreset = selectedPreset.copy(wordRevealMode = mode)
                            }
                        )
                    }
                }
                Text(
                    text = "مدت افکت: ${selectedPreset.animationDurationMs}ms",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = selectedPreset.animationDurationMs.toFloat(),
                    onValueChange = { newDur ->
                        applyMotion(
                            com.aicreatorstudio.app.model.template.MotionOverride(
                                entryDuration = newDur.toDouble() / 1000.0
                            )
                        )
                    },
                    valueRange = 60f..1500f,
                    steps = 23,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(iOSShapes.capsule)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable {
                                overrides = overrides.cleared(
                                    com.aicreatorstudio.app.model.template.TemplateAxis.MOTION
                                )
                                val base = catalogTemplates.firstOrNull {
                                    it.id == selectedPreset.id
                                } ?: initialTemplate
                                if (base != null) {
                                    selectedPreset = selectedPreset.copy(
                                        animationIn = base.animationIn,
                                        animationOut = base.animationOut,
                                        animationDurationMs = base.animationDurationMs,
                                        easingIn = base.easingIn,
                                        easingOut = base.easingOut
                                    )
                                }
                                applied = false
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "بازنشانی موشن",
                            color = LabelSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Text(
                    text = "تغییرات موشن فقط همین محور را عوض می‌کند؛ فونت، رنگ، سایه، هایلایت و پس‌زمینه دست‌نخورده می‌مانند.",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // ================= HIGHLIGHT (per-axis overrides) =================
            4 -> {
                // Effective highlight type: the override wins when
                // set, else inferred from the working copy (motion
                // engine + pill + bar presence).
                val currentOverride = overrides.wordHighlight
                val effectiveType: String = currentOverride?.type
                    ?: when {
                        selectedPreset.progressBar != null -> "progressBar"
                        selectedPreset.activeWordBackgroundArgb != null -> "box"
                        selectedPreset.motionKind == WordMotionKind.KARAOKE_FILL -> "colorChange"
                        selectedPreset.motionKind == WordMotionKind.NONE -> "none"
                        else -> "colorChange"
                    }
                fun applyHighlight(next: com.aicreatorstudio.app.model.template.HighlightOverride?) {
                    val merged = (overrides.wordHighlight ?: com.aicreatorstudio.app.model.template.HighlightOverride())
                    val updated = if (next == null) null else merged.copy(
                        type = next.type ?: merged.type,
                        activeColor = next.activeColor ?: merged.activeColor
                    )
                    userPicked = true
                    overrides = overrides.copy(wordHighlight = updated)
                    selectedPreset = with(
                        com.aicreatorstudio.app.model.template.JsonTemplateMapper
                    ) {
                        selectedPreset.withHighlightOverride(updated)
                    }
                    applied = false
                }
                Text(
                    text = "هایلایت کلمه فعال",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        "none" to "بدون هایلایت",
                        "colorChange" to "تغییر رنگ",
                        "box" to "جعبه",
                        "progressBar" to "نوار پیشرفت"
                    ).forEach { (type, label) ->
                        val selected = effectiveType == type
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) accentPrimary().copy(alpha = 0.16f)
                                    else Color.White.copy(alpha = 0.04f)
                                )
                                .clickable {
                                    applyHighlight(
                                        com.aicreatorstudio.app.model.template.HighlightOverride(type = type)
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (selected) accentBright() else LabelPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
                Text(
                    text = "رنگ کلمه فعال",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        0xFFFFD60A, 0xFF00E5FF, 0xFF30D158, 0xFFFF375F,
                        0xFFFFFFFF, 0xFFFF9F0A, 0xFFFF7A00, 0xFF00FFF7
                    ).forEach { c ->
                        val activeArgb = selectedPreset.activeWordColorArgb
                        Box(
                            modifier = Modifier
                                .background(
                                    if (activeArgb == c.toInt()) Color.White
                                    else Color.Transparent,
                                    RoundedCornerShape(percent = 50)
                                )
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Color(c), RoundedCornerShape(percent = 50))
                                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                                    .clickable {
                                        applyHighlight(
                                            com.aicreatorstudio.app.model.template.HighlightOverride(
                                                activeColor = String.format("#%06X", 0xFFFFFFL and c)
                                            )
                                        )
                                    }
                                    .width(22.dp)
                                    .height(22.dp)
                            )
                        }
                    }
                    ModeChip(
                        label = "بازنشانی هایلایت",
                        selected = false,
                        onClick = {
                            // Reset-friendly: drop the axis override
                            // and restore the highlight fields from the
                            // catalog version of the current template
                            // (other working-copy tweaks survive).
                            overrides = overrides.cleared(
                                com.aicreatorstudio.app.model.template.TemplateAxis.WORD_HIGHLIGHT
                            )
                            val base = catalogTemplates.firstOrNull {
                                it.id == selectedPreset.id
                            } ?: initialTemplate
                            if (base != null) {
                                selectedPreset = selectedPreset.copy(
                                    motionKind = base.motionKind,
                                    wordRevealMode = base.wordRevealMode,
                                    activeWordColorArgb = base.activeWordColorArgb,
                                    activeWordBackgroundArgb = base.activeWordBackgroundArgb,
                                    progressBar = base.progressBar
                                )
                            }
                            applied = false
                        }
                    )
                }
                Text(
                    text = "تغییر هایلایت فقط همین محور را عوض می‌کند؛ فونت، رنگ متن، سایه، پس‌زمینه و موشن دست‌نخورده می‌مانند.",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // ================= BACKGROUND (per-axis overrides only) =================
            5 -> {
                // Effective background kind: override wins when set,
                // else falls through to the working copy.
                val currentBgOverride = overrides.background
                val effectiveKind: CaptionBackgroundKind =
                    currentBgOverride?.type?.let { t ->
                        when (t.trim().lowercase()) {
                            "none", "nonebox" -> CaptionBackgroundKind.NONE
                            "solidbox" -> CaptionBackgroundKind.SOLID_ROUNDED
                            "gradientbox" -> CaptionBackgroundKind.GRADIENT
                            "blurvideo" -> CaptionBackgroundKind.BLUR
                            "letterboxbars" -> CaptionBackgroundKind.LETTERBOX_BARS
                            "perlinehighlight" -> CaptionBackgroundKind.PER_LINE_HIGHLIGHT
                            else -> null
                        }
                    } ?: selectedPreset.backgroundKind

                // Effective angle: override wins, else working copy.
                val effectiveAngle: Float =
                    currentBgOverride?.angle ?: selectedPreset.gradientAngleDeg ?: 90f

                // Effective background color: override wins, else working copy.
                val effectiveColor: Int = currentBgOverride?.color?.let {
                    com.aicreatorstudio.app.model.template.JsonTemplateMapper.parseColor(
                        it, selectedPreset.style.backgroundColorArgb
                    )
                } ?: selectedPreset.style.backgroundColorArgb

                fun applyBackground(next: BackgroundOverride?) {
                    val merged = (overrides.background ?: BackgroundOverride())
                    val updated = if (next == null) null else merged.copy(
                        type = next.type ?: merged.type,
                        color = next.color ?: merged.color,
                        angle = next.angle ?: merged.angle
                    )
                    userPicked = true
                    overrides = overrides.copy(background = updated)
                    selectedPreset = with(
                        com.aicreatorstudio.app.model.template.JsonTemplateMapper
                    ) {
                        selectedPreset.withBackgroundOverride(updated)
                    }
                    applied = false
                }

                Text(
                    text = "پس‌زمینه متن",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(
                        "none" to "بدون پس‌زمینه",
                        "solidbox" to "جعبه‌ی تک‌رنگ",
                        "gradientbox" to "گرادیان دو‌رنگ",
                        "letterboxbars" to "نوار سینمایی",
                        "blurvideo" to "تاری ویدیو",
                        "perlinehighlight" to "هایلایت خط"
                    ).forEach { (type, label) ->
                        val selected = when (type) {
                            "none" -> effectiveKind == CaptionBackgroundKind.NONE
                            "solidbox" -> effectiveKind == CaptionBackgroundKind.SOLID_ROUNDED
                            "gradientbox" -> effectiveKind == CaptionBackgroundKind.GRADIENT
                            "letterboxbars" -> effectiveKind == CaptionBackgroundKind.LETTERBOX_BARS
                            "blurvideo" -> effectiveKind == CaptionBackgroundKind.BLUR
                            "perlinehighlight" -> effectiveKind == CaptionBackgroundKind.PER_LINE_HIGHLIGHT
                            else -> false
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) accentPrimary().copy(alpha = 0.16f)
                                    else Color.White.copy(alpha = 0.04f)
                                )
                                .clickable {
                                    applyBackground(BackgroundOverride(type = type))
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = label,
                                color = if (selected) accentBright() else LabelPrimary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }

                // Color swatches — background color only (text/accent
                // belong to the Color tab, NOT here).
                Text(
                    text = "رنگ پس‌زمینه",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        0xFF000000, 0xFF14141A, 0xFF0A84FF, 0xFF6C3DF4,
                        0xFFFF375F, 0xFF1C1C1E
                    ).forEach { c ->
                        Box(
                            modifier = Modifier
                                .background(
                                    if (effectiveColor == c.toInt()) Color.White
                                    else Color.Transparent,
                                    RoundedCornerShape(percent = 50)
                                )
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(Color(c), RoundedCornerShape(percent = 50))
                                    .border(1.dp, HairlineSubtle, RoundedCornerShape(percent = 50))
                                    .clickable {
                                        applyBackground(
                                            BackgroundOverride(
                                                type = effectiveKind.engineTypeString,
                                                color = String.format("#%06X", 0xFFFFFFL and c)
                                            )
                                        )
                                    }
                                    .width(22.dp)
                                    .height(22.dp)
                            )
                        }
                    }
                }

                // Gradient angle slider — only visible when gradient
                // is active; CSS convention (0° = to top).
                if (effectiveKind == CaptionBackgroundKind.GRADIENT) {
                    Text(
                        text = "زاویه‌ی گرادیان: ${effectiveAngle.roundToInt()}°",
                        color = LabelPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Slider(
                        value = effectiveAngle,
                        onValueChange = { newAngle ->
                            applyBackground(
                                BackgroundOverride(
                                    type = effectiveKind.engineTypeString,
                                    angle = newAngle
                                )
                            )
                        },
                        valueRange = 0f..360f,
                        steps = 36,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Reset button
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(iOSShapes.capsule)
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable {
                                overrides = overrides.cleared(
                                    com.aicreatorstudio.app.model.template.TemplateAxis.BACKGROUND
                                )
                                val base = catalogTemplates.firstOrNull {
                                    it.id == selectedPreset.id
                                } ?: initialTemplate
                                if (base != null) {
                                    selectedPreset = selectedPreset.copy(
                                        backgroundKind = base.backgroundKind,
                                        gradientAngleDeg = base.gradientAngleDeg,
                                        style = base.style
                                    )
                                }
                                applied = false
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "بازنشانی پس‌زمینه",
                            color = LabelSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Text(
                    text = "تغییرات پس‌زمینه فقط همین محور را عوض می‌کند؛ فونت، رنگ متن، سایه، هایلایت و موشن دست‌نخورده می‌مانند.",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // ---- Save as custom preset ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = presetName,
                onValueChange = { presetName = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text("نام پریست سفارشی…", style = MaterialTheme.typography.bodySmall)
                },
                textStyle = MaterialTheme.typography.bodySmall
            )
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    // 2026-10-17 PHASE 3 — white text on the
                    // accentPrimary()→accentBright() gradient measured 2.72:1, a WCAG AA
                    // failure (the ExportDialog CTA had the identical bug, fixed
                    // in Phase 2). `ctaBrush()` (accentDeep()→AccentCtaBottom)
                    // reaches 7.1:1 and matches the editor's other primary CTAs.
                    // The disabled form is now a proper quiet surface rather
                    // than the same gradient with a dead tap target.
                    .background(
                        if (presetName.isNotBlank()) {
                            ctaBrush()
                        } else {
                            Brush.horizontalGradient(
                                listOf(CinematicElevated, CinematicElevated)
                            )
                        }
                    )
                    .border(
                        1.dp,
                        if (presetName.isNotBlank()) Color.Transparent else BorderSubtle,
                        iOSShapes.capsule
                    )
                    .clickable(enabled = presetName.isNotBlank()) {
                        // 2026-09-16 BUGFIX — the saved preset becomes
                        // the panel selection too (previously the
                        // highlight stayed on the old pack while a
                        // different template became the default), and
                        // onPickTemplate now really persists it.
                        val custom = selectedPreset.copy(
                            id = "custom_${System.currentTimeMillis()}",
                            name = presetName.trim(),
                            builtIn = false
                        )
                        onPickTemplate(custom)
                        selectedPreset = custom
                        applied = false
                        presetName = ""
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ذخیره پریست",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ---- Apply to ALL generated subtitles ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(iOSShapes.capsule)
                .background(
                    if (hasGeneratedSubtitles)
                        Brush.horizontalGradient(listOf(accentPrimary(), accentBright()))
                    else Brush.horizontalGradient(listOf(Color.Gray, Color.Gray))
                )
                .clickable(enabled = hasGeneratedSubtitles) {
                    onApplyToAll(selectedPreset)
                    applied = true
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    applied -> "اعمال شد ✓ (برای تولیدهای بعدی هم فعال است)"
                    hasGeneratedSubtitles -> "اعمال روی همه زیرنویس‌های تولیدشده"
                    else -> "ابتدا زیرنویس تولید کنید (زیرنویس AI)"
                },
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // ---- Manual add ----
        Text(
            text = "افزودن زیرنویس جدید",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "متن زیرنویس…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                textDirection = TextDirection.Rtl
            ),
            isError = error != null,
            supportingText = error?.let { e -> { Text(e) } },
            minLines = 1,
            maxLines = 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = startText,
                onValueChange = { startText = it; error = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("شروع (ثانیه)", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it; error = null },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("پایان (ثانیه)", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(iOSShapes.capsule)
                .background(
                    Brush.horizontalGradient(listOf(accentBright(), accentPrimary()))
                )
                .clickable {
                    val start = startText.trim().toDoubleOrNull()
                    val end = endText.trim().toDoubleOrNull()
                    val t = text.trim()
                    when {
                        t.isEmpty() -> error = "متن زیرنویس خالی است."
                        start == null || end == null ->
                            error = "زمان شروع و پایان را با عدد وارد کن."
                        start < 0 || end <= start ->
                            error = "زمان پایان باید بعد از شروع باشد."
                        (end * 1000).toLong() > durationMs && durationMs > 0 ->
                            error = "زمان پایان از طول ویدئو بیشتر است."
                        else -> {
                            onAddSubtitle(t, selectedPreset, (start * 1000).toLong(), (end * 1000).toLong())
                            error = null
                            text = ""
                        }
                    }
                }
                .padding(horizontal = 18.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "افزودن به ویدئو",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = "برای زیرنویس خودکار از پنل «زیرنویس AI» استفاده کن.",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.clickable { onOpenAutoSubtitles() }
        )

        // ---- 2026-10-13 CapCut-style subtitle EDITING workflow ----
        // Split / merge / retime / per-word timing for the SELECTED
        // subtitle. All the maths lives in `model/SubtitleEditOps`
        // (pure, JVM tested); this section only relays user intent.
        // Only shown when a subtitle is actually selected, so the
        // panel is unchanged for someone who just wants a template.
        if (selectedTextOverlay != null) {
            SubtitleEditingSection(
                overlay = selectedTextOverlay,
                playheadMs = playheadMs,
                onSplit = { wordIndex -> onSplitSubtitle(selectedTextOverlay.id, wordIndex) },
                onMerge = { onMergeSubtitleWithNext(selectedTextOverlay.id) },
                onRetime = { startMs, endMs ->
                    onRetimeSubtitle(selectedTextOverlay.id, startMs, endMs)
                },
                onRetimeWord = { index, startMs, endMs ->
                    onRetimeSubtitleWord(selectedTextOverlay.id, index, startMs, endMs)
                },
                onResyncText = { onResyncSubtitleText(selectedTextOverlay.id) }
            )
        }
    }
}

/**
 * 2026-10-13 — the CapCut-style editing controls for the currently
 * selected subtitle: split at the playhead, merge with the next cue,
 * nudge either boundary, and correct a single word's timing.
 *
 * Deliberately simple and deterministic: every control computes a
 * concrete `(start, end)` pair or a word index and hands it to the
 * ViewModel, which routes it through [com.aicreatorstudio.app.model.SubtitleEditOps]
 * and the existing undo/redo history. Nothing here mutates state
 * directly, and no control is enabled when it could not succeed —
 * a 1-word cue offers no split, a cue without a successor offers no
 * merge.
 */
@Composable
private fun SubtitleEditingSection(
    overlay: com.aicreatorstudio.app.model.TextOverlay,
    playheadMs: Long,
    onSplit: (wordIndex: Int) -> Unit,
    onMerge: () -> Unit,
    onRetime: (startMs: Long, endMs: Long) -> Unit,
    onRetimeWord: (index: Int, startMs: Long, endMs: Long) -> Unit,
    onResyncText: () -> Unit
) {
    val words = overlay.words
    val canSplit = words.size >= 2

    /**
     * Which word boundary the playhead is nearest — the split point a
     * user expects after scrubbing to where they want the cut. Falls
     * back to the midpoint when the playhead is outside the cue.
     */
    val splitIndex = remember(overlay.id, playheadMs, words) {
        if (words.size < 2) return@remember 1
        val interior = words.indices
            .filter { it >= 1 }
            .minByOrNull { kotlin.math.abs(words[it].startTimeMs - playheadMs) }
            ?: (words.size / 2)
        interior.coerceIn(1, words.size - 1)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(12.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "ویرایش زیرنویس انتخاب‌شده",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )

        // ---- Split / merge ----
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioActionChip(
                label = "برش در «${words.getOrNull(splitIndex)?.text ?: "—"}»",
                enabled = canSplit,
                onClick = { onSplit(splitIndex) },
                modifier = Modifier.weight(1f)
            )
            StudioActionChip(
                label = "ادغام با بعدی",
                enabled = words.isNotEmpty(),
                onClick = onMerge,
                modifier = Modifier.weight(1f)
            )
        }
        if (!canSplit) {
            Text(
                text = "برای برش، زیرنویس باید بیش از یک کلمه داشته باشد.",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }

        // ---- Boundary retime ----
        Text(
            text = "زمان‌بندی: ${"%.2f".format(overlay.startTimeMs / 1000f)}s ← " +
                "${"%.2f".format(overlay.endTimeMs / 1000f)}s",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioActionChip(
                label = "شروع −۱۰۰ms",
                enabled = true,
                onClick = { onRetime(overlay.startTimeMs - 100L, overlay.endTimeMs) },
                modifier = Modifier.weight(1f)
            )
            StudioActionChip(
                label = "شروع +۱۰۰ms",
                enabled = true,
                onClick = { onRetime(overlay.startTimeMs + 100L, overlay.endTimeMs) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioActionChip(
                label = "پایان −۱۰۰ms",
                enabled = true,
                onClick = { onRetime(overlay.startTimeMs, overlay.endTimeMs - 100L) },
                modifier = Modifier.weight(1f)
            )
            StudioActionChip(
                label = "پایان +۱۰۰ms",
                enabled = true,
                onClick = { onRetime(overlay.startTimeMs, overlay.endTimeMs + 100L) },
                modifier = Modifier.weight(1f)
            )
        }

        // ---- Per-word timing editor ----
        if (words.isNotEmpty()) {
            Text(
                text = "زمان‌بندی کلمه‌به‌کلمه",
                color = LabelPrimary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            val activeWordId = overlay.activeWordAt(playheadMs)?.id
            words.forEachIndexed { index, w ->
                val isActive = w.id == activeWordId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isActive) accentPrimary().copy(alpha = 0.16f)
                            else Color.Transparent
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = w.text,
                        color = if (isActive) accentBright() else LabelPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${"%.2f".format(w.startTimeMs / 1000f)}–" +
                            "${"%.2f".format(w.endTimeMs / 1000f)}",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                    // Nudge buttons: ±50 ms on each edge. Small enough
                    // to be a correction, large enough to see move.
                    StudioMiniButton("◀", enabled = w.startTimeMs > 0L) {
                        onRetimeWord(index, w.startTimeMs - 50L, w.endTimeMs)
                    }
                    StudioMiniButton("▶", enabled = true) {
                        onRetimeWord(index, w.startTimeMs + 50L, w.endTimeMs)
                    }
                }
            }
        }

        // ---- Restore text from the word timings ----
        StudioActionChip(
            label = "بازگرداندن متن از کلمات",
            enabled = words.isNotEmpty(),
            onClick = onResyncText,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Compact pill button used by the editing section. */
@Composable
private fun StudioActionChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(iOSShapes.capsule)
            .background(
                if (enabled) accentPrimary().copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(1.dp, if (enabled) accentBright() else HairlineSubtle, iOSShapes.capsule)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (enabled) accentBright() else LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Tiny square nudge button for the per-word editor. */
@Composable
private fun StudioMiniButton(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color.White.copy(alpha = if (enabled) 0.07f else 0.03f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            color = if (enabled) LabelSecondary else LabelSecondary.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * WYSIWYG preview — the sample caption drawn by the SHARED
 * CaptionRenderer (the exact export code) at this card's pixel
 * size. The virtual playhead advances so every word-motion kind
 * visibly animates; position, background, stroke, shadow and the
 * active-word styling are pixel-identical (modulo size) to the
 * canvas and the export by construction.
 */
@Composable
private fun TemplatePreviewCard(template: SubtitleTemplate, modifier: Modifier = Modifier) {
    // Catalog preview sentence (Persian only).
    val sample = "این یک نمونه زیرنویس فارسی است"
    val tokens = remember(sample) { sample.split(" ") }
    // Virtual word timings for the animation loop (900ms/word).
    val wordDurationMs = 900
    val totalMs = tokens.size * wordDurationMs
    val transition = rememberInfiniteTransition(label = "studioPreview")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = totalMs.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = totalMs,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "virtualPlayhead"
    )
    val virtualNow = progress.toLong()

    androidx.compose.foundation.layout.BoxWithConstraints(
        // Fixed preview height: the panel column scrolls, so the
        // height constraint would otherwise be infinite.
        modifier = modifier.then(Modifier.height(120.dp)),
        contentAlignment = Alignment.Center
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val wPx = with(density) { maxWidth.roundToPx() }
        val hPx = with(density) { maxHeight.roundToPx() }
        val image = com.aicreatorstudio.app.ui.screens.editor.rememberTemplateImageBitmap(
            template = template,
            tokens = tokens,
            frameTimeMs = virtualNow,
            wordDurationMs = wordDurationMs,
            canvasWidthPx = wPx,
            canvasHeightPx = hPx
        )
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
    }
}

/**
 * 2026-09-19 PHASE 4 — stable loading skeleton for the template
 * grid: identical 2-col metrics, zero animation. The grid swaps
 * skeleton → real cards in a single recomposition (no flicker),
 * and the EMPTY fallback never crashes.
 */
@Composable
private fun TemplateGridSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "قالب‌های آماده فارسی",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        repeat(3) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                repeat(2) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(HairlineSubtle)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SurfaceSunken)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .height(12.dp)
                                .clip(iOSShapes.capsule)
                                .background(Hairline)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .height(10.dp)
                                .clip(iOSShapes.capsule)
                                .background(HairlineSubtle)
                        )
                    }
                }
            }
        }
    }
}

/**
 * PHASE 3 — the EXPLAINED empty state for the template catalogue.
 *
 * `JsonCatalogStatus.EMPTY` is the never-crash fallback used when the
 * bundled JSON asset is missing or unreadable. It is **not** transient, so
 * showing the loading skeleton forever told the user "still loading" while
 * nothing was loading. This state names the problem and points at the
 * panel's seven manual axes (font, colour, shadow, highlight, background,
 * motion, position), which keep working because they build on the panel's
 * hand-built fallback template — so the user is not dead-ended.
 *
 * No retry affordance is offered **on purpose**: the panel's contract has
 * no reload path, and inventing one would change ViewModel behaviour,
 * which is out of scope for this phase.
 */
@Composable
private fun TemplateCatalogUnavailable() {
    EmptyState(
        title = "قالب آماده در دسترس نیست",
        message = "فهرست قالب‌های آماده خوانده نشد. با زبانه‌های «فونت»، «رنگ»، " +
            "«سایه»، «هایلایت»، «پس‌زمینه»، «موشن» و «موقعیت» می‌توانی ظاهر " +
            "زیرنویس را خودت بسازی.",
        icon = Icons.Filled.Info
    )
}

/**
 * 2026-09-19 — one picker card for the template grid: a REAL
 * static Persian preview drawn by the SHARED CaptionRenderer (the
 * exact export code) at 2x for crispness — same font, colors,
 * background, stroke, shadow and active-word treatment as canvas
 * and export. Frozen mid-highlight (word «نمونه») so 20 cards cost
 * zero animation. Name, category and selected ring below.
 */
@Composable
private fun TemplateGridCard(
    template: SubtitleTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Catalog preview sentence; frozen mid-word («نمونه») so
    // highlight templates show their identity statically.
    val sample = "این یک نمونه زیرنویس فارسی است"
    val tokens = remember(sample) { sample.split(" ") }
    val wordDurationMs = 900
    // 2026-10-17 PHASE 3 — captured so the semantics block below can
    // reference the selection without shadowing.
    val isSelected = selected
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            // 2026-10-16 (Phase 2) — the unselected fill was a raw 4% white
            // and its border the same 7% hairline as the fill's neighbours,
            // so an unselected card had almost no edge. Fill/border now use
            // the two white-film tokens at their intended roles.
            .background(
                if (selected) accentPrimary().copy(alpha = 0.16f)
                else HairlineSubtle
            )
            .border(
                1.dp,
                if (selected) accentBright() else Hairline,
                RoundedCornerShape(12.dp)
            )
            .clickable(role = Role.Button) { onClick() }
            // 2026-10-17 PHASE 3 — expose the selected template to screen
            // readers; the accent border/wash alone are visual-only.
            // `this.` is required: the `selected` parameter shadows the
            // SemanticsPropertyReceiver.selected extension property.
            .semantics { this.selected = isSelected }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(
            // Taller preview area (was 84dp): room for the larger
            // thumbnail type plus glow/pill overflow.
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceSunken),
            contentAlignment = Alignment.Center
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val wPx = with(density) { maxWidth.roundToPx() } * 2
            val hPx = with(density) { maxHeight.roundToPx() } * 2
            val image = com.aicreatorstudio.app.ui.screens.editor.rememberTemplateImageBitmap(
                template = template,
                tokens = tokens,
                frameTimeMs = (4 * wordDurationMs + wordDurationMs / 2).toLong(),
                wordDurationMs = wordDurationMs,
                canvasWidthPx = wPx,
                canvasHeightPx = hPx,
                fontScale = 0.75f
            )
            if (image != null) {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
        }
        Text(
            text = template.name,
            color = if (selected) accentBright() else LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 2026-09-19 PHASE 4 — bilingual card: English name under
        // the Persian one (category moved to the group headers).
        if (template.nameEn.isNotBlank()) {
            Text(
                text = template.nameEn,
                color = LabelSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // 2026-10-17 PHASE 3 — captured so the semantics block below can
    // reference the selection without shadowing.
    val isSelected = selected
    Box(
        modifier = Modifier
            // 2026-10-16 (Phase 2) — these chips were ~28dp tall (6dp
            // padding around an 11sp label) and used a raw 5% white fill
            // with an equally faint border. Now 36dp minimum for a
            // comfortable target, filled with [HairlineSubtle] and edged
            // with the stronger [Hairline] so an unselected chip still
            // reads as a control rather than as background text.
            .heightIn(min = 36.dp)
            .clip(iOSShapes.capsule)
            .background(
                if (selected) accentPrimary().copy(alpha = 0.22f)
                else HairlineSubtle
            )
            .border(
                1.dp,
                if (selected) accentBright() else Hairline,
                iOSShapes.capsule
            )
            .clickable(role = Role.Button) { onClick() }
            // 2026-10-17 PHASE 3 — a selected category chip / mode chip was
            // signalled by colour alone. Publish the state so it is announced.
            // `this.` is required: the `selected` parameter shadows the
            // SemanticsPropertyReceiver.selected extension property.
            .semantics { this.selected = isSelected }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) accentBright() else LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * 2026-09-15 — compact labelled slider row for the Position tab.
 * Shows the live numeric value on the end chip while dragging; the
 * track uses the same subtle glass treatment as the panel chips.
 */
@Composable
private fun SliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueLabel: String,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(iOSShapes.capsule)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = valueLabel,
                color = accentBright(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 2026-10-15 — «اندازه قلم همه زیرنویس‌ها» (global subtitle size).
 *
 * One control with three entry paths, because the gesture
 * determines how history must be recorded:
 *
 *  - **slider drag** — live: [onDragStart] fires once before the
 *    first change (the single history snapshot), [onLiveSize] on
 *    every frame (state-only), [onDragEnd] once on release
 *    (persist). A long drag must stay ONE undo step.
 *  - **+ / − chips** — discrete ±2sp, each its own undo step.
 *  - **apply button** — writes the slider's current value onto
 *    every subtitle, one undo step.
 *
 * The value shown is seeded from the active preset's size (the size
 * a template apply would write) and follows the user's own drags;
 * it is clamped to the same 14–42sp band the ViewModel enforces so
 * the UI can never offer a value the engine would reject.
 */
@Composable
private fun GlobalSubtitleSizeControl(
    initialSizeSp: Float,
    onDragStart: () -> Unit,
    onLiveSize: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDiscreteSize: (Float) -> Unit,
    onScale: (Float) -> Unit
) {
    val minSp = 14f
    val maxSp = 42f
    var sizeSp by remember(initialSizeSp) {
        mutableStateOf(initialSizeSp.coerceIn(minSp, maxSp))
    }
    var dragging by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "اندازه قلم همه زیرنویس‌ها",
            color = LabelPrimary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        // Own Slider (not the shared SliderRow) so the drag END can
        // persist: onValueChangeFinished is the only reliable
        // "finger left the track" signal, and skipping it would
        // leave a live drag unpersisted.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Slider(
                value = sizeSp,
                onValueChange = { next ->
                    if (!dragging) {
                        dragging = true
                        onDragStart()
                    }
                    sizeSp = next.coerceIn(minSp, maxSp)
                    onLiveSize(sizeSp)
                },
                onValueChangeFinished = {
                    dragging = false
                    onDragEnd()
                },
                valueRange = minSp..maxSp,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${sizeSp.toInt()}sp",
                    color = accentBright(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // −2sp
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .clickable {
                        val next = (sizeSp - 2f).coerceIn(minSp, maxSp)
                        sizeSp = next
                        onDiscreteSize(next)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "− ۲ واحد",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // +2sp
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .clickable {
                        val next = (sizeSp + 2f).coerceIn(minSp, maxSp)
                        sizeSp = next
                        onDiscreteSize(next)
                    }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+ ۲ واحد",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Apply to all subtitles
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(accentPrimary().copy(alpha = 0.22f))
                    .border(1.dp, accentBright(), iOSShapes.capsule)
                    .clickable { onDiscreteSize(sizeSp) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "اعمال روی تمام زیرنویس‌ها",
                    color = accentBright(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // Proportional scaling shortcuts — keep existing size ratios.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .clickable { onScale(0.9f) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "−۱۰٪",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .clickable { onScale(1.1f) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+۱۰٪",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Text(
            text = "اندازه روی همه زیرنویس‌های تایم‌لاین اعمال می‌شود؛ متن‌های تزئینی دست‌نخورده می‌مانند. یک بار بازگردانی، همه تغییرات را برمی‌گرداند.",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
