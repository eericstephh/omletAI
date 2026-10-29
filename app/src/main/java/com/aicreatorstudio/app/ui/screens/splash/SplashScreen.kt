package com.aicreatorstudio.app.ui.screens.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicreatorstudio.app.R
import com.aicreatorstudio.app.ui.components.t
import com.aicreatorstudio.app.ui.theme.AccentBright
import com.aicreatorstudio.app.ui.theme.LabelPrimary
import com.aicreatorstudio.app.ui.theme.LocalMotionEnabled
import com.aicreatorstudio.app.ui.theme.SplashAmbientGlowStart
import com.aicreatorstudio.app.ui.theme.SplashBackground
import com.aicreatorstudio.app.ui.theme.SplashTagline
import kotlinx.coroutines.delay

/**
 * The opening splash screen — the first thing a user sees, and therefore the
 * first place the brand must be correct.
 *
 * Visual elements, top to bottom:
 *   1. Solid [SplashBackground] fill (matches the system splash colour so
 *      the framework splash and this screen are seamless).
 *   2. One soft ambient glow behind the logo (pulsing only when motion is
 *      enabled).
 *   3. The brand logo (R.drawable.app_logo) with a spring scale-up and fade.
 *   4. The Omelet wordmark — Persian brand name + the Latin "AI" suffix laid
 *      out as two runs so mixed-direction text cannot reorder.
 *   5. A short Persian tagline.
 *
 * After ~1800ms, [onFinished] is invoked so the caller can navigate. The
 * screen does not own navigation itself — the nav graph decides what to swap
 * to, which keeps the splash a pure visual surface and easy to test.
 *
 * @param onFinished invoked once when the hold duration is over.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    holdDurationMs: Long = 1_800L
) {
    val motionEnabled = LocalMotionEnabled.current

    // Stage flag: 0 = nothing, 1 = logo in, 2 = text in. Driving a single
    // state with tween/spring animations gives a clean staggered reveal
    // without each element running its own timer.
    var stage by remember { mutableIntStateOf(if (motionEnabled) 0 else 2) }

    LaunchedEffect(Unit) {
        if (motionEnabled) {
            stage = 1
            delay(220)
            stage = 2
        }
        delay(holdDurationMs)
        onFinished()
    }

    val logoProgress by animateFloatAsState(
        targetValue = if (stage >= 1) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "logo-progress"
    )

    val logoAlpha by animateFloatAsState(
        targetValue = if (stage >= 1) 1f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "logo-alpha"
    )

    val textProgress by animateFloatAsState(
        targetValue = if (stage >= 2) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "text-progress"
    )

    val textAlpha by animateFloatAsState(
        targetValue = if (stage >= 2) 1f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "text-alpha"
    )

    // Subtle ambient pulse — a long ease-in-out loop that breathes between
    // 0.85 and 1.15 alpha. Frozen at 1 when motion is off.
    val glowPulse = if (motionEnabled) {
        rememberInfiniteTransition(label = "glow-pulse")
            .animateFloat(
                initialValue = 0.85f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2_400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glow-pulse-value"
            ).value
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SplashBackground)
    ) {
        AmbientGlow(
            pulse = glowPulse,
            modifier = Modifier
                .align(Alignment.Center)
                .size(420.dp)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            // Logo — scale-up from 0.8 to 1.0, fade in, with one soft glow
            // behind it. Scale lives in the same graphicsLayer as the alpha
            // so it is a single render pass.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .graphicsLayer {
                            scaleX = logoProgress
                            scaleY = logoProgress
                            alpha = logoAlpha * 0.55f
                        }
                        .background(
                            Brush.radialGradient(
                                listOf(
                                    AccentBright.copy(alpha = 0.35f),
                                    Color.Transparent
                                ),
                                radius = 320f
                            )
                        )
                )
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = t("املت"),
                    modifier = Modifier
                        .size(120.dp)
                        .graphicsLayer {
                            scaleX = 0.8f + 0.2f * logoProgress
                            scaleY = 0.8f + 0.2f * logoProgress
                            alpha = logoAlpha
                        }
                        .clip(RoundedCornerShape(24.dp))
                )
            }

            Spacer(Modifier.height(20.dp))

            // Wordmark — slide-up from +16dp to 0dp while fading in.
            Box(
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha
                    translationY = (1f - textProgress) * 16f
                }
            ) {
                Wordmark()
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = t("استودیو ویرایش ویدیو"),
                color = SplashTagline,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = textAlpha
                    translationY = (1f - textProgress) * 8f
                }
            )
        }
    }
}

/**
 * The Omelet wordmark.
 *
 * The Persian name and the Latin "AI" suffix are rendered as two separate
 * runs inside an explicit left-to-right Row. Keeping them separate (instead
 * of one "املتAI" string) means the bidi algorithm can never reorder the
 * suffix, and the accent colour stays on the Latin run only.
 */
@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = t("املت"),
            fontSize = 42.sp,
            fontWeight = FontWeight.Black,
            color = LabelPrimary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "AI",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AccentBright,
            modifier = Modifier.padding(bottom = 6.dp)
        )
    }
}

/**
 * A single soft radial glow behind the logo, tinted with the brand accent.
 * One layer only — the older design stacked two coloured glows plus a
 * duplicate blurred wordmark, which read as neon rather than premium.
 */
@Composable
private fun AmbientGlow(
    pulse: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .graphicsLayer { alpha = pulse }
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        SplashAmbientGlowStart,
                        SplashAmbientGlowStart.copy(alpha = 0f)
                    ),
                    radius = 380f
                )
            )
    )
}
