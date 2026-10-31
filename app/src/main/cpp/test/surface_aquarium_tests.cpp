#include "SurfaceEngine.h"
#include "aquarium/PerformanceBus.h"
#include "check.h"
#include "callback_allocations.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

using namespace snipsnap;
using namespace snipsnap::aquarium;
namespace {
using Audio = std::vector<float>;
constexpr int kRate = 48000;

void voice(SurfaceEngine& e) {
    Audio tone(256);
    for (int i = 0; i < 256; ++i) tone[i] = .4f * std::sin(6.283185307179586 * i / 32);
    e.loadSample(tone.data(), tone.size(), kRate);
    ControlFrame f; f.gate = true; f.x = .5f; f.y = 1; f.tilt = 0;
    e.pushControl(f);
}
Audio pull(SurfaceEngine& e, int frames) {
    Audio out(static_cast<size_t>(frames) * 2);
    e.onAudioReady(nullptr, out.data(), frames);
    return out;
}
float peak(const Audio& a, size_t first = 0) {
    float p = 0;
    for (size_t i = first; i < a.size(); ++i) p = std::max(p, std::abs(a[i]));
    return p;
}
PerformanceSettings tide(float gain = .125f) {
    PerformanceSettings s; s.enabled = true; s.seed = 0xffff'ffffu; s.outputGain = gain; s.bpm = 123.5;
    CHECK(setParameter(s.parameters, "aqua_tide_mix", 1));
    CHECK(setParameter(s.parameters, "aqua_tide_depth", .8f));
    CHECK(setParameter(s.parameters, "aqua_tide_rate", 2));
    CHECK(setParameter(s.parameters, "aqua_tide_stereoPhase", 180));
    return s;
}
PerformanceSettings reef() {
    PerformanceSettings s; s.enabled = true; s.outputGain = .125f;
    CHECK(setParameter(s.parameters, "aqua_reef_mix", 1));
    CHECK(setParameter(s.parameters, "aqua_reef_size", .8f));
    return s;
}
}

TEST(surface_default_and_explicit_dry_keep_the_legacy_waveform_and_full_stereo_print) {
    SurfaceEngine legacy(kRate), configured(kRate);
    voice(legacy); voice(configured);
    CHECK(configured.configureAquarium(PerformanceSettings{}));
    CHECK(configured.armPrint(5000));
    Audio heard;
    for (int n : {1, 37, 4097, 901}) {
        const auto a = pull(legacy, n), b = pull(configured, n);
        CHECK(std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0);
        heard.insert(heard.end(), b.begin(), b.end());
    }
    CHECK(configured.printState() == PrintBuffer::State::Done);
    CHECK_EQ(configured.printFrames(), 5000u);
    CHECK_EQ(configured.printSamples(), 10000u);
    for (size_t i = 0; i < configured.printSamples(); ++i) CHECK_EQ(configured.printData()[i], heard[i]);
    for (size_t i = 0; i < heard.size(); i += 2) CHECK_EQ(heard[i], heard[i + 1]);
    CHECK(peak(heard) > .01f);
}

TEST(surface_aquarium_print_is_exact_post_bus_stereo_and_matches_the_accepted_bus) {
    SurfaceEngine dry(kRate), wet(kRate); voice(dry); voice(wet);
    const auto s = tide();
    CHECK(wet.configureAquarium(s));
    PerformanceBus expected; CHECK(expected.prepare(kRate)); CHECK(expected.publish(s));
    CHECK(wet.armPrint(10000));
    Audio heard;
    for (int n : {37, 1, 256, 4097, 8193, 63}) {
        auto reference = pull(dry, n);
        expected.beginCallback(); expected.process(reference.data(), n);
        const auto actual = pull(wet, n);
        CHECK(actual == reference);
        heard.insert(heard.end(), actual.begin(), actual.end());
    }
    CHECK_EQ(wet.printFrames(), 10000u); CHECK_EQ(wet.printSamples(), 20000u);
    bool exactPrint = true, stereo = false;
    for (size_t i = 0; i < wet.printSamples(); ++i) exactPrint = exactPrint && wet.printData()[i] == heard[i];
    for (size_t i = 4000; i < heard.size(); i += 2) stereo = stereo || std::abs(heard[i] - heard[i + 1]) > 1e-4f;
    CHECK(exactPrint); CHECK(stereo); CHECK(peak(heard) > .001f);
    CHECK(!wet.aquariumFaulted());
}

TEST(surface_global_bypass_restores_dry_even_when_saved_wet_gain_is_zero) {
    SurfaceEngine e(kRate), dry(kRate); voice(e); voice(dry);
    auto s = tide(0); CHECK(e.configureAquarium(s));
    const auto muted = pull(e, 5000); pull(dry, 5000);
    CHECK_EQ(peak(muted, 8000), 0.f);
    s.enabled = false; CHECK(e.configureAquarium(s));
    const auto a = pull(e, 5000), b = pull(dry, 5000);
    for (size_t i = 8000; i < a.size(); ++i) CHECK_EQ(a[i], b[i]);
    CHECK(peak(a, 8000) > .01f);
}

TEST(surface_touch_release_keeps_room_tails_running_until_an_explicit_panic) {
    SurfaceEngine e(kRate); voice(e); CHECK(e.configureAquarium(reef()));
    pull(e, 12000);
    ControlFrame release; release.gate = false; release.x = .5f; release.y = 1; release.tilt = 0;
    e.pushControl(release);
    const auto tail = pull(e, 24000);
    CHECK(peak(tail, 32000) > 1e-7f);
    // A queued old touch cannot revive the room after priority release.
    ControlFrame old; old.gate = true;
    for (int i = 0; i < 63; ++i) e.pushControl(old);
    e.requestPanic();
    const auto quiet = pull(e, 5000);
    CHECK_EQ(peak(quiet, 8000), 0.f);
    old.x = .5f; old.y = 1; old.tilt = 0; e.pushControl(old);
    CHECK(peak(pull(e, 12000), 10000) > 1e-5f);
}

TEST(surface_dry_retains_the_existing_float_overs_in_both_output_and_print) {
    SurfaceEngine e(kRate);
    Audio source(256, 1); e.loadSample(source.data(), source.size(), kRate);
    MacroState corner; corner.echo = 1; corner.spring = 1;
    e.setCorner(0, corner);
    ControlFrame f; f.mode = 2; f.a = 1; f.b = f.c = f.d = 0; f.gate = true; f.tilt = .5;
    e.pushControl(f); pull(e, 100000);
    CHECK(e.armPrint(4097));
    const auto a = pull(e, 4097);
    CHECK(peak(a) > 1.f);
    for (size_t i = 0; i < a.size(); ++i) CHECK_EQ(e.printData()[i], a[i]);
}

TEST(surface_callback_adoption_panic_and_oversized_stereo_print_allocate_and_free_nothing) {
    SurfaceEngine e(kRate); voice(e); CHECK(e.configureAquarium(reef(), true));
    CHECK(e.armPrint(20000));
    Audio out(18000);
    performance_allocation::allocations.store(0); performance_allocation::frees.store(0);
    performance_allocation::enabled = true;
    e.onAudioReady(nullptr, out.data(), 9000);
    e.requestPanic(); e.onAudioReady(nullptr, out.data(), 9000);
    performance_allocation::enabled = false;
    CHECK_EQ(performance_allocation::allocations.load(), 0u);
    CHECK_EQ(performance_allocation::frees.load(), 0u);
    CHECK(!e.aquariumFaulted());
}

int main() { return check::runAll(); }
