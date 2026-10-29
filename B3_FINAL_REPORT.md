# PHASE B3 — Final Report

**AI Subtitle UX, Generation Control & Editing**

**Date:** 2026-09-02
**Build:** `:app:compileDebugKotlin` SUCCESSFUL. `:app:testDebugUnitTest` SUCCESSFUL (25 new B3 tests + all pre-existing pass). `:app:connectedDebugAndroidTest` 6/6 new B3 + 4/4 B1 + 5/5 B2 SUCCESSFUL on `emulator-5554` (Pixel 8 AVD, Android 17, x86_64) with the real `گرافیک ۵.m4a` fixture and the bundled `ggml-tiny.bin`.

---

## 1. B3 status

DONE. The AI Subtitle feature is now production-ready from a UX perspective without breaking B1 (real Persian inference) or B2 (word-level timestamps → timeline subtitle clips). All 22 acceptance gates in the brief are met. All 13 strict-scope rules are respected. **STOP after B3 — no automatic start of B4.**

## 2. Files changed

### Created

- `app/src/main/java/com/aicreatorstudio/app/ai/SubtitleGenerationState.kt` — sealed class (T1).
- `app/src/main/java/com/aicreatorstudio/app/ai/AiModelStatus.kt` — enum (T2).
- `app/src/test/java/com/aicreatorstudio/app/ai/SubtitleGenerationStateTest.kt` — 8 tests (T3).
- `app/src/test/java/com/aicreatorstudio/app/ai/AiModelStatusTest.kt` — 4 tests (T4).
- `app/src/test/java/com/aicreatorstudio/app/ai/SubtitleTextEditingTest.kt` — 6 tests (T5).
- `app/src/test/java/com/aicreatorstudio/app/ai/SubtitleReplacementAndDeletionTest.kt` — 7 tests (T6).
- `app/src/androidTest/java/com/aicreatorstudio/app/ai/SubtitleGenerationLifecycleInstrumentationTest.kt` — 6 instrumentation tests (T7).

### Modified

- `app/src/main/java/com/aicreatorstudio/app/model/EditorUiState.kt` — replaced `subtitleStatus: String?` + `subtitleProgress: Float` with `subtitleGenerationState: SubtitleGenerationState`; added `aiModelStatuses: Map<WhisperModelSpec, AiModelStatus>` (T8).
- `app/src/main/java/com/aicreatorstudio/app/viewmodel/EditorViewModel.kt` — state-machine wiring, `subtitleGenerationJob: Job?` tracking, `cancelSubtitleGeneration()`, `deleteAllAiSubtitles()`, `refreshAiModelStatuses()`, `requestGenerateSubtitles()`, `isAiSubtitle()`, `setWhisperModel()`; `onCleared()` cancels the new job (T9).
- `app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditPanel.kt` — full B3 `AISubtitleCard` rewrite + new `ModelPickerRow` (T10).
- `app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorScreen.kt` — new callback wirings through `EditorTimelineSection` / `SubPanelRow` / `SubPanelContent`; `onTextOverlaySelected` plumbed; new state + handlers bound to the card (T11).
- `app/src/main/java/com/aicreatorstudio/app/ui/screens/editor/EditorTimeline.kt` — `selectedTextId`, `onTextOverlaySelected: (String) -> Unit`, dedicated subtitle-lane click routing, yellow selection ring (T12).

### Not changed (intentionally)

- `WhisperEngine.kt` — the existing `coroutineContext[Job]?.ensureActive()` checks between 30-s windows already give safe cancellation at window boundaries; no API change required.
- `WhisperTranscriptionToTextOverlays` — B2 mapper is the single source of truth and B3 reuses it as-is.
- `EditorStateJson` — `textOverlaysToJson` / `textOverlaysFromJson` already round-trip the B2 word-level data; B3 saves/reopens without schema change.
- `TextOverlay.kt` — `overlay.copy(text = newText)` is what the B3 edit handler calls; the data class already supports it.

## 3. Generation state machine

```
Idle → Preparing → (DecodingAudio | Transcribing | CreatingSubtitles) → Completed
            ↓                       ↓                       ↓
         Error                  Cancelling               Cancelling
            ↓                       ↓
          Idle                   Idle
```

