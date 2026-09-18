cmake_minimum_required(VERSION 3.22.1)
project(frostsoulx_dsp_lab LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_library(frostsoulx_dsp_lab SHARED
    lab_jni.cpp
    engine/src/immersive_audio_engine.cpp)
target_include_directories(frostsoulx_dsp_lab PRIVATE engine/include)
target_compile_options(frostsoulx_dsp_lab PRIVATE -Wall -Wextra -Wpedantic)
# The imported bundle is source-controlled under engine/. For Steam Audio-enabled builds,
# copy the matching libphonon.so into jniLibs/<ABI>/ and enable the backend definition.
