# AI Video Upscaler — Model Assets

This directory holds optional neural-network weights for the
AI Video Upscaler module (see
`com.aicreatorstudio.app.ai.upscale`). The app ships **without**
any of the files in this directory — both engines gracefully
fall back to Lanczos resampling when the model is absent.

## Files

| File | Engine | Purpose | Size | Required |
| --- | --- | --- | --- | --- |
| `fast_2x.tflite` | `FastUpscaleEngine` | ESPCN/FSRCNN 2x super-resolution, runs on CPU / NNAPI / GPU. | ~50 KB | No |
| `realesrgan_x4.tflite` | `HighQualityUpscaleEngine` | Real-ESRGAN Compact 4x (TFLite port). Falls back to Lanczos 4x when missing. | ~10–30 MB | No |

## How the engines look them up

Each engine tries to `context.assets.open(<path>)` on construction.
If the call throws, `isAvailable` is `false` and the bottom sheet
surfaces a "Model not installed — using fallback" badge.

## Where to get the models

1. **fast_2x.tflite** — convert a public ESPCN 2x checkpoint
   (e.g. `https://github.com/altnames-aa/ESPCN`) to TFLite via
   `tflite_convert`. The conversion recipe is in the
   `scripts/convert_espcn.sh` helper (not shipped). Place the
   resulting file at `app/src/main/assets/models/fast_2x.tflite`.

2. **realesrgan_x4.tflite** — Real-ESRGAN Compact x4 (or
   `realesrgan-anime`) exported to ONNX and re-converted to
   TFLite. Drop the file at
   `app/src/main/assets/models/realesrgan_x4.tflite`.

3. **Build time** — Gradle's `mergeAssets` task picks the files
   up automatically. No manifest changes required.

## Performance expectations

| Profile | Engine | 30 s 1080p clip on Pixel 7 |
| --- | --- | --- |
| Fast Mode (2x) | `FastUpscaleEngine` + Lanczos | ~2 s |
| High Quality (4x) | `HighQualityUpscaleEngine` + Lanczos | ~6 s |
| High Quality (4x) | `HighQualityUpscaleEngine` + TFLite GPU | ~45 s |
| High Quality (4x) | `HighQualityUpscaleEngine` + TFLite CPU | ~5 min |

The engine emits `UpscaleProgress.Running` ticks at ~5–10 Hz so
the bottom-sheet progress bar fills smoothly.

## Privacy

All inference is on-device. No model bytes or frames leave the
phone. The engines are designed to run entirely inside the
process; the bundled TFLite interpreter never opens a network
socket.
