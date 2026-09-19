// `jni.cpp` driven by hand. Until now it was the one layer of the native
// stack no test could reach: it needs a JVM to link, so `native-tests`
// compiled the engines and skipped the bridge, and `android-build` built
// the bridge and ran nothing. Every pad hit, every SPLIT layer and every
// print crosses it.
//
// The JNIEnv here is a fake backed by plain vectors (see stub/jni.h), with
// one switch: `failAllocations` makes NewFloatArray/NewIntArray return
// null, which is what a real JVM does when it cannot find the room.
//
// One way the fake is deliberately simpler than a JVM: a real one also
// leaves an OutOfMemoryError pending, and a native method returning with
// one makes the JVM throw at the Kotlin call site rather than hand the
// caller the null. So what these two cases prove is the half that is
// ours - that the bridge does not write into a null array before it
// returns - and not what Kotlin then sees. `jni.cpp` never calls
// ExceptionCheck, so the stub has no exception machinery to model.
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

#include "PadEngine.h"
#include "SurfaceEngine.h"
#include "check.h"
#include "jni.h"
#include "measure.h"

using namespace snipsnap;

// jni.cpp's entry points, as the JVM would see them.
extern "C" {
jlong Java_com_snipsnap_app_NativePads_create(JNIEnv*, jobject, jint);
void Java_com_snipsnap_app_NativePads_destroy(JNIEnv*, jobject, jlong);
void Java_com_snipsnap_app_NativePads_beginBank(JNIEnv*, jobject, jlong);
jint Java_com_snipsnap_app_NativePads_addSample(JNIEnv*, jobject, jlong, jfloatArray, jint, jint);
void Java_com_snipsnap_app_NativePads_commitBank(JNIEnv*, jobject, jlong);
jboolean Java_com_snipsnap_app_NativePads_noteOn(JNIEnv*, jobject, jlong, jint, jint, jlong, jlong, jlong, jfloat, jfloat, jdouble, jboolean);
jboolean Java_com_snipsnap_app_NativePads_noteOnLayers(JNIEnv*, jobject, jlong, jintArray, jintArray, jlong, jlong, jlong, jfloatArray, jfloatArray, jdouble, jbooleanArray);
jintArray Java_com_snipsnap_app_NativePads_drainEnded(JNIEnv*, jobject, jlong);
jboolean Java_com_snipsnap_app_NativePads_armPrint(JNIEnv*, jobject, jlong, jint);
jint Java_com_snipsnap_app_NativePads_printState(JNIEnv*, jobject, jlong);
jfloatArray Java_com_snipsnap_app_NativePads_stopPrint(JNIEnv*, jobject, jlong);
jlong Java_com_snipsnap_app_NativeSurface_create(JNIEnv*, jobject, jint);
void Java_com_snipsnap_app_NativeSurface_destroy(JNIEnv*, jobject, jlong);
void Java_com_snipsnap_app_NativeSurface_loadSample(JNIEnv*, jobject, jlong, jfloatArray, jint, jint);
void Java_com_snipsnap_app_NativeSurface_setCorner(JNIEnv*, jobject, jlong, jint, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat);
void Java_com_snipsnap_app_NativeSurface_control(JNIEnv*, jobject, jlong, jint, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jboolean);
void Java_com_snipsnap_app_NativeSurface_setGrain(JNIEnv*, jobject, jlong, jfloat, jfloat, jfloat);
void Java_com_snipsnap_app_NativeSurface_setKey(JNIEnv*, jobject, jlong, jint, jint, jfloat);
void Java_com_snipsnap_app_NativeSurface_setModulation(JNIEnv*, jobject, jlong, jfloatArray);
jboolean Java_com_snipsnap_app_NativeSurface_armPrint(JNIEnv*, jobject, jlong, jint);
jfloatArray Java_com_snipsnap_app_NativeSurface_stopPrint(JNIEnv*, jobject, jlong);
}

