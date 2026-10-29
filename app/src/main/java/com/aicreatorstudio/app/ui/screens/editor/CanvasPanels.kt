package com.aicreatorstudio.app.ui.screens.editor

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.CanvasBackground
import com.aicreatorstudio.app.model.CanvasPreset
import com.aicreatorstudio.app.model.ClipTransform
import com.aicreatorstudio.app.model.ClipTransformUpdater
import com.aicreatorstudio.app.model.FillMode
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright

// ---------------------------------------------------------------------------
// Aspect Ratio
// ---------------------------------------------------------------------------

/**
 * Project canvas / aspect ratio picker.
 *
 * Five preset chips: Free / Original, 9:16, 16:9, 1:1, 4:5. Each chip
 * draws a thin outlined rectangle whose own aspect ratio matches the
 * preset so the user can see at a glance what they're picking. Selection
 * is indicated by an iOSBlue ring and a tinted background.
 */
@Composable
fun AspectRatioPanel(
    currentPreset: CanvasPreset,
    onPresetSelected: (CanvasPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(CanvasPreset.entries.toList(), key = { it.name }) { preset ->
            AspectRatioChip(
                preset = preset,
                isSelected = currentPreset == preset,
                onClick = { onPresetSelected(preset) }
            )
        }
    }
}

