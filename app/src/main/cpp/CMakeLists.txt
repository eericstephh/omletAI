# CMake build for AICreatorStudio's whisper.cpp integration.
#
# Builds the JNI bridge (whisper-jni.cpp) into libwhisper.so and
# statically links the whisper.cpp + ggml sources that live next
# to this file under whisper/ and ggml/ respectively.
#
# The Gradle download task (app/ai-models/download.gradle.kts)
# populates those source trees by downloading the official
# ggerganov/whisper.cpp v1.7.6 tarball and flattening its
# src/ + include/ + ggml/src/ + ggml/include/ subtrees into
# whisper/ + ggml/.

cmake_minimum_required(VERSION 3.22.1)
project(whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# On Android we never build the CPU backends that pull in
# heavy external dependencies (CUDA, Metal, OpenCL, ...).
# Defining GGML_BACKEND_DL + GGML_NO_BACKENDS-style macros
# is unnecessary; we just *exclude* the backend impls from the
# glob by ignoring the .c/.cpp files under per-backend dirs
# like ggml-cuda/, ggml-cann/, ggml-vulkan/, etc.
#
# Listing the C++ sources explicitly (no GLOB) keeps the build
# deterministic and lets the Android Gradle Plugin's CMake
# configure-cache stamp stay stable.
set(WHISPER_SOURCES
    whisper-jni.cpp
    whisper/whisper.cpp
)

set(GGML_SOURCES
    # Core C entry points.
    ggml/ggml.c
    ggml/ggml-alloc.c
    ggml/ggml-quants.c
    # C++ glue that v1.7.6's ggml.c relies on.
    ggml/ggml-backend.cpp
    ggml/ggml-backend-reg.cpp
    ggml/ggml-threading.cpp
    ggml/ggml-opt.cpp
    ggml/gguf.cpp
    # v1.7.6 whisper.cpp uses the new backend API exclusively and
    # looks up the CPU backend by type. The CPU backend lives under
    # ggml/ggml-cpu/ and its minimal "vanilla" build consists of
    # ggml-cpu.c + ggml-cpu.cpp + quants.c (the legacy quants are
    # pulled in via the ggml-cpu/CMakeLists.txt's
    # ggml_add_cpu_backend_variant_impl("") preset). We exclude the
    # AMX, kleidiai, llamafile, and HBM subdirs because they only
    # activate on x86-64 microarchitectures the Android NDK
    # toolchain does not target.
    ggml/ggml-cpu/ggml-cpu.c
    ggml/ggml-cpu/ggml-cpu.cpp
    ggml/ggml-cpu/quants.c
    # NOTE: the generic ggml/ggml-cpu/repack.cpp and the per-arch
    # ggml/ggml-cpu/arch/*/repack.cpp define overlapping symbols;
    # only one of the two can be linked. We choose the per-arch
    # one because Android only targets arm + arm64 and the generic
    # repack.cpp is just a slow fallback.
    ggml/ggml-cpu/traits.cpp
    ggml/ggml-cpu/vec.cpp
    ggml/ggml-cpu/ops.cpp
    ggml/ggml-cpu/binary-ops.cpp
    ggml/ggml-cpu/unary-ops.cpp
    # Per-architecture optimized implementations of the
    # ggml_vec_dot_iq* / ggml_quantize_iq* family. Without these
    # the static `type_traits_cpu` table in ggml-cpu.c has
    # unresolved symbols. Android targets are arm (32-bit) or
    # arm64 (aarch64); the upstream CMakeLists dispatches on
    # CMAKE_SYSTEM_PROCESSOR. We add the ARM subdir unconditionally
    # — clang in the NDK toolchain compiles both archs from the
    # same source via the per-arch preprocessor guards inside
    # arch-fallback.h.
    ggml/ggml-cpu/arch/arm/cpu-feats.cpp
    ggml/ggml-cpu/arch/arm/quants.c
    ggml/ggml-cpu/arch/arm/repack.cpp
)

add_library(whisper SHARED
    ${WHISPER_SOURCES}
    ${GGML_SOURCES}
)

target_include_directories(whisper PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/whisper
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/ggml-cpu
    ${CMAKE_CURRENT_SOURCE_DIR}/ggml/ggml-cpu/arch
)

target_link_libraries(whisper
    log
    android
)

# Persist whisper.cpp flags expected by the upstream build system.
target_compile_definitions(whisper PRIVATE
    WHISPER_BUILD_NO_FFMPEG=1
    GGML_USE_CPU=1
    # Enable every i-quant (IQ2 / IQ3 / IQ4) implementation so the
    # static ggml_type_traits_cpu table in ggml-cpu.c links. The
    # table references the i-quant functions unconditionally even
    # though whisper's tiny model only uses Q4_0 / Q5_0 / Q5_1 /
    # Q8_0; we still need the symbols present at link time. The
    # upstream ggml CMakeLists.txt does the equivalent work via
    # the GGML_CPU_ALL_VARIANTS preset, but that path pulls in
    # x86-only SIMD variants we don't need on Android.
    GGML_IQ2_XXS=1
    GGML_IQ2_XS=1
    GGML_IQ2_S=1
    GGML_IQ3_XXS=1
    GGML_IQ3_S=1
    GGML_IQ4_NL=1
    GGML_IQ4_XS=1
)

# Optimization flags for mobile inference. -fvisibility=hidden is
# important so JNI_OnLoad-style symbols aren't accidentally
# exported across .so boundaries; the JNI bridge itself uses
# JNIEXPORT to mark its public entries.
target_compile_options(whisper PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(whisper PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    # 2026-10-05 — Android 15+ 16 KB page-size support: LOAD segments must
    # be backed to 16384-byte pages or the loader runs the lib in
    # compatibility mode (and Play Console flags the build).
    -Wl,-z,max-page-size=16384
)

# ---------------------------------------------------------------------
# RNNoise (Voice Clean) — classic small-model DSP + GRU denoiser.
#
# Sources are vendored under rnnoise/ by the Gradle download task
# (app/ai-models/download.gradle.kts :downloadRnnoiseSource) from
# the pinned Yellow-Dog-Man/rnnoise snapshot, which is the
# unmodified upstream Xiph code plus a plain CMake/Android.mk
# build. The pre-trained 8-bit GRU weights are baked into
# rnnoise/rnn_data.c, so librnnoise.so is fully offline.
#
# The JNI bridge (rnnoise-jni.cpp) exposes create/destroy/denoise
# to com.aicreatorstudio.app.ai.RnnoiseNative. Built only when the
# vendored sources are present so a fresh checkout without the
# download task still configures.
# ---------------------------------------------------------------------

if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/rnnoise/denoise.c")
    set(RNNOISE_SOURCES
        rnnoise-jni.cpp
        rnnoise/denoise.c
        rnnoise/celt_lpc.c
        rnnoise/kiss_fft.c
        rnnoise/pitch.c
        rnnoise/rnn.c
        rnnoise/rnn_data.c
        rnnoise/rnn_reader.c
    )

    add_library(rnnoise SHARED ${RNNOISE_SOURCES})

    target_include_directories(rnnoise PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise
    )

    target_link_libraries(rnnoise
        log
        android
    )

    # Match the upstream Android.mk flags: float (non-fixed-point)
    # build with the Opus compile glue enabled.
    target_compile_definitions(rnnoise PRIVATE
        COMPILE_OPUS=1
        RNNOISE_BUILD=1
    )

    target_compile_options(rnnoise PRIVATE
        -O3
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
    )
    target_link_options(rnnoise PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        # 16 KB page-size support — same rationale as the whisper target.
        -Wl,-z,max-page-size=16384
    )
endif()