namespace {

// ---- the fake JVM --------------------------------------------------------------

/**
 * One type behind every array handle. The first draft used three structs
 * and read the length through whichever cast came to hand, which reads
 * past the end of the object for two of the three - a trap worth naming,
 * since it fails as a *wrong length* rather than a crash, and cost three
 * red cases before the cause was obvious. One layout, no assumptions.
 */
struct FakeArray {
    std::vector<float> f;
    std::vector<jint> i;
    std::vector<jboolean> z;
    jsize length = 0;  // what GetArrayLength reports - not always the truth
};

bool failAllocations = false;
std::vector<FakeArray*> allocated;

FakeArray* impl(jarray a) { return reinterpret_cast<FakeArray*>(a); }

jsize fakeGetArrayLength(JNIEnv*, jarray a) { return impl(a)->length; }

void fakeGetFloatArrayRegion(JNIEnv*, jfloatArray a, jsize s, jsize n, jfloat* buf) {
    for (jsize k = 0; k < n; ++k) buf[k] = impl(a)->f[static_cast<size_t>(s + k)];
}
void fakeGetIntArrayRegion(JNIEnv*, jintArray a, jsize s, jsize n, jint* buf) {
    for (jsize k = 0; k < n; ++k) buf[k] = impl(a)->i[static_cast<size_t>(s + k)];
}
void fakeGetBooleanArrayRegion(JNIEnv*, jbooleanArray a, jsize s, jsize n, jboolean* buf) {
    for (jsize k = 0; k < n; ++k) buf[k] = impl(a)->z[static_cast<size_t>(s + k)];
}

FakeArray* track(FakeArray* a) { allocated.push_back(a); return a; }

jfloatArray fakeNewFloatArray(JNIEnv*, jsize n) {
    if (failAllocations) return nullptr;
    auto* a = track(new FakeArray{});
    a->f.assign(static_cast<size_t>(n), 0.0f);
    a->length = n;
    return reinterpret_cast<jfloatArray>(a);
}
jintArray fakeNewIntArray(JNIEnv*, jsize n) {
    if (failAllocations) return nullptr;
    auto* a = track(new FakeArray{});
    a->i.assign(static_cast<size_t>(n), 0);
    a->length = n;
    return reinterpret_cast<jintArray>(a);
}
void fakeSetFloatArrayRegion(JNIEnv*, jfloatArray a, jsize s, jsize n, const jfloat* buf) {
    for (jsize k = 0; k < n; ++k) impl(a)->f[static_cast<size_t>(s + k)] = buf[k];
}
void fakeSetIntArrayRegion(JNIEnv*, jintArray a, jsize s, jsize n, const jint* buf) {
    for (jsize k = 0; k < n; ++k) impl(a)->i[static_cast<size_t>(s + k)] = buf[k];
}

const JNINativeInterface_ kIface = {
    fakeGetArrayLength,
    fakeGetFloatArrayRegion,
    fakeGetIntArrayRegion,
    fakeGetBooleanArrayRegion,
    fakeNewFloatArray,
    fakeNewIntArray,
    fakeSetFloatArrayRegion,
    fakeSetIntArrayRegion,
};

JNIEnv* env() {
    static JNIEnv_ e{&kIface};
    failAllocations = false;
    for (auto* a : allocated) delete a;
    allocated.clear();
    return &e;
}

jfloatArray floats(std::vector<float> v) {
    auto* a = track(new FakeArray{});
    a->length = static_cast<jsize>(v.size());
    a->f = std::move(v);
    return reinterpret_cast<jfloatArray>(a);
}
jintArray ints(std::vector<jint> v) {
    auto* a = track(new FakeArray{});
    a->length = static_cast<jsize>(v.size());
    a->i = std::move(v);
    return reinterpret_cast<jintArray>(a);
}
jbooleanArray bools(std::vector<jboolean> v) {
    auto* a = track(new FakeArray{});
    a->length = static_cast<jsize>(v.size());
    a->z = std::move(v);
    return reinterpret_cast<jbooleanArray>(a);
}

/** An array that reports a length it does not have, as a hostile JVM might. */
jfloatArray floatsClaiming(jsize claimed, std::vector<float> v) {
    auto* a = impl(floats(std::move(v)));
    a->length = claimed;
    return reinterpret_cast<jfloatArray>(a);
}

/** A mono ramp, so a frame's value names the frame — as in engine_tests. */
std::vector<float> ramp(int n) {
    std::vector<float> v(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) v[static_cast<size_t>(i)] = static_cast<float>(i) / 1000.0f;
    return v;
}

std::vector<float> pull(PadEngine* e, int32_t frames) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 123.0f);
    e->onAudioReady(nullptr, out.data(), frames);
    return out;
}
std::vector<float> pull(SurfaceEngine* e, int32_t frames) {
    std::vector<float> out(static_cast<size_t>(frames) * 2, 123.0f);
    e->onAudioReady(nullptr, out.data(), frames);
    return out;
}

