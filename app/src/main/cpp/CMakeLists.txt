cmake_minimum_required(VERSION 3.22)
project(pocketllm LANGUAGES C CXX)

# --- 16 KB page-size compliance (Android 15+ devices) -------------------------
# Newer Android (15/16) ships devices with 16 KB kernel page sizes; their loader
# rejects any shared library whose PT_LOAD segments are aligned below 16 KB
# (NDK r27 and lower link 4 KB-aligned ELFs by default). Linking with
# max-page-size=16384 is required by Google's page-size guide for NDK <= r27 and
# is fully backward-compatible with 4 KB devices.
# Regression-checked after every build by scripts/check_16k_alignment.py in CI.
add_link_options(
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

# Repo root: app/src/main/cpp -> 4 levels up
get_filename_component(POCKET_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(POCKET_LLAMA_DIR "${POCKET_REPO_ROOT}/native/llama.cpp")

add_library(pocketllm SHARED pocket_jni.cpp)

# Belt & braces: the official per-target form, in case add_link_options above
# is ever moved or removed.
target_link_options(pocketllm PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(log-lib log)
target_link_libraries(pocketllm PRIVATE ${log-lib} android)

target_compile_options(pocketllm PRIVATE -O3 -fexceptions)

option(POCKET_OPENCL
       "PocketNotebook: build the OpenCL GPU engine as libpocketllm_ocl.so (Adreno)" OFF)

if(EXISTS "${POCKET_LLAMA_DIR}/CMakeLists.txt")
    message(STATUS "PocketNotebook: llama.cpp found -> full native engine")
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)

    # GPU engine via the ggml OpenCL backend (see docs/backend/OPENCL.md
    # in the pinned llama.cpp). Adreno (Snapdragon) is the primary target:
    #  - GGML_OPENCL_EMBED_KERNELS (ggml default ON): OpenCL kernels are
    #    compiled INTO the .so — no .cl files to ship.
    #  - GGML_OPENCL_USE_ADRENO_KERNELS (ggml default ON): Adreno-tuned
    #    matmul/attention kernels (LFM3 ssm_conv/ssm_scan/gated_delta_net
    #    kernels included).
    #  - llama_model_default_params().n_gpu_layers == -1 ("all"), so layers
    #    offload automatically once the backend reports a device.
    # Build-time the linker needs an OpenCL stub (Khronos ICD loader, see
    # .github/workflows/release.yml); at RUNTIME the DT_NEEDED resolves to
    # the device's own vendor libOpenCL.so.
    #
    # CRITICAL: libOpenCL.so is NOT a public library on every ROM — it must
    # be listed in /vendor/etc/public.libraries.txt, and many ROMs don't.
    # A ROM that does not expose it rejects the ENTIRE .so at dlopen time
    # (the alpha.9 regression: the OpenCL engine was the only lib, so the
    # app showed "Kit build" on perfectly good release APKs). That is why
    # the OpenCL engine builds as a SEPARATE lib, pocketllm_ocl, and never
    # replaces the always-loadable CPU engine: PocketLlm.kt tries
    # pocketllm_ocl first and falls back to pocketllm. Release builds ship
    # one engine per APK (standard = CPU, -gpu = OpenCL).
    if(POCKET_OPENCL)
        message(STATUS "PocketNotebook: OpenCL GPU engine — libpocketllm_ocl.so")
        set(GGML_OPENCL ON CACHE BOOL "" FORCE)
        # Kernels compiled INTO the .so (Android ships no .cl files) and the
        # Adreno-tuned matmul/attention kernels (both are ggml defaults; set
        # explicitly so an upstream default change cannot silently regress).
        set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
        set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
        set_target_properties(pocketllm PROPERTIES OUTPUT_NAME "pocketllm_ocl")
        target_compile_definitions(pocketllm PRIVATE POCKET_WITH_OPENCL=1)
    else()
        message(STATUS "PocketNotebook: CPU engine — libpocketllm.so (default)")
    endif()
    add_subdirectory("${POCKET_LLAMA_DIR}" llama_cpp EXCLUDE_FROM_ALL)
    target_link_libraries(pocketllm PRIVATE llama)
    target_compile_definitions(pocketllm PRIVATE POCKET_WITH_LLAMA=1)
else()
    message(STATUS "PocketNotebook: llama.cpp NOT found -> stub engine (kit build)")
    target_compile_definitions(pocketllm PRIVATE POCKET_WITH_LLAMA=0)
endif()
