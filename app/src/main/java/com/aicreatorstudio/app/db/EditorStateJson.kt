package com.aicreatorstudio.app.db

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import com.aicreatorstudio.app.ai.TranscribedWord
import com.aicreatorstudio.app.model.AudioClip
import com.aicreatorstudio.app.model.AutoFrameSettings
import com.aicreatorstudio.app.model.ChromaKeySettings
import com.aicreatorstudio.app.model.CropKeyframe
import com.aicreatorstudio.app.model.MattingSettings
import com.aicreatorstudio.app.model.MotionTrackSettings
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.ProgressBarPosition
import com.aicreatorstudio.app.model.ProgressBarSettings
import com.aicreatorstudio.app.model.SpeedCurve
import com.aicreatorstudio.app.model.SpeedCurvePreset
import com.aicreatorstudio.app.model.SpeedPoint
import com.aicreatorstudio.app.model.SubtitleAlignmentH
import com.aicreatorstudio.app.model.SubtitleAlignmentV
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.VideoOverlay
import com.aicreatorstudio.app.model.VoiceCleanSettings
import com.aicreatorstudio.app.model.VoiceEffect
import com.aicreatorstudio.app.model.WordMotionKind
import com.aicreatorstudio.app.model.CaptionBackgroundKind
import com.aicreatorstudio.app.model.WordRevealMode
import com.aicreatorstudio.app.util.ChromaKeyEngine
import com.aicreatorstudio.app.util.BeatDetector
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * JSON codecs for editor state that does not fit Room's columnar model:
 * text overlays (captions + AI subtitles with karaoke word timing) and
 * media overlays (picture-in-picture blocks).
 *
 * Uses `org.json` (bundled with Android) instead of adding a
 * serialization dependency. Parsing is defensive — every accessor uses
 * `opt*` defaults — so rows written by older builds keep loading and a
 * malformed blob can never crash project restore.
 */
object EditorStateJson {

    // ---- Text overlays (captions / AI subtitles) ----

    fun textOverlaysToJson(overlays: List<TextOverlay>): String {
        val array = JSONArray()
        overlays.forEach { overlay ->
            array.put(JSONObject().apply {
                put("id", overlay.id)
                put("text", overlay.text)
                put("startMs", overlay.startTimeMs)
                put("endMs", overlay.endTimeMs)
                put("x", overlay.position.x.toDouble())
                put("y", overlay.position.y.toDouble())
                put("colorArgb", overlay.color.toArgb())
                put("fontSizeSp", overlay.fontSizeSp.toDouble())
                put("fontWeight", overlay.fontWeight.weight)
                overlay.backgroundColor?.let { put("bgColorArgb", it.toArgb()) }
                put("bgAlpha", overlay.backgroundAlpha.toDouble())
                put("rotation", overlay.rotation.toDouble())
                put("fontFamily", overlay.fontFamily)
                put("karaoke", overlay.karaokeEnabled)
                val words = JSONArray()
                overlay.words.forEach { word ->
                    words.put(JSONObject().apply {
                        put("id", word.id)
                        put("text", word.text)
                        put("startMs", word.startTimeMs)
                        put("endMs", word.endTimeMs)
                        word.confidence?.let { put("confidence", it.toDouble()) }
                    })
                }
                put("words", words)
                // Subtitle-source tag — round-tripped so the
                // editor's SRT export can find AI + SRT-imported
                // captions after a project save/reopen.
                // (PHASE B4)
                if (overlay.subtitleSource != null) {
                    put("subtitleSource", overlay.subtitleSource)
                    // 2026-09-07 — generation anchor (timeline offset
                    // added at creation). Round-tripped so the SRT
                    // export converts back to media-local times.
                    if (overlay.subtitleAnchorMs != 0L) {
                        put("subtitleAnchorMs", overlay.subtitleAnchorMs)
                    }
                }
                // PHASE B5 — production subtitle style. Skipped
                // when null or Default so legacy blobs and
                // unmodified overlays stay small.
                overlay.subtitleStyle?.takeIf { !it.isDefault() }
                    ?.let { put("subtitleStyle", subtitleStyleToJson(it)) }
                // 2026-09-06 — text animation preset (omitted when
                // NONE so legacy blobs stay small).
                if (overlay.textAnimation != TextOverlayAnimation.NONE) {
                    put("textAnimation", overlay.textAnimation.name)
                }
                // 2026-09-11 — «زیرنویس فارسی» template layer.
                overlay.templateId?.let { put("templateId", it) }
                overlay.activeWordColorArgb?.let { put("activeWordColorArgb", it) }
                if (overlay.activeWordScale != 1f) {
                    put("activeWordScale", overlay.activeWordScale.toDouble())
                }
                // 2026-09-19 — template bold + active-word pill.
                // Omitted at defaults so legacy blobs stay small.
                if (overlay.activeWordBold) {
                    put("activeWordBold", true)
                }
                overlay.activeWordBackgroundArgb?.let { put("activeWordBackgroundArgb", it) }
                // 2026-09-19 PHASE 6 — progress-bar spec (omitted when
                // off so legacy blobs stay small).
                overlay.progressBar?.let { bar ->
                    put("progressBar", JSONObject().apply {
                        put("colorArgb", bar.colorArgb)
                        put("trackArgb", bar.trackArgb)
                        put("heightRatio", bar.heightRatio.toDouble())
                        put("directionAware", bar.directionAware)
                        put("shape", bar.shape.name)
                    })
                }
                // 2026-09-19 PHASE 7 — gradient angle (omitted when
                // null so legacy blobs stay small).
                overlay.gradientAngleDeg?.let { put("gradientAngleDeg", it.toDouble()) }
                if (overlay.textAnimationOut != TextOverlayAnimation.NONE) {
                    put("textAnimationOut", overlay.textAnimationOut.name)
                }
                if (overlay.wordRevealMode != WordRevealMode.ALL_AT_ONCE) {
                    put("wordRevealMode", overlay.wordRevealMode.name)
                }
                // 2026-09-14 — viral motion + background layer.
                if (overlay.motionKind != WordMotionKind.NONE) {
                    put("motionKind", overlay.motionKind.name)
                }
                if (overlay.backgroundKind != CaptionBackgroundKind.NONE) {
                    put("backgroundKind", overlay.backgroundKind.name)
                }
                if (overlay.activeWordGlowRadiusDp > 0f) {
                    put("activeWordGlowRadiusDp", overlay.activeWordGlowRadiusDp.toDouble())
                }
                if (overlay.activeWordUnderline) {
                    put("activeWordUnderline", true)
                }
                if (overlay.accentColorArgb != 0xFF00E5FF.toInt()) {
                    put("accentColorArgb", overlay.accentColorArgb)
                }
                // PHASE 8 — easing presets (omitted when null/linear
                // so legacy blobs stay small).
                overlay.easingIn?.let { put("easingIn", it) }
                overlay.easingOut?.let { put("easingOut", it) }
                // PHASE 10E — exit animation duration (omitted when null = same as entry).
                overlay.animationDurationMsOut?.let { put("animationDurationMsOut", it) }
            })
        }
        return array.toString()
    }