/**
 * A stand-in for the real audio thread, for exactly one reason: the
 * bridge's own `stopPrinting` (jni.cpp) does not read or free a print
 * until `state() == Done`, and `Done` is only ever set from INSIDE a
 * callback - `record` seeing `Stopping` and flipping it. A synchronous
 * host test has no such thread of its own, so a JNI-level stopPrint call
 * here would time out and return null every time, whatever the state
 * genuinely would have reached with a live stream still running under
 * it. This keeps calling `onAudioReady` on a background thread for as
 * long as it's alive, the same way a real audio callback would keep
 * arriving during the bridge's wait - safe by the same atomics
 * `PrintBuffer` and the engines already rely on for the real thing.
 */
template <typename Engine>
class CallbackDriver {
public:
    CallbackDriver(Engine* engine, int32_t frames)
        : block_(static_cast<size_t>(frames) * 2, 0.0f),
          thread_([this, engine, frames] {
              while (!stop_.load(std::memory_order_acquire)) {
                  engine->onAudioReady(nullptr, block_.data(), frames);
                  std::this_thread::sleep_for(std::chrono::milliseconds(2));
              }
          }) {}

    ~CallbackDriver() {
        stop_.store(true, std::memory_order_release);
        thread_.join();
    }

private:
    std::atomic<bool> stop_{false};
    std::vector<float> block_;
    std::thread thread_;
};

}  // namespace

// ---- the bridge carries a sound end to end -------------------------------------

TEST(jni_banks_a_sample_and_starts_it) {
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);

    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    const jint ix = Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    CHECK_EQ(ix, 0);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 8);  // adopt

    CHECK(Java_com_snipsnap_app_NativePads_noteOn(e, nullptr, h, 1, 0, 100, 200, -1, 1.0f, 1.0f, 1.0, JNI_FALSE) == JNI_TRUE);
    auto out = pull(pads, 4);
    CHECK_NEAR(out[0], 0.100f, 1e-4);  // frame 100, through the bridge

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

// ---- noteOnLayers: the guards nothing had ever executed -------------------------

TEST(jni_layers_refuses_arrays_that_disagree) {
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 8);

    // Three ids but two of everything else: a group like this would have
    // read one past each shorter array. It is refused whole.
    CHECK(Java_com_snipsnap_app_NativePads_noteOnLayers(
              e, nullptr, h, ints({1, 2, 3}), ints({0, 0}), 0, 200, -1,
              floats({1.0f, 1.0f}), floats({1.0f, 1.0f}), 1.0, bools({0, 0})) == JNI_FALSE);
    CHECK_NEAR(pull(pads, 8)[0], 0.0f, 1e-7);  // and nothing sounds

    // Empty is not a group either.
    CHECK(Java_com_snipsnap_app_NativePads_noteOnLayers(
              e, nullptr, h, ints({}), ints({}), 0, 200, -1,
              floats({}), floats({}), 1.0, bools({})) == JNI_FALSE);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

TEST(jni_layers_refuses_more_layers_than_there_are_voices) {
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 8);

    // The stack buffers inside noteOnLayers are kMaxVoices long. One more
    // than that is the write that would run off the end of them.
    const int over = PadEngine::kMaxVoices + 1;
    std::vector<jint> id(static_cast<size_t>(over), 7), smp(static_cast<size_t>(over), 0);
    std::vector<float> g(static_cast<size_t>(over), 0.1f);
    std::vector<jboolean> rv(static_cast<size_t>(over), 0);
    for (int i = 0; i < over; ++i) id[static_cast<size_t>(i)] = i;
    CHECK(Java_com_snipsnap_app_NativePads_noteOnLayers(
              e, nullptr, h, ints(id), ints(smp), 0, 200, -1,
              floats(g), floats(g), 1.0, bools(rv)) == JNI_FALSE);
    CHECK_NEAR(pull(pads, 8)[0], 0.0f, 1e-7);

    // Exactly kMaxVoices is the most that is still a group, and it plays.
    const int most = PadEngine::kMaxVoices;
    std::vector<jint> id2(static_cast<size_t>(most)), smp2(static_cast<size_t>(most), 0);
    std::vector<float> g2(static_cast<size_t>(most), 0.01f);
    std::vector<jboolean> rv2(static_cast<size_t>(most), 0);
    for (int i = 0; i < most; ++i) id2[static_cast<size_t>(i)] = 100 + i;
    CHECK(Java_com_snipsnap_app_NativePads_noteOnLayers(
              e, nullptr, h, ints(id2), ints(smp2), 100, 300, -1,
              floats(g2), floats(g2), 1.0, bools(rv2)) == JNI_TRUE);
    CHECK(pull(pads, 4)[0] > 0.0f);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

