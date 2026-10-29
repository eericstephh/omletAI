package com.aicreatorstudio.app.ui.screens.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aicreatorstudio.app.R
import com.aicreatorstudio.app.export.OverlayMaskRenderer
import com.aicreatorstudio.app.model.ChromaKeySettings
import com.aicreatorstudio.app.model.OverlayMaskGeometry
import com.aicreatorstudio.app.model.OverlayPipGeometry
import com.aicreatorstudio.app.model.PipBox
import com.aicreatorstudio.app.model.VideoOverlay
import com.aicreatorstudio.app.util.ChromaKeyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 2026-10-13 — the **preview half** of Picture-in-Picture.
 *
 * ## What was missing
 *
 * Before this file, `VideoOverlay` was timeline-only metadata: nothing in
 * the app turned `positionX` / `positionY` / `scale` / `uri` into pixels,
 * and the only two `ExoPlayer.Builder` sites in the project were the main
 * preview and the audio stems — so there was no player for overlay media
 * at all. This layer, plus [OverlayPipPlayer], is the missing consumer.
 *
 * ## Parity
 *
 * Placement comes from [OverlayPipGeometry.resolveBox] and the clip shape
 * from `overlayMaskShape` → [OverlayMaskGeometry.outline] — the same two
 * pure sources `export/OverlayCompositeRenderer` consumes. Neither side
 * owns a formula, so "the exported file matches the preview" is a
 * property of the code rather than of a reviewer's attention.
 *
 * ## Layer order
 *
 * This layer is a sibling inside the canvas `Box`, placed **above** the
 * video and subtitle layers and **below** the text gesture surface. That
 * is deliberate in both directions: a facecam must draw over the picture,
 * and text must stay the topmost *tappable* thing, because a full-size
 * gesture surface would swallow every text drag.
 *
 * Hit testing is therefore done **per PiP box**, not by a full-size
 * surface, and it is mask-aware: a circular facecam does not swallow taps
 * in its own transparent corners, so the video underneath stays reachable.
 *
 * ## Images vs video
 *
 * A still overlay is decoded once and drawn with [Image]; a video overlay
 * binds an `ExoPlayer` from [OverlayPipPlayer] to a `PlayerView`. Both are
 * clipped and positioned identically — the difference is only the source
 * of the pixels.
 */
@Composable
internal fun OverlayPipLayer(
    overlays: List<VideoOverlay>,
    selectedOverlayId: String?,
    playheadMs: Long,
    canvasSizePx: Size,
    /**
     * The overlay player pool. `null` means the caller has no pool (a
     * static thumbnail), in which case a VIDEO overlay renders its
     * placeholder rather than a live frame — and, importantly, no player
     * is created inside this composable, because a conditional `remember`
     * here would leak one every time the overlay list toggled.
     */
    pipPlayer: OverlayPipPlayer?,
    onSelect: (String?) -> Unit,
    onTransformStarted: () -> Unit,
    onTransform: (id: String, x: Float, y: Float, scale: Float, rotation: Float) -> Unit,
    onTransformEnded: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (overlays.isEmpty()) return
    val canvasW = canvasSizePx.width.roundToInt()
    val canvasH = canvasSizePx.height.roundToInt()
    if (canvasW <= 0 || canvasH <= 0) return

    // The panel and this chrome must address the SAME overlay, so both go
    // through `resolveTarget` rather than each picking their own fallback.
    val effectiveSelectedId = OverlayPipGeometry
        .resolveTarget(
            overlays = overlays,
            selectedOverlayId = selectedOverlayId,
            editorMs = playheadMs,
            clipId = null
        )
        ?.id

    Box(modifier = modifier.fillMaxSize()) {
        overlays.forEach { overlay ->
            if (!OverlayPipGeometry.isVisibleAt(overlay, playheadMs)) return@forEach
            val box = OverlayPipGeometry.resolveBox(overlay, canvasW, canvasH)
            if (!box.isRenderable) return@forEach
            // Keyed on the overlay id so a player binding / decoded bitmap
            // survives a sibling being added or removed.
            key(overlay.id) {
                PipItem(
                    overlay = overlay,
                    selected = overlay.id == effectiveSelectedId,
                    box = box,
                    canvasSizePx = canvasSizePx,
                    playheadMs = playheadMs,
                    pipPlayer = pipPlayer,
                    onSelect = onSelect,
                    onTransformStarted = onTransformStarted,
                    onTransform = onTransform,
                    onTransformEnded = onTransformEnded
                )
            }
        }
    }
}

