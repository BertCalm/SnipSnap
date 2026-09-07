// The bridge: `NativeSurface` in Kotlin, one handle per engine. Every
// function here runs on a caller's thread that is never the audio
// thread (SurfaceEngine.kt serialises them); the only thing that
// crosses to the audio thread is a ControlFrame through the ring, a
// corner through its atomics, a sample through the pointer handshake.
// Nothing in this file is called from the Oboe callback.
#include <jni.h>

#include <chrono>
#include <thread>
#include <vector>

#include "SurfaceEngine.h"

using snipsnap::ControlFrame;
using snipsnap::MacroState;
using snipsnap::PrintBuffer;
using snipsnap::SurfaceEngine;

namespace {
inline SurfaceEngine* engine(jlong handle) { return reinterpret_cast<SurfaceEngine*>(handle); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_snipsnap_app_NativeSurface_create(JNIEnv*, jobject, jint preferredSampleRate) {
    return reinterpret_cast<jlong>(new SurfaceEngine(preferredSampleRate));
}

JNIEXPORT void JNICALL
Java_com_snipsnap_app_NativeSurface_destroy(JNIEnv*, jobject, jlong handle) {
    delete engine(handle);
}

JNIEXPORT jboolean JNICALL
Java_com_snipsnap_app_NativeSurface_start(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_snipsnap_app_NativeSurface_stop(JNIEnv*, jobject, jlong handle) {
    engine(handle)->stop();
}

JNIEXPORT jint JNICALL
Java_com_snipsnap_app_NativeSurface_sampleRate(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->sampleRate();
}

JNIEXPORT jboolean JNICALL
Java_com_snipsnap_app_NativeSurface_needsRestart(JNIEnv*, jobject, jlong handle) {
    return engine(handle)->needsRestart() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_snipsnap_app_NativeSurface_loadSample(JNIEnv* env, jobject, jlong handle, jfloatArray mono, jint sourceRate) {
    const jsize n = env->GetArrayLength(mono);
    // A copy on the UI thread, then the engine copies again into its own
    // Sample: two copies of a few seconds of floats is nothing next to a
    // pinned Java array held across a callback.
    std::vector<float> frames(static_cast<size_t>(n));
    env->GetFloatArrayRegion(mono, 0, n, frames.data());
    engine(handle)->loadSample(frames.data(), frames.size(), sourceRate);
}

JNIEXPORT void JNICALL
Java_com_snipsnap_app_NativeSurface_control(
    JNIEnv*, jobject, jlong handle, jint mode,
    jfloat x, jfloat y, jfloat z, jfloat tilt,
    jfloat a, jfloat b, jfloat c, jfloat d, jboolean gate) {
    ControlFrame f;
    f.mode = mode;
    f.x = x; f.y = y; f.z = z; f.tilt = tilt;
    f.a = a; f.b = b; f.c = c; f.d = d;
    f.gate = gate == JNI_TRUE;
    engine(handle)->pushControl(f);
}

JNIEXPORT void JNICALL
Java_com_snipsnap_app_NativeSurface_setCorner(
    JNIEnv*, jobject, jlong handle, jint index,
    jfloat pitch, jfloat cutoff, jfloat resonance, jfloat drive) {
    engine(handle)->setCorner(index, MacroState{pitch, cutoff, resonance, drive});
}

JNIEXPORT jboolean JNICALL
Java_com_snipsnap_app_NativeSurface_armPrint(JNIEnv*, jobject, jlong handle, jint maxFrames) {
    return engine(handle)->armPrint(maxFrames > 0 ? static_cast<size_t>(maxFrames) : 0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_snipsnap_app_NativeSurface_printState(JNIEnv*, jobject, jlong handle) {
    return static_cast<jint>(engine(handle)->printState());
}

/**
 * Stop the print and hand back what was captured, or null when nothing
 * was. Waits (bounded, UI thread) for the callback to make its last
 * write; if the stream is dead the callback will never flip the state,
 * so after the bound the buffer is read as-is - with no writer left,
 * that read is safe.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_snipsnap_app_NativeSurface_stopPrint(JNIEnv* env, jobject, jlong handle) {
    SurfaceEngine* e = engine(handle);
    e->requestStopPrint();
    for (int i = 0; i < 100 && e->printState() == PrintBuffer::State::Stopping; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    const size_t frames = e->printFrames();
    jfloatArray out = nullptr;
    if (frames > 0) {
        out = env->NewFloatArray(static_cast<jsize>(frames));
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(frames), e->printData());
    }
    e->clearPrint();
    return out;
}

}  // extern "C"
