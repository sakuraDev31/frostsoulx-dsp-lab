cmake_minimum_required(VERSION 3.22.1)
project(resonance_bridge LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(resonance_bridge SHARED bridge_core.cpp bridge_jni.cpp)
find_library(log-lib log)
target_link_libraries(resonance_bridge ${log-lib} dl)

# 16 KB page-size compatible (Android 15+ devices with 16 KB pages).
target_link_options(resonance_bridge PRIVATE -Wl,-z,max-page-size=16384)
