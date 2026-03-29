cmake_minimum_required(VERSION 3.22.1)
project(whisper-jni)

# ──────────────────────────────────────────────
# whisper.cpp JNI bridge for Safe Word Android
# ──────────────────────────────────────────────
#
# Requires whisper.cpp source at app/src/main/cpp/whisper.cpp/
#   git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git app/src/main/cpp/whisper.cpp

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

if(NOT EXISTS "${WHISPER_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_DIR}. "
        "Run: git clone --depth 1 https://github.com/ggml-org/whisper.cpp.git ${WHISPER_DIR}")
endif()

message(STATUS "Building with whisper.cpp from ${WHISPER_DIR}")

# ── CRITICAL: Force Release build for whisper.cpp (10-50x faster than Debug) ──
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG" CACHE STRING "" FORCE)

# Configure whisper.cpp build for Android
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# ── Multi-ISA CPU dispatch (ARMv8.0, ARMv8.2+dotprod, ARMv8.2+i8mm) ──
# Compiles multiple ggml-cpu variant .so files; runtime picks the fastest.
# Requires GGML_BACKEND_DL so variants are dlopen'd, not statically linked.
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)

# ── ARM NEON SIMD acceleration (3-5x faster on ARM devices) ──
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_NEON ON CACHE BOOL "" FORCE)
set(GGML_CPU_AARCH64 ON CACHE BOOL "" FORCE)

# ── OpenMP for parallel GGML ops on ARM64 (10-25% faster multi-threaded) ──
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
else()
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
endif()

# Disable unused backends
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)

# ── Vulkan GPU acceleration (5-10x faster inference on supported devices) ──
# minSdk 33 guarantees Vulkan 1.1+; runtime fallback to CPU if GPU init fails.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# NDK ships vulkan.h (C) but NOT vulkan.hpp (C++ wrapper) which ggml-vulkan requires.
# Fetch the official Vulkan-Headers from Khronos to provide vulkan.hpp.
include(FetchContent)
FetchContent_Declare(
    VulkanHeaders
    GIT_REPOSITORY https://github.com/KhronosGroup/Vulkan-Headers.git
    GIT_TAG v1.4.309
    GIT_SHALLOW TRUE
)
FetchContent_Populate(VulkanHeaders)
set(Vulkan_INCLUDE_DIR "${vulkanheaders_SOURCE_DIR}/include" CACHE PATH "" FORCE)
message(STATUS "Using fetched Vulkan headers: ${vulkanheaders_SOURCE_DIR}/include")

# Help FindVulkan.cmake locate NDK's glslc shader compiler (host tool, not in sysroot)
if(CMAKE_ANDROID_NDK)
    file(GLOB _glslc_candidates "${CMAKE_ANDROID_NDK}/shader-tools/*/glslc*")
    if(_glslc_candidates)
        list(GET _glslc_candidates 0 _glslc_path)
        set(Vulkan_GLSLC_EXECUTABLE "${_glslc_path}" CACHE FILEPATH "" FORCE)
        message(STATUS "Using NDK glslc: ${_glslc_path}")
    endif()
endif()

add_subdirectory(${WHISPER_DIR} whisper.cpp)

# Build the whisper JNI bridge shared library
add_library(whisper-jni SHARED whisper-jni.cpp)
target_link_libraries(whisper-jni whisper ggml log android)
target_include_directories(whisper-jni PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)
target_compile_options(whisper-jni PRIVATE -O3 -DNDEBUG -std=c++17 -fPIC)
