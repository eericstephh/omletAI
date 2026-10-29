# Subtitle pipeline

The most important subsystem in the app. Persian, RTL, word-level. **Never corrupt a Whisper
timestamp and never lose word identity.**

## End-to-end chain

```text
Whisper (local whisper.cpp | Groq cloud)
↓  raw transcript
Word-level timestamps
↓  Persian normalization        PersianNormalizer
↓  Persian correction           ai/subtitle/corrector/*
↓  Subtitle model               TranscribedWord / Subtitle / TextOverlay
↓  Template renderer            CaptionRenderer  (ONE renderer, shared)
↓  Timeline  →  Preview  →  Export
```

## 1. Data model

| Type | File | Fields |
|---|---|---|
| `TranscribedWord` | `ai/Transcription.kt:16` | `id`, `text`, `startTimeMs: Long`, `endTimeMs: Long`, `confidence: Float?`; `isActiveAt(ms)` |
| `Subtitle` (cue) | `ai/Transcription.kt:37` | `id`, `text`, `startTimeMs`, `endTimeMs`, `words: List<TranscribedWord>`, `confidence`; `isVisibleAt`, `activeWordAt` |
| `Transcription` | `ai/Transcription.kt:68` | `segments`, `words` |
| `SrtCue` | `ai/SrtParser.kt:311` | SRT import |
| `TextOverlay` | `model/TextOverlay.kt:22` | the persisted form: `subtitleSource` (`"ai"`/`"srt"`/null), `words`, `subtitleStyle`, `subtitleAnchorMs`, `templateId`, karaoke/motion fields; `activeWordAt(frameTimeMs)` :161 |

Overlays live on the project as `textOverlays: List<TextOverlay>`, persisted through
`db/Converters.kt` / `EditorStateJson.kt`.

## 2. Word-level timestamps

- Native shape: `ai/WhisperNative.kt:36` — `WordTimestamp(text, t0Cs, t1Cs, probability)`, returned by
  `fullWithWordTimestamps`.
- Parsing: `ai/WhisperEngine.kt:651-670` → `wordStartMs = windowOffsetMs + entry.t0Cs * 10L` →
  `TranscribedWord`. Grouping: `groupWordsIntoSubtitlesForWhisper` (`WhisperEngine.kt:1040`).
- Cloud: `ai/subtitle/cloud/SubtitleResultParser.kt`, `GroqResponseParser.GroqWord`,
  `CloudWhisperService.Word`.
- Into overlays: `ai/WhisperTranscriptionToTextOverlays.kt:79#toTextOverlays` shifts by
  `clipOffsetMs` and keeps the words.
- **Timestamps survive editing.** The normalizer, merger and corrector copy only `text`; the
  segmenter rebins; `SubtitleDelegate.updateTextContent` touches text only. Preserve this property.

## 3. Normalization (never confuses with correction)

`ai/subtitle/PersianNormalizer.kt` — `normalize()` maps `ي→ی`, `ك→ک`, strips BOM/ZWSP, **preserves
ZWNJ**, collapses whitespace; `isPersianScript()` is the RTL test used at render time. No digit
conversion. (An Arabic-Indic digit map exists only for display in `EditPanel.kt:677`.)

`ai/ocr/OcrTextNormalizer.kt` does the OCR path's own normalization (`ي/ك`, NFKC, tatweel).

## 4. Persian correction (context-aware, timestamp-preserving)

Package `ai/subtitle/corrector/`:

| Class | Role |
|---|---|
| `SuspiciousWordDetector.kt` | Flags trailing-repeat, suffix-junk, and fuzzy low-confidence (Levenshtein) words |
| `CorrectionCandidateGenerator.kt` | Proposes candidates: collapse, strip, `می`-prefix, edit distance ≤ 2 |
| `ContextValidator.kt` | `OfflineContextValidator` — weighted selection among candidates |
| `PersianLexicon.kt` | ~1000 words + `ZWNJ_FIXES` |
| `TranscriptCorrector.kt` | Orchestrator with confidence gates |

**Only `text` changes.** Ids and timestamps are carried through untouched. Target examples:
`آشق→عاشق`, `مسول→مسئول`, `میخام→میخوام`, `نمیدونم→نمیدونم`-style ZWNJ fixes.

## 5. Post-processing order