TEST(jni_layers_carries_each_layers_own_gain_and_direction) {
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 8);

    // Two layers of one window: one forward at half, one backwards at a
    // quarter. If the bridge mixed up which array belonged to which layer,
    // the first frame would not be this number.
    CHECK(Java_com_snipsnap_app_NativePads_noteOnLayers(
              e, nullptr, h, ints({10, 11}), ints({0, 0}), 100, 200, -1,
              floats({0.5f, 0.25f}), floats({0.5f, 0.25f}), 1.0, bools({0, 1})) == JNI_TRUE);
    auto out = pull(pads, 1);
    CHECK_NEAR(out[0], 0.100f * 0.5f + 0.199f * 0.25f, 1e-4);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

// ---- the allocation doors -------------------------------------------------------

TEST(jni_refuses_a_sample_it_cannot_copy) {
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);

    // A length that casts to an enormous size_t. Reaching the vector with
    // it throws, and an exception crossing back into the JVM kills the
    // process; -1 is the refusal the rest of the stack understands.
    const jint ix = Java_com_snipsnap_app_NativePads_addSample(
        e, nullptr, h, floatsClaiming(-1000, ramp(16)), 1, 48000);
    CHECK_EQ(ix, -1);

    // And the engine treats that -1 as nothing to play rather than an
    // index: the note is reported ended, so the allocator lets it go.
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 8);
    CHECK(Java_com_snipsnap_app_NativePads_noteOn(e, nullptr, h, 5, -1, 0, 100, -1, 1.0f, 1.0f, 1.0, JNI_FALSE) == JNI_TRUE);
    pull(pads, 8);
    int32_t buf[8];
    CHECK_EQ(static_cast<int>(pads->drainEnded(buf, 8)), 1);
    CHECK_EQ(buf[0], 5);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

TEST(jni_surface_refuses_a_sample_it_cannot_copy) {
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativeSurface_create(e, nullptr, 48000);
    // Nothing to assert but survival: the point is that this returns at
    // all rather than terminating on an exception crossing the bridge.
    Java_com_snipsnap_app_NativeSurface_loadSample(e, nullptr, h, floatsClaiming(-1000, ramp(16)), 48000, 0);
    Java_com_snipsnap_app_NativeSurface_destroy(e, nullptr, h);
}

TEST(jni_surface_set_corner_forwards_crush_and_echo_without_swapping_them) {
    // setCorner gained two more floats this stage - crush and echo. A
    // swapped or dropped argument in the bridge (jni.cpp) would pass
    // every SurfaceEngine-level test, since none of those go through
    // this JNI entry point at all - they all call setCorner directly in
    // C++ (see SurfaceEngine.h). Toggling the two macros individually
    // (1,0 then 0,1) and checking echo's own observable effect - a tail
    // that keeps ringing after release, see engine_tests.cpp - catches
    // both a swap (crush's "1" landing in echo's slot would produce a
    // tail where none is asserted) and a drop (echo's "1" never
    // reaching the engine would produce no tail where one is asserted).
    JNIEnv* e = env();
    std::vector<float> tone(100, 0.33f);

    auto tailPeakAfterRelease = [&](float crush, float echo) {
        const jlong h = Java_com_snipsnap_app_NativeSurface_create(e, nullptr, 48000);
        auto* surf = reinterpret_cast<SurfaceEngine*>(h);
        surf->loadSample(tone.data(), tone.size(), 48000);
        Java_com_snipsnap_app_NativeSurface_setCorner(e, nullptr, h, 0, 0.5f, 1.0f, 0.0f, 0.0f, crush, echo, 0.0f);
        ControlFrame f;
        f.mode = 2;
        f.gate = true;
        f.a = 1.0f; f.b = f.c = f.d = 0.0f;
        surf->pushControl(f);
        for (int i = 0; i < 20; ++i) pull(surf, 64);
        ControlFrame off = f;
        off.gate = false;
        surf->pushControl(off);
        for (int i = 0; i < 40; ++i) pull(surf, 64);
        float peak = 0.0f;
        for (int i = 0; i < 400; ++i) {
            auto out = pull(surf, 64);
            for (float v : out) peak = std::max(peak, std::fabs(v));
        }
        Java_com_snipsnap_app_NativeSurface_destroy(e, nullptr, h);
        return peak;
    };

    CHECK(tailPeakAfterRelease(1.0f, 0.0f) < 1e-4f);  // crush on, echo off: no tail
    CHECK(tailPeakAfterRelease(0.0f, 1.0f) > 0.01f);  // echo on, crush off: a real tail
}

