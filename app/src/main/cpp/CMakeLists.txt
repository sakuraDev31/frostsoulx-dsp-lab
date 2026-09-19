cmake_minimum_required(VERSION 3.22.1)
project(frostsoulx_dsp_lab LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(STEAM_AUDIO_DIR "android-armv8")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(STEAM_AUDIO_DIR "android-x64")
else()
    message(FATAL_ERROR "FrostSoulX DSP Lab supports arm64-v8a and x86_64 only; unsupported ABI: ${ANDROID_ABI}")
endif()

set(STEAM_AUDIO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/steamaudio")
set(STEAM_AUDIO_LIBRARY "${STEAM_AUDIO_ROOT}/lib/${STEAM_AUDIO_DIR}/libphonon.so")

add_library(frostsoulx_dsp_lab SHARED
    lab_jni.cpp
    engine/src/immersive_audio_engine.cpp)

target_include_directories(frostsoulx_dsp_lab PRIVATE
    engine/include
    "${STEAM_AUDIO_ROOT}/include")

target_compile_definitions(frostsoulx_dsp_lab PRIVATE FROSTSOULX_STEAM_AUDIO_AVAILABLE=1)
target_compile_options(frostsoulx_dsp_lab PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(frostsoulx_dsp_lab PRIVATE "${STEAM_AUDIO_LIBRARY}")

# libphonon.so is also copied into app/src/main/jniLibs so Android packages and
# resolves the dependency beside the lab JNI library at runtime.