`ai/subtitle/SubtitlePostProcessor.kt:69#postProcess`:

```text
normalize → mergeBpeFragments → enforceWordIntegrity → corrector → silence → repetition → segmenter
```

Tests: `SubtitlePostProcessorTest`, `SubtitlePostProcessIntegrationTest`, `RepetitionFilterTest`,
`SilenceFilterTest`, `SubtitleSegmenterTest` (+ CpsPause / WordCount / WordlessChunk variants),
`corrector/TranscriptCorrectorTest`.

## 6. Templates — the real catalogs

There are **two** catalogs. Do not assume a template name is real; look it up here.

### (a) Built-in, in Kotlin — `model/BuiltInSubtitleTemplates.kt:33` — **exactly 20**

`fa_01_clean` (CLEAN, the default), `fa_02_impact`, `fa_03_yellow_word`, `fa_04_karaoke`,
`fa_05_capsule`, `fa_06_glass`, `fa_07_creator`, `fa_08_cinematic`, `fa_09_news`, `fa_10_podcast`,
`fa_11_gaming`, `fa_12_neon`, `fa_13_outline`, `fa_14_shadow_pop`, `fa_15_dynamic`, `fa_16_bar`,
`fa_17_modern_box`, `fa_18_social`, `fa_19_premium`, `fa_20_signature`. `ALL` at :620.

### (b) JSON, in assets — `app/src/main/assets/subtitle_templates.json` — **exactly 19**

`fast_zoom_reveal`, `glitch_cut`, `bounce_text_pop`, `karaoke_classic`, `progress_bar_underline`,
`typewriter_reveal`, `slide_reveal`, `minimal_clean`, `neon_glow`, `cinematic_bar`,
`keyword_punch_zoom`, `cinema_black_box`, `cinema_yellow_script`, `cinema_red_box`,
`cinema_dark_box`, `cinema_black_pill`, `cinema_plain_white`,
**`wave_accent`** («موج رنگی»), **`filmstrip_frame`** («نوار فیلم»).

The last two were added 2026-09-24 (17 → 19) and are the **only** JSON templates that needed new
*engine* axes rather than just a new row:

- `wave_accent` — `wordHighlight.type = "waveAccent"`; same bar contract as `progress_bar_underline`
  but `ProgressBarSpec.shape = ProgressBarShape.WAVE`. Rendered by `CaptionRenderer.drawWaveAccent`
  (pure math: `waveOffsetY`, `wavePhaseAt`, `WAVE_CYCLE_MS`). The track and the fill share one phase.
- `filmstrip_frame` — `background.type = "filmstrip"` → `CaptionBackgroundKind.FILMSTRIP`: a dark
  plate plus two rows of round-rect perforations (hole count capped at 20, no blur/shader).

⚠️ **Adding a new value on a string-keyed axis means updating EVERY `when` on that string.**
`JsonTemplateMapper.mapMotion` has two independent branches keyed on the highlight type — one for
`kind`, one for `reveal` — and `withHighlightOverride` has a third. Missing one leaves the template
compiling and mapping while the per-word engine silently never runs (this happened to `waveaccent`).
Grep the **literal string**, and add a mapping test that asserts the resolved engine field.

