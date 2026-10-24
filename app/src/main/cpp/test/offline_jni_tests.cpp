// SPDX-License-Identifier: MIT
// Production offline JNI driven without a JVM. Exception-aware fake operations
// reject work after a pending exception, so missed guards are observable.
#include "aquarium/AquariumProcessor.h"
#include "aquarium/OfflineRender.h"
#include "check.h"
#include "jni.h"

#include <algorithm>
#include <limits>
#include <memory>
#include <string>
#include <vector>

extern "C" {
jfloatArray Java_com_snipsnap_app_NativeAquarium_renderOffline(
    JNIEnv*, jobject, jfloatArray, jint, jint, jfloatArray, jlong, jdouble,
    jint, jint, jfloat, jfloat, jint);
jint Java_com_snipsnap_app_NativeAquarium_maximumOutputFrames(JNIEnv*, jobject);
}

namespace {
using namespace snipsnap::aquarium;
struct FakeArray : _jfloatArray {
    std::vector<float> values;
    jsize reportedLength = 0;
    int reads = 0;
};
struct FakeClass : _jclass { std::string name; };
struct FakeJvm;
FakeJvm* activeJvm = nullptr;

struct FakeJvm {
    JNIEnv env;
    std::vector<std::unique_ptr<FakeArray>> arrays;
    std::vector<std::unique_ptr<FakeClass>> classes;
    std::string pendingClass, pendingMessage;
    int arrayLengthCalls = 0, reads = 0, writes = 0, allocations = 0;
    int throws = 0, finds = 0, deletedRefs = 0, illegalPendingCalls = 0;
    bool failNewArray = false, newArraySetsException = true;
    bool failSetArray = false, failFindClass = false;
    jfloatArray failReadArray = nullptr;
    FakeJvm* previous = nullptr;

