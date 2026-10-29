package com.aicreatorstudio.app.debugtools.showcase

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicreatorstudio.app.model.SubtitleStyle
import com.aicreatorstudio.app.model.SubtitleTemplate
import com.aicreatorstudio.app.model.template.JsonCatalogStatus
import com.aicreatorstudio.app.model.template.SubtitleTemplateCategory
import com.aicreatorstudio.app.model.template.SubtitleTemplateTaxonomy
import com.aicreatorstudio.app.ui.screens.editor.rememberCaptionImageBitmap

/**
 * PHASE 5 — the debug-only subtitle showcase.
 *
 * ## What this is
 *
 * A development surface that renders EVERY template in the production
 * registry against one deterministic Persian fixture, through the SAME
 * renderer the canvas preview and the MP4 burn-in use
 * ([rememberCaptionImageBitmap] → `CaptionRenderer.render`). There is no
 * second caption renderer here, and no Compose `Text` stand-in pretending to
 * be a caption: the only caption pixels on screen come out of the export
 * renderer.
 *
 * ## What it is not
 *
 * Not a product feature. It lives in `src/debug`, so it is absent from
 * release builds by construction. There is no launcher icon and no entry
 * point in the app's navigation graph.
 *
 * ## Known limitation (deliberate)
 *
 * The backdrops are synthetic, drawn in Compose — they are NOT real video
 * frames. They exist so the same caption can be judged against black, white,
 * mid-gray, a split light/dark scene and a busy scene with a wide luminance
 * spread. Final judgement over real footage still requires a device and a
 * real clip; see `docs/subtitles/phase-5-report.md` §6.
 */

/** Render canvas: half of 1080x1920 at the same 9:16 ratio. */
private const val CANVAS_W = 540
private const val CANVAS_H = 960

/** Synthetic test background (NOT footage). */
enum class ShowcaseBackdrop(val label: String) {
    BLACK("Black"),
    WHITE("White"),
    MID_GRAY("Mid gray"),
    SPLIT("Split light/dark"),
    BUSY("Busy synthetic")
}