The `SubtitleStudioPanel` **manual** override pickers keep their own local string↔kind lists (6
original kinds); a new axis is reachable via the Templates section but not as a manual override
option. Those lists degrade gracefully (`else -> null` falls back to the template's own kind).

### Template machinery

| Concern | Location |
|---|---|
| Model | `model/SubtitleTemplate.kt:27`; `WordRevealMode` :226, `ProgressBarShape` :249, `ProgressBarSpec` :265, `withTemplate()` :268, `templatePositionAsAnchorDelta` :326 |
| Style bundle | `model/SubtitleStyle.kt:26` (+ `AllPresets` :226) |
| Motion vocabulary | `model/WordMotionKind.kt` (11 motion + 7 background kinds) |
| JSON parse/map/cache | `model/template/JsonTemplate.kt`, `JsonTemplateMapper.kt`, `TemplateCatalogCache.kt` |
| JSON loader | `data/subtitle/JsonTemplateCatalog.kt`, wired in `EditorViewModel.kt:2341` |
| Repository | `data/subtitle/SubtitleTemplateRepository.kt:24` (built-ins + user JSON in `filesDir/subtitle-templates`) |
| UI | `ui/screens/editor/subtitle/SubtitleStudioPanel.kt:99` (`TemplateGridCard` :1720, `TemplatePreviewCard` :1604); `SubtitleStylePanel.kt` |
| Entry point | tab `ToolPanel.SUBTITLE_STUDIO` (`model/EditorUiState.kt:465`); dock entry at `EditorScreen.kt:3397` |

Applying a template: `EditorViewModel.applySubtitleTemplateToAll` :2289, `addTemplateSubtitle` :2385,
`studioTemplate()` :2359 (resolved from `SettingsRepository.studioTemplateId()/studioTemplateJson()`).

**No template is a static image.** Both catalogs are pure data; the JSON has no image or thumbnail
keys. Picker cards draw synthetic demo words through the real renderer — that is a preview, not a
fake template. Keep it that way.

## 7. Rendering — one renderer, two callers

`export/CaptionRenderer.kt:75#render` is the **single** implementation.

| Path | Call site |
|---|---|
| Compose preview | `ui/screens/editor/CaptionBitmap.kt:59` inside `rememberCaptionImageBitmap` (:34); `rememberTemplateImageBitmap` (:104); drawn over the video at `EditorScreen.kt:2356-2405` |
| Media3 export | `export/SubtitleBurnIn.kt:163` and `:211`, via `export/VideoExportEngine.kt#buildSubtitleOverlays` (:1006) → `SubtitleBurnInFactory.createOverlays` (:1019) → `androidx.media3.effect.OverlayEffect` (:937) |
| Template picker cards | `SubtitleStudioPanel.kt:1597, 1713` |

- Export classes: `SubtitleBurnInFactory` (`SubtitleBurnIn.kt:53`), `SubtitleWindowOverlay` (:142,
  extends Media3 `BitmapOverlay`), `DynamicSubtitleOverlay` (:264), `OverlaySettings` anchors (0,0);
  scheduler `export/SubtitleOverlayScheduler.kt`.
- **Active word** is derived *inside* the renderer from `frameTimeMs` via
  `TextOverlay.activeWordAt` + `ai/WordMotionEngine.kt#wordStateAt` (:60). Entrance/exit transforms:
  `captionTransformAt` (`WordMotionEngine.kt:264`).
- **Animation is pure math**, not Compose animation APIs — a per-frame bitmap keyed to the playhead
  (~33 ms). Keep it that way for performance.
- **RTL** is handled by `StaticLayout` + `TextDirectionHeuristics.FIRSTSTRONG_RTL` inside
  `CaptionRenderer`; `isRtl()` delegates to `PersianNormalizer.isPersianScript`. There is no
  `String.reverse` anywhere in the path.

Tests: `export/CaptionRendererParityTest`, `export/SubtitleOverlaySchedulerTest`,
`export/CompositionTimelineTest`, `ai/WordMotionEngineTest`,
`ai/WhisperTranscriptionToTextOverlaysTest`, `model/SubtitleTemplateApplyTest`,
`model/BuiltInSubtitleTemplatesFaFontTest` (asserts **20** unique), `model/KaraokeRangesTest`,
`model/template/JsonTemplateParserTest`, `TemplateCatalogCacheTest`,
`db/TextOverlaySubtitleSourceRoundTripTest`.

## 8. Timeline ↔ subtitle synchronisation

- `EditorViewModel.onPositionChanged` :423 → `activeSubtitleIdAt` :532 (linear scan of
  `ms in [start, end)`) → `activeWordIdAt` :555 → `activeWordAt`. Also `onSeekTo` :448 and
  `onScrubEnded` :505. Recompute is **skipped while `isScrubbing`** — preserve that.
- Export rebases time in `export/CompositionTimeline.kt`: `subtitleOriginMs` :63,
  `outputMsForTimelineMs` :119. `SubtitleBurnInFactory` rebases the words accordingly.

## 9. Whisper (transcription) — the sensitive pipeline

whisper.cpp **v1.7.6** via JNI/NDK (no ONNX, no pure-Kotlin port on the local path).

- JNI declarations: `ai/WhisperNative.kt` — `nativeInit`, `nativeFree`,
  `nativeFullWithWordTimestamps`, `nativeSelfTest`.
- Native bridge: `app/src/main/cpp/whisper-jni.cpp`; CMake builds `libwhisper.so` and
  `librnnoise.so`; `abiFilters` = `arm64-v8a` only.
- Pipeline in call order:
  1. `EditorViewModel.generateSubtitles` → `ai/SubtitleGenerator.kt` (`initializeModelFor`,
     `generateTranscription`).
  2. `ai/WhisperEngine.kt#initializeModelFor` → `AiModelInstaller.installWithManifest` →
     `WhisperNative.initFromFile` (`ensureNativeContext`).
  3. `ai/AudioExtractor.kt#prepareAudioSourceForDecode` (MediaExtractor + MediaMuxer remux to a cache
     `.m4a`) → `decodeTo16kMonoFloat` (MediaExtractor + MediaCodec → `int16BytesToMonoShort` →
     `resampleMonoShortTo16kFloat`, 16 kHz mono float).
  4. 30 s windows (`CHUNK_MS = 30000`) → `WhisperNative.fullWithWordTimestamps`.
  5. Sub-word merge (leading-space rule) → `TranscribedWord` (centiseconds × 10 + window offset) →
     `groupWordsIntoSubtitlesForWhisper` → `Transcription`.
- Resampling helper: `ai/AudioResampler.kt`.
- Cloud alternative: `ai/CloudWhisperEngine.kt` → `ai/subtitle/cloud/CloudWhisperRepository.kt` →
  `GroqWhisperClient` (`api.groq.com/openai/v1/audio/transcriptions`, `whisper-large-v3-turbo`).
  Key: `ai/groq/GroqApiKeyStore.kt`, AES-256/GCM in the Android Keystore. **No embedded key.**

### Debug order when transcription misbehaves

```text
Audio extraction → Model availability → Model loading → Native runtime
→ Inference → Timestamp generation → Result parsing
```

Name the broken link. Do not swap the model or the runtime first.

## 10. Model management

- Catalog: `ai/AiModels.kt` (`AiModelCatalog`, `CatalogEntry`) — for Whisper only `WHISPER_SMALL`.
- Lockfile: `app/ai-models/models.lock.json` (`whisper-small`, `selfie_multiclass`,
  `pose_landmarker_lite`, `esrgan_tflite_4x`, `blaze_face_short_range`, `tesseract_fas/ara/eng`).
- SHA source: `app/src/main/assets/ai-models/manifest.json` (`AiModels.sha256For`).
- Installers: `ai/AiModelInstaller.kt` (`installWithManifest`, `downloadToPartial` — **no retry**) and
  `ai/UnifiedModelInstaller.kt` (`install`, **retry 3× exponential 2/4/8 s**, `HttpFetcher` seam).
  Both report **real byte progress**. Storage `filesDir/models/<dirName>/<fileName>`; partials in
  `cacheDir/ai-model-downloads/` or `.partial`. Integrity = SHA-256 vs manifest/lockfile plus a
  `.sha256` sidecar (`AiModels.isVerifiedInstalled`). Storage pre-flight 2× size.
- "Loaded" means `WhisperEngine.nativeContext != 0L && currentModel == spec`; UI status via
  `ai/AiModelStatus.kt` + `EditorViewModel.refreshAiModelStatusesLocked`.

## 11. Audio processing

Extraction `ai/AudioExtractor.kt` (+ `ai/subtitle/cloud/SubtitleAudioExtractor.kt`); resampling
`ai/AudioResampler.kt`; waveform peaks `model/WaveformGenerator.kt`; pairing
`audio/AudioPairingService.kt`. Effects: `VolumeScalingAudioProcessor`, `FadeAudioProcessor` +
`model/AudioFade.kt` (`gainAt`, used by both renderers), `SpeedChangingAudioProcessor` — all in
`export/VideoExportEngine.kt`; voice clean `ai/engines/RnnoiseVoiceCleanEngine.kt`,
`SpectralGateVoiceClean.kt`, `ai/RnnoiseNative.kt`; mux via `buildAudioProcessors` /
`buildAudioSequences` + `export/AiExportHooks.kt#maybeSwapCleanAudio`.

There is **no** `android.media.AudioTrack` / Media3 `AudioSink` usage. Timeline audio playback uses
`ui/screens/editor/AudioMixerPlayer.kt` — one ExoPlayer per audio clip.