    FakeJvm();
    ~FakeJvm() { activeJvm = previous; }
    bool beforeOperation() {
        if (pendingClass.empty()) return true;
        ++illegalPendingCalls;
        return false;
    }
    jfloatArray array(std::vector<float> values) {
        auto a = std::make_unique<FakeArray>();
        a->reportedLength = static_cast<jsize>(values.size());
        a->values = std::move(values);
        auto* ptr = a.get(); arrays.push_back(std::move(a));
        return ptr;
    }
    jfloatArray parameters(Preset preset = Preset::Dry) {
        auto snapshot = parametersForPreset(preset);
        return array({snapshot.values.begin(), snapshot.values.end()});
    }
    void pending(std::string name, std::string message = "injected JVM failure") {
        pendingClass = std::move(name); pendingMessage = std::move(message);
    }
};
FakeArray& arrayImpl(jarray a) { return *static_cast<FakeArray*>(a); }
jsize getLength(JNIEnv*, jarray a) {
    auto& j = *activeJvm; ++j.arrayLengthCalls;
    if (!j.beforeOperation()) return 0;
    return arrayImpl(a).reportedLength;
}
void getFloatRegion(JNIEnv*, jfloatArray a, jsize start, jsize count, jfloat* out) {
    auto& j = *activeJvm; ++j.reads; ++arrayImpl(a).reads;
    if (!j.beforeOperation()) return;
    auto& values = arrayImpl(a).values;
    if (j.failReadArray == a || start < 0 || count < 0 ||
        static_cast<std::size_t>(start) + static_cast<std::size_t>(count) > values.size()) {
        j.pending("java/lang/ArrayIndexOutOfBoundsException"); return;
    }
    std::copy_n(values.data() + start, count, out);
}
jfloatArray newFloatArray(JNIEnv*, jsize length) {
    auto& j = *activeJvm; ++j.allocations;
    if (!j.beforeOperation()) return nullptr;
    if (j.failNewArray) {
        if (j.newArraySetsException) j.pending("java/lang/OutOfMemoryError");
        return nullptr;
    }
    return j.array(std::vector<float>(static_cast<std::size_t>(length)));
}
void setFloatRegion(JNIEnv*, jfloatArray a, jsize start, jsize count, const jfloat* in) {
    auto& j = *activeJvm; ++j.writes;
    if (!j.beforeOperation()) return;
    auto& values = arrayImpl(a).values;
    if (j.failSetArray || start < 0 || count < 0 ||
        static_cast<std::size_t>(start) + static_cast<std::size_t>(count) > values.size()) {
        j.pending("java/lang/ArrayIndexOutOfBoundsException"); return;
    }
    std::copy_n(in, count, values.data() + start);
}
jclass findClass(JNIEnv*, const char* name) {
    auto& j = *activeJvm; ++j.finds;
    if (!j.beforeOperation()) return nullptr;
    if (j.failFindClass) { j.pending("java/lang/NoClassDefFoundError"); return nullptr; }
    auto c = std::make_unique<FakeClass>(); c->name = name;
    auto* ptr = c.get(); j.classes.push_back(std::move(c)); return ptr;
}
jint throwNew(JNIEnv*, jclass c, const char* message) {
    auto& j = *activeJvm; ++j.throws;
    if (!j.beforeOperation()) return -1;
    j.pending(static_cast<FakeClass*>(c)->name, message ? message : ""); return 0;
}
jboolean exceptionCheck(JNIEnv*) { return activeJvm->pendingClass.empty() ? JNI_FALSE : JNI_TRUE; }
void deleteRef(JNIEnv*, jobject) { ++activeJvm->deletedRefs; }
const JNINativeInterface_& functions() {
    static const JNINativeInterface_ table = [] {
        JNINativeInterface_ t{};
        t.GetArrayLength = getLength;
        t.GetFloatArrayRegion = getFloatRegion;
        t.NewFloatArray = newFloatArray;
        t.SetFloatArrayRegion = setFloatRegion;
        t.FindClass = findClass;
        t.ThrowNew = throwNew;
        t.ExceptionCheck = exceptionCheck;
        t.DeleteLocalRef = deleteRef;
        return t;
    }();
    return table;
}
FakeJvm::FakeJvm() : env{&functions()}, previous(activeJvm) { activeJvm = this; }

jfloatArray render(FakeJvm& j, jfloatArray samples, jfloatArray parameters,
                  jint channels = 2, jint rate = 44100, jlong seed = 1337,
                  jdouble bpm = 110, jint tail = 0, jint fade = 0,
                  jfloat gain = 1, jfloat ceiling = 1, jint block = 256) {
    return Java_com_snipsnap_app_NativeAquarium_renderOffline(&j.env, nullptr, samples,
        channels, rate, parameters, seed, bpm, tail, fade, gain, ceiling, block);
}
void rejected(FakeJvm& j, jfloatArray result, const char* className = "java/lang/IllegalArgumentException") {
    CHECK(result == nullptr);
    CHECK(j.pendingClass == className);
    CHECK_EQ(j.writes, 0);
    CHECK_EQ(j.illegalPendingCalls, 0);
}
}  // namespace

TEST(offline_jni_dry_stereo_roundtrip_and_native_bound) {
    FakeJvm j;
    auto samples = j.array({.1f, -.2f, .3f, -.4f});
    auto parameters = j.parameters();
    const auto before = arrayImpl(samples).values;
    const auto valuesBefore = arrayImpl(parameters).values;
    auto result = render(j, samples, parameters);
    CHECK(result != nullptr);
    if (result) CHECK(arrayImpl(result).values == before);
    CHECK(arrayImpl(samples).values == before);
    CHECK(arrayImpl(parameters).values == valuesBefore);
    CHECK(j.pendingClass.empty());
    CHECK_EQ(j.allocations, 1);
    CHECK_EQ(j.writes, 1);
    CHECK_EQ(j.illegalPendingCalls, 0);
    CHECK_EQ(Java_com_snipsnap_app_NativeAquarium_maximumOutputFrames(&j.env, nullptr), 8000000);
}

TEST(offline_jni_mono_expands_to_stereo_with_gain_and_tail) {
    FakeJvm j;
    auto result = render(j, j.array({.1f, -.3f}), j.parameters(), 1, 48000, 0, 0, 2, 0, .5f);
    CHECK(result != nullptr);
    if (result) CHECK(arrayImpl(result).values == std::vector<float>({.05f,.05f,-.15f,-.15f,0,0,0,0}));
    CHECK(j.pendingClass.empty());
    CHECK_EQ(j.illegalPendingCalls, 0);
}

