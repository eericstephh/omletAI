package com.aicreatorstudio.app.ui.screens.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.components.iOSSlider
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSShapes
import kotlin.math.roundToInt

@Composable
fun FloatingPanel(
    title: String,
    isExpanded: Boolean,
    onClose: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var positionX by remember { mutableStateOf(0f) }
    var positionY by remember { mutableStateOf(0f) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasBeenDragged by remember { mutableStateOf(false) }

    val panelWidth = 360.dp
    val panelHeight = if (isExpanded) 500.dp else 200.dp

    val alpha by animateFloatAsState(if (isExpanded) 1f else 0.96f, label = "panel-alpha")

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { hasBeenDragged = true },
                    onDrag = { change, dragAmount ->
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                        change.consume()
                    },
                    onDragEnd = {
                        positionX += dragOffsetX
                        positionY += dragOffsetY
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    }
                )
            }
            .offset { IntOffset((positionX + dragOffsetX).roundToInt(), (positionY + dragOffsetY).roundToInt()) }
    ) {
        GlassSurface(
            shape = RoundedCornerShape(22.dp),
            material = GlassMaterial.Regular,
            modifier = Modifier
                .width(panelWidth)
                .height(panelHeight)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Panel Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Drag Handle
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DragIndicator,
                                contentDescription = "Drag",
                                tint = LabelSecondary.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleSmall,
                                color = LabelPrimary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }

                    // Action Buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Expand toggle (small glass icon button)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(iOSShapes.capsule)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onToggleExpand() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = LabelSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        // Close (small glass icon button, slightly red-tinted)
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(iOSShapes.capsule)
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { onClose() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = LabelSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Hairline divider
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HairlineSubtle)
                )

                // Panel Content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
fun FloatingPanelContainer(
    panels: Map<String, FloatingPanelState>,
    onPanelClose: (String) -> Unit,
    onPanelToggleExpand: (String) -> Unit,
    onPanelPositionChange: (String, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        panels.forEach { (panelId, state) ->
            if (state.isVisible) {
                FloatingPanel(
                    title = state.title,
                    isExpanded = state.isExpanded,
                    onClose = { onPanelClose(panelId) },
                    onToggleExpand = { onPanelToggleExpand(panelId) },
                    modifier = Modifier
                        .offset { IntOffset(state.positionX.roundToInt(), state.positionY.roundToInt()) }
                ) {
                    state.content()
                }
            }
        }
    }
}

@Composable
fun FloatingVolumeControl(
    volume: Float,
    onVolumeChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var localVolume by remember { mutableFloatStateOf(volume) }

    LaunchedEffect(volume) {
        localVolume = volume
    }

    GlassSurface(
        shape = RoundedCornerShape(20.dp),
        material = GlassMaterial.Regular,
        modifier = modifier.width(240.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // iOS-style circular icon container
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(iOSShapes.capsule)
                        .background(iOSBlue.copy(alpha = 0.18f))
                        .border(1.dp, iOSBlue.copy(alpha = 0.4f), iOSShapes.capsule),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = iOSBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Volume: ${(localVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LabelPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            iOSSlider(
                value = localVolume,
                valueRange = 0f..2f,
                onValueChange = {
                    localVolume = it
                    onVolumeChanged(it)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

data class FloatingPanelState(
    val title: String,
    val isVisible: Boolean = false,
    val isExpanded: Boolean = true,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val content: @Composable () -> Unit = {}
)
