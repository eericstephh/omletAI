package com.aicreatorstudio.app.ui.screens.editor

import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.aicreatorstudio.app.model.TransitionEngine
import com.aicreatorstudio.app.model.TransitionPose
import com.aicreatorstudio.app.model.VideoClip
import com.aicreatorstudio.app.model.VideoEffect
import kotlin.math.roundToInt

/**
 * Aspect ratio buckets the canvas can render in. The "fit" math picks the
 * smallest bucket that contains the source frame.
 */
enum class CanvasAspect(val ratio: Float) {
    Square(1f),
    Wide(16f / 9f),
    Vertical(9f / 16f)
}

/**
 * Live, responsive video canvas.
 *
 * Goals:
 *   - Zero black-screen glitch when a clip loads: the [TextureView] is
 *     constructed once and re-bound to ExoPlayer; the surface is detached
 *     on dispose so ExoPlayer never holds a dangling reference.
 *   - Picks the largest frame that fits the available box while preserving
 *     the source aspect ratio (16:9 / 9:16 / 1:1).
 *   - Color-matrix effects (brightness/contrast/saturation, famous filters)
 *     are applied via [Modifier.drawWithCache] so the matrix is computed
 *     once per state change, not on every frame.
 *   - Transition poses drive scale/rotation/blur so the canvas is also
 *     the visual stage for clip-to-clip transitions.
 */
@Composable
fun EditorVideoCanvas(
    clip: VideoClip?,
    player: ExoPlayer,
    isPlaying: Boolean,
    isReady: Boolean,
    currentPositionMs: Long,
    selectedEffect: VideoEffect,
    brightness: Float,
    contrast: Float,
    saturation: Float,
    rotation: Int,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Detect the source frame size and choose the right aspect bucket.
    var videoAspect by remember(clip?.uri) { mutableStateOf(16f / 9f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width.toFloat() / videoSize.height.toFloat()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Re-bind the media item whenever the active clip changes.
    LaunchedEffect(clip?.uri) {
        if (clip != null && clip.uri.isNotBlank()) {
            if (player.currentMediaItem?.localConfiguration?.uri?.toString() != clip.uri) {
                player.setMediaItem(MediaItem.fromUri(clip.uri))
                player.prepare()
            }
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = EditorLayoutSpec.VideoCanvasMaxHeight)
    ) {
        val stageMaxWidthDp = maxWidth
        val stageMaxHeightDp = maxHeight

        val aspect = videoAspect.coerceIn(0.4f, 3f)
        val fitWidth = minOf(stageMaxWidthDp, stageMaxHeightDp * aspect)
        val fitHeight = fitWidth / aspect

        val colorMatrix = remember(
            brightness, contrast, saturation, selectedEffect
        ) {
            buildColorMatrix(brightness, contrast, saturation, selectedEffect)
        }

        val transitionPose: TransitionPose = remember(clip?.id, currentPositionMs) {
            if (clip == null) TransitionPose()
            else TransitionEngine.poseForCurrentPosition(listOf(clip), currentPositionMs)
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(fitWidth, fitHeight)
                .clip(RoundedCornerShape(EditorLayoutSpec.VideoCanvasCornerRadius))
                .background(Color.Black)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.06f),
                    shape = RoundedCornerShape(EditorLayoutSpec.VideoCanvasCornerRadius)
                )
        ) {
            if (clip == null) {
                EmptyCanvas()
            } else {
                CanvasSurface(
                    player = player,
                    colorMatrix = colorMatrix,
                    transitionPose = transitionPose,
                    rotation = rotation,
                    modifier = Modifier.fillMaxSize()
                )
                // Black/white flash overlays (used by transition poses).
                if (transitionPose.flashWhite > 0.01f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = transitionPose.flashWhite))
                    )
                }
                if (transitionPose.flashBlack > 0.01f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = transitionPose.flashBlack))
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCanvas() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF151517)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No video loaded",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun CanvasSurface(
    player: ExoPlayer,
    colorMatrix: ColorMatrix,
    transitionPose: TransitionPose,
    rotation: Int,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier
            .graphicsLayer {
                alpha = transitionPose.alpha.coerceIn(0f, 1f)
                val rotComp = if (rotation == 90 || rotation == 270) 16f / 9f else 1f
                scaleX = rotComp * transitionPose.scaleX
                scaleY = rotComp * transitionPose.scaleY
                rotationZ = rotation.toFloat() + transitionPose.rotationZDeg
                rotationX = transitionPose.rotationXDeg
                translationX = transitionPose.translationXDp.dp.toPx() * 0.6f
                translationY = transitionPose.translationYDp.dp.toPx() * 0.6f
            }
            .drawWithCache {
                val paint = Paint().apply {
                    colorFilter = ColorFilter.colorMatrix(colorMatrix)
                }
                onDrawWithContent {
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(
                            Rect(0f, 0f, size.width, size.height),
                            paint
                        )
                        drawContent()
                        canvas.restore()
                    }
                }
            }
            .blur(transitionPose.blurDp.dp),
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        // Detach any previous surface first to avoid a flash
                        // of black between clip changes.
                        runCatching { player.setVideoSurface(null) }
                        runCatching { player.setVideoSurface(Surface(surface)) }
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: android.graphics.SurfaceTexture,
                        width: Int,
                        height: Int
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(
                        surface: android.graphics.SurfaceTexture
                    ): Boolean {
                        runCatching { player.setVideoSurface(null) }
                        runCatching { surface.release() }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(
                        surface: android.graphics.SurfaceTexture
                    ) = Unit
                }
            }
        }
    )
}

/**
 * Compose brightness/contrast/saturation with the active [VideoEffect] into
 * a single [ColorMatrix]. The result is cached by the caller so it only
 * recomputes when one of the inputs changes (not on every frame).
 */
private fun buildColorMatrix(
    brightness: Float,
    contrast: Float,
    saturation: Float,
    effect: VideoEffect
): ColorMatrix {
    val b = brightness.coerceIn(0f, 2f)
    val c = contrast.coerceIn(0f, 2f)
    val s = saturation.coerceIn(0f, 2f)
    val k = c * b * s
    val off = 127.5f * (1f - c) * b

    val base = ColorMatrix(
        floatArrayOf(
            k, 0f, 0f, 0f, off,
            0f, k, 0f, 0f, off,
            0f, 0f, k, 0f, off,
            0f, 0f, 0f, 1f, 0f
        )
    )
    return if (effect != VideoEffect.NONE) {
        val withEffect = ColorMatrix(effect.toColorMatrix().array)
        withEffect.timesAssign(base)
        withEffect
    } else base
}