TEST(offline_jni_marshals_nontrivial_parameters_seed_tempo_and_postprocessing) {
    FakeJvm j;
    std::vector<float> source(2400);
    for (std::size_t i = 0; i < source.size() / 2; ++i) {
        source[i * 2] = .13f * std::sin(float(i) * .031f);
        source[i * 2 + 1] = .09f * std::cos(float(i) * .047f);
    }
    const auto parameters = parametersForPreset(Preset::Full);
    OfflineRenderOptions options;
    options.bpm = 127; options.tailFrames = 129; options.fadeFrames = 33;
    options.outputGain = .8f; options.peakCeiling = 8; options.blockSize = 17;
    std::vector<float> expected;
    CHECK(snipsnap::aquarium::renderOffline(source.data(), source.size(), 2, 44100,
        parameters, 4294967295u, options, expected) == OfflineRenderStatus::Ok);
    auto samples = j.array(source);
    auto values = j.array({parameters.values.begin(), parameters.values.end()});
    auto result = render(j, samples, values, 2, 44100, 4294967295LL, 127, 129, 33, .8f, 8, 17);
    CHECK(result != nullptr);
    if (result) CHECK(arrayImpl(result).values == expected);
    CHECK(arrayImpl(samples).values == source);
    CHECK(j.pendingClass.empty());
    CHECK_EQ(j.illegalPendingCalls, 0);
}

TEST(offline_jni_rejects_invalid_options_without_allocating_java_output) {
    for (int mode = 0; mode < 7; ++mode) {
        FakeJvm j;
        auto samples = j.array({.1f,-.2f}), parameters = j.parameters();
        const double bpm = mode == 0 ? std::numeric_limits<double>::quiet_NaN() : mode == 1 ? 1001 : 0;
        const int fade = mode == 2 ? -1 : mode == 3 ? 2 : 0;
        const float gain = mode == 4 ? -1 : 1;
        const float ceiling = mode == 5 ? 0 : 1;
        const int block = mode == 6 ? 8193 : 256;
        rejected(j, render(j, samples, parameters, 2, 44100, 1337, bpm, 0, fade, gain, ceiling, block));
        CHECK_EQ(j.allocations, 0);
    }
}

TEST(offline_jni_rejects_null_and_wrong_snapshot_shape) {
    for (int mode = 0; mode < 4; ++mode) {
        FakeJvm j;
        auto samples = mode == 0 ? nullptr : j.array({.1f, -.1f});
        auto parameters = mode == 1 ? nullptr : j.parameters();
        if (mode == 2) parameters = j.array(std::vector<float>(kParameterCount - 1));
        if (mode == 3) parameters = j.array(std::vector<float>(kParameterCount + 1));
        rejected(j, render(j, samples, parameters));
        CHECK_EQ(j.allocations, 0);
    }
}

TEST(offline_jni_rejects_bad_shape_rate_and_seed) {
    for (int mode = 0; mode < 9; ++mode) {
        FakeJvm j;
        auto samples = j.array(mode == 0 ? std::vector<float>{} :
                               mode == 1 ? std::vector<float>{.1f,.2f,.3f} : std::vector<float>{.1f,.2f});
        auto parameters = j.parameters();
        const jint channels = mode == 2 ? 0 : mode == 3 ? 3 : 2;
        const jint rate = mode == 4 ? 7999 : mode == 5 ? 192001 : 44100;
        const jlong seed = mode == 6 ? -1 : mode == 7 ? 4294967296LL : 1337;
        const jint tail = mode == 8 ? -1 : 0;
        rejected(j, render(j, samples, parameters, channels, rate, seed, 0, tail));
        CHECK_EQ(j.allocations, 0);
    }
}

TEST(offline_jni_rejects_nonfinite_samples_and_parameters) {
    for (int mode = 0; mode < 5; ++mode) {
        FakeJvm j;
        auto samples = j.array({.1f,-.2f});
        auto parameters = j.parameters();
        if (mode == 0) arrayImpl(samples).values[0] = std::numeric_limits<float>::quiet_NaN();
        if (mode == 1) arrayImpl(samples).values[0] = 32.01f;
        if (mode == 2) arrayImpl(parameters).values[0] = std::numeric_limits<float>::infinity();
        if (mode == 3) arrayImpl(parameters).values[0] = 1.01f;
        if (mode == 4) {
            for (std::size_t i = 0; i < kParameterCount; ++i)
                if (parameterInfo()[i].name == "aqua_tide_mode") arrayImpl(parameters).values[i] = .5f;
        }
        rejected(j, render(j, samples, parameters));
        CHECK_EQ(j.allocations, 0);
    }
}

