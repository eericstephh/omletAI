# The R8 / protobuf break — Background Remove on the release build

**Date:** 2026-10-22
**Device:** Xiaomi 2107113SG (`vili_global`), Android 14, arm64-v8a
**App:** 1.4.0 (4) · release build
**Reported by:** the in-app diagnostic report the user shared

---

## 1. What the user saw

The report's latest event:

```
2026-10-22 14:33:29.952 [ERROR] ai_tool/run_failed:
  موتور تشخیص سوژه اجرا نشد: موتور هوش مصنوعی نتوانست مدل را اجرا کند.
  برنامه‌های پس‌زمینه را ببندید و دوباره تلاش کنید ...
  (RuntimeException: Field platform_ for c9.i0 not found.
   Known fields are [public static final c9.i0 c9.i0.e,
                     public static volatile com.google.protobuf.h0 c9.i0.f])
  | tool=matting
```

In English: **Background Remove could not run the model** — and the app
told the user to close background apps and try again, which is not what
happened.

The report also carried two older events (2026-10-20 22:51) from a build
that predates the fixes in this series:

| Time | Event | Status |
|---|---|---|
| 22:51:18 | `crash/uncaught_exception` — `NullPointerException: … ConcurrentHashMap.put(…) on a null object reference` | **Already fixed** on 2026-10-20 (the AI-tool registries moved behind a `by lazy` holder, so a static initialiser can no longer be observed as null). The device was still running the old build. |
| 22:51:48–52 | `ai_tool/run_failed` — "Model integrity check failed" (matting) | The *old* wording. The engine's message was reworded the same day because the model file's SHA-256 **did** match — the integrity check was never the cause. |
| 2026-10-22 14:33 | the protobuf error above | **This document.** The real cause, finally visible because the message now carries the technical detail. |

---

## 2. Root cause

**R8 renamed the fields of MediaPipe's protobuf-generated messages, and
protobuf's runtime resolves a message's fields BY NAME.**

The project's ProGuard file kept two of MediaPipe's packages:

```proguard
-keep class com.google.mediapipe.tasks.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
```

but **not** the protobuf packages. MediaPipe's generated messages live in
five more:

```
com.google.mediapipe.proto
com.google.mediapipe.calculator.proto
com.google.mediapipe.formats.proto
com.google.mediapipe.util.proto
com.google.mediapipe.gpu
```

R8 renamed every one of them. Reading the release mapping proves it:

```
com.google.mediapipe.proto.MediaPipeLoggingProto$SystemInfo -> c9.i0:
```

— `c9.i0` is exactly the class in the error message. **85** protobuf
classes across those five packages were obfuscated, so this was not a
one-class fluke: *every* MediaPipe protobuf message was broken, and the
first one the runtime happened to touch is the one that killed the
feature.

The failure lands inside `ImageSegmenter.createFromOptions(...)`:
protobuf initialises the message class, builds its field table from the
field **names** the generated code carries as strings (`platform_`, …),
finds no such field because R8 renamed it, and throws.

Two details worth recording:

- The error names `com.google.protobuf.h0` — the protobuf runtime's own
  class — so the runtime is intact; only the *message* was broken.
- The "Known fields" list contains only the two **static** members. The
  instance fields are private and were renamed, which is why the lookup
  by name failed.

---

## 3. The fix

```proguard
# 2026-10-22 — THE WHOLE MediaPipe TREE MUST BE KEPT
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.framework.** { *; }

# 2026-10-22 — the protobuf runtime and its message fields
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 { <fields>; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class com.google.firebase.encoders.proto.** { <fields>; }
```

### Verified against the built artefact

The release mapping is the proof — the check is mechanical:

| Package | renamed classes, before | after |
|---|---|---|
| `com.google.mediapipe.**` | **85** | **0** |
| `com.google.protobuf.**` | (renamed) | **0** |
| `org.tensorflow.**` | 0 | 0 |
| `com.googlecode.tesseract.**` | 0 | 0 |
| `com.google.firebase.encoders.proto.**` | renamed | kept (fields) |

`MediaPipeLoggingProto$SystemInfo` no longer appears in the mapping at
all — it keeps its real name and its real fields.

**Size cost:** 94.21 MB → **94.51 MB** (+0.3 MB). Worth it: the
alternative is a feature that silently does not work in the release build
and cannot be reproduced in a debug build.

---

## 4. Why this was invisible until now

- **It only affects the release build.** A debug build is not minified, so
  the classes keep their names and the feature works. Every manual test
  on a debug build therefore passed.
- **The failure is far from the cause.** The engine caught the throwable,
  classified it with `NativeEngineFailure.classify`, and produced the
  generic "close background apps and try again" sentence — a sentence
  about the *device*, for a fault that was in *our build*. The 2026-10-20
  change that started carrying the technical detail into the report is
  what finally made this diagnosable; without it, this bug would still be
  open.

---

## 5. Regression guard

`app/src/test/java/com/aicreatorstudio/app/ai/R8KeepRulesTest.kt` pins:

- the whole MediaPipe tree is kept, and the narrow `tasks.**` rule is
  **not** the only one (its presence is what hid the bug);
- the protobuf runtime and the fields of both message bases are kept;
- the other engines' keep rules (TFLite, Tesseract, Media3, whisper)
  are still present, so a future tidy-up cannot silently break one of
  them the same way.

---

## 6. Files changed

| File | Change |
|---|---|
| `app/proguard-rules.pro` | The keep rules above. |
| `app/src/test/java/com/aicreatorstudio/app/ai/R8KeepRulesTest.kt` (new) | The regression guard. |

## 7. Verification

- **3164 unit tests, 0 failures.**
- Release APK rebuilt; `apksigner` → **v2 verified**; `zipalign -c -P 16`
  → **successful**; 16 KB page check → **0 not aligned**.
- The release mapping shows **0** renamed MediaPipe/protobuf classes.
- **The feature itself has not yet been exercised on the device**: the
  phone was locked when the fix was built, and MIUI blocks both input
  injection and the USB install dialog while the screen is locked. The
  fix is verified at the artefact level (the mapping); the end-to-end
  check — tapping "Background Remove" and watching the engine log
  `matting pass: frames=…` — still needs an unlocked screen.