@Composable
private fun AspectRatioChip(
    preset: CanvasPreset,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isSelected) iOSBlue.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) iOSBlueBright
                        else HairlineSubtle,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // The chip's aspect-rectangle. For FREE we show a wavy-ish
        // dashed placeholder; the rest get an exact outline.
        if (preset == CanvasPreset.FREE) {
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .width(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, LabelSecondary, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Auto",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else {
            // Size the rectangle so it visually represents the ratio
            // within a 48dp × 34dp bounding box.
            val ratio = preset.aspectRatio
            val w = if (ratio >= 1f) 48.dp else 48.dp * ratio
            val h = if (ratio >= 1f) 48.dp / ratio else 48.dp
            Box(
                modifier = Modifier
                    .size(width = w, height = h.coerceAtMost(40.dp))
                    .border(
                        width = 1.dp,
                        color = if (isSelected) iOSBlueBright else LabelSecondary,
                        shape = RoundedCornerShape(3.dp)
                    )
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = preset.displayName,
            color = if (isSelected) LabelPrimary else LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

// ---------------------------------------------------------------------------
// Transform
// ---------------------------------------------------------------------------

/**
 * Per-clip Transform panel. Three sections:
 *   1. Fill mode segmented control (Fit / Fill / Manual)
 *   2. Rotate row (90° left, 90° right, 180°, Reset)
 *   3. Flip row (H, V)
 *
 * When the clip is null the panel shows a hint instead of controls.
 */
@Composable
fun TransformPanel(
    selectedClip: VideoClip?,
    onFillModeChanged: (FillMode) -> Unit,
    onTransformChanged: ClipTransformUpdater,
    onRotate90Left: () -> Unit,
    onRotate90Right: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectedClip == null) {
            Text(
                text = "Select a clip on the timeline to apply a transform.",
                color = LabelSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }

        // Section 1: Fill mode
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SectionLabel("Fit", modifier = Modifier.width(56.dp))
            FillModeSegmented(
                current = selectedClip.fillMode,
                onSelected = onFillModeChanged
            )
        }

        // Section 2: Manual state hint (visible when MANUAL)
        if (selectedClip.fillMode == FillMode.MANUAL) {
            ManualStateRow(transform = selectedClip.transform)
        }

        // Section 3: Rotate
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SectionLabel("Rotate", modifier = Modifier.width(56.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolButton(
                    icon = Icons.Filled.RotateLeft,
                    label = "90°",
                    onClick = onRotate90Left
                )
                ToolButton(
                    icon = Icons.Filled.RotateRight,
                    label = "90°",
                    onClick = onRotate90Right
                )
                ToolButton(
                    icon = Icons.Filled.RotateRight,
                    label = "180°",
                    onClick = {
                        val t = selectedClip.transform
                        onTransformChanged.update(
                            scale = t.scale,
                            offsetX = t.offsetX,
                            offsetY = t.offsetY,
                            rotation = (t.rotation + 180) % 360,
                            flipH = t.flipH,
                            flipV = t.flipV
                        )
                    }
                )
            }
        }

        // Section 4: Flip
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            SectionLabel("Flip", modifier = Modifier.width(56.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip(
                    label = "H",
                    isOn = selectedClip.transform.flipH,
                    onToggle = {
                        val t = selectedClip.transform
                        onTransformChanged.update(
                            scale = t.scale,
                            offsetX = t.offsetX,
                            offsetY = t.offsetY,
                            rotation = t.rotation,
                            flipH = it,
                            flipV = t.flipV
                        )
                    }
                )
                ToggleChip(
                    label = "V",
                    isOn = selectedClip.transform.flipV,
                    onToggle = {
                        val t = selectedClip.transform
                        onTransformChanged.update(
                            scale = t.scale,
                            offsetX = t.offsetX,
                            offsetY = t.offsetY,
                            rotation = t.rotation,
                            flipH = t.flipH,
                            flipV = it
                        )
                    }
                )
            }
        }

        // Section 5: Reset
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Reset",
                color = iOSBlueBright,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onReset() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun FillModeSegmented(
    current: FillMode,
    onSelected: (FillMode) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(10.dp))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        FillMode.entries.forEach { mode ->
            val isSelected = current == mode
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) iOSBlue.copy(alpha = 0.85f)
                        else Color.Transparent
                    )
                    .clickable { onSelected(mode) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (mode) {
                        FillMode.FIT -> "Fit"
                        FillMode.FILL -> "Fill"
                        FillMode.MANUAL -> "Manual"
                    },
                    color = if (isSelected) Color.White else LabelSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun ManualStateRow(transform: ClipTransform) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Pinch the preview to zoom · drag to pan",
            color = LabelSecondary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Badge(text = "× ${"%.2f".format(transform.scale)}")
        Badge(text = "x ${"%+.2f".format(transform.offsetX)}")
        Badge(text = "y ${"%+.2f".format(transform.offsetY)}")
    }
}

@Composable
private fun Badge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = LabelPrimary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = LabelPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun ToggleChip(
    label: String,
    isOn: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isOn) iOSBlue.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.05f)
            )
            .border(
                width = 1.dp,
                color = if (isOn) iOSBlueBright else HairlineSubtle,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onToggle(!isOn) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Flip,
                contentDescription = null,
                tint = if (isOn) Color.White else LabelPrimary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = if (isOn) Color.White else LabelPrimary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LabelSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// Background
// ---------------------------------------------------------------------------

/**
 * Project canvas background / letterbox fill picker.
 *
 * Two sections:
 *   1. Solid color: 8-swatch palette + custom hex input. Tapping a
 *      swatch sets [CanvasBackground.SolidColor]; the first swatch
 *      (Black) sets [CanvasBackground.Black] (the default).
 *   2. Blur: a single toggle card that fills the letterbox with a
 *      blurred, scaled-up copy of the underlying video.
 */
@Composable
fun BackgroundPanel(
    current: CanvasBackground,
    onBackgroundChanged: (CanvasBackground) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Section: solid color
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Color",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                BACKGROUND_SWATCHES.forEach { swatch ->
                    val isCurrentBlack = current is CanvasBackground.Black &&
                        swatch.argb == 0xFF000000L
                    val isCurrentSolid = current is CanvasBackground.SolidColor &&
                        current.colorValue == swatch.argb
                    ColorSwatch(
                        argb = swatch.argb,
                        label = swatch.label,
                        isSelected = isCurrentBlack || isCurrentSolid,
                        onClick = {
                            if (swatch.argb == 0xFF000000L) {
                                onBackgroundChanged(CanvasBackground.Black)
                            } else {
                                onBackgroundChanged(
                                    CanvasBackground.SolidColor(swatch.argb)
                                )
                            }
                        }
                    )
                }
                // Custom hex input
                CustomHexInput(
                    initial = (current as? CanvasBackground.SolidColor)
                        ?.colorValue
                        ?.let(::formatHex) ?: "",
                    onSubmit = { hex ->
                        parseHex(hex)?.let { value ->
                            onBackgroundChanged(CanvasBackground.SolidColor(value))
                        }
                    }
                )
            }
        }

        // Section: blur
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Fill",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            BlurToggleCard(
                isOn = current is CanvasBackground.Blur,
                onToggle = { onBackgroundChanged(CanvasBackground.Blur) }
            )
        }
    }
}