TEST(jni_surface_set_corner_forwards_spring_without_swapping_it_with_echo) {
    // setCorner gained a seventh float this stage - spring, appended right
    // after echo. The same swap/drop risk the crush/echo test above
    // guards against applies to this new neighbouring pair, but spring
    // and echo both ring after release (unlike crush), so telling them
    // apart needs a different signature than "has a tail at all": spring
    // is diffuse and immediate - no built-in delay, so it is already at
    // its loudest in the instant the gate closes - while echo is silent
    // for its own fixed 220 ms round trip before its first repeat lands.
    // Measuring right at release (well under 220 ms) isolates spring;
    // measuring a window straddling 220-430 ms isolates echo's first
    // repeat, by which point spring's own short default room (SIZE/TONE
    // baked in - see SurfaceEngine.h's own comment) has already decayed
    // well down. A swap lands one macro's value in the other's slot,
    // which flips which of the two windows lights up; a drop leaves both
    // windows reading whatever the *other*, untouched macro alone
    // produces - either mistake fails at least one of the four checks
    // below.
    JNIEnv* e = env();
    std::vector<float> tone(2000, 0.5f);

    auto peakInWindow = [&](float echo, float spring, int extraCallbacksBeforeWindow) {
        const jlong h = Java_com_snipsnap_app_NativeSurface_create(e, nullptr, 48000);
        auto* surf = reinterpret_cast<SurfaceEngine*>(h);
        surf->loadSample(tone.data(), tone.size(), 48000);
        Java_com_snipsnap_app_NativeSurface_setCorner(e, nullptr, h, 0, 0.5f, 1.0f, 0.0f, 0.0f, 0.0f, echo, spring);
        ControlFrame f;
        f.mode = 2;
        f.gate = true;
        f.a = 1.0f; f.b = f.c = f.d = 0.0f;
        surf->pushControl(f);
        for (int i = 0; i < 20; ++i) pull(surf, 64);
        ControlFrame off = f;
        off.gate = false;
        surf->pushControl(off);
        for (int i = 0; i < 40; ++i) pull(surf, 64);
        for (int i = 0; i < extraCallbacksBeforeWindow; ++i) pull(surf, 64);
        float peak = 0.0f;
        for (int i = 0; i < 100; ++i) {
            auto out = pull(surf, 64);
            for (float v : out) peak = std::max(peak, std::fabs(v));
        }
        Java_com_snipsnap_app_NativeSurface_destroy(e, nullptr, h);
        return peak;
    };

    // Right at release (window ends ~133 ms in, comfortably under echo's
    // 220 ms trip): spring's immediate diffuse energy shows; echo cannot
    // have produced a repeat yet.
    CHECK(peakInWindow(0.0f, 1.0f, 0) > 0.05f);   // spring on, echo off: rings immediately
    CHECK(peakInWindow(1.0f, 0.0f, 0) < 0.01f);   // echo on, spring off: too early for a repeat

    // 185 more callbacks (~247 ms) later, the window sits at 300-433 ms:
    // echo's first repeat lands squarely inside it, while spring's own
    // short room has already decayed well below its immediate peak.
    CHECK(peakInWindow(1.0f, 0.0f, 185) > 0.05f);  // echo on, spring off: the repeat has landed
    CHECK(peakInWindow(0.0f, 1.0f, 185) < 0.03f);  // spring on, echo off: mostly decayed by now
}