@Composable
fun SubtitleShowcaseScreen(vm: SubtitleShowcaseViewModel = hiltViewModel()) {
    val registry by vm.registry.collectAsStateWithLifecycle()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var variantName by rememberSaveable { mutableStateOf(ShowcaseCaption.SHORT.name) }
    var backdropName by rememberSaveable { mutableStateOf(ShowcaseBackdrop.MID_GRAY.name) }
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var showGuides by rememberSaveable { mutableStateOf(true) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var playheadMs by rememberSaveable { mutableStateOf(0L) }

    val templates = registry.templates
    val selected = templates.firstOrNull { it.id == selectedId } ?: templates.firstOrNull()
    val variant = ShowcaseCaption.entries.firstOrNull { it.name == variantName }
        ?: ShowcaseCaption.SHORT
    val cue = remember(variant) { ShowcaseFixture.cueFor(variant) }
    val backdrop = ShowcaseBackdrop.entries.firstOrNull { it.name == backdropName }
        ?: ShowcaseBackdrop.MID_GRAY

    // A shorter fixture must never leave the playhead past its window.
    LaunchedEffect(cue) { if (playheadMs > cue.durationMs) playheadMs = 0L }

    // Auto-advance drives the REAL word-motion code path: the renderer is
    // handed an advancing frame time, exactly as the canvas does.
    LaunchedEffect(playing, cue) {
        if (!playing) return@LaunchedEffect
        var previous = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    val dtMs = ((now - previous) / 1_000_000L).coerceAtLeast(0L)
                    playheadMs = (playheadMs + dtMs) % cue.durationMs.coerceAtLeast(1L)
                }
                previous = now
            }
        }
    }

    Surface(color = Color(0xFF101014), modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            ShowcaseHeader(templateCount = templates.size, registry = registry)

            Box(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                contentAlignment = Alignment.Center
            ) {
                if (selected == null || registry.loading) {
                    CircularProgressIndicator()
                } else {
                    CaptionPreview(
                        template = selected,
                        cue = cue,
                        playheadMs = playheadMs,
                        backdrop = backdrop,
                        showGuides = showGuides,
                        modifier = Modifier.height(280.dp).aspectRatio(CANVAS_W.toFloat() / CANVAS_H)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (selected != null) {
                    NowPlaying(
                        template = selected,
                        cue = cue,
                        playheadMs = playheadMs,
                        playing = playing,
                        showGuides = showGuides,
                        onPlayingChange = { playing = it },
                        onScrub = {
                            playing = false
                            playheadMs = it
                        },
                        onStepNext = {
                            playing = false
                            playheadMs = nextWordStartMs(cue, playheadMs)
                        },
                        onGuidesChange = { showGuides = it }
                    )
                }

                ChipRow(
                    label = "Caption",
                    options = ShowcaseCaption.entries.map { it.name },
                    selected = variant.name,
                    render = { name ->
                        val v = ShowcaseCaption.entries.first { it.name == name }
                        "${v.label} · ${v.detail}"
                    },
                    onSelect = {
                        playing = false
                        variantName = it
                        playheadMs = 0L
                    }
                )

                ChipRow(
                    label = "Backdrop (synthetic)",
                    options = ShowcaseBackdrop.entries.map { it.name },
                    selected = backdrop.name,
                    render = { name -> ShowcaseBackdrop.entries.first { it.name == name }.label },
                    onSelect = { backdropName = it }
                )

                ChipRow(
                    label = "Category",
                    options = listOf(ALL_CATEGORIES) + SubtitleTemplateCategory.entries.map { it.key },
                    selected = categoryKey ?: ALL_CATEGORIES,
                    render = { key ->
                        if (key == ALL_CATEGORIES) "All"
                        else SubtitleTemplateCategory.entries.first { it.key == key }.displayNameEn
                    },
                    onSelect = { categoryKey = if (it == ALL_CATEGORIES) null else it }
                )

                HorizontalDivider(color = Color(0x22FFFFFF))

                if (registry.error != null) {
                    Text(
                        text = "Registry failed to load: ${registry.error}",
                        color = Color(0xFFFF6B6B),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                TemplateBrowser(
                    templates = templates,
                    selectedId = selected?.id,
                    categoryFilter = categoryKey,
                    onSelect = { id ->
                        selectedId = id
                        playing = false
                        playheadMs = 0L
                    }
                )

                Text(
                    text = "Persian fixture text and word timings are authored demo data; " +
                        "no speech recognition produced them and no ASR accuracy is implied.",
                    color = Color(0x88FFFFFF),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

/**
 * The caption pixels on screen. Built from the template + fixture via the
 * production `withTemplate` + `rememberCaptionImageBitmap` path — the same
 * one the canvas preview and the burn-in use.
 */
@Composable
private fun CaptionPreview(
    template: SubtitleTemplate,
    cue: ShowcaseCue,
    playheadMs: Long,
    backdrop: ShowcaseBackdrop,
    showGuides: Boolean,
    modifier: Modifier = Modifier
) {
    val overlay = remember(template, cue) { ShowcaseFixture.overlayFor(template, cue) }
    val bitmap: ImageBitmap? = rememberCaptionImageBitmap(
        overlay = overlay,
        style = overlay.subtitleStyle ?: SubtitleStyle.Default,
        canvasWidthPx = CANVAS_W,
        canvasHeightPx = CANVAS_H,
        frameTimeMs = playheadMs
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawBackdrop(backdrop) }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "caption preview for ${template.id}",
                modifier = Modifier.fillMaxSize()
            )
        }
        if (showGuides) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawGuides() }
        }
    }
}

private fun DrawScope.drawBackdrop(backdrop: ShowcaseBackdrop) {
    when (backdrop) {
        ShowcaseBackdrop.BLACK -> drawRect(Color(0xFF000000))
        ShowcaseBackdrop.WHITE -> drawRect(Color(0xFFFFFFFF))
        ShowcaseBackdrop.MID_GRAY -> drawRect(Color(0xFF808080))
        ShowcaseBackdrop.SPLIT -> {
            drawRect(Color(0xFFF2F2F2), size = Size(size.width, size.height / 2f))
            drawRect(
                Color(0xFF14141A),
                topLeft = Offset(0f, size.height / 2f),
                size = Size(size.width, size.height / 2f)
            )
        }

        ShowcaseBackdrop.BUSY -> {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color(0xFFCFE0F2), Color(0xFF6E7684), Color(0xFF23232B))
                )
            )
            drawCircle(
                color = Color(0xFFF7F7F7),
                radius = size.minDimension * 0.20f,
                center = Offset(size.width * 0.26f, size.height * 0.18f)
            )
            drawCircle(
                color = Color(0xFF0C0C10),
                radius = size.minDimension * 0.26f,
                center = Offset(size.width * 0.80f, size.height * 0.70f)
            )
            drawRect(
                color = Color(0xFF9AA3B0),
                topLeft = Offset(size.width * 0.05f, size.height * 0.46f),
                size = Size(size.width * 0.55f, size.height * 0.07f)
            )
            drawRect(
                color = Color(0xFF3A3A46),
                topLeft = Offset(size.width * 0.42f, size.height * 0.86f),
                size = Size(size.width * 0.58f, size.height * 0.14f)
            )
        }
    }
}

