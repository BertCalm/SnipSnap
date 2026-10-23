// SPDX-License-Identifier: MIT
// Offline bridge only: no Oboe stream, playback routing or persistent handles.
#include <jni.h>

#include <cstdint>
#include <limits>
#include <new>
#include <stdexcept>
#include <vector>

#include "OfflineRender.h"

namespace {
using namespace snipsnap::aquarium;

void throwJava(JNIEnv* env, const char* className, const char* message) noexcept {
    if (env->ExceptionCheck()) return;  // Preserve JVM allocation/copy exceptions.
    jclass errorClass = env->FindClass(className);
    if (!errorClass) return;  // FindClass leaves its own exception pending.
    if (!env->ExceptionCheck()) env->ThrowNew(errorClass, message);
    env->DeleteLocalRef(errorClass);  // Legal with a pending exception.
}

void throwRenderStatus(JNIEnv* env, OfflineRenderStatus status) noexcept {
    const char* errorClass = "java/lang/IllegalArgumentException";
    if (status == OfflineRenderStatus::AllocationFailure) errorClass = "java/lang/OutOfMemoryError";
    else if (status == OfflineRenderStatus::ProcessingFailure || status == OfflineRenderStatus::NonFiniteOutput)
        errorClass = "java/lang/IllegalStateException";
    throwJava(env, errorClass, offlineRenderStatusMessage(status));
}
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_snipsnap_app_NativeAquarium_maximumOutputFrames(JNIEnv*, jobject) {
    return static_cast<jint>(snipsnap::aquarium::kMaxOfflineOutputFrames);
}

JNIEXPORT jfloatArray JNICALL
Java_com_snipsnap_app_NativeAquarium_renderOffline(
    JNIEnv* env, jobject, jfloatArray samples, jint channels, jint rate,
    jfloatArray parameterValues, jlong seedLong, jdouble bpm, jint tailFrames,
    jint fadeFrames, jfloat outputGain, jfloat peakCeiling, jint blockSize) {
    using namespace snipsnap::aquarium;
    if (env->ExceptionCheck()) return nullptr;
    try {
        if (!samples || !parameterValues) {
            throwJava(env, "java/lang/IllegalArgumentException", "Source and all 52 Aquarium controls are required");
            return nullptr;
        }
        if (seedLong < 0 || seedLong > static_cast<jlong>(std::numeric_limits<std::uint32_t>::max())) {
            throwJava(env, "java/lang/IllegalArgumentException", "Aquarium seed must fit an unsigned 32-bit value");
            return nullptr;
        }
        const jsize parameterCount = env->GetArrayLength(parameterValues);
        if (env->ExceptionCheck()) return nullptr;
        const jsize sampleCount = env->GetArrayLength(samples);
        if (env->ExceptionCheck()) return nullptr;
        if (parameterCount != static_cast<jsize>(kParameterCount) || sampleCount < 0) {
            throwJava(env, "java/lang/IllegalArgumentException", "Aquarium requires exactly 52 parameter values and a valid source length");
            return nullptr;
        }
        ParameterSnapshot parameters;
        env->GetFloatArrayRegion(parameterValues, 0, parameterCount, parameters.values.data());
        if (env->ExceptionCheck()) return nullptr;
        const OfflineRenderOptions options{bpm, tailFrames, fadeFrames, outputGain, peakCeiling, blockSize};
        const auto validation = validateOfflineRender(static_cast<std::size_t>(sampleCount), channels, rate, parameters, options);
        if (validation != OfflineRenderStatus::Ok) {
            throwRenderStatus(env, validation);
            return nullptr;
        }
        // Requests beyond the hard frame bound are rejected before PCM copying.
        // Region copies avoid pinning a Java array through a long offline render.
        std::vector<float> source(static_cast<std::size_t>(sampleCount));
        env->GetFloatArrayRegion(samples, 0, sampleCount, source.data());
        if (env->ExceptionCheck()) return nullptr;
        std::vector<float> rendered;
        const auto status = renderOffline(source.data(), source.size(), channels, rate, parameters,
                                          static_cast<std::uint32_t>(seedLong), options, rendered);
        if (status != OfflineRenderStatus::Ok) {
            throwRenderStatus(env, status);
            return nullptr;
        }
        // 8m output frames means at most 16m floats, well inside signed jsize.
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(rendered.size()));
        if (!result) {
            throwJava(env, "java/lang/OutOfMemoryError", "Cannot allocate Aquarium output array");
            return nullptr;
        }
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(rendered.size()), rendered.data());
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(result);
            return nullptr;
        }
        return result;
    } catch (const std::bad_alloc&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Cannot allocate Aquarium offline render buffers");
    } catch (const std::length_error&) {
        throwJava(env, "java/lang/OutOfMemoryError", "Aquarium offline buffer length exceeds allocation limits");
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unexpected Aquarium offline render failure");
    }
    return nullptr;
}

}  // extern "C"
