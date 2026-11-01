plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// whisper.cpp source is fetched by the downloadAiAssets Gradle task
// (see app/ai-models/download.gradle.kts). The download task
// pre-populates app/src/main/cpp/whisper/ + app/src/main/cpp/ggml/
// from the official ggerganov/whisper.cpp v1.7.6 tarball, and the
// preBuild hook ensures the sources land BEFORE any compile step.
// The presence guard below only skips the native build when the
// download task hasn't run yet (e.g. on a fresh CI checkout before
// the first build).
val whisperSourcesAvailable = file("src/main/cpp/whisper/whisper.cpp").exists() &&
        file("src/main/cpp/ggml/ggml.c").exists()

// 2026-10-04 — release signing credentials moved out of this file (they used
// to be committed as plaintext here). They now live in local.properties
// (git-ignored, machine-local) or environment variables:
//   OMELET_STORE_FILE / OMELET_STORE_PASSWORD / OMELET_KEY_ALIAS / OMELET_KEY_PASSWORD
// If the properties are absent, release builds fail fast with a clear
// message instead of silently falling back to debug signing.
// Minimal key=value parse of local.properties (avoids java.util in the
// script, whose default imports vary by Gradle version).
val keystoreProps: Map<String, String> = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) null else
            line.substring(0, idx).trim() to line.substring(idx + 1).trim()
    }
    ?.toMap()
    ?: emptyMap()
fun signingProp(name: String): String =
    keystoreProps[name]
        ?: System.getenv(name)
        ?: throw GradleException(
            "Release signing credential '$name' is missing. " +
                "Add it to local.properties (never commit it) or export it as an environment variable."
        )