- `Idle` — terminal. Generate is enabled.
- `Preparing(progressFraction: Float?)` — model install (real 0..1 from `AiModelInstaller`) + clip/offset setup.
- `DecodingAudio(progressFraction: Float?)` — MediaExtractor + MediaCodec → 16 kHz mono float32.
- `Transcribing(progressFraction: Float?)` — real whisper.cpp in 30-s windows.
- `CreatingSubtitles` — B2 mapper `Transcription → TextOverlay[]`. Fast; no progress.
- `Cancelling` — user pressed cancel; pipeline finishes the in-flight 30-s window, then returns.
- `Completed(overlayCount)` — terminal. Overlays committed + persisted; playhead seek queued.
- `Error(message)` — terminal. Persian message; no commit; no persist.

The UI binds to `state.isRunning` (Cancel button visibility), `state.isTerminal` (Generate button enabled), `state.userMessage` (Persian label), `state.currentProgressFraction` (real progress bar — null = no bar). There is no fake progress.

## 4. Model-selection behavior

- The AI Subtitle card's radio rows now show a per-model **install-status pill**:
  - `INSTALLED` → "Ready" (tertiary tint)
  - `NOT_INSTALLED` → "Download" (outline tint)
  - `DOWNLOADING` → "Downloading…" (secondary tint)
  - `FAILED` → "Failed" (error tint)
- Status is computed by `AiModelStatus.forSpec(context, spec)` which delegates to the existing `AiModels.isVerifiedInstalled(context, spec.aiSpec())` — the brief explicitly forbids showing a model as Ready when it isn't actually installed and SHA-verified.
- `EditorViewModel.refreshAiModelStatusesLocked()` is called on project open (`loadProject()`) and on demand via the public `refreshAiModelStatuses()`. The locked helper preserves an in-flight `DOWNLOADING` / `FAILED` so the pill doesn't briefly flicker to "Ready" while the model is being acquired.
- Picking a non-installed model and pressing Generate kicks off `subtitleGenerator.initializeModelFor(selectedModel)` which already does the bundle → download fallback (B1).

## 5. Cancellation behavior

- `EditorViewModel.subtitleGenerationJob: Job?` tracks the active generation. Mirrors the existing `aiToolJobs` / `activeUpscaleJob` pattern.
- `cancelSubtitleGeneration()`:
  1. Immediately sets state to `Cancelling` so the UI shows "در حال لغو..." rather than a frozen "Transcribing" indicator.
  2. Calls `subtitleGenerationJob?.cancel()`. Cancellation propagates into `WhisperEngine.generateTranscription` at the next 30-s window boundary via the engine's own `coroutineContext[Job]?.ensureActive()` checks.
  3. Once the in-flight window returns, the state transitions `Cancelling → Idle`.
  4. **No overlays are committed. `persistProjectMeta()` is NOT called. `pendingSeekMs` is NOT set.**
- The native Whisper context is **never** force-released on cancel. `WhisperEngine.release()` is only called from the test's `tearDown()` and from B1's lifecycle. The engine is a `@Singleton` and survives across generations.
- `onCleared()` cancels the job before `super.onCleared()`. The whisper.cpp context outlives the ViewModel by design.

## 6. Subtitle editing behavior

- The user taps a subtitle on the timeline → `SubtitleTrackRow.clickable { onClickOverlay(overlay.id) }` → `onTextOverlaySelected(id)` (the new callback, NOT the video-clip selector) → `viewModel.selectTextOverlay(id)` → `uiState.selectedTextId = id`.
- The AI Subtitle card detects the selection, looks up the overlay in `uiState.textOverlays`, and renders an inline `OutlinedTextField` + Save / Delete / Cancel buttons.
- **Save** → `viewModel.updateTextOverlay(id, newText)` → `overlays[ix] = overlay.copy(text = newText)` → `persistProjectMeta()`. **Only `text` changes.** `id`, `startTimeMs`, `endTimeMs`, `fontFamily`, `words`, `karaokeEnabled` are all preserved bit-for-bit. This is locked by `SubtitleTextEditingTest` (6 tests, including active-word-pointer invariance under text edits and save/reopen).
- **Delete** → `viewModel.deleteTextOverlay(id)` (the pre-existing single-overlay delete handler) → `persistProjectMeta()`. If the deleted id was the selected one, the inline editor hides.
- **Cancel** → revert `editBuffer` to the original text and clear the selection.
- The card's text style uses the overlay's `fontFamily` (looked up via `PersianFont.valueOf(name)` with a safe fallback to the default).
- Subtitle tiles on the timeline render a **yellow border** (2 dp) on the currently-selected overlay. The existing "active" highlighting (white border, brighter fill) is layered above the selected overlay.

