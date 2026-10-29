package com.aicreatorstudio.app.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.aicreatorstudio.app.model.ClipAnimation
import com.aicreatorstudio.app.model.ClipMotion
import com.aicreatorstudio.app.model.VideoEffect

/** ColorMatrix preset for each famous effect. */
fun VideoEffect.toColorMatrix(): android.graphics.ColorMatrix {
    val m = android.graphics.ColorMatrix()
    when (this) {
        VideoEffect.NONE -> Unit

        VideoEffect.NOIR -> m.set(floatArrayOf(
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))

        VideoEffect.SEPIA -> m.set(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))

        VideoEffect.VIVID -> {
            val s = 1.7f * 1.15f
            m.set(floatArrayOf(
                s, 0f, 0f, 0f, 0f,
                0f, s, 0f, 0f, 0f,
                0f, 0f, s, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        VideoEffect.FADE -> {
            val c = 0.75f
            val b = 1.12f
            val g = c * b
            m.set(floatArrayOf(
                g, 0f, 0f, 0f, 127.5f * (1f - c) * b,
                0f, g, 0f, 0f, 127.5f * (1f - c) * b,
                0f, 0f, g, 0f, 127.5f * (1f - c) * b,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        VideoEffect.COLD -> m.set(floatArrayOf(
            0.85f, 0f, 0f, 0f, 0f,
            0f, 0.98f, 0f, 0f, 8f,
            0f, 0f, 1.3f, 0f, 16f,
            0f, 0f, 0f, 1f, 0f
        ))

        VideoEffect.WARM -> m.set(floatArrayOf(
            1.25f, 0f, 0f, 0f, 12f,
            0f, 1.02f, 0f, 0f, 4f,
            0f, 0f, 0.8f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))

        VideoEffect.INVERT -> {
            m.set(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
        }

        VideoEffect.DREAM -> m.set(floatArrayOf(
            0.95f, 0f, 0.05f, 0f, 28f,
            0f, 0.82f, 0f, 0f, 18f,
            0.05f, 0f, 1.02f, 0f, 38f,
            0f, 0f, 0f, 1f, 0f
        ))

        VideoEffect.CYBER -> m.set(floatArrayOf(
            0.8f, 0f, 0f, 0f, 0f,
            0.1f, 1.35f, 0.1f, 0f, 10f,
            0f, 0f, 0.9f, 0f, 6f,
            0f, 0f, 0f, 1f, 0f
        ))

        VideoEffect.VHS -> m.set(floatArrayOf(
            1.08f, 0f, 0f, 0f, 14f,
            0f, 0.92f, 0f, 0f, 0f,
            0f, 0f, 0.94f, 0f, -10f,
            0f, 0f, 0f, 1f, 0f
        ))
    }
    return m
}

/**
 * PHASE A (Persistence Integrity) — combine the famous-effect ColorMatrix
 * with the brightness/contrast/saturation adjustment.
 *
 * **The formula now lives in exactly one place.** This used to compute
 * its own B/C/S matrix — a brightness *offset* of `(b-1)*128` on a
 * diagonal contrast scale, plus a saturation *lerp* toward luminance —
 * while the export pipeline used a pivot gain of `k = c·b·s` with
 * `off = 127.5·(1-c)·b`. Both KDoc blocks claimed "the result is
 * identical", and both were wrong: the two only agreed when
 * saturation == 1f, and only at mid-grey.
 *
 * The consequence was that `brightness = 1.5` previewed as `R + 64` and
 * exported as `1.5·R`, and `contrast = 1.5, saturation = 1.5` produced
 * two entirely different images. The preview now delegates to
 * [ColorGradingMath.fullExportMatrix] with an identity grade, so the
 * on-screen matrix is literally the one the renderer bakes.
 *
 * 2026-10-21 — the preview no longer calls this function: it calls
 * [ColorGradingMath.fullPreviewExportMatrix], which is the *complete*
 * chain (B/C/S → effect → grade → AI-upscaler CAS) and is the one the
 * export bakes too. This helper remains as the narrow
 * "B/C/S + effect, no grade, no upscale" accessor.
 */
internal fun combinedColorMatrix(
    effect: VideoEffect,
    brightness: Float,
    contrast: Float,
    saturation: Float
): androidx.compose.ui.graphics.ColorMatrix = androidx.compose.ui.graphics.ColorMatrix(
    ColorGradingMath.previewColorMatrixArray(effect, brightness, contrast, saturation)
)

/**
 * Small colorful scene rendered once and reused as the base image for all
 * effect/motion thumbnails so users can see what each option does.
 */
@Composable
fun rememberTestImage(): ImageBitmap = remember {
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    paint.shader = LinearGradient(
        0f, 0f, size.toFloat(), size.toFloat(),
        android.graphics.Color.CYAN, android.graphics.Color.MAGENTA,
        Shader.TileMode.CLAMP
    )
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

    paint.shader = null
    paint.color = android.graphics.Color.YELLOW
    canvas.drawCircle(size * 0.68f, size * 0.32f, size * 0.17f, paint)

    paint.color = android.graphics.Color.rgb(34, 130, 70)
    canvas.drawRect(0f, size * 0.72f, size.toFloat(), size.toFloat(), paint)

    paint.color = android.graphics.Color.DKGRAY
    canvas.drawCircle(size * 0.33f, size * 0.5f, size * 0.075f, paint)
    canvas.drawRect(size * 0.26f, size * 0.57f, size * 0.40f, size * 0.86f, paint)

    bitmap.asImageBitmap()
}

// 2026-09-16: `MotionPose` / `motionPoseAt` used to live here, which is
// why the export could not reach them — the render path must not depend on
// this package. The mapping moved to
// [com.aicreatorstudio.app.model.ClipMotion], where the export evaluates
// the identical curve, so a preset can no longer look one way on screen
// and another in the exported file. Callers use `ClipMotion.poseAt`.

@Composable
fun EffectThumbnail(
    effect: VideoEffect,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Image(
            bitmap = rememberTestImage(),
            contentDescription = effect.displayName,
            contentScale = ContentScale.Crop,
            colorFilter = if (effect == VideoEffect.NONE) null
                          else ColorFilter.colorMatrix(
                              ColorMatrix(effect.toColorMatrix().array)
                          ),
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp)
                )
        )
        Text(
            text = effect.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
fun MotionThumbnail(
    animation: ClipAnimation,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "motionDemo")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "motionT"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            // `t` comes from the looping infinite transition below, so the
            // thumbnail cycles the preset on purpose — unlike the editor
            // preview, which ramps it once across the clip.
            val pose = ClipMotion.poseAt(animation, t)
            Image(
                bitmap = rememberTestImage(),
                contentDescription = animation.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .graphicsLayer {
                        alpha = pose.alpha
                        scaleX = pose.scaleX
                        scaleY = pose.scaleY
                        translationX = pose.translationXDp.dp.toPx()
                        translationY = pose.translationYDp.dp.toPx()
                        rotationZ = pose.rotationZ
                    }
            )
        }
        Text(
            text = animation.displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
