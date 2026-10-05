# The native library: SurfaceEngine (the tactile surface), PadEngine (pad
# and key voices), LiveSnapEngine (the camera preview voice), and the JNI
# bridge in jni.cpp. The host harness
# (app/src/main/cpp/test/CMakeLists.txt) names these sources again;
# scripts/check_native_sources.py fails when the two lists diverge.
# Oboe arrives as a prefab package from the Gradle dependency
# com.google.oboe:oboe — no submodule, no vendored source.
cmake_minimum_required(VERSION 3.22.1)
project(snipsnap_surface CXX)

find_package(oboe REQUIRED CONFIG)

add_library(snipsnap_surface SHARED
    SurfaceEngine.cpp
    PadEngine.cpp
    LiveSnapEngine.cpp
    jni.cpp
)

target_compile_features(snipsnap_surface PRIVATE cxx_std_17)
target_compile_options(snipsnap_surface PRIVATE -Wall -Wextra -O2)

target_link_libraries(snipsnap_surface PRIVATE
    oboe::oboe
    android
    log
)
