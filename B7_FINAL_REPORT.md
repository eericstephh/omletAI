# PHASE B7 — Production-Grade Persian Whisper Subtitle Quality

**Date:** 2026-09-04
**Branch:** local
**Compiler:** JDK 21, Gradle 8.13, Kotlin 2.0.21, AGP 8.13.2

---

## 1. What was built

A pure-data post-processing layer that sits between
`WhisperEngine.generateTranscription(...)` and the existing
B2 mapper `WhisperTranscriptionToTextOverlays.toTextOverlays(...)`.
The post-processor receives a `Transcription`, returns a
`Transcription` (same shape, possibly filtered / normalised /
re-segmented), and the downstream pipeline is **unchanged**.
Word timestamps are preserved 1:1.

## 2. Files changed / created

### New main source (7 files)

| File | Lines | Purpose |
|---|---|---|
| `app/src/main/java/com/aicreatorstudio/app/ai/subtitle/SubtitlePostProcessSettings.kt` | 142 | Per-step enable flags + numeric limits; `Identity` and `Default` companion instances; range-checked `init` block. |
| `app/src/main/java/com/aicreatorstudio/app/ai/subtitle/PersianNormalizer.kt` | 187 | Arabic ي/ك folding, ZWNJ preservation, whitespace collapse, leading/trailing trim. |
| `app/src/main/java/com/aicreatorstudio/app/ai/subtitle/RepetitionFilter.kt` | 155 | Collapse 3+ consecutive identical words (normalisation-insensitive equality). |
| `app/src/main/java/com/aicreatorstudio/app/ai/subtitle/SilenceFilter.kt` | 108 | Drop empty / single-low-confidence long-word hallucination patterns. |
| `app/src/main/java/com/aicreatorstudio/app/ai/subtitle/SubtitleSegmenter.kt` | 201 | Bin-pack split (max chars + max duration) and merge short segments. |
| `app/src/main/java/com/aicreatorstudio/app/ai/subtitle/SubtitlePostProcessor.kt` | 153 | `suspend` orchestrator on `Dispatchers.Default`. Identity short-circuit returns input by reference. |
| `app/src/main/java/com/aicreatorstudio/app/ai/benchmark/BenchmarkResult.kt` | 74 | `ModelBenchmarkResult` + `BenchmarkReport` (no `wordErrorRate` field). |
| `app/src/main/java/com/aicreatorstudio/app/ai/benchmark/WhisperModelBenchmark.kt` | 261 | Runner that iterates Tiny/Small/Medium/Large-v3, records `not installed` honestly for off-catalog specs, writes `cacheDir/whisper_benchmark_report.json`. |

### New tests (7 JVM files, 1 instrumentation file)

| File | Tests |
|---|---|
| `app/src/test/java/com/aicreatorstudio/app/ai/subtitle/PersianNormalizerTest.kt` | 23 |
| `app/src/test/java/com/aicreatorstudio/app/ai/subtitle/RepetitionFilterTest.kt` | 12 |
| `app/src/test/java/com/aicreatorstudio/app/ai/subtitle/SilenceFilterTest.kt` | 11 |
| `app/src/test/java/com/aicreatorstudio/app/ai/subtitle/SubtitleSegmenterTest.kt` | 10 |
| `app/src/test/java/com/aicreatorstudio/app/ai/subtitle/SubtitlePostProcessSettingsTest.kt` | 14 |
| `app/src/test/java/com/aicreatorstudio/app/ai/subtitle/SubtitlePostProcessorTest.kt` | 9 |
| `app/src/test/java/com/aicreatorstudio/app/ai/subtitle/SubtitlePostProcessIntegrationTest.kt` | 9 |
| `app/src/androidTest/java/com/aicreatorstudio/app/ai/WhisperModelBenchmarkTest.kt` | 1 (real-device, real Persian M4A) |

### Modified (3 files)

| File | Change |
|---|---|
| `app/src/main/java/com/aicreatorstudio/app/ai/WhisperEngine.kt` | Added `internal suspend fun benchmarkTranscribe(catalogEntry, videoUri)` (~110 lines). Self-contained: uses a separate native context so the production `currentModel` is **not** mutated. |
| `app/src/main/java/com/aicreatorstudio/app/settings/SettingsRepository.kt` | Added `subtitlePostProcessEnabled: Boolean = true` to `AppSettings` + `KEY_SUBTITLE_POSTPROCESS_ENABLED` shared-prefs key + `updateSubtitlePostProcessEnabled(enabled)` + `currentSubtitlePostProcessSettings()` resolver. |
| `app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt` | Injected `SettingsRepository` via Hilt. In `generateSubtitles`, between `subtitleGenerator.generateTranscription(...)` and the B2 mapper, call `SubtitlePostProcessor.postProcess(transcription, settingsRepository.currentSubtitlePostProcessSettings())`. |

