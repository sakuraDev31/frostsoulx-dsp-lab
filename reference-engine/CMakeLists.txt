cmake_minimum_required(VERSION 3.22)
project(ref_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(ref_engine SHARED src/reference_engine.cpp)
target_include_directories(ref_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../engine-sdk)
target_compile_options(ref_engine PRIVATE -O2)

# 16 KB page-size compatible (Android 15+ devices with 16 KB pages).
if(ANDROID)
  target_link_options(ref_engine PRIVATE -Wl,-z,max-page-size=16384)
endif()
