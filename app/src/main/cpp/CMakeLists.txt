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
    add_subdirectory("${POCKET_LLAMA_DIR}" llama_cpp EXCLUDE_FROM_ALL)
    target_link_libraries(pocketllm PRIVATE llama)
    target_compile_definitions(pocketllm PRIVATE POCKET_WITH_LLAMA=1)
else()
    message(STATUS "PocketNotebook: llama.cpp NOT found -> stub engine (kit build)")
    target_compile_definitions(pocketllm PRIVATE POCKET_WITH_LLAMA=0)
endif()
