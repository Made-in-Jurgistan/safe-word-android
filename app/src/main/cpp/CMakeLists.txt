cmake_minimum_required(VERSION 3.22.1)
project(safeword-moonshine CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─── ONNX Runtime path override ───────────────────────────────────────────────
# moonshine-core's third-party/onnxruntime/lib/android/arm64/ directory is
# intentionally empty. Point cmake to the libonnxruntime.so already extracted
# from the onnxruntime-android AAR into jniLibs.
#
# MUST be set BEFORE add_subdirectory so that the downstream script
#   moonshine-core/core/third-party/onnxruntime/find-ort-library-path.cmake
# (which uses `set(... CACHE INTERNAL "")` without FORCE) cannot override it.
set(ONNXRUNTIME_LIB_PATH
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libonnxruntime.so"
    CACHE INTERNAL "" FORCE)

# moonshine-core links the TTS ONNX module (moonshine_tts_ort) unconditionally on
# Android. MOONSHINE_TTS_BUILD_ONNX=ON is therefore required to satisfy that link —
# Safe Word does not invoke TTS at runtime. Because ONNXRUNTIME_LIB_PATH is set
# above, moonshine-tts-onnxruntime.cmake reuses our pre-built libonnxruntime.so
# and skips the host-side library discovery that would fail on Android.
set(MOONSHINE_TTS_BUILD_ONNX ON CACHE BOOL "" FORCE)

# ─── moonshine-core (open-source STT core library) ────────────────────────────
add_subdirectory(
    moonshine-core/core
    ${CMAKE_CURRENT_BINARY_DIR}/moonshine-core-build)

# moonshine-core's build system unconditionally adds the SDK JNI bridge target
# (android/moonshine-jni → libmoonshine-jni.so) on Android. That target belongs
# to the commercial ai.moonshine:moonshine-voice AAR namespace; exclude it so
# Gradle does not package it alongside our own bridge.
if(TARGET moonshine-jni)
    set_target_properties(moonshine-jni PROPERTIES EXCLUDE_FROM_ALL TRUE)
endif()

# ─── Android dead-code stub injection ─────────────────────────────────────────
# moonshine-model.cpp, word-alignment.cpp, and cosine-distance.cpp are excluded
# from MOONSHINE_SRC_FILES on Android (see moonshine-core/core/CMakeLists.txt).
# transcriber.cpp still compiles the non-streaming else-branches which reference
# MoonshineModel symbols, so we inject minimal linker stubs here instead.
set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/moonshine-core/core)
target_sources(moonshine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/moonshine-android-stubs.cpp)
target_include_directories(moonshine PRIVATE ${CORE_DIR})

# Workaround: NDK 28.2 clang 19.0.1 (LLVM 19) crashes with a frontend SIGSEGV
# generating standalone DWARF debug info for some moonshine-tts C++20 lang-specific
# files (confirmed on turkish.cpp; potentially others). -fno-standalone-debug switches
# from DW_AT_inline "abstract origins" to line-table-only DWARF, which avoids the
# crash. Safe Word never debugs TTS C++ directly, so this has zero runtime impact.
if(TARGET moonshine_tts_core)
    target_compile_options(moonshine_tts_core PRIVATE -fno-standalone-debug)
endif()

# ─── Safe Word JNI bridge ─────────────────────────────────────────────────────
add_library(moonshine-bridge SHARED moonshine-bridge.cpp)

# Release optimizations: LTO + hidden visibility to reduce .so size and improve
# inlining across translation units. Only applied for non-Debug builds.
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    set_property(TARGET moonshine-bridge PROPERTY INTERPROCEDURAL_OPTIMIZATION ON)
    target_compile_options(moonshine-bridge PRIVATE -fvisibility=hidden)
endif()

# moonshine headers are PRIVATE in core/CMakeLists.txt; add them explicitly so
# moonshine-c-api.h is visible to our translation unit.
target_include_directories(moonshine-bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/moonshine-core/core)

target_link_libraries(moonshine-bridge PRIVATE moonshine log)