/**
 * The three style anchors the renderer places text at (`anchorFractions`:
 * 0.12 / 0.5 / 0.88) plus the frame edge. Drawn so a caption can be checked
 * against the safe area rather than eyeballed.
 */
private fun DrawScope.drawGuides() {
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 14f))
    listOf(0.12f, 0.5f, 0.88f).forEach { f ->
        drawLine(
            color = Color(0x55FFFFFF),
            start = Offset(size.width * f, 0f),
            end = Offset(size.width * f, size.height),
            strokeWidth = 2f,
            pathEffect = dash
        )
        drawLine(
            color = Color(0x55FFFFFF),
            start = Offset(0f, size.height * f),
            end = Offset(size.width, size.height * f),
            strokeWidth = 2f,
            pathEffect = dash
        )
    }
    drawRect(color = Color(0x99FFFFFF), style = Stroke(width = 2f))
}

// ---------------------------------------------------------------------------
// Info + controls
// ---------------------------------------------------------------------------

@Composable
private fun NowPlaying(
    template: SubtitleTemplate,
    cue: ShowcaseCue,
    playheadMs: Long,
    playing: Boolean,
    showGuides: Boolean,
    onPlayingChange: (Boolean) -> Unit,
    onScrub: (Long) -> Unit,
    onStepNext: () -> Unit,
    onGuidesChange: (Boolean) -> Unit
) {
    val category = SubtitleTemplateTaxonomy.classify(template)
    val activeIndex = cue.activeTokenIndex(playheadMs)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = template.name + if (template.nameEn.isNotBlank()) "  ·  ${template.nameEn}" else "",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "id=${template.id}   ·   category=${category.displayNameEn} " +
                "(${category.key})   ·   word=${template.activeWordScale}x",
            color = Color(0xAAFFFFFF),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = if (activeIndex == null) {
                "ACTIVE WORD: none — playhead is between words (t=$playheadMs ms)"
            } else {
                "ACTIVE WORD: ${activeIndex + 1} / ${cue.tokenCount} — " +
                    "«${cue.words[activeIndex].text}»  (t=$playheadMs ms)"
            },
            color = if (activeIndex == null) Color(0xFF9AA3B0) else Color(0xFF7CE38B),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "motion=${template.motionKind}  reveal=${template.wordRevealMode}  " +
                "scene=${template.backgroundKind}  " +
                "stroke=${if (template.style.strokeEnabled) "yes" else "no"}  " +
                "plate=${if (template.style.backgroundEnabled) "yes" else "no"}",
            color = Color(0x88FFFFFF),
            style = MaterialTheme.typography.labelSmall
        )

        Slider(
            value = playheadMs.toFloat(),
            onValueChange = { onScrub(it.toLong()) },
            valueRange = 0f..cue.durationMs.toFloat().coerceAtLeast(1f)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = { onScrub(0L) }) {
                Icon(Icons.Filled.Replay, contentDescription = "restart", tint = Color.White)
            }
            IconButton(onClick = { onPlayingChange(!playing) }) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "pause" else "play the fixture",
                    tint = Color.White
                )
            }
            IconButton(onClick = onStepNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "next word", tint = Color.White)
            }
            Text(
                text = "${cue.variant.label} · ${cue.lineCount} line(s) · ${cue.tokenCount} words",
                color = Color(0xAAFFFFFF),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "guides",
                color = Color(0xAAFFFFFF),
                style = MaterialTheme.typography.labelSmall
            )
            Switch(checked = showGuides, onCheckedChange = onGuidesChange)
        }
    }
}

