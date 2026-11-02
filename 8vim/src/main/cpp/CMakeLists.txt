cmake_minimum_required(VERSION 3.22.1)
project(vim8_whisper)

# Upstream whisper.cpp (MIT) is a git submodule; only the small JNI bridge below is ours.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
add_subdirectory(whisper.cpp ${CMAKE_CURRENT_BINARY_DIR}/whisper)

add_library(vim8whisper SHARED whisper_jni.cpp)
target_link_libraries(vim8whisper whisper log)