@Composable
private fun PipItem(
    overlay: VideoOverlay,
    selected: Boolean,
    box: PipBox,
    canvasSizePx: Size,
    playheadMs: Long,
    pipPlayer: OverlayPipPlayer?,
    onSelect: (String?) -> Unit,
    onTransformStarted: () -> Unit,
    onTransform: (id: String, x: Float, y: Float, scale: Float, rotation: Float) -> Unit,
    onTransformEnded: () -> Unit
) {
    val density = LocalDensity.current
    val canvasH = canvasSizePx.height.roundToInt()
    val boxW = box.widthPx.roundToInt().coerceAtLeast(1)
    val boxH = box.heightPx.roundToInt().coerceAtLeast(1)

    val wDp = with(density) { boxW.toDp() }
    val hDp = with(density) { boxH.toDp() }

    // ---- live refs so the gesture never restarts mid-drag ----
    //
    // The same lesson the text gesture layer learned on 2026-09-06: keying
    // a `pointerInput` on values that change DURING the gesture (the
    // overlay's own position, the playhead) cancels the in-flight gesture
    // and the PiP "follows the finger for a moment, then freezes".
    val overlayRef = rememberUpdatedState(overlay)
    val canvasRef = rememberUpdatedState(canvasSizePx)

    // ---- the clip shape, from the shared outline ----
    val sanitizedMask = remember(overlay.maskSettings) {
        OverlayMaskGeometry.sanitize(overlay.maskSettings)
    }
    val maskShape = remember(overlay.maskSettings, canvasH) {
        overlayMaskShape(overlay.maskSettings, canvasH)
    }

    // ---- inversion ----
    //
    // Compose's `PathFillType` has only `NonZero` and `EvenOdd` — there is
    // no inverse variant, so an inverted mask cannot be a `Modifier.clip`.
    // The preview expresses it as a `DstOut` knockout of the shape instead,
    // which keeps only the OUTSIDE: the exact operation the export gets
    // from `clipPath` with `Path.FillType.INVERSE_EVEN_ODD`. Same outline,
    // same result, each renderer's own idiom.
    //
    // `remember` is called unconditionally (never inside the `if`) so the
    // slot table stays stable when the user toggles the switch.
    val invertOutline = remember(overlay.maskSettings, boxW, boxH, canvasH) {
        if (!sanitizedMask.hasShape || !sanitizedMask.invert) {
            null
        } else {
            overlayMaskOutlinePath(
                settings = overlay.maskSettings,
                boxWidthPx = boxW.toFloat(),
                boxHeightPx = boxH.toFloat(),
                canvasHeightPx = canvasH
            )
        }
    }

    // ---- feathered edge, from the shared alpha mask ----
    //
    // A hard edge needs nothing: `clip(shape)` is the whole story, and it
    // matches the export's `clipPath` exactly. A feathered edge has no
    // clip equivalent, so both sides rasterize the SAME alpha mask and
    // composite it the same way (`BlendMode.DstIn` here, `PorterDuff
    // DST_IN` in `OverlayMaskRenderer.applySoftMask`).
    val featherMask: ImageBitmap? = remember(overlay.maskSettings, boxW, boxH, canvasH) {
        if (!sanitizedMask.needsSoftEdge) {
            null
        } else {
            OverlayMaskRenderer
                .rasterizeAlphaMask(overlay.maskSettings, boxW, boxH, canvasH)
                ?.asImageBitmap()
        }
    }

    val clipModifier = when {
        invertOutline != null -> Modifier
            // An offscreen layer is REQUIRED: `DstOut` composites against
            // whatever is already in the layer, and without isolation that
            // would be the canvas underneath rather than the PiP's own
            // pixels.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawPath(
                    path = invertOutline,
                    color = Color.Black,
                    blendMode = BlendMode.DstOut
                )
            }
        maskShape != null -> Modifier.clip(maskShape)
        else -> Modifier
    }
    val featherModifier = if (featherMask != null) {
        Modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawImage(featherMask, blendMode = BlendMode.DstIn)
            }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(box.leftPx.roundToInt(), box.topPx.roundToInt()) }
            .size(width = wDp, height = hDp)
            .graphicsLayer {
                // Rotation carries the mask with it, exactly as the export's
                // `canvas.rotate(deg, cx, cy)` before `clipPath` does.
                rotationZ = overlay.rotationDegrees
                alpha = overlay.opacity.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
            }
            .then(clipModifier)
            .then(featherModifier)
            .pointerInput(overlay.id) {
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val o = overlayRef.value
                    // Mask-aware hit test in the box's own normalised space.
                    // A tap in a circle mask's transparent corner is NOT a
                    // tap on this PiP — it belongs to the video underneath.
                    val nx = if (size.width > 0) first.position.x / size.width.toFloat() else 0f
                    val ny = if (size.height > 0) first.position.y / size.height.toFloat() else 0f
                    if (!OverlayMaskGeometry.contains(o.maskSettings, nx, ny)) {
                        return@awaitEachGesture
                    }

                    onSelect(o.id)
                    val startScale = o.scale
                    val startRotation = o.rotationDegrees
                    var lastX = o.positionX
                    var lastY = o.positionY
                    var moved = false
                    var initialSpan: Float? = null
                    var initialAngle: Float? = null

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) {
                            if (moved) onTransformEnded()
                            break
                        }
                        val canvas = canvasRef.value
                        if (active.size == 1) {
                            val change = active[0]
                            val dx = if (canvas.width > 0f) {
                                (change.position.x - change.previousPosition.x) / canvas.width
                            } else {
                                0f
                            }
                            val dy = if (canvas.height > 0f) {
                                (change.position.y - change.previousPosition.y) / canvas.height
                            } else {
                                0f
                            }
                            if (abs(dx) > 0.0005f || abs(dy) > 0.0005f) {
                                if (!moved) {
                                    moved = true
                                    // One history snapshot per gesture, on
                                    // the first moved frame — never per
                                    // frame, or a single drag floods the
                                    // 20-deep undo stack.
                                    onTransformStarted()
                                }
                            }
                            if (moved) {
                                lastX = OverlayPipGeometry.clampNorm(lastX + dx)
                                lastY = OverlayPipGeometry.clampNorm(lastY + dy)
                                onTransform(o.id, lastX, lastY, o.scale, o.rotationDegrees)
                            }
                            change.consume()
                        } else if (active.size >= 2) {
                            val p0 = active[0]
                            val p1 = active[1]
                            val span = hypot(
                                (p1.position.x - p0.position.x).toDouble(),
                                (p1.position.y - p0.position.y).toDouble()
                            ).toFloat()
                            val angle = Math.toDegrees(
                                atan2(
                                    (p1.position.y - p0.position.y).toDouble(),
                                    (p1.position.x - p0.position.x).toDouble()
                                )
                            ).toFloat()
                            if (initialSpan == null) {
                                initialSpan = span
                                initialAngle = angle
                                if (!moved) {
                                    moved = true
                                    onTransformStarted()
                                }
                            }
                            val spanInit = initialSpan ?: span
                            val angleInit = initialAngle ?: angle
                            if (spanInit > 1f) {
                                val newScale = OverlayPipGeometry
                                    .clampScale(startScale * (span / spanInit).coerceIn(0.2f, 5f))
                                val delta = angle - angleInit
                                val newRotation = ((startRotation + delta) % 360f + 360f) % 360f
                                onTransform(o.id, lastX, lastY, newScale, newRotation)
                            }
                            p0.consume()
                            p1.consume()
                        }
                    }
                }
            }
    ) {
        PipContent(overlay = overlay, playheadMs = playheadMs, pipPlayer = pipPlayer)
    }

    // ---- selection chrome ----
    //
    // A SIBLING of the clipped box, drawn unclipped so the outline is
    // visible even for a heart or a star, and never part of the export
    // (the export reads `VideoOverlay`, not this composable).
    if (selected) {
        PipSelectionChrome(
            overlay = overlay,
            box = box,
            canvasHeightPx = canvasH,
            density = density
        )
    }
}

