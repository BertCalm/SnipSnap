# The one native library in SnipSnap: the tactile surface's low-latency
# engine. Everything else in the repo is pure Kotlin/JVM on purpose; this
# exists because a finger-driven macro wants a callback under 10 ms, and
# AudioTrack's blocking-write clock (AndroidAudioSink) is built for the
# opposite trade. Oboe arrives as a prefab package from the Gradle
# dependency `com.google.oboe:oboe` — no submodule, no vendored source.
cmake_minimum_required(VERSION 3.22.1)
project(snipsnap_surface CXX)

find_package(oboe REQUIRED CONFIG)

add_library(snipsnap_surface SHARED
    SurfaceEngine.cpp
    jni.cpp
)

target_compile_features(snipsnap_surface PRIVATE cxx_std_17)
target_compile_options(snipsnap_surface PRIVATE -Wall -Wextra -O2)

target_link_libraries(snipsnap_surface PRIVATE
    oboe::oboe
    android
    log
)