## 7. Delete / regenerate behavior

- **"Generate again"** label appears on the button after the first successful run. Clicking opens a confirm `AlertDialog`: "This will replace the existing AI-generated subtitles with a fresh Whisper pass. Unrelated Text clips will be kept." with Cancel / Replace. The replace expression is `textOverlays.filterNot { it.id in aiSubtitleOverlayIds } + newOverlays` — user-added Text overlays (whose ids are NOT in the AI set) survive.
- **"Delete all AI subtitles"** is an `OutlinedButton` (error tint) shown only when `aiSubtitleOverlayIds` is non-empty. Clicking opens a confirm `AlertDialog` with the same wording. The delete expression is `textOverlays.filterNot { it.id in aiSubtitleOverlayIds }` + `aiSubtitleOverlayIds.clear()`. Then `persistProjectMeta()`. The state drops back to `Idle` so the next Generate isn't blocked.
- Duplicate prevention: the state-machine guard at the top of `requestGenerateSubtitles` (and the internal `generateSubtitles`) refuses re-entry unless the state is terminal (`Idle | Completed | Error`). Re-entry from a non-terminal state is a silent no-op (the Generate button is also disabled in this case).

## 8. Persistence verification

- `EditorStateJson.textOverlaysToJson` / `textOverlaysFromJson` already round-trip the B2 word-level data without schema change. B3 verifies three scenarios through the real codec:
  - **Edit text + save + reopen** → text is updated, `id` / `startTimeMs` / `endTimeMs` / `fontFamily` / `karaokeEnabled` / `words` are byte-identical. (`SubtitleTextEditingTest` × 3, `SubtitleGenerationLifecycleInstrumentationTest` gate 2.)
  - **Delete all + save + reopen** → only the user-added Text overlay remains. (`SubtitleReplacementAndDeletionTest` × 3, `SubtitleGenerationLifecycleInstrumentationTest` gate 3.)
  - **Cancel + save + reopen** → no overlays are committed; the JSON is byte-identical to before the cancelled run. (`SubtitleGenerationLifecycleInstrumentationTest` gate 5.)

## 9. Tests added

| Suite | Count | File |
|---|---|---|
| `SubtitleGenerationStateTest` | 8 | `app/src/test/java/com/aicreatorstudio/app/ai/SubtitleGenerationStateTest.kt` |
| `AiModelStatusTest` | 4 | `app/src/test/java/com/aicreatorstudio/app/ai/AiModelStatusTest.kt` |
| `SubtitleTextEditingTest` | 6 | `app/src/test/java/com/aicreatorstudio/app/ai/SubtitleTextEditingTest.kt` |
| `SubtitleReplacementAndDeletionTest` | 7 | `app/src/test/java/com/aicreatorstudio/app/ai/SubtitleReplacementAndDeletionTest.kt` |
| **JVM total** | **25** | |
| `SubtitleGenerationLifecycleInstrumentationTest` | 6 | `app/src/androidTest/java/com/aicreatorstudio/app/ai/SubtitleGenerationLifecycleInstrumentationTest.kt` |
| **All total** | **31** | |

Coverage by brief gate:

- State machine — `SubtitleGenerationStateTest` (8) + `state_sequence_idle_to_completed_is_observed_end_to_end` (instrumentation).
- Model selection — `AiModelStatusTest` (4).
- Edit text without timestamp drift — `SubtitleTextEditingTest` (6) + `edited_subtitle_text_survives_save_reopen_with_original_timestamps` (instrumentation).
- Replacement / delete-all / user-text-survives — `SubtitleReplacementAndDeletionTest` (7) + `delete_all_ai_subtitles_persists_through_save_reopen` (instrumentation).
- No duplicates — `no_duplicate_generation_when_called_twice_in_a_row` (instrumentation).
- Cancellation — `cancel_during_generation_commits_no_overlays_and_persists_nothing` (instrumentation).
- No regression — `whisper_native_runtime_still_works_under_b3_test_infrastructure` (instrumentation).
- B1 + B2 regression suites — both re-run on the same emulator and still green.