/**
 * The pixels: a keyed frame for a chroma-keyed video, a live player for a
 * plain video, and a decoded (optionally keyed) bitmap for a still.
 *
 * @param playheadMs drives the keyed read-back. The player already ticks
 *   with the position poller, so keying the read-back on the playhead makes
 *   the preview track playback with no loop of its own — and makes it do
 *   nothing at all while the user is paused and not scrubbing.
 */
@Composable
private fun PipContent(
    overlay: VideoOverlay,
    playheadMs: Long,
    pipPlayer: OverlayPipPlayer?
) {
    if (overlay.isVideo) {
        val player = pipPlayer?.playerFor(overlay.id)
        if (player != null) {
            val context = LocalContext.current
            // Remembered rather than built in the `factory`, so the keyed
            // read-back can reach the texture the player renders into. The
            // player is bound ONCE here and NEVER in an `update` block —
            // re-binding it on recomposition (which happens ~30×/s while the
            // position poller ticks) detaches and re-attaches the
            // TextureView's surface and drops frames.
            val playerView = remember(player) {
                val inflated = LayoutInflater.from(context)
                    .inflate(R.layout.player_view_texture, null, false) as PlayerView
                inflated.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                inflated.useController = false
                inflated.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                inflated.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                inflated.player = player
                inflated
            }
            val keying = overlay.chromaKeySettings.isEnabled
            val readback: () -> Bitmap? = remember(playerView) {
                val shot: () -> Bitmap? = {
                    val texture = playerView.videoSurfaceView as? TextureView
                    runCatching { texture?.getBitmap() }.getOrNull()
                }
                shot
            }
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    // The un-keyed texture must not show through the
                    // transparent regions of the keyed frame drawn above it.
                    // The TextureView's own surface keeps rendering at alpha
                    // 0 — the player is untouched — which is exactly what
                    // keeps `getBitmap` returning live frames.
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (keying) 0f else 1f },
                    factory = { playerView }
                )
                if (keying) {
                    OverlayChromaKeyedFrame(
                        settings = overlay.chromaKeySettings,
                        playheadMs = playheadMs,
                        readback = readback,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            return
        }
    }
    val bitmap = rememberOverlayPipBitmap(overlay.uri, overlay.chromaKeySettings)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        // A placeholder while the decode is in flight, so a still overlay
        // does not blink in and out of existence on first composition.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
        )
    }
}