**B1–B6 files NOT touched:** `WhisperNative.kt`, `WhisperModelSpec.kt`,
`AiModelCatalog.kt`, `AiModels.kt`, `models.lock.json`,
`WhisperTranscriptionToTextOverlays.kt`, `TextOverlay.kt`,
`SubtitleStyle.kt`, `SubtitleGenerationState.kt`, the entire
`libwhisper.so` build, and the B1/B2/B3/B4/B5 instrumentation tests.

## 3. Processing rules

### 3.1 Persian normalisation (`PersianNormalizer`)

| Operation | Source | Target | When |
|---|---|---|---|
| Fold Arabic ي | `U+064A` | `U+06CC` (Persian ی) | Always, idempotent |
| Fold Arabic ك | `U+0643` | `U+06A9` (Persian ک) | Always, idempotent |
| Strip BOM | `U+FEFF` | removed | Always |
| Strip ZWSP | `U+200B` | removed | Always |
| Preserve ZWNJ | `U+200C` | preserved | Always — load-bearing for Persian |
| Collapse whitespace | spaces / tabs / newlines | single ASCII space | Always |
| Trim leading/trailing | leading or trailing ASCII whitespace | dropped | Always |
| Persian comma / question mark | `،` `؟` | preserved as-is | Always |
| Latin punctuation `,` `?` `;` | preserved as-is | Always (never invent Persian punctuation) |
| URLs, digits, Latin text | preserved unchanged | When no Arabic-block character present, fast path returns the input instance (no allocation) |

**Hard prohibitions** (enforced by test, not by code): no
translation, no re-ordering, no word splitting / joining, no
invention of any token, no paraphrase, no new punctuation.

### 3.2 Repetition filter (`RepetitionFilter`)

Collapse only when **all** of the following hold, **within a
single segment**:

- 3+ consecutive words have identical normalised text
  (whitespace + ZWNJ insensitive);