TEST(jni_surface_grain_knobs_and_key_cross_the_bridge_in_order) {
    // setGrain and setKey are new entry points, and setKey's first two
    // arguments are both jints - a root and a mask swapped in jni.cpp
    // would pass every SurfaceEngine-level test (those call setKey
    // directly) and only show up as the wrong note on a phone. So the
    // whole GRAIN path is driven through the bridge here - the sample,
    // the knobs, the key, the control frame - and the pitch read back:
    // a pad at MIDI 70.4 under C major must come out on B (71). A
    // swapped root/mask reads as root 5 with an empty (so chromatic)
    // mask and lands on A# instead; a dropped source note snaps toward
    // MIDI 0 and lands nowhere near either.
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativeSurface_create(e, nullptr, 48000);
    auto* surf = reinterpret_cast<SurfaceEngine*>(h);
    const float sourceMidi = 70.4f;
    const float sourceHz = 440.0f * std::exp2((sourceMidi - 69.0f) / 12.0f);
    Java_com_snipsnap_app_NativeSurface_loadSample(e, nullptr, h, floats(measure::sine(sourceHz, 48000, 48000)), 48000, 0);
    Java_com_snipsnap_app_NativeSurface_setGrain(e, nullptr, h, 1.0f, 0.0f, 0.0f);  // one long grain, alone, from POSITION exactly
    const jint cMajor = (1 << 0) | (1 << 2) | (1 << 4) | (1 << 5) | (1 << 7) | (1 << 9) | (1 << 11);
    Java_com_snipsnap_app_NativeSurface_setKey(e, nullptr, h, 0, cMajor, sourceMidi);
    Java_com_snipsnap_app_NativeSurface_control(
        e, nullptr, h, 4,
        0.0f, 0.5f, 0.0f, 0.5f,
        0.25f, 0.25f, 0.25f, 0.25f,
        1.0f, 0.0f, 0.0f, 0.0f, JNI_TRUE);
    std::vector<float> mono;
    for (int i = 0; i < 12000 / 64 + 1; ++i) {
        for (float v : measure::left(pull(surf, 64))) mono.push_back(v);
    }
    const double heard = measure::hz(mono, 3000, 9000, 48000);
    const double b4 = 440.0 * std::exp2(2.0 / 12.0);
    CHECK_NEAR(heard, b4, b4 * 0.01);
    Java_com_snipsnap_app_NativeSurface_destroy(e, nullptr, h);
}

TEST(jni_surface_modulation_crosses_the_bridge_by_index_and_a_short_array_is_zeros) {
    // Eleven offsets in an array, by Modulator.Target's ordinal: index 1
    // is CUTOFF. Sending -1 there through the bridge must darken a bright
    // source in XY (cutoff 1.0 + -1 = closed), and sending the same -1 at
    // index 0 (PITCH) must not - an off-by-one in the marshalling would
    // swap those two outcomes. A short array (one value) leaves cutoff
    // untouched, so it reads exactly like no modulation at all.
    JNIEnv* e = env();
    auto peakWith = [&](std::vector<float> offsets) {
        const jlong h = Java_com_snipsnap_app_NativeSurface_create(e, nullptr, 48000);
        auto* surf = reinterpret_cast<SurfaceEngine*>(h);
        // A 2 kHz sine: through a wide-open filter untouched, through an
        // 80 Hz one all but gone (see engine_tests' own cutoff test).
        const auto bright = measure::sine(2000.0f, 240, 48000);
        surf->loadSample(bright.data(), bright.size(), 48000);
        Java_com_snipsnap_app_NativeSurface_setModulation(e, nullptr, h, floats(std::move(offsets)));
        Java_com_snipsnap_app_NativeSurface_control(
            e, nullptr, h, 0,
            0.5f, 1.0f, 0.0f, 0.5f,
            0.25f, 0.25f, 0.25f, 0.25f,
            1.0f, 0.0f, 0.0f, 0.0f, JNI_TRUE);
        for (int i = 0; i < 400; ++i) pull(surf, 64);
        float peak = 0.0f;
        for (int i = 0; i < 50; ++i) {
            for (float v : pull(surf, 64)) peak = std::max(peak, std::fabs(v));
        }
        Java_com_snipsnap_app_NativeSurface_destroy(e, nullptr, h);
        return peak;
    };
    const float open = peakWith({});
    const float closed = peakWith({0.0f, -1.0f});
    const float pitchOnly = peakWith({-1.0f, 0.0f});
    const float shortArray = peakWith({0.0f});
    CHECK(open > 0.1f);
    CHECK(closed < open * 0.2f);   // CUTOFF's slot reached the filter
    CHECK(pitchOnly > open * 0.5f);  // PITCH's slot did not
    CHECK_NEAR(shortArray, open, 1e-3);
}