android {
    namespace = "com.aicreatorstudio.app"
    compileSdk = 35
    // The whisper.cpp JNI build needs a modern NDK (26.x+) for Android 7.0+
    // coverage and the C++17 STL we rely on. This machine's pinned
    // 26.3.11579264 folder is a broken/interrupted install (missing
    // source.properties), so we target the fully-installed NDK r30.
    ndkVersion = "30.0.16138531"

    signingConfigs {
        create("release") {
            storeFile = file(signingProp("OMELET_STORE_FILE"))
            storePassword = signingProp("OMELET_STORE_PASSWORD")
            keyAlias = signingProp("OMELET_KEY_ALIAS")
            keyPassword = signingProp("OMELET_KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.aicreatorstudio.app"
        // Minimum required Android version.
        // 24 (Android 7.0) gives broad device coverage while still supporting
        // the codecs/APIs the video pipeline will need later (Media3/MediaCodec).
        minSdk = 24
        targetSdk = 35
        // 2026-09-30 — these two are now READ AT RUNTIME by
        // `AppChangelogProvider` (via the generated `BuildConfig`) to decide
        // whether the Home screen's notification bell shows an unread badge,
        // and to label the changelog sheet's "نسخه جاری".
        //
        // They are therefore no longer inert build metadata: bumping
        // `versionCode` is what makes the bell light up for users on an older
        // build. The changelog itself lives in
        // `data/AppChangelogProvider.kt` and its newest entry must carry the
        // SAME versionCode — `AppChangelogProviderTest` fails the build if the
        // two drift apart.
        // 2026-11-01 — store release «1.1». versionCode is still the
        // monotonic build number: the old 1.1.0–1.4.0 dev line shipped as
        // codes 1–4, so this release MUST be 5 or the installed base
        // (code 4) would refuse the update and the bell would never light
        // up for it (`hasUnreadUpdates` compares codes, not names).
        versionCode = 5
        versionName = "1.1"

        // 2026-09-16 Tapsell Mediation — the SDK reads its app key from
        // the `ir.tapsell.mediation.APPLICATION_KEY` manifest meta-data
        // (declared in the SDK's own manifest as a placeholder).
        // 2026-09-29 — real key from the Tapsell console. This is the value
        // the SDK ACTUALLY reads; `TapsellAds.APP_KEY` is only what the
        // "configured" guard checks, so the two must stay in sync. A mismatch
        // fails confusingly (SDK uninitialised, or initialised with a key the
        // console does not know).
        manifestPlaceholders["TapsellMediationAppKey"] =
            "gpperaotjlhstpjinpeqtsjmpqcdgkrothifljdgthfplnmmhkjbmprsqehstdcnlositc"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Native (JNI) build for whisper.cpp. The library is built
        // from app/src/main/cpp/whisper-jni.cpp + the vendored
        // whisper.cpp sources under app/src/main/cpp/whisper/.
        // The download task populates those sources on every clean
        // build (see app/ai-models/download.gradle.kts); the
        // `whisperSourcesAvailable` flag is a fast-path that only
        // skips the native build on a fresh checkout before the
        // download task has run.
        if (whisperSourcesAvailable) {
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                    arguments += listOf("-DANDROID_STL=c++_shared")
                }
            }
            ndk {
                // 2026-10-11 ABI AUDIT — arm64-v8a ONLY, and that is
                // deliberate, not an oversight.
                //
                // The previous comment here claimed three ABIs; it was
                // stale and directly contradicted the line beneath it.
                // The 2026-10-11 whisper compatibility audit verified
                // the built APK contains .so files under
                // `lib/arm64-v8a/` exclusively (14 native libraries),
                // so advertising armeabi-v7a or x86_64 was false
                // advertising: a 32-bit or x86 device would install
                // nothing and fail at `System.loadLibrary("whisper")`.
                //
                // Why only arm64-v8a is kept:
                //  - whisper-small's f16 weights are ~488 MB and its
                //    working set is ~1.6 GB; the 32-bit address space
                //    cannot host the ggml pool reliably, so an
                //    armeabi-v7a build would ship a binary that fails
                //    on the very devices it targets.
                //  - Every supported Android phone from ~2017 on is
                //    64-bit. Google Play has required 64-bit since
                //    2019.
                // Raising the floor to arm64-v8a was therefore already
                // the de-facto situation; this comment now matches it.
                //
                // Do NOT add an ABI here without also verifying that
                // abi/CMakeLists.txt builds it and that the model
                // working set is addressable on it.
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    if (whisperSourcesAvailable) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // 2026-09-30 — required by `AppChangelogProvider`, which reads
        // `BuildConfig.VERSION_CODE` / `VERSION_NAME` so the "what's new"
        // badge and the sheet's version label come from the real build
        // metadata instead of a hand-copied literal that can go stale.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // Lets `android.net.Uri` and similar framework stubs return
        // `null` instead of throwing "not mocked" when exercised from
        // JVM unit tests. Without this, a single test that touches
        // `Uri.parse`/`fromFile` will fail the whole unit-test task.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        checkDependencies = false
        disable += "all"
    }

    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            variant.outputs.all {
                val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
                outputImpl?.outputFileName = "AICreatorStudio-v${variant.versionName}-release.apk"
            }
        }
    }
}

