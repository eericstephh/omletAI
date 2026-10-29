package com.aicreatorstudio.app.ui.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.ui.components.GlassMaterial
import com.aicreatorstudio.app.ui.components.GlassSurface
import com.aicreatorstudio.app.ui.theme.HairlineSubtle
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LabelSecondary
import com.aicreatorstudio.app.ui.theme.iOSBlue
import com.aicreatorstudio.app.ui.theme.iOSBlueBright
import com.aicreatorstudio.app.ui.theme.iOSPurple
import com.aicreatorstudio.app.ui.theme.iOSShapes

/**
 * 2026-09-10 — **optional membership glass card** on the app's entry
 * (Home) screen. PRESENTATIONAL ONLY by explicit request: the email
 * stays in local composable state, the button shows a confirmation,
 * and nothing is sent anywhere.
 *
 * The panel's own background is animated: two slowly orbiting
 * radial-gradient blobs (blue/purple) + a breathing alpha pulse,
 * rendered on a Canvas clipped to the card — cheap, GPU-friendly,
 * and independent of the screen-level ambient motion.
 */
@Composable
fun MembershipGlassCard(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (!visible) return

    val transition = rememberInfiniteTransition(label = "membershipMotion")
    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(16000, easing = LinearEasing)),
        label = "orbitAngle"
    )
    val blobDrift by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blobDrift"
    )
    val breathe by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(3600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    Box(modifier = modifier.fillMaxWidth()) {
        GlassSurface(
            shape = RoundedCornerShape(22.dp),
            material = GlassMaterial.Regular
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(22.dp))
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                // ---- animated motion background ----
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(22.dp))
                ) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f
                    val cy = h / 2f
                    rotate(degrees = orbitAngle, pivot = Offset(cx, cy)) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    iOSBlue.copy(alpha = 0.35f * breathe),
                                    Color.Transparent
                                ),
                                center = Offset(cx - w * 0.30f, cy - h * 0.2f),
                                radius = w * 0.55f
                            ),
                            radius = w * 0.55f,
                            center = Offset(cx - w * 0.30f, cy - h * 0.2f)
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    iOSPurple.copy(alpha = 0.40f * breathe),
                                    Color.Transparent
                                ),
                                center = Offset(cx + w * 0.32f, cy + h * 0.15f),
                                radius = w * 0.5f
                            ),
                            radius = w * 0.5f,
                            center = Offset(cx + w * 0.32f, cy + h * 0.15f)
                        )
                    }
                    // Third slow horizontal drifter (no rotation).
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                iOSBlueBright.copy(alpha = 0.22f),
                                Color.Transparent
                            ),
                            center = Offset(cx + blobDrift * w * 0.35f, cy - h * 0.35f),
                            radius = w * 0.38f
                        ),
                        radius = w * 0.38f,
                        center = Offset(cx + blobDrift * w * 0.35f, cy - h * 0.35f)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.WorkspacePremium,
                            contentDescription = null,
                            tint = iOSBlueBright,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "عضویت اختیاری",
                            color = LabelPrimary,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { visible = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "بستن",
                                tint = LabelSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "برای خبرنامه و پشتیبانی زودتر، ایمیلت را وارد کن. کاملاً اختیاری است.",
                        color = LabelSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = email,
                        onValueChange = {
                            email = it
                            error = null
                            submitted = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("you@example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        isError = error != null,
                        supportingText = error?.let { msg -> { Text(msg) } }
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(iOSShapes.capsule)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(iOSBlue, iOSBlueBright, iOSPurple.copy(alpha = 0.9f))
                                )
                            )
                            .clickable {
                                val e = email.trim()
                                when {
                                    e.isEmpty() -> error = "ایمیل را وارد کن."
                                    !e.contains("@") || !e.contains(".") ->
                                        error = "ایمیل معتبر نیست."
                                    else -> {
                                        error = null
                                        submitted = true
                                    }
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (submitted) "ثبت شد ✓ (حالت نمایشی)" else "عضویت",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