TEST(jni_drain_survives_a_jvm_that_cannot_allocate) {
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 8);
    Java_com_snipsnap_app_NativePads_noteOn(e, nullptr, h, 3, 0, 0, 4, -1, 1.0f, 1.0f, 1.0, JNI_FALSE);
    for (int i = 0; i < 4; ++i) pull(pads, 8);  // it ends, so there is something to drain

    failAllocations = true;
    // Null, not a write into null - which is the undefined behaviour this
    // guards. The ids drained just before are gone with it; a JVM this
    // short of room has bigger trouble, and jni.cpp says so where it
    // happens.
    CHECK(Java_com_snipsnap_app_NativePads_drainEnded(e, nullptr, h) == nullptr);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

TEST(jni_a_print_the_jvm_cannot_hold_comes_back_empty_handed) {
    // The largest allocation the bridge ever asks for: seconds of audio in
    // one array. A JVM that refuses returns null with an OutOfMemoryError
    // pending; writing into that null is undefined, and undefined is a
    // crash where the pending error is something STOP PRINT can report.
    // It would land on whoever pressed it after a long take - exactly
    // when the phone is most likely to be short of room.
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativeSurface_create(e, nullptr, 48000);
    auto* surf = reinterpret_cast<SurfaceEngine*>(h);
    Java_com_snipsnap_app_NativeSurface_loadSample(e, nullptr, h, floats(ramp(500)), 48000, 0);
    CHECK(Java_com_snipsnap_app_NativeSurface_armPrint(e, nullptr, h, 4096) == JNI_TRUE);

    // Play a little so there is something captured to hand back.
    std::vector<float> block(256 * 2, 0.0f);
    for (int i = 0; i < 8; ++i) surf->onAudioReady(nullptr, block.data(), 256);
    CHECK(surf->printFrames() > 0);

    // Drive Stopping -> Done ourselves before the bridge call: the fix for
    // the review's use-after-free finding means stopPrint's own bounded
    // wait now requires Done, which nothing reaches on its own in a
    // synchronous host test (Done is only ever set from inside a
    // callback). Without this the null below would come from the wait
    // timing out, not from the allocation failure this test means to
    // exercise - a real assertion turned into an accidental one.
    surf->requestStopPrint();
    surf->onAudioReady(nullptr, block.data(), 256);
    CHECK(surf->printState() == PrintBuffer::State::Done);

    failAllocations = true;
    CHECK(Java_com_snipsnap_app_NativeSurface_stopPrint(e, nullptr, h) == nullptr);

    Java_com_snipsnap_app_NativeSurface_destroy(e, nullptr, h);
}

TEST(jni_the_pads_print_crosses_as_stereo_at_its_full_length) {
    // The bridge's own arithmetic, and the mistake this one invites: the
    // surface's print is mono, so there frames and floats are the same
    // number and either reads correctly. The pads' is not. An array sized
    // in frames would hand back the first half of the take at half its
    // length - a bounce that ends early and plays at the wrong speed -
    // and nothing above this line would notice, because the engine's
    // buffer would be perfectly correct.
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);

    pull(pads, 256);  // adopt the bank
    CHECK(Java_com_snipsnap_app_NativePads_armPrint(e, nullptr, h, 4096) == JNI_TRUE);
    CHECK(Java_com_snipsnap_app_NativePads_printState(e, nullptr, h) ==
          static_cast<jint>(PrintBuffer::State::Recording));
    CHECK(Java_com_snipsnap_app_NativePads_noteOn(
              e, nullptr, h, 1, 0, 0, 900, -1, 1.0f, 0.25f, 1.0, JNI_FALSE) == JNI_TRUE);
    for (int i = 0; i < 3; ++i) pull(pads, 256);

    const size_t frames = pads->printFrames();
    CHECK_EQ(static_cast<int>(frames), 768);
    // Read the engine's own copy before stopPrint clears it, to compare against.
    const std::vector<float> kept(pads->printData(), pads->printData() + frames * 2);

    // Drive Stopping -> Done ourselves, deterministically, before calling
    // the bridge: see the surface OOM test above for why this is
    // necessary now. The call that performs the transition writes
    // nothing further - record() takes no samples once it sees Stopping
    // - so the captured length stays exactly 768.
    pads->requestStopPrint();
    pull(pads, 256);
    CHECK(pads->printState() == PrintBuffer::State::Done);

    jfloatArray got = Java_com_snipsnap_app_NativePads_stopPrint(e, nullptr, h);
    CHECK(got != nullptr);
    CHECK_EQ(static_cast<int>(impl(got)->length), static_cast<int>(frames * 2));
    for (size_t i = 0; i < kept.size(); ++i) CHECK_NEAR(impl(got)->f[i], kept[i], 1e-6);
    // Stopping hands the buffer back and lets go of it, ready for the next take.
    CHECK(Java_com_snipsnap_app_NativePads_printState(e, nullptr, h) ==
          static_cast<jint>(PrintBuffer::State::Idle));

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

