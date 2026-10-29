pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — Tesseract4Android (libtesseract.so + libleptonica.so
        // prebuilt for arm64-v8a / armeabi-v7a / x86 / x86_64) is published
        // here and nowhere else. Required by the offline AI OCR engine.
        maven { url = uri("https://jitpack.io") }
        // 2026-09-16 Tapsell — app-open interstitial ads. Official
        // Tapsell repo per docs (mediation artifacts also resolve
        // from Maven Central).
        maven { url = uri("https://repo.tapsell.ir/repository/tapsell-plus-maven/") }
    }
}

rootProject.name = "AI Creator Studio"
include(":app")
