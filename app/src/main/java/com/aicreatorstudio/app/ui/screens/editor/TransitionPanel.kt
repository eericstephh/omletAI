package com.aicreatorstudio.app.ui.screens.editor

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.TransitionConfig
import com.aicreatorstudio.app.model.TransitionDir
import com.aicreatorstudio.app.model.TransitionEasing
import com.aicreatorstudio.app.model.TransitionEngine
import com.aicreatorstudio.app.model.TransitionPreset
import com.aicreatorstudio.app.model.TransitionPresets
import com.aicreatorstudio.app.model.TransitionScope
import com.aicreatorstudio.app.model.TransitionType
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.components.iOSSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.LabelTertiary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSRed

@Composable
fun TransitionPanel(
    selectedClip: VideoClip?,
    onApply: (TransitionScope, TransitionConfig?) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedType by remember { mutableStateOf(TransitionType.FADE_IN) }
    var scope by remember { mutableStateOf(TransitionScope.BOTH) }
    var durationMs by remember { mutableFloatStateOf(400f) }
    var intensity by remember { mutableFloatStateOf(1f) }
    var easing by remember { mutableStateOf(TransitionEasing.EASE_IN_OUT) }
    var activeCategory by remember { mutableStateOf<String?>(null) }

    val draftConfig = TransitionConfig(selectedType, durationMs.toInt(), intensity, easing)
    val categories = TransitionType.entries.filter { it != TransitionType.NONE }
        .groupBy { it.category }.toList()

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Category chips ────────────────────────────────────
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories.size) { idx ->
                val (cat, types) = categories[idx]
                val isActive = activeCategory == cat.name
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isActive) iOSBlue.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (isActive) iOSBlue.copy(alpha = 0.35f) else HairlineSubtle, RoundedCornerShape(10.dp))
                        .clickable { activeCategory = if (isActive) null else cat.name }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(cat.displayName, style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) iOSBlueBright else LabelSecondary, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── Transition grid ───────────────────────────────────
        val displayTypes = if (activeCategory != null) {
            val cat = categories.firstOrNull { it.first.name == activeCategory }
            cat?.second ?: TransitionType.entries.filter { it != TransitionType.NONE }
        } else {
            TransitionPresets.viral.map { it.config.type } +
                TransitionType.entries.filter { it != TransitionType.NONE }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(displayTypes.distinct()) { type ->
                TransitionThumb(type = type, isSelected = type == selectedType,
                    onClick = { selectedType = type })
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Controls ──────────────────────────────────────────
        GlassSurface(shape = RoundedCornerShape(14.dp), material = GlassMaterial.Thin,
            modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {

                // Scope selector
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    ScopeChip("In", scope == TransitionScope.IN, modifier = Modifier.weight(1f)) { scope = TransitionScope.IN }
                    ScopeChip("Out", scope == TransitionScope.OUT, modifier = Modifier.weight(1f)) { scope = TransitionScope.OUT }
                    ScopeChip("Both", scope == TransitionScope.BOTH, modifier = Modifier.weight(1f)) { scope = TransitionScope.BOTH }
                }

                Spacer(Modifier.height(8.dp))

                // Duration
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Duration", style = MaterialTheme.typography.labelSmall,
                        color = LabelSecondary, modifier = Modifier.width(56.dp))
                    iOSSlider(value = durationMs, onValueChange = { durationMs = it },
                        valueRange = 100f..2000f, activeColor = iOSBlue,
                        modifier = Modifier.weight(1f).height(28.dp))
                    Text("${durationMs.toInt()}ms", style = MaterialTheme.typography.labelSmall,
                        color = LabelPrimary, modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(4.dp))

                // Intensity
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Strength", style = MaterialTheme.typography.labelSmall,
                        color = LabelSecondary, modifier = Modifier.width(56.dp))
                    iOSSlider(value = intensity, onValueChange = { intensity = it },
                        valueRange = 0.1f..3f, activeColor = iOSBlue,
                        modifier = Modifier.weight(1f).height(28.dp))
                    Text("${(intensity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                        color = LabelPrimary, modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(8.dp))

                // Easing
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TransitionEasing.entries.forEach { e ->
                        val sel = e == easing
                        Box(modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(if (sel) iOSBlue.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
                            .border(1.dp, if (sel) iOSBlue.copy(alpha = 0.4f) else HairlineSubtle, RoundedCornerShape(8.dp))
                            .clickable { easing = e }
                            .padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(e.displayName, style = MaterialTheme.typography.labelSmall,
                                color = if (sel) iOSBlueBright else LabelSecondary)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Action buttons ────────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                .background(
                    if (selectedClip != null) Brush.horizontalGradient(listOf(iOSBlue, iOSBlueBright))
                    else Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.08f)))
                )
                .clickable(enabled = selectedClip != null) { onApply(scope, draftConfig) }
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(if (selectedClip != null) "Apply" else "Select a clip",
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, HairlineSubtle, RoundedCornerShape(50))
                .clickable(enabled = selectedClip != null) { onApply(scope, null) }
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text("Remove", color = iOSRed.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(10.dp))
        .background(if (isSelected) iOSBlue.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f))
        .border(1.dp, if (isSelected) iOSBlue.copy(alpha = 0.35f) else HairlineSubtle, RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(vertical = 7.dp),
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) iOSBlueBright else LabelSecondary,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun TransitionThumb(type: TransitionType, isSelected: Boolean, onClick: () -> Unit) {
    val demoCfg = remember(type) { TransitionConfig(type, 1400, 1f, TransitionEasing.LINEAR) }
    val transition = rememberInfiniteTransition(label = "tt${type.name}")
    val t by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), "ttP")
    val pose = if (t < 0.5f) TransitionEngine.evaluate(demoCfg, TransitionDir.IN, t * 2f)
    else TransitionEngine.evaluate(demoCfg, TransitionDir.OUT, (t - 0.5f) * 2f)

    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)) {
        Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A20))
            .border(if (isSelected) 1.5.dp else 1.dp,
                if (isSelected) iOSBlue else HairlineSubtle,
                RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.matchParentSize().graphicsLayer {
                alpha = pose.alpha.coerceIn(0f, 1f)
                scaleX = pose.scaleX; scaleY = pose.scaleY
                translationX = pose.translationXDp.dp.toPx() * 0.12f
                translationY = pose.translationYDp.dp.toPx() * 0.12f
                rotationZ = pose.rotationZDeg
            }.background(Brush.verticalGradient(listOf(iOSBlue.copy(alpha = 0.6f), iOSBlueBright.copy(alpha = 0.3f)))))
            if (pose.flashWhite > 0.01f) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = pose.flashWhite)))
        }
        Text(type.displayName, style = MaterialTheme.typography.labelSmall,
            maxLines = 1, color = if (isSelected) iOSBlueBright else LabelTertiary,
            modifier = Modifier.padding(top = 3.dp))
    }
}

// private fun BoxScope.fillMaxSize() = Modifier.matchParentSize()