/**
 * Live chroma key for a **video** overlay in the preview.
 *
 * The player keeps rendering into its `TextureView` exactly as it did
 * before this feature existed — one clock, one surface, no second decoder —
 * and the key is applied by reading that texture back, keying it, and
 * drawing the result on top. That is the only arrangement which both needs
 * no extra decoder and gives the shared [ChromaKeyEngine] real pixels.
 *
 * ⚠️ Reading back once per playhead tick is deliberate, not laziness. A
 * `LaunchedEffect(playheadMs)` inherits the position poller's ~30 Hz, so
 * the keyed preview tracks playback with no loop of its own and idles
 * completely when paused and not scrubbing. An unbounded `while (isActive)`
 * read-back loop would keep paying a GPU↔CPU sync every frame forever while
 * the user sits still.
 *
 * ⚠️ `readback` must run on the **main** thread — `TextureView.getBitmap`
 * reads a view's content. The effect body runs on the composition's
 * main-dispatcher scope, so it does; only the keying itself moves off-thread.
 *
 * ⚠️ No `runCatching` wraps the keying. `keyedCopy` is internally
 * exception-safe and returns null instead of throwing, and omitting the
 * wrapper matters: `runCatching` would swallow the `CancellationException`
 * that a playhead change throws at the suspension point.
 */
@Composable
private fun OverlayChromaKeyedFrame(
    settings: ChromaKeySettings,
    playheadMs: Long,
    readback: () -> Bitmap?,
    modifier: Modifier = Modifier
) {
    var image by remember(readback) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(playheadMs, settings, readback) {
        val shot = readback() ?: return@LaunchedEffect
        val keyed = withContext(Dispatchers.Default) {
            ChromaKeyEngine.keyedCopy(shot, settings)
        }
        if (keyed != null) image = keyed.asImageBitmap()
    }

    val current = image
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize()
        )
    }
}