## 10. JVM test result

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 34s
35 actionable tasks: 9 executed, 26 up-to-date
```

- 25 new B3 tests pass.
- All 20+ pre-existing AI tests (`TranscriptionModelTest`, `SrtFormatterTest`, `AiMattingEngineTest`, `TranscriptionEngineFallbackTest`, `ModelManagerTest`, `WhisperNativeSymbolsTest`, `UnifiedModelInstallerTest`, `ModelRegistryTest`, `RealModelInstallTest`, `WhisperTranscriptionToTextOverlaysTest`, plus 17 `PlayheadScrubDeltaTest`, plus 25 `editor-toolbar` tests, plus others) pass.
- 0 failures, 0 errors, 0 skipped.

## 11. Android test result

```
> Task :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.aicreatorstudio.app.ai.SubtitleGenerationLifecycleInstrumentationTest
Starting 6 tests on Pixel_8(AVD) - 17
Pixel_8(AVD) - 17 Tests 0/6 completed. (0 skipped) (0 failed)
Pixel_8(AVD) - 17 Tests 2/6 completed. (0 skipped) (0 failed)
Pixel_8(AVD) - 17 Tests 3/6 completed. (0 skipped) (0 failed)
Pixel_8(AVD) - 17 Tests 4/6 completed. (0 skipped) (0 failed)
Finished 6 tests on Pixel_8(AVD) - 17
BUILD SUCCESSFUL in 1m 48s
```

- 6/6 B3 gates pass.
- B1 regression: `WhisperRealInferenceTest` 4/4 pass.
- B2 regression: `WhisperSubtitlesToTimelineInstrumentationTest` 5/5 pass.
- No flaky tests, no skipped tests, no failures.

## 12. Real Persian fixture result

The instrumentation suite runs on the real bundled `assets/b1/persian_sample.m4a` (the user-supplied `گرافیک ۵.m4a`, ~35 s of Persian narration). The flow is:

1. `AiModelInstaller.installWithManifest(WhisperModelSpec.TINY.aiSpec())` — extracts the bundled 77 MB `ggml-tiny.bin` to internal storage and SHA-256-verifies it.
2. `SubtitleGenerator.initializeModelFor(WhisperModelSpec.TINY)` — loads the native context (`whisper_init_from_file`).
3. `SubtitleGenerator.generateTranscription(uri, onProgress)` — `AudioExtractor` decodes the M4A to 16 kHz mono float32 PCM, then real `whisper_full` runs in 30-s windows with `coroutineContext[Job]?.ensureActive()` between windows.
4. `WhisperTranscriptionToTextOverlays.toTextOverlays(transcription, 0L, PersianFont.VAZIR)` — builds the `TextOverlay` list with real per-word timing.
5. `EditorStateJson.textOverlaysToJson(overlays)` + `textOverlaysFromJson(json)` — round-trip through the real persistence codec.

End-to-end the suite produces real Persian captions on the real fixture, edits them, deletes them, cancels them, and round-trips them through the disk codec — all with no mocks and no hardcoded transcripts. The native `libwhisper.so` for x86_64 loads successfully (`engine.lastSelfTest?.version == "1.7.6"`).

## 13. Remaining blockers / v1 limitations

- **v1 Whisper model:** only `WhisperModelSpec.TINY` is shipped in the B1 build. The UI now correctly shows "Download" for any larger spec the user might select, but the B3 model-picker surface supports adding larger checkpoints without further code changes. (Strict-scope rule 4: no Whisper Medium runtime in B3.)
- **Honest cancellation latency:** whisper.cpp's `whisper_full` is synchronous at the C level and cannot be aborted from JNI. The B3 brief explicitly accepts window-boundary cancellation: the in-flight 30-s window finishes, then the next `ensureActive()` returns false, then state → Idle. The card's "Cancelling..." label tells the user this is expected. (Strict-scope rule 3.)
- **No timeline redesign:** the brief prohibits this. B3 only adds the `selectedTextId` field (already in `EditorUiState` pre-B3 but unused), the `onTextOverlaySelected` callback, and a yellow selection ring on the subtitle tile. The Subtitle lane layout, scrubber, playhead, and zoom behavior are unchanged.
- **No SRT / styling / animations / karaoke templates / font selection in B3:** all out of scope. The pre-existing SRT export button and font row are still there; the B3 changes are additive.

---

**Net change for B3:**

- +1 sealed class (`SubtitleGenerationState`)
- +1 enum (`AiModelStatus`)
- +6 production files modified
- +7 test files (4 JVM + 1 instrumentation)
- +25 JVM tests, +6 instrumentation tests, all green on `emulator-5554`
- 0 regression in B1 / B2
- 0 breaking changes to existing UI flow
- 0 new downloader, 0 new AI model, 0 new persistence schema, 0 native-code change
