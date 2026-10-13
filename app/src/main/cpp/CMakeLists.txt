# ClearKit: builds llama.cpp (CPU-only, arm64) + mtmd vision + JNI bridge into libllama.so
cmake_minimum_required(VERSION 3.22)
project(clearkit_llama CXX C)

set(LLAMA_CPP_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp"
    CACHE PATH "llama.cpp source directory (override with -DLLAMA_CPP_SRC=...)")

if (NOT EXISTS "${LLAMA_CPP_SRC}/include/llama.h")
    message(FATAL_ERROR
        "llama.cpp not found at ${LLAMA_CPP_SRC}. "
        "Clone with submodules: git clone --recurse-submodules <url> "
        "or run: git submodule update --init --depth 1 third_party/llama.cpp")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# llama core (mirrors src/CMakeLists.txt)
file(GLOB LLAMA_SOURCES
    "${LLAMA_CPP_SRC}/src/*.cpp"
    "${LLAMA_CPP_SRC}/src/models/*.cpp")

# ggml core (mirrors ggml-base target)
set(GGML_SOURCES
    "${LLAMA_CPP_SRC}/ggml/src/ggml.c"
    "${LLAMA_CPP_SRC}/ggml/src/ggml.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-alloc.c"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-backend.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-backend-meta.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-opt.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-threading.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-quants.c"
    "${LLAMA_CPP_SRC}/ggml/src/gguf.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-backend-reg.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-backend-dl.cpp"
    "${LLAMA_CPP_SRC}/vendor/hash/hash.cpp"
    "${LLAMA_CPP_SRC}/vendor/hash/sha256/sha256.c"
    # ggml-cpu backend, ARM single-variant (mirrors ggml-cpu CMake for ARM)
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/ggml-cpu.c"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/ggml-cpu.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/repack.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/hbm.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/quants.c"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/traits.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/binary-ops.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/unary-ops.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/vec.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/ops.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/amx/amx.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/amx/mmq.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/tiled/tiled.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/tiled/tiled-kernel.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/arch/arm/quants.c"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/arch/arm/repack.cpp"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu/arch/arm/cpu-feats.cpp")

# mtmd multimodal (image path only; audio/video compiled out by non-use)
file(GLOB MTMD_MODEL_SOURCES "${LLAMA_CPP_SRC}/tools/mtmd/models/*.cpp")
set(MTMD_SOURCES
    "${LLAMA_CPP_SRC}/tools/mtmd/mtmd.cpp"
    "${LLAMA_CPP_SRC}/tools/mtmd/mtmd-image.cpp"
    "${LLAMA_CPP_SRC}/tools/mtmd/mtmd-audio.cpp"
    "${LLAMA_CPP_SRC}/tools/mtmd/mtmd-helper.cpp"
    "${LLAMA_CPP_SRC}/tools/mtmd/clip.cpp"
    ${MTMD_MODEL_SOURCES})

add_library(llama SHARED
    bridge.cpp
    ${LLAMA_SOURCES}
    ${GGML_SOURCES}
    ${MTMD_SOURCES})

# ggml needs this on Linux/Android for cpu_set_t et al (mirrors ggml CMake)
add_compile_definitions(_GNU_SOURCE)

target_include_directories(llama PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    "${LLAMA_CPP_SRC}/include"
    "${LLAMA_CPP_SRC}/src"
    "${LLAMA_CPP_SRC}/ggml/include"
    "${LLAMA_CPP_SRC}/ggml/src"
    "${LLAMA_CPP_SRC}/ggml/src/ggml-cpu"
    "${LLAMA_CPP_SRC}/tools/mtmd"
    "${LLAMA_CPP_SRC}/vendor"
    "${LLAMA_CPP_SRC}/vendor/hash")

# Phone CPU has dotprod+fp16 (verified via /proc/cpuinfo) — big matmul speedup.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(llama PRIVATE -march=armv8.2-a+dotprod+fp16)
endif()
target_compile_options(llama PRIVATE -O3)

find_library(LOG_LIB log)
target_link_libraries(llama ${LOG_LIB})
