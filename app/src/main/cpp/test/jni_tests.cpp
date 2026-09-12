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
#include <cstring>
#include <vector>

#include "PadEngine.h"
#include "SurfaceEngine.h"
#include "check.h"
#include "jni.h"

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
void Java_com_snipsnap_app_NativeSurface_loadSample(JNIEnv*, jobject, jlong, jfloatArray, jint);
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
    Java_com_snipsnap_app_NativeSurface_loadSample(e, nullptr, h, floatsClaiming(-1000, ramp(16)), 48000);
    Java_com_snipsnap_app_NativeSurface_destroy(e, nullptr, h);
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
    Java_com_snipsnap_app_NativeSurface_loadSample(e, nullptr, h, floats(ramp(500)), 48000);
    CHECK(Java_com_snipsnap_app_NativeSurface_armPrint(e, nullptr, h, 4096) == JNI_TRUE);

    // Play a little so there is something captured to hand back.
    std::vector<float> block(256 * 2, 0.0f);
    for (int i = 0; i < 8; ++i) surf->onAudioReady(nullptr, block.data(), 256);
    CHECK(surf->printFrames() > 0);

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

    failAllocations = true;
    CHECK(Java_com_snipsnap_app_NativePads_stopPrint(e, nullptr, h) == nullptr);

    Java_com_snipsnap_app_NativePads_destroy(e, nullptr, h);
}
