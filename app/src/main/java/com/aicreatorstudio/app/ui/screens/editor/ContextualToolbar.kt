package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.EditorContext
import com.aicreatorstudio.app.model.ToolPanel
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.StateSelectedBorder
import com.aicreatorstudio.app.ui.theme.StateSelectedFill
import com.aicreatorstudio.app.ui.theme.iOSRed

/**
 * The editor's contextual toolbar — one **horizontally scrollable** row of
 * fixed-width tiles whose contents are a pure function of [context].
 *
 * 2026-10-04 — CapCut-style relayout; 2026-10-05 — flattened fully.
 * The previous renderer divided one non-scrolling row between the chips
 * with `weight(1f)`, so the 7-chip clip toolbar plus its destructive
 * cluster squeezed every tile to ~30dp on a 360dp phone: labels collided,
 * and the IA had to bury Speed / Animation / Trim inside group sheets just
 * to keep the row short. The row now scrolls and EVERYTHING is flat:
 *
 *  1. **Fixed-width tiles** (60dp) with a uniform rhythm — every tool gets
 *     the same footprint, in the row AND inside a sheet.
 *  2. **One tap = one panel.** All frequent tools are direct flat chips;
 *     the only remaining [ToolGroupSheet] is the AI collection.
 *  3. **Destructive actions are icon-only**, at the END of the scroll
 *     content behind a divider — never adjacent to Split.
 *  4. **The row cross-fades on context change** so the user sees that
 *     tapping an object changed the available tools.
 */
@Composable
fun ContextualToolbar(
    context: EditorContext,
    selectedPanel: ToolPanel?,
    hasAdjacentClip: Boolean,
    onPanelSelected: (ToolPanel?) -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var openGroupId by remember { mutableStateOf<String?>(null) }

    // The sheet belongs to the chips of one context; when the selection
    // changes those chips no longer exist, so the sheet must not linger.
    LaunchedEffect(context) { openGroupId = null }

    val chips = ContextualToolbarSpec.chipsFor(context, hasAdjacentClip)
    val openGroup = chips
        .filterIsInstance<ContextChip.Group>()
        .firstOrNull { it.id == openGroupId }

    // Retain the last shown group so the collapse animation still has content
    // to render while it shrinks (a null here would blank the sheet mid-exit).
    var lastOpenGroup by remember { mutableStateOf<ContextChip.Group?>(null) }
    if (openGroup != null) lastOpenGroup = openGroup

    fun resolve(chip: ContextChip) {
        when (chip) {
            is ContextChip.Action -> {
                openGroupId = null
                onAction(chip.actionId)
            }
            is ContextChip.Panel -> {
                openGroupId = null
                // Tapping the active tool again closes it, matching the old
                // dock's toggle behaviour.
                onPanelSelected(if (selectedPanel == chip.toolPanel) null else chip.toolPanel)
            }
            is ContextChip.Group -> {
                openGroupId = if (openGroupId == chip.id) null else chip.id
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = openGroup != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            lastOpenGroup?.let { group ->
                ToolGroupSheet(
                    group = group,
                    selectedPanel = selectedPanel,
                    onPick = { resolve(it) },
                    onDismiss = { openGroupId = null }
                )
            }
        }

        Crossfade(
            targetState = context,
            animationSpec = tween(durationMillis = 180),
            label = "contextual-toolbar-context"
        ) { animatedContext ->
            val contextChips = ContextualToolbarSpec.chipsFor(animatedContext, hasAdjacentClip)
            val trailing = ContextualToolbarSpec.trailingActionsFor(animatedContext)

            // Floating frosted dock — static glass (no blur; the top bar
            // owns this screen's single chrome layer).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
            ) {
                GlassSurface(
                    shape = RoundedCornerShape(18.dp),
                    material = GlassMaterial.Thin,
                    modifier = Modifier.matchParentSize()
                ) {}
                LazyRow(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(contextChips, key = { it.id }) { chip ->
                    ToolbarTile(
                        chip = chip,
                        isSelected = when (chip) {
                            is ContextChip.Panel -> selectedPanel == chip.toolPanel
                            is ContextChip.Group -> openGroupId == chip.id ||
                                chip.members.any { it is ContextChip.Panel && it.toolPanel == selectedPanel }
                            is ContextChip.Action -> false
                        },
                        onClick = { resolve(chip) }
                    )
                }

                if (trailing.isNotEmpty()) {
                    // Divider: the destructive cluster is deliberately set
                    // apart so Delete is never a slip away from Split.
                    item(key = "trailing_divider") {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 4.dp)
                                .width(1.dp)
                                .height(34.dp)
                                .background(Color.White.copy(alpha = 0.14f))
                        )
                    }
                    items(trailing, key = { it.id }) { chip ->
                        TrailingIconAction(
                            chip = chip,
                            onClick = { resolve(chip) }
                        )
                    }
                }
                }
            }
        }
    }
}

