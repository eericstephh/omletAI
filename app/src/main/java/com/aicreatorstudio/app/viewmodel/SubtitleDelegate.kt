package com.aicreatorstudio.app.viewmodel

import androidx.compose.runtime.Stable
import com.aicreatorstudio.app.model.EditorUiState
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.TextOverlay
import com.aicreatorstudio.app.model.TextOverlayAnimation
import com.aicreatorstudio.app.model.PersianFont
import com.aicreatorstudio.app.model.ClipIdTransformUpdater
import com.aicreatorstudio.app.model.withTemplate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

@Stable
class SubtitleDelegate(
    private val state: MutableStateFlow<EditorUiState>,
    private val history: HistoryDelegate
) {
    // ── Text overlays ──────────────────────────────────────
    fun addTextOverlay(text: String, startMs: Long, endMs: Long, fontName: String) {
        history.push()
        state.update { s ->
            val overlay = TextOverlay.default(text, startMs.coerceAtLeast(0L), endMs.coerceAtLeast(startMs))
                .copy(fontFamily = fontName)
            s.copy(textOverlays = s.textOverlays + overlay)
        }
    }

    fun addParagraphOverlay(text: String, startMs: Long, endMs: Long, fontName: String, x: Float, y: Float) {
        history.push()
        state.update { s ->
            val overlay = TextOverlay(
                text = text,
                startTimeMs = startMs.coerceAtLeast(0L),
                endTimeMs = endMs.coerceAtLeast(startMs),
                position = androidx.compose.ui.geometry.Offset(x, y),
                fontFamily = fontName
            )
            s.copy(textOverlays = s.textOverlays + overlay)
        }
    }

    fun updateTextOverlay(id: String, newText: String) {
        history.push()
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map { if (it.id == id) it.copy(text = newText) else it })
        }
    }

    fun updateTextPosition(id: String, x: Float, y: Float) {
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == id) it.copy(position = androidx.compose.ui.geometry.Offset(x, y)) else it
            })
        }
    }

    fun updateTextTimeRange(id: String, startMs: Long, endMs: Long) {
        history.push()
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == id) it.copy(startTimeMs = startMs, endTimeMs = endMs) else it
            })
        }
    }

    fun deleteTextOverlay(id: String) {
        history.push()
        state.update { s ->
            s.copy(
                textOverlays = s.textOverlays.filter { it.id != id },
                selectedTextId = if (s.selectedTextId == id) null else s.selectedTextId
            )
        }
    }

    fun deleteAllAiSubtitles() {
        history.push()
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.filter { it.subtitleSource == null || it.subtitleSource != "ai" })
        }
    }

    // ── Subtitle styling ──────────────────────────────────
    fun applySubtitleStyleToSelected(style: SubtitleStyle) {
        history.push()
        state.update { s ->
            val textId = s.selectedTextId ?: return@update s
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == textId) it.copy(subtitleStyle = style) else it
            })
        }
    }

    fun applySubtitleStyleToAll(style: SubtitleStyle) {
        history.push()
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map { it.copy(subtitleStyle = style) })
        }
    }

    fun applyViralStyleToSelected(style: SubtitleStyle) {
        history.push()
        state.update { s ->
            val textId = s.selectedTextId ?: return@update s
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == textId) it.copy(subtitleStyle = style) else it
            })
        }
    }

    fun applyViralStyleToAll(style: SubtitleStyle) {
        history.push()
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map { it.copy(subtitleStyle = style) })
        }
    }

    fun applySubtitleTemplateToAll(template: SubtitleTemplate) {
        history.push()
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map { overlay ->
                overlay.withTemplate(template)
            })
        }
    }

    fun addTemplateSubtitle(text: String, template: SubtitleTemplate, startMs: Long, endMs: Long) {
        history.push()
        state.update { s ->
            val overlay = TextOverlay.default(text, startMs.coerceAtLeast(0L), endMs.coerceAtLeast(startMs))
                .withTemplate(template)
            s.copy(textOverlays = s.textOverlays + overlay)
        }
    }

    // ── Text style setters ─────────────────────────────────
    fun updateSelectedTextFont(fontName: String) = mutateSelected { copy(fontFamily = fontName) }
    fun updateSelectedTextFontSize(size: Float) = mutateSelected { copy(fontSizeSp = size) }
    fun updateSelectedTextColor(argb: Int) = mutateSelected { copy(color = androidx.compose.ui.graphics.Color(argb)) }
    fun updateSelectedTextRotation(deg: Float) = mutateSelected { copy(rotation = deg) }
    fun updateSelectedTextStroke(enabled: Boolean) = mutateSelectedStyle { copy(strokeEnabled = enabled) }
    fun updateSelectedTextShadow(enabled: Boolean) = mutateSelectedStyle { copy(shadowEnabled = enabled) }
    fun updateSelectedTextBackground(enabled: Boolean) = mutateSelectedStyle { copy(backgroundEnabled = enabled) }
    fun updateSelectedTextAnimation(anim: TextOverlayAnimation) = mutateSelected { copy(textAnimation = anim) }
    fun updateSelectedTextDuration(ms: Long) = mutateSelected { copy(endTimeMs = startTimeMs + ms) }

    fun moveSelectedTextToPosition(posMs: Long) {
        history.push()
        state.update { s ->
            val id = s.selectedTextId ?: return@update s
            val delta = s.textOverlays.firstOrNull { it.id == id }?.let { it.endTimeMs - it.startTimeMs } ?: 3000L
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == id) it.copy(startTimeMs = posMs, endTimeMs = posMs + delta) else it
            })
        }
    }

    // ── Selection ──────────────────────────────────────────
    //
    // 2026-09-28 IA FIX — mutual exclusion. `selectClip` and
    // `selectAudioClip` each null out the other two selection ids, but
    // these two did not, so tapping a subtitle left `selectedClipId`
    // pointing at a video clip. That was invisible while no consumer read
    // more than one id; it becomes a real bug the moment
    // `resolveEditorContext` derives the toolbar from all three (the
    // toolbar would show the clip's tools for a selected subtitle).
    // The invariant is now: at most one of selectedClipId /
    // selectedAudioClipId / selectedTextId is non-null.
    //
    // Clearing on *deselect* (id == null) is deliberately not done — a
    // null here means "clear the text selection", not "select nothing",
    // and the other ids are already null whenever this was the selection.
    fun selectTextOverlay(id: String?) {
        state.update { s ->
            if (id == null) {
                s.copy(selectedTextId = null)
            } else {
                s.copy(
                    selectedTextId = id,
                    selectedClipId = null,
                    selectedAudioClipId = null
                )
            }
        }
    }

    // 2026-09-28 IA (Q2) — `selectTextOverlayWithPanel` was removed here and
    // in `EditorViewModel`. Tapping a text/subtitle tile now only selects it;
    // the contextual toolbar switches to the text tool set instead of a panel
    // being force-opened over the video preview.

    fun clearTextSelection() {
        state.update { it.copy(selectedTextId = null) }
    }

    fun beginTextTransform() {
        history.push()
    }

    fun updateTextStyle(id: String, fontSizeSp: Float?, rotation: Float?) {
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map { t ->
                if (t.id != id) t else t.copy(
                    fontSizeSp = fontSizeSp ?: t.fontSizeSp,
                    rotation = rotation ?: t.rotation
                )
            })
        }
    }

    fun snapSelectedTextPosition(x: Float, y: Float) {
        state.update { s ->
            val id = s.selectedTextId ?: return@update s
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == id) it.copy(position = androidx.compose.ui.geometry.Offset(x, y)) else it
            })
        }
    }

    fun nudgeSelectedTextPosition(dx: Float, dy: Float) {
        state.update { s ->
            val id = s.selectedTextId ?: return@update s
            s.copy(textOverlays = s.textOverlays.map { t ->
                if (t.id != id) t else t.copy(
                    position = androidx.compose.ui.geometry.Offset(
                        (t.position.x + dx).coerceIn(0f, 1f),
                        (t.position.y + dy).coerceIn(0f, 1f)
                    )
                )
            })
        }
    }

    fun updateTextLivePosition(id: String, x: Float, y: Float) {
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == id) it.copy(position = androidx.compose.ui.geometry.Offset(x, y)) else it
            })
        }
    }

    fun updateTextLiveStyle(id: String, fontSizeSp: Float?, rotation: Float?) {
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map { t ->
                if (t.id != id) t else t.copy(
                    fontSizeSp = fontSizeSp ?: t.fontSizeSp,
                    rotation = rotation ?: t.rotation
                )
            })
        }
    }

    fun updateTextContent(id: String, text: String) {
        history.push()
        state.update { s ->
            s.copy(textOverlays = s.textOverlays.map { if (it.id == id) it.copy(text = text) else it })
        }
    }

    // ── Internal ───────────────────────────────────────────
    private fun mutateSelected(transform: TextOverlay.() -> TextOverlay) {
        history.push()
        state.update { s ->
            val id = s.selectedTextId ?: return@update s
            s.copy(textOverlays = s.textOverlays.map { if (it.id == id) it.transform() else it })
        }
    }

    private fun mutateSelectedStyle(transform: SubtitleStyle.() -> SubtitleStyle) {
        history.push()
        state.update { s ->
            val id = s.selectedTextId ?: return@update s
            s.copy(textOverlays = s.textOverlays.map {
                if (it.id == id) it.copy(subtitleStyle = it.subtitleStyle?.transform()) else it
            })
        }
    }
}