    fun textOverlaysFromJson(json: String?): List<TextOverlay> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val wordsJson = obj.optJSONArray("words") ?: JSONArray()
                TextOverlay(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    text = obj.optString("text"),
                    startTimeMs = obj.optLong("startMs"),
                    endTimeMs = obj.optLong("endMs"),
                    position = Offset(
                        obj.optDouble("x", 0.5).toFloat(),
                        obj.optDouble("y", 0.5).toFloat()
                    ),
                    color = Color(obj.optInt("colorArgb", android.graphics.Color.WHITE)),
                    fontSizeSp = obj.optDouble("fontSizeSp", 24.0).toFloat(),
                    fontWeight = FontWeight(obj.optInt("fontWeight", FontWeight.Normal.weight)),
                    backgroundColor = if (obj.has("bgColorArgb"))
                        Color(obj.optInt("bgColorArgb")) else null,
                    backgroundAlpha = obj.optDouble("bgAlpha", 0.5).toFloat(),
                    rotation = obj.optDouble("rotation", 0.0).toFloat(),
                    fontFamily = obj.optString("fontFamily", PersianFont.DEFAULT.name),
                    words = (0 until wordsJson.length()).mapNotNull { wIndex ->
                        val w = wordsJson.optJSONObject(wIndex) ?: return@mapNotNull null
                        TranscribedWord(
                            id = w.optString("id", UUID.randomUUID().toString()),
                            text = w.optString("text"),
                            startTimeMs = w.optLong("startMs"),
                            endTimeMs = w.optLong("endMs"),
                            confidence = if (w.has("confidence"))
                                w.optDouble("confidence").toFloat() else null
                        )
                    },
                    karaokeEnabled = obj.optBoolean("karaoke", true),
                    // PHASE B4: subtitleSource survives project
                    // reload. Old blobs without the key decode
                    // as null (decorative text) — that is the
                    // safe default.
                    subtitleSource = if (obj.has("subtitleSource"))
                        obj.optString("subtitleSource").takeIf { it.isNotBlank() }
                    else null,
                    // 2026-09-07: generation anchor (see writer).
                    subtitleAnchorMs = obj.optLong("subtitleAnchorMs"),
                    // PHASE B5: subtitleStyle survives project
                    // reload. Old blobs without the key decode
                    // as null; the renderer falls back to
                    // SubtitleStyle.Default at draw time.
                    subtitleStyle = if (obj.has("subtitleStyle"))
                        subtitleStyleFromJson(obj.optJSONObject("subtitleStyle"))
                    else null,
                    // 2026-09-06: text animation survives project
                    // reload. Unknown / missing names decode as NONE
                    // (the safe legacy default).
                    textAnimation = runCatching {
                        TextOverlayAnimation.valueOf(
                            obj.optString("textAnimation", "NONE")
                        )
                    }.getOrDefault(TextOverlayAnimation.NONE),
                    // 2026-09-11: «زیرنویس فارسی» template layer —
                    // every field optional; legacy blobs decode clean.
                    templateId = obj.optStringOrNull("templateId"),
                    activeWordColorArgb =
                        if (obj.has("activeWordColorArgb"))
                            obj.optInt("activeWordColorArgb") else null,
                    activeWordScale = obj.optDouble("activeWordScale", 1.0).toFloat(),
                    // 2026-09-19 — template bold + active-word pill.
                    // Missing in legacy blobs → safe defaults (off).
                    activeWordBold = obj.optBoolean("activeWordBold", false),
                    activeWordBackgroundArgb =
                        if (obj.has("activeWordBackgroundArgb"))
                            obj.optInt("activeWordBackgroundArgb") else null,
                    // 2026-09-19 PHASE 6 — missing in legacy blobs → off.
                    progressBar = obj.optJSONObject("progressBar")?.let { b ->
                        com.aicreatorstudio.app.model.ProgressBarSpec(
                            colorArgb = b.optInt("colorArgb", 0xFFFF7A00.toInt()),
                            trackArgb = b.optInt("trackArgb", 0x33FFFFFF),
                            heightRatio = b.optDouble("heightRatio", 0.12).toFloat(),
                            directionAware = b.optBoolean("directionAware", true),
                            // 2026-09-24 — absent in legacy blobs → STRAIGHT.
                            shape = runCatching {
                                com.aicreatorstudio.app.model.ProgressBarShape.valueOf(
                                    b.optString(
                                        "shape",
                                        com.aicreatorstudio.app.model.ProgressBarShape.STRAIGHT.name
                                    )
                                )
                            }.getOrDefault(
                                com.aicreatorstudio.app.model.ProgressBarShape.STRAIGHT
                            )
                        )
                    },
                    // 2026-09-19 PHASE 7 — missing in legacy blobs → null (legacy horizontal).
                    gradientAngleDeg = if (obj.isNull("gradientAngleDeg")) null
                    else obj.optDouble("gradientAngleDeg").toFloat(),
                    textAnimationOut = runCatching {
                        TextOverlayAnimation.valueOf(
                            obj.optString("textAnimationOut", "NONE")
                        )
                    }.getOrDefault(TextOverlayAnimation.NONE),
                    wordRevealMode = runCatching {
                        WordRevealMode.valueOf(
                            obj.optString("wordRevealMode", "ALL_AT_ONCE")
                        )
                    }.getOrDefault(WordRevealMode.ALL_AT_ONCE),
                    // 2026-09-14 — viral motion + background layer —
                    // every field optional; legacy blobs decode clean.
                    motionKind = runCatching {
                        WordMotionKind.valueOf(obj.optString("motionKind", "NONE"))
                    }.getOrDefault(WordMotionKind.NONE),
                    backgroundKind = runCatching {
                        CaptionBackgroundKind.valueOf(obj.optString("backgroundKind", "NONE"))
                    }.getOrDefault(CaptionBackgroundKind.NONE),
                    activeWordGlowRadiusDp =
                        obj.optDouble("activeWordGlowRadiusDp", 0.0).toFloat(),
                    activeWordUnderline = obj.optBoolean("activeWordUnderline", false),
                    accentColorArgb = obj.optInt(
                        "accentColorArgb", 0xFF00E5FF.toInt()
                    ),
                    // PHASE 8 — easing presets (null = linear).
                    easingIn = if (obj.isNull("easingIn")) null
                    else obj.optString("easingIn", null),
                    easingOut = if (obj.isNull("easingOut")) null
                    else obj.optString("easingOut", null),
                    animationDurationMsOut = if (obj.isNull("animationDurationMsOut")) null
                    else obj.optInt("animationDurationMsOut", 260)
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---- Subtitle style (PHASE B5) ----

    /**
     * Serialize a [SubtitleStyle] to a nested JSON object. Every
     * field is written unconditionally so the read side can use
     * `opt*` defaults for partial blobs.
     */
    fun subtitleStyleToJson(style: SubtitleStyle): JSONObject = JSONObject().apply {
        put("fontFamily", style.fontFamily)
        put("fontSizeSp", style.fontSizeSp.toDouble())
        put("fontWeightValue", style.fontWeightValue)
        put("textColorArgb", style.textColorArgb)
        put("bold", style.bold)
        put("italic", style.italic)
        put("strokeEnabled", style.strokeEnabled)
        put("strokeColorArgb", style.strokeColorArgb)
        put("strokeWidthSp", style.strokeWidthSp.toDouble())
        put("shadowEnabled", style.shadowEnabled)
        put("shadowColorArgb", style.shadowColorArgb)
        put("shadowOpacity", style.shadowOpacity.toDouble())
        put("shadowOffsetYDp", style.shadowOffsetYDp.toDouble())
        put("backgroundEnabled", style.backgroundEnabled)
        put("backgroundColorArgb", style.backgroundColorArgb)
        put("backgroundOpacity", style.backgroundOpacity.toDouble())
        put("backgroundCornerRadiusDp", style.backgroundCornerRadiusDp.toDouble())
        put("horizontalAlignment", style.horizontalAlignment.name)
        put("verticalAlignment", style.verticalAlignment.name)
        put("positionX", style.positionX.toDouble())
        put("positionY", style.positionY.toDouble())
        put("maxTextWidthFraction", style.maxTextWidthFraction.toDouble())
    }

    /**
     * Decode a [SubtitleStyle] from a JSON object. Tolerates null
     * (returns null) and tolerates missing fields (falls back to
     * [SubtitleStyle.Default] values via `opt*`).
     */
    fun subtitleStyleFromJson(obj: JSONObject?): SubtitleStyle? {
        if (obj == null) return null
        return runCatching {
            SubtitleStyle(
                fontFamily = obj.optString("fontFamily", PersianFont.DEFAULT.name),
                fontSizeSp = obj.optDouble("fontSizeSp", 24.0).toFloat(),
                fontWeightValue = obj.optInt("fontWeightValue", 400),
                textColorArgb = obj.optInt("textColorArgb", android.graphics.Color.WHITE),
                bold = obj.optBoolean("bold", true),
                italic = obj.optBoolean("italic", false),
                strokeEnabled = obj.optBoolean("strokeEnabled", true),
                strokeColorArgb = obj.optInt("strokeColorArgb", android.graphics.Color.BLACK),
                strokeWidthSp = obj.optDouble("strokeWidthSp", 2.0).toFloat(),
                shadowEnabled = obj.optBoolean("shadowEnabled", false),
                shadowColorArgb = obj.optInt("shadowColorArgb", android.graphics.Color.BLACK),
                shadowOpacity = obj.optDouble("shadowOpacity", 0.5).toFloat(),
                shadowOffsetYDp = obj.optDouble("shadowOffsetYDp", 2.0).toFloat(),
                backgroundEnabled = obj.optBoolean("backgroundEnabled", true),
                backgroundColorArgb = obj.optInt("backgroundColorArgb", android.graphics.Color.BLACK),
                backgroundOpacity = obj.optDouble("backgroundOpacity", 0.55).toFloat(),
                backgroundCornerRadiusDp = obj.optDouble("backgroundCornerRadiusDp", 8.0).toFloat(),
                horizontalAlignment = runCatching {
                    SubtitleAlignmentH.valueOf(obj.optString("horizontalAlignment", "CENTER"))
                }.getOrDefault(SubtitleAlignmentH.CENTER),
                verticalAlignment = runCatching {
                    SubtitleAlignmentV.valueOf(obj.optString("verticalAlignment", "BOTTOM"))
                }.getOrDefault(SubtitleAlignmentV.BOTTOM),
                positionX = obj.optDouble("positionX", 0.5).toFloat(),
                positionY = obj.optDouble("positionY", 0.85).toFloat(),
                maxTextWidthFraction = obj.optDouble("maxTextWidthFraction", 0.9).toFloat()
            )
        }.getOrNull()
    }

    // ---- Media overlays (picture-in-picture / layered media) ----

    fun mediaOverlaysToJson(overlays: List<VideoOverlay>): String {
        val array = JSONArray()
        overlays.forEach { overlay ->
            array.put(JSONObject().apply {
                put("id", overlay.id)
                put("clipId", overlay.clipId)
                put("startMs", overlay.startTimeMs)
                put("endMs", overlay.endTimeMs)
                put("x", overlay.positionX.toDouble())
                put("y", overlay.positionY.toDouble())
                put("scale", overlay.scale.toDouble())
                put("opacity", overlay.opacity.toDouble())
                put("uri", overlay.uri)
                put("label", overlay.label)
                put("isVideo", overlay.isVideo)
                // ---- 2026-10-13 PiP shape masks ----
                //
                // Written ONLY when non-default, so a project saved
                // before this feature existed serialises to exactly the
                // bytes it did then and reopening it cannot churn the
                // file. Every key below is optional on read and falls
                // back to the model's own default.
                if (overlay.aspectRatio != 1f && overlay.aspectRatio.isFinite()) {
                    put("aspect", overlay.aspectRatio.toDouble())
                }
                if (overlay.rotationDegrees != 0f && overlay.rotationDegrees.isFinite()) {
                    put("rot", overlay.rotationDegrees.toDouble())
                }
                val mask = overlay.maskSettings
                if (!mask.isIdentity) {
                    put("maskType", mask.type.name)
                    if (mask.type == com.aicreatorstudio.app.model.OverlayMaskType.ROUNDED_RECT) {
                        put("maskRadius", mask.cornerRadiusDp.toDouble())
                    }
                    if (mask.featherDp > 0f) {
                        put("maskFeather", mask.featherDp.toDouble())
                    }
                    if (mask.invert) put("maskInvert", true)
                }
                // ---- 2026-10-13 chroma key (green/blue screen) ----
                //
                // Same rule as the mask above: each key is written ONLY when
                // it differs from the model default, so a project saved
                // before this feature existed serialises to exactly the
                // bytes it did then. The guard is `isIdentity` (which is
                // true when the key is off AND untuned) rather than
                // `isEnabled`, so a user who tunes the colour and then
                // switches the key off does not lose that tuning on save.
                val chroma = overlay.chromaKeySettings
                if (!chroma.isIdentity) {
                    if (chroma.isEnabled) put("chromaOn", true)
                    if (chroma.keyColorArgb != ChromaKeySettings.DEFAULT_KEY_COLOR_ARGB) {
                        put("chromaColor", chroma.keyColorArgb)
                    }
                    if (chroma.similarity != ChromaKeySettings.DEFAULT_SIMILARITY) {
                        put("chromaSimilarity", chroma.similarity.toDouble())
                    }
                    if (chroma.smoothness != ChromaKeySettings.DEFAULT_SMOOTHNESS) {
                        put("chromaSmoothness", chroma.smoothness.toDouble())
                    }
                    if (chroma.spillReduction != ChromaKeySettings.DEFAULT_SPILL_REDUCTION) {
                        put("chromaSpill", chroma.spillReduction.toDouble())
                    }
                }
            })
        }
        return array.toString()
    }

    fun mediaOverlaysFromJson(json: String?): List<VideoOverlay> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                VideoOverlay(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    clipId = obj.optString("clipId"),
                    startTimeMs = obj.optLong("startMs"),
                    endTimeMs = obj.optLong("endMs", 5000L),
                    positionX = obj.optDouble("x", 0.1).toFloat(),
                    positionY = obj.optDouble("y", 0.1).toFloat(),
                    scale = obj.optDouble("scale", 0.3).toFloat(),
                    opacity = obj.optDouble("opacity", 1.0).toFloat(),
                    uri = obj.optString("uri"),
                    label = obj.optString("label"),
                    isVideo = obj.optBoolean("isVideo", false),
                    aspectRatio = obj.optDouble("aspect", 1.0).toFloat(),
                    rotationDegrees = obj.optDouble("rot", 0.0).toFloat(),
                    // 2026-10-13 — absent keys mean "no mask", which is
                    // exactly what a pre-feature project means. An
                    // unrecognised `maskType` also decodes to NONE rather
                    // than throwing, so a project written by a newer
                    // build still opens.
                    maskSettings = com.aicreatorstudio.app.model.OverlayMaskGeometry
                        .sanitize(
                            com.aicreatorstudio.app.model.OverlayMaskSettings(
                                type = com.aicreatorstudio.app.model.OverlayMaskGeometry
                                    .typeFromName(obj.optString("maskType", null)),
                                cornerRadiusDp = obj.optDouble("maskRadius", 16.0).toFloat(),
                                featherDp = obj.optDouble("maskFeather", 0.0).toFloat(),
                                invert = obj.optBoolean("maskInvert", false)
                            )
                        ),
                    // 2026-10-13 — chroma key. Absent keys mean "off and
                    // untuned", which is exactly what a pre-feature project
                    // means. `sanitize` clamps a hand-edited or corrupt
                    // value and forces the key colour opaque, so the
                    // algorithm never receives a colour it cannot measure.
                    chromaKeySettings = ChromaKeyEngine.sanitize(
                        ChromaKeySettings(
                            isEnabled = obj.optBoolean("chromaOn", false),
                            keyColorArgb = obj.optInt(
                                "chromaColor",
                                ChromaKeySettings.DEFAULT_KEY_COLOR_ARGB
                            ),
                            similarity = obj.optDouble(
                                "chromaSimilarity",
                                ChromaKeySettings.DEFAULT_SIMILARITY.toDouble()
                            ).toFloat(),
                            smoothness = obj.optDouble(
                                "chromaSmoothness",
                                ChromaKeySettings.DEFAULT_SMOOTHNESS.toDouble()
                            ).toFloat(),
                            spillReduction = obj.optDouble(
                                "chromaSpill",
                                ChromaKeySettings.DEFAULT_SPILL_REDUCTION.toDouble()
                            ).toFloat()
                        )
                    )
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---- Audio clips (synchronized video-original audio timeline, PART 13-19) ----

    /**
     * Serialize the project's audio clips to a JSON array. Stored on
     * the project row as `audioClipsJson` (v7 migration). Mirrors the
     * `mediaOverlaysJson` shape so save / reopen round-trips the
     * video-original audio track exactly as the user left it.
     *
     * Every field is preserved; absent fields default to empty list
     * on load, so a v6 project with no audio data opens without an
     * audio track.
     */
    fun audioClipsToJson(clips: List<AudioClip>): String {
        val array = JSONArray()
        clips.forEach { clip ->
            array.put(JSONObject().apply {
                put("id", clip.id)
                put("sourceUri", clip.sourceUri)
                put("fileName", clip.fileName)
                put("startTimeMs", clip.startTimeMs)
                put("durationMs", clip.durationMs)
                put("clipOffsetMs", clip.clipOffsetMs)
                put("sourceStartMs", clip.sourceStartMs)
                put("sourceEndMs", clip.sourceEndMs)
                put("volume", clip.volume.toDouble())
                put("muted", clip.muted)
                put("fadeInMs", clip.fadeInMs)
                put("fadeOutMs", clip.fadeOutMs)
                clip.waveformCacheKey?.let { put("waveformCacheKey", it) }
                clip.originVideoClipId?.let { put("originVideoClipId", it) }
                clip.cachedPcmPath?.let { put("cachedPcmPath", it) }
                // 2026-10-13 Voice Changer — omitted while the effect is
                // off, so a project that never used it is byte-identical
                // to the blob this feature shipped against.
                if (!clip.voiceEffect.isIdentity) {
                    put("voiceEffect", clip.voiceEffect.name)
                }
                beatDataToJson(clip.beatData)?.let { put("beatData", it) }
            })
        }
        return array.toString()
    }

    /**
     * Parse the `audioClipsJson` blob back into typed [AudioClip]s.
     * Defensive — missing fields fall back to safe defaults and a
     * single malformed entry does NOT corrupt the rest of the
     * project. Returns `[]` for null / blank input so legacy v6 rows
     * load cleanly.
     */
    fun audioClipsFromJson(json: String?): List<AudioClip> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                AudioClip(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    sourceUri = obj.optString("sourceUri"),
                    fileName = obj.optString("fileName"),
                    startTimeMs = obj.optLong("startTimeMs"),
                    durationMs = obj.optLong("durationMs"),
                    clipOffsetMs = obj.optLong("clipOffsetMs"),
                    sourceStartMs = obj.optLong("sourceStartMs"),
                    sourceEndMs = obj.optLong("sourceEndMs", -1L),
                    volume = obj.optDouble("volume", 1.0).toFloat(),
                    muted = obj.optBoolean("muted", false),
                    fadeInMs = obj.optLong("fadeInMs"),
                    fadeOutMs = obj.optLong("fadeOutMs"),
                    waveformCacheKey = obj.optStringOrNull("waveformCacheKey"),
                    originVideoClipId = obj.optStringOrNull("originVideoClipId"),
                    cachedPcmPath = obj.optStringOrNull("cachedPcmPath"),
                    beatData = beatDataFromJson(obj.optJSONObject("beatData")),
                    // 2026-10-13 Voice Changer. Absent → NONE.
                    voiceEffect = voiceEffectFromName(obj.optStringOrNull("voiceEffect"))
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Serialise the project's video progress bar settings (v8).
     *
     * A single JSON object rather than six columns: the six knobs are one
     * document value, and a blob keeps the projects table from growing a
     * column per future knob. Mirrors the `aiToolsJson` shape — one row,
     * one blob, one decoder.
     */
    fun progressBarSettingsToJson(settings: ProgressBarSettings): String =
        JSONObject().apply {
            put("enabled", settings.isEnabled)
            put("position", settings.position.name)
            put("heightDp", settings.heightDp.toDouble())
            put("colorArgb", settings.colorArgb)
            put("bgColorArgb", settings.backgroundColorArgb)
            put("cornerRadiusDp", settings.cornerRadiusDp.toDouble())
            put("marginDp", settings.marginDp.toDouble())
        }.toString()

    /**
     * Parse the `progressBarSettingsJson` blob back into a typed
     * [ProgressBarSettings].
     *
     * Defensive in three ways, all of which matter because this blob is
     * read on **every** project open:
     *
     *  - `null` / blank / `"{}"` (a v7 row after the additive migration)
     *    decode to [ProgressBarSettings.Disabled] — the feature is simply
     *    off, exactly as it was before it existed;
     *  - an unknown `position` name falls back to `BOTTOM` instead of
     *    throwing, so a blob written by a future build that adds an edge
     *    still opens;
     *  - the result is passed through [ProgressBarSettings.clamped], so a
     *    hand-edited `heightDp` of `9999` (or a `NaN`) can never produce
     *    geometry that throws inside `Canvas.drawRoundRect` and aborts an
     *    export.
     *
     * A wholly malformed blob returns the default rather than propagating:
     * project restore must never crash on a bad row.
     */
    fun progressBarSettingsFromJson(json: String?): ProgressBarSettings {
        if (json.isNullOrBlank()) return ProgressBarSettings.Disabled
        return runCatching {
            val obj = JSONObject(json)
            val defaults = ProgressBarSettings.Disabled
            val position = runCatching {
                ProgressBarPosition.valueOf(
                    obj.optString("position", defaults.position.name)
                )
            }.getOrDefault(defaults.position)
            ProgressBarSettings(
                isEnabled = obj.optBoolean("enabled", defaults.isEnabled),
                position = position,
                heightDp = obj.optDouble("heightDp", defaults.heightDp.toDouble()).toFloat(),
                colorArgb = obj.optInt("colorArgb", defaults.colorArgb),
                backgroundColorArgb = obj.optInt("bgColorArgb", defaults.backgroundColorArgb),
                cornerRadiusDp = obj.optDouble(
                    "cornerRadiusDp",
                    defaults.cornerRadiusDp.toDouble()
                ).toFloat(),
                marginDp = obj.optDouble("marginDp", defaults.marginDp.toDouble()).toFloat()
            ).clamped()
        }.getOrDefault(ProgressBarSettings.Disabled)
    }

    /**
     * Serialise detected beat markers, or null when there is nothing worth
     * storing.
     *
     * A clip that was never analysed (`beatData == null`) writes no key at
     * all, matching the optional-field pattern used by `waveformCacheKey`. A
     * clip that *was* analysed and genuinely contains no beats still writes a
     * payload with an empty marker array, because "analysed, no beats" must
     * survive a restart — otherwise reopening the project would re-offer the
     * Detect action forever.
     */
    private fun beatDataToJson(beatData: BeatDetector.AudioBeatData?): JSONObject? {
        if (beatData == null) return null
        val markers = JSONArray()
        beatData.markers.forEach { marker ->
            markers.put(JSONObject().apply {
                put("t", marker.timestampMs)
                put("i", marker.intensity.toDouble())
            })
        }
        return JSONObject().apply {
            put("markers", markers)
            beatData.bpm?.let { put("bpm", it) }
        }
    }

    /**
     * Parse a `beatData` object back into [BeatDetector.AudioBeatData].
     *
     * Defensive in the same way as the rest of this file: a malformed or
     * absent payload yields null rather than throwing, and individual bad
     * markers are dropped rather than poisoning the clip. Markers are
     * re-sorted and de-duplicated on read so a hand-edited or partially
     * written blob cannot produce a non-monotonic list that would break the
     * timeline's binary-search snapping.
     */
    private fun beatDataFromJson(obj: JSONObject?): BeatDetector.AudioBeatData? {
        if (obj == null) return null
        return runCatching {
            val array = obj.optJSONArray("markers") ?: JSONArray()
            val markers = ArrayList<BeatDetector.BeatMarker>(array.length())
            for (i in 0 until array.length()) {
                val m = array.optJSONObject(i) ?: continue
                val time = m.optLong("t", -1L)
                if (time < 0L) continue
                val intensity = m.optDouble("i", 1.0)
                    .takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 1f) ?: 1.0f
                markers.add(BeatDetector.BeatMarker(timestampMs = time, intensity = intensity))
            }
            markers.sortBy { it.timestampMs }
            val deduped = ArrayList<BeatDetector.BeatMarker>(markers.size)
            for (marker in markers) {
                val last = deduped.lastOrNull()
                if (last == null || marker.timestampMs > last.timestampMs) {
                    deduped.add(marker)
                } else if (marker.intensity > last.intensity) {
                    deduped[deduped.size - 1] = marker
                }
            }
            val bpm = if (obj.has("bpm")) obj.optInt("bpm", -1).takeIf { it > 0 } else null
            BeatDetector.AudioBeatData(markers = deduped, bpm = bpm)
        }.getOrNull()
    }

    // ---- AI tools (Background Remove / Auto Frame / Voice Clean) ----

    /**
     * Data carrier for the four per-clip AI tool settings. Stored
     * per-clip in [aiToolsToJson] and merged back onto each
     * [com.aicreatorstudio.app.model.VideoClip] at load time by the
     * repository.
     */
    data class ClipAiTools(
        val matting: MattingSettings = MattingSettings.Identity,
        val autoFrame: AutoFrameSettings = AutoFrameSettings.Identity,
        val cropKeyframes: List<CropKeyframe> = CropKeyframe.Empty,
        /**
         * 2026-09-28 — Motion Tracking settings. Identity = no tracked
         * overlay on this clip. Persisted alongside the shared
         * [cropKeyframes] trajectory.
         */
        val motionTrack: MotionTrackSettings = MotionTrackSettings.Identity,
        val voiceClean: VoiceCleanSettings = VoiceCleanSettings.Identity,
        /** 2026-09-10 — committed AI OCR result (encoded OcrClipResult). */
        val ocrResult: String? = null,
        /**
         * 2026-09-29 — Speed Ramping curve. `null` = no ramp, i.e. the
         * clip uses the plain constant `speed` column as it always has.
         *
         * Rides the AI-tools blob rather than a new Room column on
         * purpose: a column would need a schema migration for a feature
         * whose entire value is optional, and this blob already
         * round-trips a list-valued, per-clip, occasionally-large
         * structure (`cropKeyframes`) with the same shape.
         */
        val speedCurve: SpeedCurve? = null,
        /**
         * PHASE A (Persistence Integrity) — per-clip look settings
         * (effect, grading, opacity, fill mode, transform, fades).
         * Absent in legacy blobs → Identity → the old columns keep
         * driving playback, exactly as before.
         */
        val look: com.aicreatorstudio.app.model.ClipLookSettings = com.aicreatorstudio.app.model.ClipLookSettings.Identity,
        /**
         * PHASE 7 — approved highlight ranges. Written only when
         * non-empty; absent in legacy blobs → empty (no highlights).
         */
        val highlightRanges: List<com.aicreatorstudio.app.model.HighlightRange> = emptyList(),
        /**
         * 2026-10-13 — Voice Changer effect for this clip.
         *
         * Rides the AI-tools blob for exactly the reason [speedCurve]
         * documents above: the value is optional, defaults to "off", and
         * a Room column would cost a schema migration to store what is
         * almost always the default. Absent in every legacy blob → NONE,
         * which is what those clips already sounded like.
         */
        val voiceEffect: VoiceEffect = VoiceEffect.NONE
    )

    /**
     * Decodes a persisted [VoiceEffect] name.
     *
     * `null`, blank and any unrecognised name all decode to
     * [VoiceEffect.NONE]. That is the whole contract: a project written
     * by a future build, a renamed enum constant, or a hand-edited file
     * must degrade to "no effect" — never to a crash, and never to a
     * guess at a different effect the user did not choose.
     */
    fun voiceEffectFromName(name: String?): VoiceEffect {
        if (name.isNullOrBlank()) return VoiceEffect.NONE
        return VoiceEffect.entries.firstOrNull { it.name == name } ?: VoiceEffect.NONE
    }

    /**
     * Serialize a `Map<clipId, ClipAiTools>` to a JSON object blob.
     * Defensive on the way out — any field that holds an identity
     * value is dropped so the persisted blob stays small.
     */
    fun aiToolsToJson(tools: Map<String, ClipAiTools>): String {
        val root = JSONObject()
        for ((clipId, t) in tools) {
            val obj = JSONObject()
            if (t.matting.enabled || t.matting.replacementBackgroundUri != null ||
                t.matting.backgroundMode != com.aicreatorstudio.app.model.MattingBackgroundMode.ORIGINAL ||
                t.matting.reverse ||
                t.matting.expansion != 0f
            ) {
                obj.put("mattingEnabled", t.matting.enabled)
                obj.put("mattingThreshold", t.matting.threshold.toDouble())
                // 2026-10-25 — composition-time knobs persist in their
                // normalized units. `mattingFeatherPx` (the old
                // generation-time pixel value) is no longer written:
                // those sidecars carry raw masks now, and the old key
                // would migrate to 0 on read anyway.
                obj.put("mattingFeather", t.matting.feather.toDouble())
                obj.put("mattingExpansion", t.matting.expansion.toDouble())
                obj.put("mattingReverse", t.matting.reverse)
                obj.put("mattingBg", t.matting.replacementBackgroundUri ?: JSONObject.NULL)
                obj.put("mattingAlgorithm", t.matting.algorithm)
                obj.put("mattingBgMode", t.matting.backgroundMode.name)
                obj.put("mattingBgColor", t.matting.backgroundColor)
            }
            if (t.autoFrame.enabled) {
                obj.put("autoFrameEnabled", true)
                obj.put("autoFrameTargetW", t.autoFrame.targetAspectWidth.toDouble())
                obj.put("autoFrameTargetH", t.autoFrame.targetAspectHeight.toDouble())
                obj.put("autoFrameSmoothing", t.autoFrame.smoothing.toDouble())
                obj.put("autoFrameAlgorithm", t.autoFrame.algorithm)
            }
            // ---- 2026-09-28 Motion Tracking ----
            if (t.motionTrack.enabled) {
                val mt = t.motionTrack
                obj.put("motionTrackEnabled", true)
                obj.put("mtOverlayId", mt.overlayId ?: JSONObject.NULL)
                obj.put("mtMaskMode", mt.maskMode.name)
                obj.put("mtMaskStrength", mt.maskStrength.toDouble())
                obj.put("mtAnchorU", mt.anchorU.toDouble())
                obj.put("mtAnchorV", mt.anchorV.toDouble())
                obj.put("mtScaleWithBox", mt.scaleWithBox)
                obj.put("mtRefBoxHeight", mt.referenceBoxHeight.toDouble())
                obj.put("mtPosSmoothing", mt.positionSmoothing.toDouble())
                obj.put("mtScaleSmoothing", mt.scaleSmoothing.toDouble())
                obj.put("mtMinConfidence", mt.minConfidence.toDouble())
                obj.put("mtAlgorithm", mt.algorithm)
            }
            // The trajectory is written whenever it is NON-EMPTY, not
            // only when Auto Frame is enabled. Motion Tracking uses the
            // same list while its own `autoFrame.enabled` is false, so
            // gating this on Auto Frame would silently discard every
            // tracked trajectory on save.
            if (t.cropKeyframes.isNotEmpty()) {
                val kf = JSONArray()
                t.cropKeyframes.forEach { k ->
                    kf.put(JSONObject().apply {
                        put("t", k.timeMs)
                        put("x", k.centerX.toDouble())
                        put("y", k.centerY.toDouble())
                        put("z", k.zoom.toDouble())
                        // Extent + quality are written only when they
                        // carry information, keeping the blob small for
                        // the Auto Frame case (which has neither) and
                        // round-tripping the defaults when absent.
                        k.width?.let { put("w", it.toDouble()) }
                        k.height?.let { put("h", it.toDouble()) }
                        if (k.confidence != 1f) put("c", k.confidence.toDouble())
                        if (!k.isFound) put("f", false)
                    })
                }
                obj.put("cropKeyframes", kf)
            }
            if (t.voiceClean.enabled) {
                obj.put("voiceCleanEnabled", true)
                obj.put("voiceCleanStrength", t.voiceClean.strength.toDouble())
                obj.put("voiceCleanAlgorithm", t.voiceClean.algorithm)
            }
            if (!t.ocrResult.isNullOrBlank()) {
                obj.put("ocrResult", t.ocrResult)
            }
            // ---- 2026-09-29 Speed Ramping ----
            // Written only when the curve actually ramps. A curve the
            // user opened and closed again is five knots at 1f, which
            // is semantically identical to "no ramp" — persisting it
            // would grow every project's blob and, worse, would let a
            // stale ramp masquerade as an active one on reopen.
            t.speedCurve?.takeIf { it.isActive }?.let { curve ->
                val pts = JSONArray()
                curve.sanitized().points.forEach { p ->
                    pts.put(JSONObject().apply {
                        put("t", p.normalizedTime.toDouble())
                        put("v", p.speed.toDouble())
                    })
                }
                obj.put("speedCurve", JSONObject().apply {
                    put("points", pts)
                    put("preset", curve.preset.name)
                    put("preservePitch", curve.preservePitch)
                })
            }
            // ---- PHASE 7 Smart Highlights ----
            // Written only when non-empty; validated ranges only
            // (end > start, finite score). Future AutoEdit reads this.
            if (t.highlightRanges.isNotEmpty()) {
                val arr = JSONArray()
                t.highlightRanges.forEach { r ->
                    if (r.endMs > r.startMs && r.startMs >= 0) {
                        arr.put(JSONObject().apply {
                            put("s", r.startMs)
                            put("e", r.endMs)
                            put("score", r.score.toDouble().takeIf { it.isFinite() } ?: 0.0)
                            if (r.reason.isNotBlank()) put("reason", r.reason)
                        })
                    }
                }
                if (arr.length() > 0) obj.put("highlightRanges", arr)
            }
            // ---- 2026-10-13 Voice Changer ----
            // Written only when the effect is on, for the reason every
            // other optional field here is: writing `NONE` would add a
            // key to every project's blob for a value that is already the
            // decoder's default.
            if (!t.voiceEffect.isIdentity) {
                obj.put("voiceEffect", t.voiceEffect.name)
            }
            // ---- PHASE A: per-clip look settings ----
            // Written only when they are off-default. Writing identity
            // would grow every project's blob and, worse, would let a
            // stale crop/fade masquerade as a real edit on reopen —
            // same rule `speedCurve` already follows.
            if (!t.look.isIdentity) {
                val look = t.look
                val lo = JSONObject()
                if (look.videoEffect != com.aicreatorstudio.app.model.VideoEffect.NONE) {
                    lo.put("effect", look.videoEffect.name)
                }
                if (!look.colorGrading.isIdentity) {
                    val g = JSONObject()
                    g.put("bri", look.colorGrading.brightness.toDouble())
                    g.put("con", look.colorGrading.contrast.toDouble())
                    g.put("exp", look.colorGrading.exposure.toDouble())
                    g.put("hig", look.colorGrading.highlights.toDouble())
                    g.put("sha", look.colorGrading.shadows.toDouble())
                    g.put("whi", look.colorGrading.whites.toDouble())
                    g.put("bla", look.colorGrading.blacks.toDouble())
                    g.put("tem", look.colorGrading.temperature.toDouble())
                    g.put("tin", look.colorGrading.tint.toDouble())
                    g.put("sat", look.colorGrading.saturation.toDouble())
                    g.put("vib", look.colorGrading.vibrance.toDouble())
                    g.put("clr", look.colorGrading.clarity.toDouble())
                    g.put("vig", look.colorGrading.vignetteAmount.toDouble())
                    g.put("vft", look.colorGrading.vignetteFeather.toDouble())
                    g.put("vrd", look.colorGrading.vignetteRoundness.toDouble())
                    g.put("fad", look.colorGrading.fade.toDouble())
                    g.put("shp", look.colorGrading.sharpness.toDouble())
                    g.put("grn", look.colorGrading.grain.toDouble())
                    g.put("hr", look.colorGrading.hueRed.toDouble())
                    g.put("ho", look.colorGrading.hueOrange.toDouble())
                    g.put("hy", look.colorGrading.hueYellow.toDouble())
                    g.put("hg", look.colorGrading.hueGreen.toDouble())
                    g.put("ha", look.colorGrading.hueAqua.toDouble())
                    g.put("hb", look.colorGrading.hueBlue.toDouble())
                    g.put("hp", look.colorGrading.huePurple.toDouble())
                    g.put("hm", look.colorGrading.hueMagenta.toDouble())
                    lo.put("grade", g)
                }
                if (look.opacity != 1f) lo.put("opacity", look.opacity.toDouble())
                if (look.fillMode != com.aicreatorstudio.app.model.FillMode.FIT) {
                    lo.put("fill", look.fillMode.name)
                }
                if (!look.transform.isIdentity) {
                    lo.put(
                        "transform",
                        JSONObject().apply {
                            put("sc", look.transform.scale.toDouble())
                            put("ox", look.transform.offsetX.toDouble())
                            put("oy", look.transform.offsetY.toDouble())
                            put("rt", look.transform.rotation)
                            put("fh", look.transform.flipH)
                            put("fv", look.transform.flipV)
                        }
                    )
                }
                if (look.fadeInMs > 0) lo.put("fadeIn", look.fadeInMs)
                if (look.fadeOutMs > 0) lo.put("fadeOut", look.fadeOutMs)
                // 2026-10-21 — the AI upscaler settings. Written only
                // when they are off-default, like every other look
                // field, so an untouched project's blob does not grow.
                if (look.upscale != com.aicreatorstudio.app.model.UpscalerSettings.Identity) {
                    lo.put(
                        "upscale",
                        JSONObject().apply {
                            put("preset", look.upscale.preset.name)
                            put("strength", look.upscale.strength.toDouble())
                        }
                    )
                }
                if (lo.length() > 0) obj.put("look", lo)
            }
            root.put(clipId, obj)
        }
        return root.toString()
    }

    /**
     * Parse an [aiToolsToJson] blob back into a `Map<clipId,
     * ClipAiTools>`. Every accessor uses `opt*` defaults so a
     * partially-written or legacy row never crashes the load.
     */
    fun aiToolsFromJson(json: String?): Map<String, ClipAiTools> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            val out = mutableMapOf<String, ClipAiTools>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val clipId = keys.next()
                val obj = root.optJSONObject(clipId) ?: continue
                val matting = if (obj.optBoolean("mattingEnabled", false) ||
                    !obj.isNull("mattingBg") ||
                    obj.optString("mattingBgMode", "ORIGINAL") != "ORIGINAL" ||
                    obj.optBoolean("mattingReverse", false) ||
                    obj.optDouble("mattingExpansion", 0.0) != 0.0
                ) {
                    // 2026-10-25 — feather moved from generation-time
                    // pixels to a composition-time normalized knob.
                    // Migration rule: a blob lacking `mattingFeather`
                    // was produced when the engine feathered at
                    // generation time — that feather is ALREADY BAKED
                    // into its sidecar PNGs, so it must load as 0,
                    // never re-applied on top (double feathering).
                    val feather = if (obj.has("mattingFeather")) {
                        obj.optDouble("mattingFeather", 0.0).toFloat().coerceIn(0f, 1f)
                    } else 0f
                    MattingSettings(
                        enabled = obj.optBoolean("mattingEnabled", true),
                        // 2026-10-17 — clamps widened to the full range the
                        // engine can produce/run (threshold 0.05..0.95,
                        // feather 0..10 in MediaPipeMattingEngine); the old
                        // deserializer-only 0.9/5 caps made a high-threshold
                        // run silently drift across save/load.
                        threshold = obj.optDouble("mattingThreshold", 0.2).toFloat()
                            .coerceIn(0.05f, 0.95f),
                        feather = feather,
                        expansion = obj.optDouble("mattingExpansion", 0.0).toFloat()
                            .coerceIn(-1f, 1f),
                        reverse = obj.optBoolean("mattingReverse", false),
                        replacementBackgroundUri = obj.optString("mattingBg").takeIf {
                            it.isNotBlank() && it != "null"
                        },
                        algorithm = obj.optString("mattingAlgorithm", "lab-distance-v1"),
                        backgroundMode = com.aicreatorstudio.app.model.MattingBackgroundMode.fromName(
                            obj.optString("mattingBgMode", "ORIGINAL")
                        ),
                        backgroundColor = obj.optInt("mattingBgColor", 0xFF111111.toInt())
                    )
                } else MattingSettings.Identity

                val autoFrame = if (obj.optBoolean("autoFrameEnabled", false)) {
                    AutoFrameSettings(
                        enabled = true,
                        targetAspectWidth = obj.optDouble("autoFrameTargetW", 9.0).toFloat(),
                        targetAspectHeight = obj.optDouble("autoFrameTargetH", 16.0).toFloat(),
                        smoothing = obj.optDouble("autoFrameSmoothing", 0.5).toFloat()
                            .coerceIn(0f, 1f),
                        algorithm = obj.optString("autoFrameAlgorithm", "saliency-v1")
                    )
                } else AutoFrameSettings.Identity

                val kfJson = obj.optJSONArray("cropKeyframes") ?: JSONArray()
                val keyframes = (0 until kfJson.length()).mapNotNull { i ->
                    val k = kfJson.optJSONObject(i) ?: return@mapNotNull null
                    // Extent is absent for Auto Frame rows (which never
                    // model it) and present for Motion Tracking. Absence
                    // must decode to `null`, not to 0f — a 0-width box
                    // would be treated as "has extent" downstream and
                    // collapse the overlay to nothing.
                    val w = k.optDouble("w", Double.NaN).takeIf { it.isFinite() }?.toFloat()
                    val h = k.optDouble("h", Double.NaN).takeIf { it.isFinite() }?.toFloat()
                    CropKeyframe(
                        timeMs = k.optLong("t"),
                        centerX = k.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        centerY = k.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                        zoom = k.optDouble("z", 1.0).toFloat().coerceAtLeast(1f),
                        width = w?.coerceIn(0f, 1f),
                        height = h?.coerceIn(0f, 1f),
                        confidence = k.optDouble("c", 1.0).toFloat().coerceIn(0f, 1f),
                        // `optBoolean(name, true)` — absent means found,
                        // which is the correct reading for every Auto
                        // Frame row written before today.
                        isFound = k.optBoolean("f", true)
                    )
                }

                val motionTrack = if (obj.optBoolean("motionTrackEnabled", false)) {
                    MotionTrackSettings(
                        enabled = true,
                        overlayId = obj.optStringOrNull("mtOverlayId"),
                        maskMode = com.aicreatorstudio.app.model.MotionTrackSettings.MaskMode
                            .entries.firstOrNull { it.name == obj.optString("mtMaskMode", "NONE") }
                            ?: com.aicreatorstudio.app.model.MotionTrackSettings.MaskMode.NONE,
                        maskStrength = obj.optDouble("mtMaskStrength", 0.5)
                            .toFloat().coerceIn(0f, 1f),
                        anchorU = obj.optDouble("mtAnchorU", 0.5).toFloat().coerceIn(0f, 1f),
                        anchorV = obj.optDouble("mtAnchorV", 0.5).toFloat().coerceIn(0f, 1f),
                        scaleWithBox = obj.optBoolean("mtScaleWithBox", false),
                        referenceBoxHeight = obj.optDouble("mtRefBoxHeight", 0.1)
                            .toFloat().coerceIn(0.001f, 1f),
                        positionSmoothing = obj.optDouble("mtPosSmoothing", 0.5)
                            .toFloat().coerceIn(0f, 1f),
                        scaleSmoothing = obj.optDouble("mtScaleSmoothing", 0.7)
                            .toFloat().coerceIn(0f, 1f),
                        minConfidence = obj.optDouble("mtMinConfidence", 0.35)
                            .toFloat().coerceIn(0f, 1f),
                        algorithm = obj.optString(
                            "mtAlgorithm",
                            com.aicreatorstudio.app.model.MotionTrackSettings.ALGORITHM_CSRT_V1
                        )
                    )
                } else MotionTrackSettings.Identity

                // ---- 2026-09-29 Speed Ramping ----
                // Absent in every legacy blob → `null` → the constant
                // `speed` column drives playback, exactly as before.
                val speedCurve = obj.optJSONObject("speedCurve")?.let { sc ->
                    val arr = sc.optJSONArray("points")
                    val pts = if (arr == null) emptyList() else {
                        (0 until arr.length()).mapNotNull { i ->
                            val p = arr.optJSONObject(i) ?: return@mapNotNull null
                            val v = p.optDouble("v", 1.0).toFloat()
                            SpeedPoint(
                                normalizedTime = p.optDouble("t", 0.0).toFloat()
                                    .coerceIn(0f, 1f),
                                // A stored 0 / NaN would collapse the
                                // mapping, so a hostile value falls
                                // back to normal rather than being
                                // trusted; `sanitized()` would clamp
                                // it anyway, but doing it here keeps
                                // the decoded object meaningful.
                                speed = if (v.isFinite() && v > 0f) v else 1f
                            )
                        }
                    }
                    if (pts.size < 2) {
                        null
                    } else {
                        SpeedCurve(
                            points = pts,
                            // Unknown / renamed preset names decode as
                            // CUSTOM — the safe, descriptive-only
                            // fallback.
                            preset = SpeedCurvePreset.entries.firstOrNull {
                                it.name == sc.optString("preset", "CUSTOM")
                            } ?: SpeedCurvePreset.CUSTOM,
                            preservePitch = sc.optBoolean("preservePitch", true)
                        )
                    }
                }

                val voiceClean = if (obj.optBoolean("voiceCleanEnabled", false)) {
                    VoiceCleanSettings(
                        enabled = true,
                        strength = obj.optDouble("voiceCleanStrength", 0.6).toFloat()
                            .coerceIn(0f, 1f),
                        algorithm = obj.optString(
                            "voiceCleanAlgorithm", "spectral-gate-mmse-v1"
                        )
                    )
                } else VoiceCleanSettings.Identity

                out[clipId] = ClipAiTools(
                    matting = matting,
                    autoFrame = autoFrame,
                    cropKeyframes = keyframes,
                    motionTrack = motionTrack,
                    voiceClean = voiceClean,
                    ocrResult = obj.optStringOrNull("ocrResult"),
                    speedCurve = speedCurve,
                    // ---- PHASE 7 Smart Highlights ----
                    // Absent in legacy blobs → empty. Defensive: drop
                    // inverted/out-of-range rows rather than trusting.
                    highlightRanges = obj.optJSONArray("highlightRanges")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            val r = arr.optJSONObject(i) ?: return@mapNotNull null
                            val s = r.optLong("s", -1L)
                            val e = r.optLong("e", -1L)
                            if (e <= s || s < 0) null
                            else com.aicreatorstudio.app.model.HighlightRange(
                                startMs = s,
                                endMs = e,
                                score = r.optDouble("score", 0.0).toFloat()
                                    .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                                reason = r.optString("reason", "")
                            )
                        }
                    } ?: emptyList(),
                    // ---- PHASE A: per-clip look settings ----
                    // Absent in legacy blobs → Identity → the old
                    // brightness/contrast/saturation columns keep
                    // driving playback. Every accessor uses `opt*`
                    // defaults and clamps so a corrupt or hand-edited
                    // row degrades to a sane clip rather than
                    // throwing the whole project away.
                    look = decodeLook(obj),
                    // ---- 2026-10-13 Voice Changer ----
                    // Absent in legacy blobs → NONE, i.e. the clip sounds
                    // exactly as it did before this feature existed. An
                    // unrecognised name (a project written by a future
                    // build, or a hand-edited file) also degrades to NONE
                    // rather than throwing: the whole point of the
                    // default is that "no effect" is always a safe answer.
                    voiceEffect = voiceEffectFromName(obj.optStringOrNull("voiceEffect"))
                )
            }
            out
        }.getOrDefault(emptyMap())
    }

    /**
     * PHASE A — decode the `"look"` sub-object into [ClipLookSettings].
     *
     * Defensive by construction:
     *  - an unknown effect / fill-mode name falls back to the default
     *    (NONE / FIT) rather than throwing;
     *  - numeric fields are clamped to the ranges the model documents;
     *  - a non-finite number (NaN / Infinity) is dropped to the default.
     */
    private fun decodeLook(obj: JSONObject): com.aicreatorstudio.app.model.ClipLookSettings {
        val lo = obj.optJSONObject("look") ?: return com.aicreatorstudio.app.model.ClipLookSettings.Identity
        fun fin(v: Double, fallback: Float): Float =
            if (v.isFinite()) v.toFloat().takeIf { it.isFinite() } ?: fallback else fallback

        val effect = com.aicreatorstudio.app.model.VideoEffect.entries
            .firstOrNull { it.name == lo.optString("effect", "NONE") }
            ?: com.aicreatorstudio.app.model.VideoEffect.NONE
        val fill = com.aicreatorstudio.app.model.FillMode.entries
            .firstOrNull { it.name == lo.optString("fill", "FIT") }
            ?: com.aicreatorstudio.app.model.FillMode.FIT
        val g = lo.optJSONObject("grade")
        val grade = if (g == null) {
            com.aicreatorstudio.app.model.ColorGrading.Identity
        } else {
            com.aicreatorstudio.app.model.ColorGrading(
                brightness = fin(g.optDouble("bri"), 0f).coerceIn(-1f, 1f),
                contrast = fin(g.optDouble("con"), 0f).coerceIn(-1f, 1f),
                exposure = fin(g.optDouble("exp"), 0f).coerceIn(-1f, 1f),
                highlights = fin(g.optDouble("hig"), 0f).coerceIn(-1f, 1f),
                shadows = fin(g.optDouble("sha"), 0f).coerceIn(-1f, 1f),
                whites = fin(g.optDouble("whi"), 0f).coerceIn(-1f, 1f),
                blacks = fin(g.optDouble("bla"), 0f).coerceIn(-1f, 1f),
                temperature = fin(g.optDouble("tem"), 0f).coerceIn(-1f, 1f),
                tint = fin(g.optDouble("tin"), 0f).coerceIn(-1f, 1f),
                saturation = fin(g.optDouble("sat"), 0f).coerceIn(-1f, 1f),
                vibrance = fin(g.optDouble("vib"), 0f).coerceIn(-1f, 1f),
                clarity = fin(g.optDouble("clr"), 0f).coerceIn(-1f, 1f),
                vignetteAmount = fin(g.optDouble("vig"), 0f).coerceIn(-1f, 1f),
                vignetteFeather = fin(g.optDouble("vft"), 0.5f).coerceIn(0f, 1f),
                vignetteRoundness = fin(g.optDouble("vrd"), 0.5f).coerceIn(0f, 1f),
                fade = fin(g.optDouble("fad"), 0f).coerceIn(0f, 1f),
                sharpness = fin(g.optDouble("shp"), 0f).coerceIn(0f, 1f),
                grain = fin(g.optDouble("grn"), 0f).coerceIn(0f, 1f),
                hueRed = fin(g.optDouble("hr"), 0f).coerceIn(-180f, 180f),
                hueOrange = fin(g.optDouble("ho"), 0f).coerceIn(-180f, 180f),
                hueYellow = fin(g.optDouble("hy"), 0f).coerceIn(-180f, 180f),
                hueGreen = fin(g.optDouble("hg"), 0f).coerceIn(-180f, 180f),
                hueAqua = fin(g.optDouble("ha"), 0f).coerceIn(-180f, 180f),
                hueBlue = fin(g.optDouble("hb"), 0f).coerceIn(-180f, 180f),
                huePurple = fin(g.optDouble("hp"), 0f).coerceIn(-180f, 180f),
                hueMagenta = fin(g.optDouble("hm"), 0f).coerceIn(-180f, 180f)
            )
        }
        val t = lo.optJSONObject("transform")
        val transform = if (t == null) {
            com.aicreatorstudio.app.model.ClipTransform()
        } else {
            com.aicreatorstudio.app.model.ClipTransform(
                scale = fin(t.optDouble("sc"), 1f).coerceIn(0.25f, 6f),
                offsetX = fin(t.optDouble("ox"), 0f).coerceIn(-0.5f, 0.5f),
                offsetY = fin(t.optDouble("oy"), 0f).coerceIn(-0.5f, 0.5f),
                rotation = t.optInt("rt", 0).let { ((it % 360) + 360) % 360 },
                flipH = t.optBoolean("fh", false),
                flipV = t.optBoolean("fv", false)
            )
        }
        val up = lo.optJSONObject("upscale")
        val upscale = if (up == null) {
            com.aicreatorstudio.app.model.UpscalerSettings.Identity
        } else {
            // An unknown preset name (a future build renamed one, or a
            // hand-edited file) degrades to OFF — never to a crash.
            val preset = com.aicreatorstudio.app.model.UpscalerPreset.entries
                .firstOrNull { it.name == up.optString("preset", "OFF") }
                ?: com.aicreatorstudio.app.model.UpscalerPreset.OFF
            com.aicreatorstudio.app.model.UpscalerSettings(
                preset = preset,
                strength = fin(up.optDouble("strength"), 0.5f).coerceIn(0f, 1f)
            )
        }
        return com.aicreatorstudio.app.model.ClipLookSettings(
            videoEffect = effect,
            colorGrading = grade,
            opacity = fin(lo.optDouble("opacity"), 1f).coerceIn(0f, 1f),
            fillMode = fill,
            transform = transform,
            fadeInMs = lo.optLong("fadeIn", 0L).coerceIn(0L, 600_000L),
            fadeOutMs = lo.optLong("fadeOut", 0L).coerceIn(0L, 600_000L),
            upscale = upscale
        )
    }

    /**
     * Returns the string at [name] when present and non-blank, else
     * null. Distinct from [JSONObject.optString] which returns the
     * literal `"null"` for absent keys — that ambiguity is what
     * allowed legacy audio-overlay rows to round-trip "null" strings.
     */
    private fun JSONObject.optStringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val v = optString(name)
        return v.takeIf { it.isNotBlank() }
    }
}
