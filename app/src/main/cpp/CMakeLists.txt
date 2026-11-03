cmake_minimum_required(VERSION 3.22)
project(pocketllm LANGUAGES C CXX)

# Repo root: app/src/main/cpp -> 4 levels up
get_filename_component(POCKET_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(POCKET_LLAMA_DIR "${POCKET_REPO_ROOT}/native/llama.cpp")

add_library(pocketllm SHARED pocket_jni.cpp)

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
