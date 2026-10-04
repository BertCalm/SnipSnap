# The production library and the host test binary name the same
# translation units. A new .cpp has to appear here once, or the host
# suite silently stops covering it. The two CMake files share nothing
# else — this list is the one quantity they must not keep in two places.
set(SNIPSNAP_ENGINE_SOURCES
    SurfaceEngine.cpp
    PadEngine.cpp
    LiveSnapEngine.cpp
    jni.cpp
)