private data class SwatchSpec(val argb: Long, val label: String)

private val BACKGROUND_SWATCHES = listOf(
    SwatchSpec(0xFF000000L, "Black"),
    SwatchSpec(0xFFFFFFFFL, "White"),
    SwatchSpec(0xFF0A84FFL, "Blue"),
    SwatchSpec(0xFFBF5AF2L, "Purple"),
    SwatchSpec(0xFFFF375FL, "Pink"),
    SwatchSpec(0xFFFF9F0AL, "Orange"),
    SwatchSpec(0xFF30D158L, "Green"),
    SwatchSpec(0xFFFF453AL, "Red")
)

@Composable
private fun ColorSwatch(
    argb: Long,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (isSelected) 2.5.dp else 1.dp,
                color = if (isSelected) iOSBlueBright
                        else if (argb == 0xFFFFFFFFL) HairlineSubtle
                        else Color.Transparent,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Inner ring on dark swatches for visual depth.
        if (argb == 0xFF000000L && !isSelected) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            )
        }
    }
}

@Composable
private fun CustomHexInput(
    initial: String,
    onSubmit: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    Box(
        modifier = Modifier
            .height(36.dp)
            .width(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#",
                color = LabelSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(2.dp))
            BasicTextField(
                value = text,
                onValueChange = { value ->
                    // Accept only 6-char hex (with or without the #).
                    val cleaned = value.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                        .take(6)
                    text = cleaned
                },
                singleLine = true,
                cursorBrush = SolidColor(iOSBlueBright),
                textStyle = TextStyle(
                    color = LabelPrimary,
                    fontSize = MaterialTheme.typography.labelMedium.fontSize,
                    fontWeight = FontWeight.Medium
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 2.dp)
            )
        }
    }
    // Commit on each text change so the panel stays in sync with input.
    // (parseHex ignores invalid input — no error to surface here.)
    LaunchedEffect(text) { onSubmit(text) }
}

@Composable
private fun BlurToggleCard(
    isOn: Boolean,
    onToggle: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isOn) iOSBlue.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.04f)
            )
            .border(
                width = if (isOn) 1.5.dp else 1.dp,
                color = if (isOn) iOSBlueBright else HairlineSubtle,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.BlurOn,
                contentDescription = null,
                tint = if (isOn) iOSBlueBright else LabelPrimary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Blurred video",
                    color = LabelPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Fill the letterbox with a heavily-blurred copy of the source.",
                    color = LabelSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            // Toggle indicator
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(22.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(
                        if (isOn) iOSBlue else Color.White.copy(alpha = 0.10f)
                    ),
                contentAlignment = if (isOn) Alignment.CenterEnd
                                   else Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hex helpers
// ---------------------------------------------------------------------------

/** Render an `0xAARRGGBB` Long as a 6-char `#RRGGBB` string. */
private fun formatHex(argb: Long): String {
    val rgb = argb and 0x00FFFFFFL
    return "%06X".format(rgb)
}

/** Parse a 6-char hex (with or without leading `#`) into `0xFFRRGGBB`. */
private fun parseHex(hex: String): Long? {
    if (hex.length != 6) return null
    val v = hex.toLongOrNull(16) ?: return null
    return 0xFF000000L or v
}