/**
 * The still for an overlay, chroma-keyed when the overlay's key is on.
 *
 * Deliberately separate from [rememberOverlayImageBitmap] so the decode
 * path stays exactly as it was and the key is applied to the bitmap the
 * **preview** decoded. Falls back to the un-keyed bitmap whenever the
 * pixels cannot be read, so a still never disappears because the key
 * failed.
 */
@Composable
private fun rememberOverlayPipBitmap(
    uriString: String,
    settings: ChromaKeySettings
): ImageBitmap? {
    val base = rememberOverlayImageBitmap(uriString)
    val keyed = remember(base, settings) {
        if (!settings.isEnabled) {
            null
        } else {
            base?.asAndroidBitmap()?.let { ChromaKeyEngine.keyedCopy(it, settings) }
        }
    }
    return keyed?.asImageBitmap() ?: base
}

/**
 * Decode a still overlay once, downsampled to a sane ceiling.
 *
 * `inSampleSize` comes from a bounds-only first pass, so a 12 MP phone
 * photo does not allocate 48 MB to fill a 200 px PiP box. Re-runs only
 * when the URI changes.
 */
@Composable
private fun rememberOverlayImageBitmap(uriString: String): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uriString) {
        if (uriString.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > MAX_DECODE_DIM ||
                    bounds.outHeight / sample > MAX_DECODE_DIM
                ) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }.getOrNull()?.asImageBitmap()
        }
    }
    return bitmap
}

/**
 * The mask outline plus four corner handles.
 *
 * The outline is built from `overlayMaskOutlinePath`, i.e. the SAME
 * polygon the clip uses, so the chrome can never disagree with the shape
 * it decorates. It is drawn as a sibling (not a child) of the clipped box
 * so the outline stays visible outside the clip — a heart's lobes are
 * exactly where a clipped child would hide its own border.
 */
@Composable
private fun PipSelectionChrome(
    overlay: VideoOverlay,
    box: PipBox,
    canvasHeightPx: Int,
    density: androidx.compose.ui.unit.Density
) {
    val outlinePath = remember(overlay.maskSettings, box.widthPx, box.heightPx, canvasHeightPx) {
        overlayMaskOutlinePath(
            settings = overlay.maskSettings,
            boxWidthPx = box.widthPx,
            boxHeightPx = box.heightPx,
            canvasHeightPx = canvasHeightPx
        )
    }
    val wDp = with(density) { box.widthPx.roundToInt().coerceAtLeast(1).toDp() }
    val hDp = with(density) { box.heightPx.roundToInt().coerceAtLeast(1).toDp() }
    Box(
        modifier = Modifier
            .offset { IntOffset(box.leftPx.roundToInt(), box.topPx.roundToInt()) }
            .size(width = wDp, height = hDp)
            .graphicsLayer { rotationZ = overlay.rotationDegrees }
            .drawWithContent {
                drawContent()
                val stroke = Stroke(width = CHROME_STROKE_PX, cap = StrokeCap.Round)
                if (outlinePath != null) {
                    drawPath(path = outlinePath, color = CHROME_COLOR, style = stroke)
                } else {
                    drawRect(
                        color = CHROME_COLOR,
                        topLeft = Offset.Zero,
                        size = Size(size.width, size.height),
                        style = stroke
                    )
                }
                // Corner handles sit on the BOX, not on the shape: they are
                // the scale affordance, and a heart's outline offers no
                // clean corner to grab.
                val r = HANDLE_RADIUS_PX
                listOf(
                    Offset(0f, 0f),
                    Offset(size.width, 0f),
                    Offset(0f, size.height),
                    Offset(size.width, size.height)
                ).forEach { corner ->
                    drawCircle(color = CHROME_COLOR, radius = r, center = corner)
                }
            }
    )
}

/** Decode ceiling for a still overlay, in pixels on the longer side. */
private const val MAX_DECODE_DIM = 1440
private val CHROME_COLOR = Color(0xFF4FC3F7)
private const val CHROME_STROKE_PX = 4f
private const val HANDLE_RADIUS_PX = 9f
