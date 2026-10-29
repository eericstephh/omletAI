---
name: ai-video-upscale-2026-09-01
description: New AI Video Upscaler module (Fast / High Quality / Auto) — sealed UpscaleProfile, two engines, registry, device capability detector, bottom sheet UI.
metadata:
  type: project
---

New `com.aicreatorstudio.app.ai.upscale` package. Coexists with the existing `Media3UpscalerExportEngine` enhancement-export path (different scope: this one is the "2x/4x" engine-driven pipeline, not the preset-based v1 enhancement).

**Why:** The brief asked for a dual-pipeline (Fast / HQ) super-resolution module with auto device-capability selection, a Compose bottom sheet, and engine extensibility.

**How to apply:**
- `UpscaleProfile` is a sealed class (Fast / HighQuality / Auto) — extends cleanly by adding a new data object.
- `UpscaleEngine` interface has `id`, `displayName`, `isAvailable`, `modelStatusText`, `start(...)`. The interface defaults `modelStatusText` to "Engine: <displayName>" so new engines don't need to override.
- `UpscaleProgress` is a sealed class with `Running(stage, percent, elapsedMs)`, `Succeeded(...)`, `Failed(message)`, `Cancelled` (data object).
- `DeviceCapabilityDetector.recommend(Probe)` is on the companion object (pure function) so JVM unit tests don't need an Android Context.
- `UpscaleEngineRegistry` extends `UpscaleEngineRegistryBase` — the base class takes a plain `(UpscaleProfile) -> UpscaleProfile` lambda for the Auto resolver, so tests can construct the registry without Hilt or Context.
- `FastUpscaleEngine` (2x Lanczos) and `HighQualityUpscaleEngine` (4x with optional TFLite) both live in `ai/upscale/`. v1 implementations are synthetic progress emitters — real TFLite/ONNX drop-ins go in `assets/models/fast_2x.tflite` and `assets/models/realesrgan_x4.tflite`.
- Bottom sheet is `UpscaleBottomSheet` in `ui/screens/editor` — fully controlled by `EditorUiState.selectedUpscaleProfile` + `upscaleProgress` + `upscaleEtaMs`.
- ViewModel fields/methods: `onUpscaleSelected(profile)`, `onUpscaleStartClicked()`, `onUpscaleCancel()`, `consumeUpscaleProgress()`, `autoResolvedUpscaleProfile()`. The active job is held in a private `activeUpscaleJob: UpscaleJob?` and cancelled on `onCleared`.
- Hilt bindings live in `UpscaleModule` — the engines list is provided as `@Provides @Singleton List<UpscaleEngine>`.
- `FastUpscaleEngine` `id = "fast"`, `HighQualityUpscaleEngine` `id = "high_quality"`. `UpscaleEngineId.AUTO` is a sentinel only — never registered.

Tests: 4 files in `app/src/test/java/com/aicreatorstudio/app/ai/upscale/`. ~25 test cases total covering profile lookups, engine id round-trips, device capability thresholds, registry routing, and progress data class invariants.
