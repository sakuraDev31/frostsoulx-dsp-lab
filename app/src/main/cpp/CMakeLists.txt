cmake_minimum_required(VERSION 3.22.1)
project(resonance_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(resonance_bridge SHARED bridge_core.cpp bridge_jni.cpp telemetry.cpp)
find_library(log-lib log)
target_link_libraries(resonance_bridge ${log-lib} dl)

# Telemetry runs per audio block: keep it optimised even in debug builds.
# No -ffast-math: the NaN / Inf detection in telemetry.cpp relies on real IEEE semantics.
target_compile_options(resonance_bridge PRIVATE -O2)

# 16 KB page-size compatible (Android 15+ devices with 16 KB pages).
target_link_options(resonance_bridge PRIVATE -Wl,-z,max-page-size=16384)