private fun nextWordStartMs(cue: ShowcaseCue, fromMs: Long): Long =
    cue.words.firstOrNull { it.startTimeMs > fromMs }?.startTimeMs ?: 0L

// ---------------------------------------------------------------------------
// Browsing the registry
// ---------------------------------------------------------------------------

private const val ALL_CATEGORIES = "__all__"

@Composable
private fun ChipRow(
    label: String,
    options: List<String>,
    selected: String,
    render: (String) -> String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = Color(0xAAFFFFFF),
            style = MaterialTheme.typography.labelSmall
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScrollRow()
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = {
                        Text(
                            text = render(option),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                )
            }
        }
    }
}

/** One horizontal scrolling row — chips never wrap in this screen. */
@Composable
private fun Modifier.horizontalScrollRow(): Modifier =
    this.horizontalScroll(rememberScrollState())

/**
 * Enumerates the registry grouped by the eight product categories. The
 * grouping comes from the production [SubtitleTemplateTaxonomy]; no id list
 * is repeated here, so a template added to the catalog appears automatically.
 */
@Composable
private fun TemplateBrowser(
    templates: List<SubtitleTemplate>,
    selectedId: String?,
    categoryFilter: String?,
    onSelect: (String) -> Unit
) {
    if (templates.isEmpty()) {
        Text(
            text = "No templates loaded.",
            color = Color(0xFFFF6B6B),
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    val filtered = if (categoryFilter == null) templates
    else templates.filter { SubtitleTemplateTaxonomy.classify(it).key == categoryFilter }

    Text(
        text = "Registry: ${templates.size} templates · showing ${filtered.size}",
        color = Color(0xAAFFFFFF),
        style = MaterialTheme.typography.labelMedium
    )

    SubtitleTemplateTaxonomy.group(filtered).forEach { (category, items) ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${category.displayNameEn} (${items.size})",
                color = Color(0xFF7CC7FF),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            items.chunked(2).forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    row.forEach { template ->
                        TemplateCard(
                            template = template,
                            selected = template.id == selectedId,
                            onClick = { onSelect(template.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A compact registry card.
 *
 * The colour dots are a DATA SWATCH read straight off the template (base
 * text, plate, active word) — not a caption render. Rendering 45 real
 * caption bitmaps at once would cost tens of megabytes for no extra
 * information; the single preview above is the real render.
 */
@Composable
private fun TemplateCard(
    template: SubtitleTemplate,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) Color(0xFF7CC7FF) else Color(0x22FFFFFF)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1B1B22))
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SwatchDot(template.style.textColorArgb)
            // Transparent when the template ships no plate at all.
            SwatchDot(
                if (template.style.backgroundEnabled) template.style.backgroundColorArgb else 0
            )
            SwatchDot(template.activeWordColorArgb)
        }
        Text(
            text = template.name,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = template.id,
            color = Color(0x88FFFFFF),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SwatchDot(argb: Int) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(1.dp, Color(0x44FFFFFF), CircleShape)
    )
}

@Composable
private fun ShowcaseHeader(templateCount: Int, registry: ShowcaseRegistryState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = "Omelet · Subtitle Showcase",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "DEBUG ONLY · renders the production registry through the production " +
                "caption renderer (CaptionRenderer.render). Not shipped in release.",
            color = Color(0x99FFFFFF),
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = buildString {
                append("registry=")
                append(if (registry.loading) "loading…" else "$templateCount templates")
                append("  ·  catalog=")
                append(registry.catalogStatus ?: JsonCatalogStatus.EMPTY)
                append("  ·  json wins on duplicate ids")
            },
            color = Color(0x99FFFFFF),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
