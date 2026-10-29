package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.ColorGrading
import com.aicreatorstudio.app.ui.components.ZeroSnapSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSIndigo
import com.aicreatorstudio.app.ui.theme.iOSMint
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.iOSShapes
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Studio-grade Lumetri-style Adjust / Color Grading panel.
 *
 * Layout (top-to-bottom):
 *   1. Header (title, "before/after" hold button, reset, expand state)
 *   2. Basic Tone    — Brightness, Contrast, Exposure
 *   3. Tone Range    — Highlights, Shadows, Whites, Blacks
 *   4. Color         — Temperature, Tint, Saturation, Vibrance
 *   5. Detail & Look — Sharpness, Clarity, Vignette, Fade, Grain
 *   6. HSL           — Hue shift for 8 primary colors (collapsible)
 *
 * Every slider uses [ZeroSnapSlider] (zero-snap, haptic tick on
 * crossing, signed value display) and shows a precise numeric
 * readout to the right of the label.
 *
 * @param grading current ColorGrading state
 * @param onGradingChange called with the new state on every slider tick
 * @param onReset called when the user taps the Reset button
 * @param onBeforeAfterChange true when "compare before/after" is held
 */
@Composable
fun ColorGradingPanel(
    grading: ColorGrading,
    onGradingChange: (ColorGrading) -> Unit,
    onReset: () -> Unit,
    onBeforeAfterChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var hslExpanded by remember { mutableStateOf(false) }
    var isComparing by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // -------- Header --------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Contrast,
                    contentDescription = null,
                    tint = iOSBlueBright,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Color Grading",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Lumetri · Studio",
                        color = iOSBlueBright,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // Reset pill
            Box(
                modifier = Modifier
                    .clip(iOSShapes.capsule)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onReset()
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = LabelSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Reset All",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // -------- Before / After hold button (full-width) --------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(iOSShapes.capsule)
                .background(
                    if (isComparing) iOSBlue.copy(alpha = 0.30f)
                    else Color.White.copy(alpha = 0.06f)
                )
                .border(1.dp, HairlineSubtle, iOSShapes.capsule)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isComparing = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onBeforeAfterChange(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                isComparing = false
                                onBeforeAfterChange(false)
                            }
                        }
                    )
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isComparing) Icons.Filled.Visibility
                                  else Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = if (isComparing) iOSBlueBright else LabelSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (isComparing) "Showing Original" else "Hold to Compare Original",
                    color = if (isComparing) iOSBlueBright else LabelSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // -------- Basic Tone --------
        SectionLabel("BASIC TONE", icon = Icons.Filled.WbSunny, tint = iOSBlueBright)
        ToneSlider(
            label = "Brightness",
            value = grading.brightness,
            onValueChange = { onGradingChange(grading.copy(brightness = it)) },
            valueText = signed(grading.brightness * 100, "%.0f")
        )
        ToneSlider(
            label = "Contrast",
            value = grading.contrast,
            onValueChange = { onGradingChange(grading.copy(contrast = it)) },
            valueText = signed(grading.contrast * 100, "%.0f")
        )
        ToneSlider(
            label = "Exposure",
            value = grading.exposure,
            onValueChange = { onGradingChange(grading.copy(exposure = it)) },
            valueText = signed(grading.exposure, "+%.1f", withSign = true),
            range = -2f..2f
        )

        // -------- Tone Range --------
        SectionLabel("TONE RANGE", tint = iOSIndigo)
        ToneSlider(
            label = "Highlights",
            value = grading.highlights,
            onValueChange = { onGradingChange(grading.copy(highlights = it)) },
            valueText = signed(grading.highlights * 100, "%.0f")
        )
        ToneSlider(
            label = "Shadows",
            value = grading.shadows,
            onValueChange = { onGradingChange(grading.copy(shadows = it)) },
            valueText = signed(grading.shadows * 100, "%.0f")
        )
        ToneSlider(
            label = "Whites",
            value = grading.whites,
            onValueChange = { onGradingChange(grading.copy(whites = it)) },
            valueText = signed(grading.whites * 100, "%.0f")
        )
        ToneSlider(
            label = "Blacks",
            value = grading.blacks,
            onValueChange = { onGradingChange(grading.copy(blacks = it)) },
            valueText = signed(grading.blacks * 100, "%.0f")
        )

        // -------- Color --------
        SectionLabel("COLOR", tint = iOSPurple)
        ToneSlider(
            label = "Temperature",
            value = grading.temperature,
            onValueChange = { onGradingChange(grading.copy(temperature = it)) },
            valueText = signed(grading.temperature * 100, "%.0f")
        )
        ToneSlider(
            label = "Tint",
            value = grading.tint,
            onValueChange = { onGradingChange(grading.copy(tint = it)) },
            valueText = signed(grading.tint * 100, "%.0f")
        )
        ToneSlider(
            label = "Saturation",
            value = grading.saturation,
            onValueChange = { onGradingChange(grading.copy(saturation = it)) },
            valueText = signed(grading.saturation * 100, "%.0f")
        )
        ToneSlider(
            label = "Vibrance",
            value = grading.vibrance,
            onValueChange = { onGradingChange(grading.copy(vibrance = it)) },
            valueText = signed(grading.vibrance * 100, "%.0f")
        )

        // -------- Detail & Look --------
        SectionLabel("DETAIL & LOOK", icon = Icons.Filled.GraphicEq, tint = iOSMint)
        DetailSlider(
            label = "Sharpness",
            value = grading.sharpness,
            onValueChange = { onGradingChange(grading.copy(sharpness = it)) },
            valueText = (grading.sharpness * 100).toInt().toString()
        )
        ToneSlider(
            label = "Clarity",
            value = grading.clarity,
            onValueChange = { onGradingChange(grading.copy(clarity = it)) },
            valueText = signed(grading.clarity * 100, "%.0f")
        )
        DetailSlider(
            label = "Vignette",
            value = grading.vignetteAmount,
            onValueChange = { onGradingChange(grading.copy(vignetteAmount = it)) },
            valueText = (grading.vignetteAmount * 100).toInt().toString()
        )
        DetailSlider(
            label = "Vignette Feather",
            value = grading.vignetteFeather,
            onValueChange = { onGradingChange(grading.copy(vignetteFeather = it)) },
            valueText = (grading.vignetteFeather * 100).toInt().toString()
        )
        DetailSlider(
            label = "Vignette Roundness",
            value = grading.vignetteRoundness,
            onValueChange = { onGradingChange(grading.copy(vignetteRoundness = it)) },
            valueText = (grading.vignetteRoundness * 100).toInt().toString()
        )
        DetailSlider(
            label = "Fade",
            value = grading.fade,
            onValueChange = { onGradingChange(grading.copy(fade = it)) },
            valueText = (grading.fade * 100).toInt().toString()
        )
        DetailSlider(
            label = "Grain",
            value = grading.grain,
            onValueChange = { onGradingChange(grading.copy(grain = it)) },
            valueText = (grading.grain * 100).toInt().toString()
        )

        // -------- HSL (collapsible) --------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { hslExpanded = !hslExpanded }
                .padding(vertical = 4.dp)
        ) {
            SectionLabelInline("HSL · HUE SHIFT", tint = iOSBlue)
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (hslExpanded) Icons.Filled.ExpandLess
                              else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = LabelSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(
            visible = hslExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HslHueRow("Red", grading.hueRed) {
                    onGradingChange(grading.copy(hueRed = it))
                }
                HslHueRow("Orange", grading.hueOrange) {
                    onGradingChange(grading.copy(hueOrange = it))
                }
                HslHueRow("Yellow", grading.hueYellow) {
                    onGradingChange(grading.copy(hueYellow = it))
                }
                HslHueRow("Green", grading.hueGreen) {
                    onGradingChange(grading.copy(hueGreen = it))
                }
                HslHueRow("Aqua", grading.hueAqua) {
                    onGradingChange(grading.copy(hueAqua = it))
                }
                HslHueRow("Blue", grading.hueBlue) {
                    onGradingChange(grading.copy(hueBlue = it))
                }
                HslHueRow("Purple", grading.huePurple) {
                    onGradingChange(grading.copy(huePurple = it))
                }
                HslHueRow("Magenta", grading.hueMagenta) {
                    onGradingChange(grading.copy(hueMagenta = it))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(
    text: String,
    icon: ImageVector? = null,
    tint: Color = iOSBlueBright
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SectionLabelInline(text: String, tint: Color) {
    Text(
        text = text,
        color = tint,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ToneSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueText: String,
    range: ClosedFloatingPointRange<Float> = -1f..1f
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = LabelPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = if (value == 0f) LabelSecondary else iOSBlueBright,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        ZeroSnapSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range
        )
    }
}

@Composable
private fun DetailSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueText: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = LabelPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = if (value == 0f) LabelSecondary else iOSBlueBright,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
        ZeroSnapSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            showZeroTick = false
        )
    }
}

