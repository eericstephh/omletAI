---
name: android-build
description: How to build, test and diagnose Gradle errors in this Android project
---
## Commands (Windows: use gradlew.bat)
- Build: `gradlew.bat assembleDebug`
- Unit tests: `gradlew.bat testDebugUnitTest`
- Lint: `gradlew.bat lintDebug`
Run build first; run tests only if the build passes.

## Reading failures
- Find the first line starting with `e: ` (Kotlin) or `> Task ... FAILED`; the root cause is usually the FIRST error, later ones cascade.
- Missing symbol: check imports and dependency in build.gradle.kts / libs.versions.toml.
- Compose errors: check @Composable annotations and Compose compiler/Kotlin version match.
- Never delete tests or disable lint to make the build pass.