TEST(jni_a_pad_print_the_jvm_cannot_hold_comes_back_empty_handed) {
    // The surface's case, for the engine whose prints are twice the size.
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 256);
    CHECK(Java_com_snipsnap_app_NativePads_armPrint(e, nullptr, h, 4096) == JNI_TRUE);
    Java_com_snipsnap_app_NativePads_noteOn(e, nullptr, h, 1, 0, 0, 900, -1, 1.0f, 1.0f, 1.0, JNI_FALSE);
    pull(pads, 256);
    CHECK(pads->printFrames() > 0);

    // See the surface OOM test above: drive Stopping -> Done ourselves so
    // the null this test checks for is the allocation failure, not the
    // bridge's own wait timing out for lack of a live audio thread.
    pads->requestStopPrint();
    pull(pads, 256);
    CHECK(pads->printState() == PrintBuffer::State::Done);

    failAllocations = true;
    CHECK(Java_com_snipsnap_app_NativePads_stopPrint(e, nullptr, h) == nullptr);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

TEST(jni_stop_print_times_out_rather_than_reading_a_print_still_stopping) {
    // The fix itself, isolated from both cases above: nothing at all
    // drives a further callback here, so `record` never observes
    // Stopping and the state never reaches Done. Before review's finding
    // this returned whatever had been captured regardless; now the wait
    // gives up and stopPrint reports nothing, the honest answer when it
    // cannot prove the buffer is safe to touch.
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 256);
    CHECK(Java_com_snipsnap_app_NativePads_armPrint(e, nullptr, h, 4096) == JNI_TRUE);
    Java_com_snipsnap_app_NativePads_noteOn(e, nullptr, h, 1, 0, 0, 900, -1, 1.0f, 1.0f, 1.0, JNI_FALSE);
    pull(pads, 256);
    CHECK(pads->printFrames() > 0);

    CHECK(Java_com_snipsnap_app_NativePads_stopPrint(e, nullptr, h) == nullptr);
    // Left alone, not cleared: the buffer is still there for whenever a
    // callback (or a later stopPrint, once one does land) actually
    // finishes it, rather than freed on the strength of a guess.
    CHECK(Java_com_snipsnap_app_NativePads_printState(e, nullptr, h) ==
          static_cast<jint>(PrintBuffer::State::Stopping));
    CHECK(pads->printFrames() > 0);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}

TEST(jni_stop_print_reaches_a_real_callback_still_arriving_concurrently) {
    // The shape this whole fix is for: a genuinely live audio thread
    // still calling `onAudioReady` while the UI thread's stopPrint waits
    // on it, raced for real rather than driven by hand on one thread.
    // `PrintBuffer`'s atomics are what make this safe; this is what
    // proves it rather than assuming it.
    JNIEnv* e = env();
    const jlong h = Java_com_snipsnap_app_NativePads_create(e, nullptr, 48000);
    auto* pads = reinterpret_cast<PadEngine*>(h);
    Java_com_snipsnap_app_NativePads_beginBank(e, nullptr, h);
    Java_com_snipsnap_app_NativePads_addSample(e, nullptr, h, floats(ramp(1000)), 1, 48000);
    Java_com_snipsnap_app_NativePads_commitBank(e, nullptr, h);
    pull(pads, 256);
    CHECK(Java_com_snipsnap_app_NativePads_armPrint(e, nullptr, h, 4096) == JNI_TRUE);
    Java_com_snipsnap_app_NativePads_noteOn(e, nullptr, h, 1, 0, 0, 900, -1, 1.0f, 1.0f, 1.0, JNI_FALSE);
    pull(pads, 256);
    CHECK(pads->printFrames() > 0);

    jfloatArray got;
    {
        CallbackDriver<PadEngine> driver(pads, 256);
        got = Java_com_snipsnap_app_NativePads_stopPrint(e, nullptr, h);
    }
    CHECK(got != nullptr);
    CHECK(Java_com_snipsnap_app_NativePads_printState(e, nullptr, h) ==
          static_cast<jint>(PrintBuffer::State::Idle));

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}