dependencies {
    // Core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose UI (Jetpack Compose + Material 3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Navigation (single graph today, ready for more screens later)
    implementation(libs.androidx.navigation.compose)

    // Dependency injection
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // Coroutines for async work (video processing, AI calls, etc.)
    implementation(libs.kotlinx.coroutines.android)

    // 2026-09-16 Tapsell Mediation — rewarded video ads gating the
    // cloud Whisper subtitle feature. Official coordinates per the
    // mediation sample (github.com/tapsellorg/TapsellMediation-
    // AndroidSample): the `ir.tapsell:tapsell` facade pulls the
    // mediation core (`ir.tapsell.mediation:tapsell`) + adapters.
    // NOTE: `ir.tapsell.mediation:tapsell-sdk-android` does not exist
    // as a published artifact — this is its real equivalent.
    implementation("ir.tapsell:tapsell:1.4.0-alpha03")

    // Media3 - reserved for the future video playback / processing module.
    // Included now so the "video" package has a supported foundation to build on.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.effect)

    // Room Database for project persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // TensorFlow Lite for offline AI
    // 2026-10-05 — 2.17.0: 16 KB page-size alignment (Android 15+).
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4") {
        // 2026-10-05 — tflite 2.17 ships the renamed LiteRT artifacts;
        // the support lib still pins tensorflow-lite-api 2.13, whose
        // classes duplicate litert-api 1.0.1. LiteRT provides the same
        // org.tensorflow.lite API, so drop the stale duplicate.
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }
    implementation("org.tensorflow:tensorflow-lite-gpu:2.17.0")

    // MediaPipe Tasks Vision - on-device image segmentation used by
    // the Background Remove (matting) tool. The library bundles the
    // native segmenter binary; no NDK build is required.
    implementation(libs.mediapipe.tasks.vision)

    // Tesseract4Android (Tesseract 5 + Leptonica, prebuilt .so for
    // arm64-v8a / armeabi-v7a / x86 / x86_64) — the on-device OCR
    // engine for AI OCR. Real native inference, fully offline. The
    // traineddata language packs are NOT bundled in the APK; they
    // are downloaded + SHA-verified at runtime via models.lock.json
    // (entries tesseract_fas / tesseract_ara / tesseract_eng).
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    // ONNX Runtime — Silero VAD for automatic silence detection/cutting
    // 2026-10-05 — bumped 1.18.0 → 1.20.0 for 16 KB page-size alignment
    // (Android 15+); the Silero VAD API surface is unchanged.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    // ------------------------------------------------------------------
    // OpenCV — Motion Tracking appearance tier (TrackerCSRT / TrackerKCF).
    //
    // 2026-09-28. Gated behind a property so the build cannot be broken
    // by an artifact-resolution failure: the `opencv` artifact on Maven
    // Central does not reliably ship the **contrib** `tracking` module,
    // and the module is what contains TrackerCSRT/TrackerKCF.
    //
    //   ./gradlew assembleDebug                     -> NCC fallback only
    //   ./gradlew assembleDebug -PwithOpenCv=true   -> OpenCV tier enabled
    //
    // The code reads OpenCV **reflectively** (see
    // ai/tracking/OpenCvRoiTracker.kt), so:
    //   - building without this dependency compiles cleanly, and
    //   - running on a device where the tracking module is absent
    //     degrades to the pure-Kotlin NCC tracker instead of crashing.
    //
    // To enable: add `withOpenCv=true` to gradle.properties, OR vendor
    // the contrib build into app/src/main/jniLibs/arm64-v8a/ (matching
    // the existing abiFilters) and drop the dependency entirely — the
    // reflective loader finds it either way.
    // ------------------------------------------------------------------
    if (project.findProperty("withOpenCv")?.toString() == "true") {
        implementation("org.opencv:opencv:4.10.0")
    }

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM-side org.json so ModelRegistry.parse() works in unit tests
    // (Android bundles org.json in android.jar, but the JVM unit-test
    // classpath doesn't have it). Version matches the implementation
    // org.json shipped in Android 14+.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// ---------------------------------------------------------------------
// AI model download task
// ---------------------------------------------------------------------
// Registers the downloadAiAssets Gradle task that fetches whisper.cpp
// source + every AI model weight from the official publishers, SHA-256
// verifies each artifact against models.lock.json, populates
// app/src/main/cpp/{whisper,ggml}/ and app/src/main/assets/ai-models/,
// and generates the runtime manifest. The task hooks preBuild +
// mergeDebugAssets + mergeReleaseAssets so a single `./gradlew assembleDebug`
// does everything end-to-end.
//
// Invoked by:   ./gradlew downloadAiAssets
// Hooked into:  preBuild, mergeDebugAssets, mergeReleaseAssets
// ---------------------------------------------------------------------
apply(from = "ai-models/download.gradle.kts")