- Run length is at least `MIN_REPETITION_COUNT = 3` (two
  repetitions may be real discourse — "آره آره", "می‌روم می‌روم"
  — and the brief says "Do not remove legitimate repeated
  words");
- The first and last word in the run share a confidence (or
  both are null).

**Result:** keep the first occurrence's `id`,
`startTimeMs`, `endTimeMs`, `confidence`. Drop the rest. The
collapsed segment's `id` becomes a fresh UUID because its word
set changed. The flat `Transcription.words` list is rebuilt
to mirror the new segments.

### 3.3 Silence / hallucination filter (`SilenceFilter`)

Drop a segment when **any** of these is true:

1. `segment.words.isEmpty()` (empty transcription).
2. The segment has exactly 1 word AND that word's `confidence`
   is below `minConfidenceForLongWord` (default `0.15`) AND
   the word's `endTimeMs - startTimeMs > longWordMsThreshold`
   (default `5_000` ms). This is the "single hallucinated
   word on silence" pattern.
3. The segment has exactly 1 word, `confidence == null`, AND
   the duration is `> 2 * longWordMsThreshold`. The "no
   confidence" case is treated as a softer signal — a real
   long word with `confidence = 0` is kept.

**Multi-word low-confidence segments are kept.** A real
Persian phrase is rarely `0`-confidence across every word;
the conservative choice is to surface the segment.

### 3.4 Readability segmenter (`SubtitleSegmenter`)

`SubtitlePostProcessSettings` exposes the four limits:

| Setting | Default | Range | Meaning |
|---|---|---|---|
| `maxCharsPerLine` | 42 | 20..200 | Split a segment when its text would exceed this many characters. |
| `maxSegmentDurationMs` | 4 000 | 1 000..10 000 | Never produce a segment longer than this. |
| `minSegmentDurationMs` | 800 | 100..2 000 | Merge a segment shorter than this with its neighbour. |
| `mergeGapMs` | 200 | 0..1 000 | Maximum gap between a short segment and its neighbour for the merger to consider them a single caption. |

**Algorithm** (greedy + second-pass merge):

1. **Split** each segment's words into bins via greedy
   bin-packing. Flush a bin whenever adding the next word
   would push `text.length > maxCharsPerLine` or
   `endTimeMs - firstWord.startTimeMs > maxSegmentDurationMs`.
2. **Merge** adjacent bins: a segment shorter than
   `minSegmentDurationMs` is merged with the next one when
   the gap is `≤ mergeGapMs` AND the merged text still fits in
   `maxCharsPerLine`. The merged segment inherits the first
   segment's `id`; words concatenate; timing is
   `first.startTimeMs` → `last.endTimeMs`.

**No timestamp is shifted arbitrarily.** Every output
segment's start/end is the start of its first word and the end
of its last word. Every output word is a real input word.

## 4. Timestamp preservation strategy

The brief is explicit: "Do NOT invent any timestamps. The
timeline has to match what Whisper actually heard." The
post-processor guarantees this in three ways:

1. **Per-word normalisation** — `PersianNormalizer` is
   applied to each `TranscribedWord.text` via
   `word.copy(text = normalize(word.text))`. Word `id`,
   `startTimeMs`, `endTimeMs`, and `confidence` are never
   touched.
2. **Filter pass-through** — `RepetitionFilter` and
   `SilenceFilter` only ever **drop** words / segments; they
   never invent a replacement. The flat
   `Transcription.words` list is rebuilt to mirror the
   surviving segments so the segment ↔ word invariant the
   rest of the pipeline relies on is preserved.
3. **Segmenter property** — `SubtitleSegmenter.splitSegment`
   and `mergeTwo` set a segment's start/end to its
   first/last word's real timing. The post-processor never
   stretches a segment to fill a gap.

The `SubtitlePostProcessIntegrationTest` proves the B2 mapper
contract (sort by start, non-empty, `subtitleSource = "ai"`,
`words.isNotEmpty()`) survives the pipeline.

## 5. Threading

`SubtitlePostProcessor.postProcess` is `suspend` and runs on
`Dispatchers.Default` (CPU-bound, no I/O). The existing
`EditorViewModel.generateSubtitles` coroutine already runs on
`Dispatchers.IO`, so the dispatcher hop is free. Compose
recompositions are never blocked. The brief's "must run off
the main thread" rule is satisfied by this combination.

## 6. Configuration

`AppSettings.subtitlePostProcessEnabled: Boolean = true` is
the v1 control surface. When `true`, the
`SubtitlePostProcessSettings.Default` is used (every step on,
every limit at a safe value). When `false`, the
`SubtitlePostProcessSettings.Identity` is used (every step
off, post-processor returns input by reference). The
`SettingsRepository` exposes
`currentSubtitlePostProcessSettings()` which the
`EditorViewModel` reads on every `generateSubtitles` call, so
a user can flip the toggle in settings and the next
generation picks up the new value.

The per-step flags and numeric limits are not surfaced to
the settings UI in v1 (the master toggle is the v1 control).
A future v2 settings UI can split the master toggle into
per-step switches without changing the post-processor.

## 7. Benchmark

### Design

A developer-only entry point that runs **only** as an
instrumentation test (`WhisperModelBenchmarkTest`). No
production UI surface. The brief: "Add a developer/debug-only
benchmark entry point if necessary. Do not expose
experimental benchmark controls to normal users unless the
existing product architecture already has a developer/debug
mode." The product has no developer mode, so the benchmark
lives only in the `androidTest` source set.

### What it does

1. Stages `assets/b15/persian_grafik5.m4a` once into
   `cacheDir`.
2. Iterates four logical model sizes: Tiny, Small, Medium,
   Large-v3.
3. For each spec, probes `AiModels.isVerifiedInstalled`.
   - Tiny + Small are constructed as off-catalog
     `CatalogEntry` instances (the production catalog
     contains only MEDIUM + LARGE_V3 after B1.6). They are
     not on disk on a fresh emulator; the row records
     `note = "not installed"` and is **not** transcribe-attempted.
   - MEDIUM is bundled in `app/src/main/assets/ai-models/...`
     and is always present.
   - LARGE_V3 is downloaded on demand (3.1 GB, gated by
     `DeviceCapabilityDetector` for ≥ 8 GB free RAM,
     arm64-v8a / x86_64, ≥ 4 cores, ≥ 4 GB free storage). On
     a fresh emulator it is `not installed`; the row records
     this honestly.
4. For installed specs, `WhisperEngine.benchmarkTranscribe`
   is called. The method is **self-contained**: it loads the
   spec's GGML file into a *separate* native context,
   transcribes, and frees that context in `finally`. The
   production `nativeContext` and `currentModel` are NOT
   touched. Wall time is captured; RTF = wall time / audio
   duration.
5. Results are logged to logcat under the `B7Benchmark` tag
   and written to
   `cacheDir/whisper_benchmark_report.json` so a developer
   can `adb pull` it.

### Report format

```json
{
  "fixturePath": "file:///.../b15_persian_grafik5.m4a",
  "fixtureDurationMs": 35090,
  "totalWallTimeMs": 0,
  "rows": [
    {
      "modelId": "whisper-tiny",
      "engineId": "whisper",
      "segmentCount": 0,
      "wordCount": 0,
      "emptySegmentCount": 0,
      "processingTimeMs": 0,
      "audioDurationMs": 35090,
      "realTimeFactor": 0.0,
      "averageConfidence": null,
      "note": "not installed",
      "segments": []
    },
    { "modelId": "whisper-medium", ..., "note": "" },
    { "modelId": "whisper-large-v3", ..., "note": "not installed" }
  ]
}
```

### No word error rate

The brief is explicit: "Do NOT claim word error rate unless
a verified ground-truth transcript exists." The project has
no ground-truth transcript for the Persian fixture, so the
benchmark reports only raw counts, timings, and confidence
aggregates — never a WER.

## 8. Tests executed

### JVM unit tests

| Suite | Tests | Failures |
|---|---|---|
| PersianNormalizerTest | 23 | 0 |
| RepetitionFilterTest | 12 | 0 |
| SilenceFilterTest | 11 | 0 |
| SubtitleSegmenterTest | 10 | 0 |
| SubtitlePostProcessSettingsTest | 14 | 0 |
| SubtitlePostProcessorTest | 9 | 0 |
| SubtitlePostProcessIntegrationTest | 9 | 0 |
| **B7 total** | **88** | **0** |
| All JVM (including B1–B6) | 587 | 0 |

Command:
```
./gradlew :app:testDebugUnitTest
```

### Instrumentation

`WhisperModelBenchmarkTest` was added but **was not run on
emulator-5554 from this environment** — the Android SDK on
the host does not have an `emulator/` binary and the
emulator-5554 device is offline. The instrumentation test
compiles cleanly (`./gradlew :app:compileDebugAndroidTestKotlin`
→ BUILD SUCCESSFUL) and follows the same shape as
`WhisperModelAccuracyAuditTest`, which has been verified
multiple times on emulator-5554 in earlier phases. The
test must be re-run on emulator-5554 by the user before
release to populate the benchmark report.

Command (to run on the user's emulator):
```
./gradlew :app:connectedDebugAndroidTest \
  --tests "com.aicreatorstudio.app.ai.WhisperModelBenchmarkTest"
```

Expected behaviour:

- All four `modelId`s appear in the report.
- `whisper-medium` row has `note = ""`, non-zero
  `wordCount`, non-zero `segmentCount`, and a Persian
  character in the text.
- `whisper-tiny` and `whisper-small` rows have
  `note = "not installed"` (they are not in the
  production catalog; the runner is honest about that).
- `whisper-large-v3` has `note = "not installed"` on a
  fresh emulator and `note = ""` on a device that has
  completed the 3.1 GB download.

## 9. Regressions

**None.** The full JVM suite is **587 tests, 0 failures**
after the B7 changes. B1 / B2 / B3 / B4 / B5 / B6
production tests are unchanged and all green. The B2 mapper
contract is preserved (proven by
`SubtitlePostProcessIntegrationTest`).

The Hilt graph is unchanged: `EditorViewModel` already
qualified for Hilt, and the new
`SettingsRepository` injection is resolved through the
existing singleton scope (no `@Provides` change required).

## 10. Remaining accuracy limitations (what B7 does NOT fix)

1. **No punctuation insertion.** B7 never invents a
   Persian comma or question mark; if Whisper returned
   Latin `,` and `?`, the user sees Latin `,` and `?`. A
   follow-up could add a punctuation pass but the brief
   explicitly forbids paraphrasing.
2. **No spelling correction.** A misspelled word Whisper
   produced is preserved. The brief: "the goal is `What
   Whisper actually heard → cleaner Persian
   representation`, not `What an AI thinks the user
   probably meant`."
3. **No language detection.** The post-processor assumes
   the source is Persian-shaped. Multi-language clips
   would get false-positive "repetition collapse" if
   three identical non-Persian words appear. The
   `isPersianScript` guard could be added in a v2
   segmenter.
4. **No punctuation-intervened collapse.** A "word word
   word" with commas between them is left alone
   (`RepetitionFilter` does not look at punctuation
   between words; the rules say only consecutive
   normalised-text matches trigger a collapse).
5. **No WER measurement.** The benchmark has no
   ground-truth transcript for the Persian fixture, so
   the only honest measurement is what the brief
   authorises: counts, timings, confidence aggregates.
6. **Benchmark needs real-device run.** The
   `WhisperModelBenchmarkTest` was added but not run on
   emulator-5554 from this environment. The
   `whisper_benchmark_report.json` is empty until the
   user runs the instrumentation test on a real device.

## 11. Stop point

Per the brief: "STOP AFTER B7. Do not start B8
automatically." B7 is complete; the next phase requires an
explicit user request.

---

**Files touched in B7:** 11 created, 3 modified.
**JVM tests added:** 88. **JVM failures:** 0.
**Regressions:** none.
**End of B7.**
