# The native engines under test on the host - no NDK, no device, no
# stream. The engines' audio callbacks are driven directly with buffers,
# so what gets asserted is the DSP and the handshakes: a choke is a fade,
# a bank swap silences and reports every voice, a stale command never
# plays by index, the print buffer's state machine ends in Done.
#
# Oboe is headers only here (the callback interfaces the engines derive
# from); the two entry points the engines reference at link time are
# stubbed in oboe_stubs.cpp, since start() is never called. The headers
# come from SNIPSNAP_OBOE_INCLUDE when set (a local checkout) and are
# fetched from the pinned release otherwise (CI).
#
#   cmake -S app/src/main/cpp/test -B build/native-tests
#   cmake --build build/native-tests
#   ctest --test-dir build/native-tests --output-on-failure
cmake_minimum_required(VERSION 3.22)
project(snipsnap_native_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(SNIPSNAP_OBOE_INCLUDE "" CACHE PATH "A directory holding oboe/Oboe.h; empty fetches Oboe 1.9.0")
if(SNIPSNAP_OBOE_INCLUDE)
    set(OBOE_INCLUDE_DIR "${SNIPSNAP_OBOE_INCLUDE}")
else()
    include(FetchContent)
    # SOURCE_SUBDIR none: Oboe's own CMakeLists targets Android and must not
    # be added to this host build - only its headers are wanted.
    FetchContent_Declare(
        oboe
        URL https://github.com/google/oboe/archive/refs/tags/1.9.0.tar.gz
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
        SOURCE_SUBDIR none
    )
    FetchContent_MakeAvailable(oboe)
    set(OBOE_INCLUDE_DIR "${oboe_SOURCE_DIR}/include")
endif()

set(ENGINE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")

add_executable(engine_tests
    engine_tests.cpp
    oboe_stubs.cpp
    "${ENGINE_DIR}/SurfaceEngine.cpp"
    "${ENGINE_DIR}/PadEngine.cpp"
)
target_include_directories(engine_tests PRIVATE
    "${ENGINE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/stub"
    "${OBOE_INCLUDE_DIR}"
)
# One Oboe header uses memset without <cstring>; the NDK pulls it in
# transitively, a host libstdc++ does not.
target_compile_options(engine_tests PRIVATE -Wall -Wextra -include cstring)
find_package(Threads REQUIRED)
target_link_libraries(engine_tests PRIVATE Threads::Threads)

enable_testing()
add_test(NAME engine_tests COMMAND engine_tests)