/**
 * One CapCut-style tool tile: a fixed 60dp column with the icon in a 40dp
 * rounded box and the label underneath. Used BOTH by the main row and by
 * the group sheet's members, so every tool in the app has the identical
 * footprint, icon size and label style — the visual consistency the old
 * mixed sheet/row styling lacked. Selected tools get an accent-tinted box;
 * the whole tile scales down on press for tactile feedback.
 *
 * A [comingSoon] tile shows a lock glyph and a "به‌زودی" badge instead of
 * the label and swallows the tap, so an unfinished feature is honest.
 */
@Composable
private fun ToolbarTile(
    chip: ContextChip,
    isSelected: Boolean,
    onClick: () -> Unit,
    comingSoon: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    // Restrained press — 0.96, no bounce (premium, not playful).
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "toolbar-tile-scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(60.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !comingSoon,
                role = Role.Button,
                onClickLabel = chip.label,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            )
            .semantics { selected = isSelected }
            .padding(vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                // Idle tiles keep a faint uniform fill so the whole row
                // reads as one continuous strip of identical boxes rather
                // than loose icons floating at different weights.
                .background(
                    when {
                        comingSoon -> Color.White.copy(alpha = 0.03f)
                        isSelected -> StateSelectedFill
                        else -> Color.White.copy(alpha = 0.06f)
                    }
                )
                .border(
                    width = 1.dp,
                    color = if (isSelected) StateSelectedBorder else HairlineSubtle,
                    shape = RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (comingSoon) Icons.Filled.Lock else chip.icon,
                contentDescription = null,
                tint = when {
                    // 65% keeps the "locked" dimming while staying AA-safe
                    // for the badge text (was 45% ≈ 4.3:1, below AA).
                    comingSoon -> LabelPrimary.copy(alpha = 0.65f)
                    isSelected -> LabelPrimary
                    else -> Color.White.copy(alpha = 0.92f)
                },
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (comingSoon) "به‌زودی" else chip.label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                comingSoon -> LabelPrimary.copy(alpha = 0.65f)
                isSelected -> LabelPrimary
                else -> LabelPrimary.copy(alpha = 0.85f)
            },
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Icon-only action for the trailing cluster (Duplicate / Delete). Sits at the
 * end of the scroll content behind the divider, tinted [iOSRed] when
 * destructive.
 */
@Composable
private fun TrailingIconAction(
    chip: ContextChip,
    onClick: () -> Unit
) {
    val destructive = chip is ContextChip.Action && chip.destructive
    val haptics = LocalHapticFeedback.current
    val tint = if (destructive) iOSRed else LabelPrimary
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (destructive) iOSRed.copy(alpha = 0.12f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(
                1.dp,
                if (destructive) iOSRed.copy(alpha = 0.40f) else HairlineSubtle,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                role = Role.Button,
                onClickLabel = chip.label,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = chip.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * The sub-tool chooser for a [ContextChip.Group].
 *
 * Rendered as a compact glass card immediately above the chip row. Members
 * scroll horizontally *inside the sheet*, which is acceptable because the
 * sheet is a deliberate drill-down the user opened, unlike the always-visible
 * root row.
 *
 * A member marked coming-soon is rendered with a "به‌زودی" badge and is not
 * clickable, so an unfinished feature is honest instead of a chip that eats a
 * tap (the old `locked = true` dock behaviour).
 */
@Composable
private fun ToolGroupSheet(
    group: ContextChip.Group,
    selectedPanel: ToolPanel?,
    onPick: (ContextChip) -> Unit,
    onDismiss: () -> Unit
) {
    // 2026-10-05 — the glass is a BACKGROUND LAYER here, not the container.
    // GlassSurface's inner layer is `fillMaxSize`, so using it as the
    // container let the sheet adopt the dock's leftover height and render
    // as a full-screen glass slab with the tile row pinned at its top (and
    // the toolbar row pushed off screen). `IntrinsicSize.Min` is NOT an
    // option either — the member row is a LazyRow, a SubcomposeLayout whose
    // intrinsics crash. This Box flips the dependency: the real content
    // (title + tiles) sizes the Box, and the glass simply paints behind it
    // via `matchParentSize`.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 2.dp)
    ) {
        GlassSurface(
            shape = RoundedCornerShape(18.dp),
            material = GlassMaterial.Regular,
            modifier = Modifier.matchParentSize()
        ) {}

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 6.dp, top = 8.dp)
            ) {
                Text(
                    text = group.sheetTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = LabelPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = "بستن",
                            onClick = onDismiss
                        )
                        // 44dp minimum dismiss target.
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "بستن",
                        style = MaterialTheme.typography.labelMedium,
                        color = LabelPrimary.copy(alpha = 0.75f)
                    )
                }
            }
            // Empty-state guard: a group with no members would otherwise
            // render a zero-height LazyRow — a sheet that opens to nothing.
            // The spec never produces one today, but a future edit to
            // `ContextualToolbarSpec` would fail silently, so it is handled
            // here rather than assumed away.
            if (group.members.isEmpty()) {
                Text(
                    text = "ابزاری برای این بخش ثبت نشده",
                    style = MaterialTheme.typography.labelMedium,
                    color = LabelPrimary.copy(alpha = 0.65f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
            } else {
                // 2026-10-05 — members render with the SAME [ToolbarTile]
                // the main row uses. The old 96dp member cards had their own
                // icon-box size, label style and badge layout, so opening a
                // sheet swapped the visual language mid-interaction — the
                // "messy" the relayout is here to remove.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(group.members, key = { it.id }) { member ->
                        ToolbarTile(
                            chip = member,
                            isSelected = member is ContextChip.Panel &&
                                selectedPanel == member.toolPanel,
                            comingSoon = member.id in ComingSoonChipIds,
                            onClick = { onPick(member) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Chips that open a panel which is not finished yet.
 *
 * Q4 of the approved IA: an unfinished tool carries a "به‌زودی" badge
 * **inside the sheet** rather than a dock chip that swallows the tap. A
 * `comingSoon` tile is not merely badged — [ToolbarTile] sets
 * `enabled = !comingSoon`, so the panel is unreachable.
 *
 * 2026-10-19 — the Upscaler was removed from this set. It was locked on the
 * strength of the Q4 note, but the feature underneath it is complete:
 * `UpscaleEngineRegistry` resolves Fast/HighQuality engines that run the real
 * ESRGAN TFLite pipeline (`EsrganUpscalePipeline`), the bundled 4x model is
 * installed through `UnifiedModelInstaller`, and the panel has download,
 * progress, cancel and result states. Keeping the lock made a working tool
 * look broken. Re-add an id here only when the panel really has no engine.
 */
private val ComingSoonChipIds: Set<String> = emptySet()