@Composable
private fun HslHueRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    val swatch = hueSwatchColor(label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, HairlineSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = LabelPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp)
        )
        ZeroSnapSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = -60f..60f,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = hueDegreesText(value),
            color = if (value == 0f) LabelSecondary else iOSBlueBright,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(48.dp)
        )
    }
}

private fun hueSwatchColor(name: String): Color = when (name) {
    "Red" -> Color(0xFFFF453A)
    "Orange" -> Color(0xFFFF9F0A)
    "Yellow" -> Color(0xFFFFD60A)
    "Green" -> Color(0xFF30D158)
    "Aqua" -> Color(0xFF64D2FF)
    "Blue" -> Color(0xFF0A84FF)
    "Purple" -> Color(0xFFBF5AF2)
    "Magenta" -> Color(0xFFFF375F)
    else -> Color.White
}

private fun hueDegreesText(value: Float): String {
    val deg = value.roundToInt()
    return when {
        deg == 0 -> "0°"
        deg > 0 -> "+$deg°"
        else -> "$deg°"
    }
}

/**
 * Format a float as a signed value (e.g. `+12`, `-5`, `+1.5`, `0`).
 * Used by the tone sliders to display the live numeric readout.
 *
 * @param value the value to display
 * @param format printf-style format string for the magnitude (must NOT
 *   contain a sign prefix; the prefix is added by this function when
 *   appropriate). The number of decimal places in the format string
 *   is mirrored in the zero value so the readout doesn't jump width
 *   between `0` and `0.0`.
 * @param withSign when true, positive values are prefixed with `+`
 */
private fun signed(value: Float, format: String, withSign: Boolean = true): String {
    val rounded = (value * 100f).roundToInt() / 100f
    if (abs(rounded) < 0.0001f) {
        // Mirror the format's decimal precision for the zero case.
        val decimals = if (format.contains("%.1f")) 1 else 0
        return when {
            format.contains("°") -> "0°"
            decimals == 1 -> "0.0"
            else -> "0"
        }
    }
    val sgn = if (withSign && rounded > 0f) "+" else ""
    return sgn + format.format(rounded)
}