TEST(offline_jni_rejects_peak_instead_of_clipping) {
    FakeJvm j;
    auto samples = j.array({.2f,-.3f});
    const auto before = arrayImpl(samples).values;
    rejected(j, render(j, samples, j.parameters(), 2, 44100, 1337, 0, 0, 0, 4.f, .5f));
    CHECK(arrayImpl(samples).values == before);
    CHECK_EQ(j.allocations, 0);
}

TEST(offline_jni_enforces_bound_before_reading_large_input) {
    for (int mode = 0; mode < 2; ++mode) {
        FakeJvm j;
        auto samples = j.array({.1f});
        arrayImpl(samples).reportedLength = mode == 0 ? 8000001 : std::numeric_limits<jsize>::max();
        auto parameters = j.parameters();
        rejected(j, render(j, samples, parameters, 1));
        CHECK_EQ(arrayImpl(samples).reads, 0);
        CHECK_EQ(j.allocations, 0);
    }
}

TEST(offline_jni_preserves_already_pending_exception) {
    FakeJvm j;
    auto samples = j.array({.1f,-.2f}), parameters = j.parameters();
    j.pending("java/lang/IllegalStateException", "preserve this pending exception");
    auto result = render(j, samples, parameters);
    CHECK(result == nullptr);
    CHECK(j.pendingClass == "java/lang/IllegalStateException");
    CHECK(j.pendingMessage == "preserve this pending exception");
    CHECK_EQ(j.arrayLengthCalls, 0);
    CHECK_EQ(j.reads, 0);
    CHECK_EQ(j.writes, 0);
    CHECK_EQ(j.allocations, 0);
    CHECK_EQ(j.finds, 0);
    CHECK_EQ(j.throws, 0);
    CHECK_EQ(j.illegalPendingCalls, 0);
}

TEST(offline_jni_preserves_parameter_and_source_copy_exceptions) {
    for (bool failParameters : {false,true}) {
        FakeJvm j;
        auto samples = j.array({.1f,-.2f}), parameters = j.parameters();
        j.failReadArray = failParameters ? parameters : samples;
        auto result = render(j, samples, parameters);
        rejected(j, result, "java/lang/ArrayIndexOutOfBoundsException");
        CHECK_EQ(j.finds, 0);
        CHECK_EQ(j.throws, 0);
        CHECK_EQ(j.allocations, 0);
    }
}

TEST(offline_jni_handles_jvm_output_allocation_failure) {
    for (bool pending : {false,true}) {
        FakeJvm j;
        auto samples = j.array({.1f,-.2f}), parameters = j.parameters();
        j.failNewArray = true; j.newArraySetsException = pending;
        rejected(j, render(j, samples, parameters), "java/lang/OutOfMemoryError");
        CHECK_EQ(j.allocations, 1);
        CHECK_EQ(j.writes, 0);
        CHECK_EQ(j.throws, pending ? 0 : 1);
    }
}

TEST(offline_jni_preserves_output_copy_exception) {
    FakeJvm j;
    auto samples = j.array({.1f,-.2f}), parameters = j.parameters();
    j.failSetArray = true;
    auto result = render(j, samples, parameters);
    CHECK(result == nullptr);
    CHECK(j.pendingClass == "java/lang/ArrayIndexOutOfBoundsException");
    CHECK_EQ(j.allocations, 1);
    CHECK_EQ(j.writes, 1);
    CHECK_EQ(j.finds, 0);
    CHECK_EQ(j.throws, 0);
    CHECK_EQ(j.illegalPendingCalls, 0);
}

TEST(offline_jni_preserves_exception_class_lookup_failure) {
    FakeJvm j;
    j.failFindClass = true;
    auto result = render(j, nullptr, j.parameters());
    CHECK(result == nullptr);
    CHECK(j.pendingClass == "java/lang/NoClassDefFoundError");
    CHECK_EQ(j.finds, 1);
    CHECK_EQ(j.throws, 0);
    CHECK_EQ(j.writes, 0);
    CHECK_EQ(j.illegalPendingCalls, 0);
}
