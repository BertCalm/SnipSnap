// SPDX-License-Identifier: MIT
#include "aquarium/PerformanceBus.h"
#include "check.h"

#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

namespace performance_allocation {
thread_local bool enabled = false;
std::atomic<std::size_t> allocations{0}, frees{0};
}
void* operator new(std::size_t size) {
    if (performance_allocation::enabled) ++performance_allocation::allocations;
    if (auto* p = std::malloc(size ? size : 1)) return p;
    throw std::bad_alloc{};
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* p) noexcept {
    if (p && performance_allocation::enabled) ++performance_allocation::frees;
    std::free(p);
}
void operator delete[](void* p) noexcept { ::operator delete(p); }
void operator delete(void* p, std::size_t) noexcept { ::operator delete(p); }
void operator delete[](void* p, std::size_t) noexcept { ::operator delete(p); }
void* operator new(std::size_t size, const std::nothrow_t&) noexcept {
    try { return ::operator new(size); } catch (...) { return nullptr; }
}
void* operator new[](std::size_t size, const std::nothrow_t&) noexcept {
    try { return ::operator new(size); } catch (...) { return nullptr; }
}
void operator delete(void* p, const std::nothrow_t&) noexcept { ::operator delete(p); }
void operator delete[](void* p, const std::nothrow_t&) noexcept { ::operator delete(p); }
void* operator new(std::size_t size, std::align_val_t alignment) {
    if (performance_allocation::enabled) ++performance_allocation::allocations;
    void* p = nullptr;
    if (posix_memalign(&p, static_cast<std::size_t>(alignment), size ? size : 1) == 0) return p;
    throw std::bad_alloc{};
}
void* operator new[](std::size_t size, std::align_val_t a) { return ::operator new(size, a); }
void operator delete(void* p, std::align_val_t) noexcept { ::operator delete(p); }
void operator delete[](void* p, std::align_val_t) noexcept { ::operator delete(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { ::operator delete(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { ::operator delete(p); }

namespace {
using namespace snipsnap::aquarium;
using Audio = std::vector<float>;
constexpr int kRate = 48000, kRamp = 960;
constexpr double kPi = 3.14159265358979323846;

PerformanceSettings flat(float gain = 0.1f) {
    PerformanceSettings s;
    CHECK(setParameter(s.parameters, "aqua_tide_mix", 1));
    CHECK(setParameter(s.parameters, "aqua_tide_depth", 0));
    s.enabled = true;
    s.outputGain = gain;
    return s;
}

PerformanceSettings surface(float gain = 0.1f) {
    PerformanceSettings s;
    CHECK(setParameter(s.parameters, "aqua_surface_mix", 1));
    CHECK(setParameter(s.parameters, "aqua_surface_gain", 0));
    CHECK(setParameter(s.parameters, "aqua_surface_sweep", 0));
    s.enabled = true;
    s.outputGain = gain;
    return s;
}

void ready(PerformanceBus& bus, const PerformanceSettings& s, int rate = kRate) {
    CHECK(bus.publish(s));
    CHECK(bus.prepare(rate));
}

Audio constant(int frames, float l = 0.4f, float r = 0.2f) {
    Audio a(static_cast<std::size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) { a[2 * i] = l; a[2 * i + 1] = r; }
    return a;
}

Audio tone(int frames, int rate = kRate, double hz = 733, float amplitude = 0.2f) {
    Audio a(static_cast<std::size_t>(frames) * 2);
    for (int i = 0; i < frames; ++i) {
        a[2 * i] = amplitude * static_cast<float>(std::sin(2 * kPi * hz * i / rate));
        a[2 * i + 1] = 0.25f * a[2 * i];
    }
    return a;
}

void process(PerformanceBus& bus, Audio& a, int block = 256) {
    const int frames = static_cast<int>(a.size() / 2);
    for (int offset = 0; offset < frames;) {
        const int n = std::min(block, frames - offset);
        bus.beginCallback();
        bus.process(a.data() + 2 * offset, n);
        offset += n;
    }
}

float peak(const Audio& a, int firstFrame = 0) {
    float value = 0;
    for (std::size_t i = static_cast<std::size_t>(firstFrame) * 2; i < a.size(); ++i)
        value = std::max(value, std::abs(a[i]));
    return value;
}

void same(const Audio& a, const Audio& b, float tolerance = 0) {
    CHECK_EQ(a.size(), b.size());
    float error = 0;
    for (std::size_t i = 0; i < std::min(a.size(), b.size()); ++i)
        error = std::max(error, std::abs(a[i] - b[i]));
    CHECK_NEAR(error, 0, tolerance);
}

Audio freshIdle(const PerformanceSettings& s, int frames) {
    PerformanceBus fresh;
    ready(fresh, s);
    Audio idle(static_cast<std::size_t>(frames) * 2, 0);
    process(fresh, idle);
    return idle;
}

TEST(performance_policy_has_a_fixed_sample_ceiling_and_default_exact_dry_bypass) {
    CHECK_EQ(PerformanceBus::kOutputCeiling, 0.99f);
    CHECK_EQ(PerformanceBus::kTransitionSeconds, 0.020);
    CHECK_EQ(PerformanceBus::kLimiterReleaseSeconds, 0.050);
    PerformanceBus bus;
    ready(bus, PerformanceSettings{});
    auto a = tone(20003);
    a[12] = -0.0f;
    a[14] = 1.0f;
    a[15] = -1.0f;
    const auto before = a;
    process(bus, a, 9001);  // callback sizes are not the fixed DSP scratch size
    CHECK(std::memcmp(a.data(), before.data(), a.size() * sizeof(float)) == 0);
    CHECK(!bus.faulted());
    CHECK_EQ(bus.limitedFrames(), 0u);
    auto explicitlyDry = PerformanceSettings{};
    explicitlyDry.enabled = true;
    explicitlyDry.outputGain = 0;
    CHECK(bus.publish(explicitlyDry));
    a = before;
    process(bus, a);
    CHECK(std::memcmp(a.data(), before.data(), a.size() * sizeof(float)) == 0);
}

TEST(performance_global_bypass_keeps_the_accepted_processor_clock_and_history) {
    auto s = flat(0.1f);
    s.parameters = parametersForPreset(Preset::Full);
    s.seed = 0xffffffffu;
    s.bpm = 123;
    s.enabled = false;
    PerformanceBus bus;
    ready(bus, s);
    Processor reference;
    CHECK(reference.prepare(kRate, 256));
    CHECK(reference.applySnapshot(s.parameters));
    reference.reset(s.seed);
    auto warm = tone(12000);
    const auto dry = warm;
    process(bus, warm);
    CHECK(std::memcmp(warm.data(), dry.data(), warm.size() * sizeof(float)) == 0);
    std::array<float, 256> l{}, r{};
    auto runReference = [&](const Audio& input) {
        Audio result(input.size());
        const int frames = static_cast<int>(input.size() / 2);
        for (int start = 0; start < frames; start += 256) {
            const int n = std::min(256, frames - start);
            for (int i = 0; i < n; ++i) { l[i] = input[2 * (start + i)]; r[i] = input[2 * (start + i) + 1]; }
            CHECK(reference.process(l.data(), r.data(), n, s.bpm) == ProcessResult::Ok);
            for (int i = 0; i < n; ++i) {
                result[2 * (start + i)] = l[i] * s.outputGain;
                result[2 * (start + i) + 1] = r[i] * s.outputGain;
            }
        }
        return result;
    };
    runReference(dry);
    Audio gap(12000 * 2, 0);
    const auto gapSource = gap;
    process(bus, gap);
    runReference(gapSource);
    s.enabled = true;
    CHECK(bus.publish(s));
    auto after = tone(4096);
    const auto expected = runReference(after);
    CHECK(peak(expected) < PerformanceBus::kOutputCeiling);
    process(bus, after);
    same(Audio(after.begin() + kRamp * 2, after.end()),
         Audio(expected.begin() + kRamp * 2, expected.end()), 0.000002f);
}

TEST(performance_all_presets_and_arbitrary_callback_partitions_retain_one_stereo_state) {
    for (int rate : {44100, 48000}) for (std::size_t p = 0; p < kPresetCount; ++p) {
        PerformanceSettings s;
        s.parameters = parametersForPreset(static_cast<Preset>(p));
        s.enabled = true;
        s.outputGain = 0.125f;
        s.bpm = 117;
        PerformanceBus one, split;
        ready(one, s, rate);
        ready(split, s, rate);
        auto a = tone(6000, rate);
        auto b = a;
        process(one, a, 4096);
        process(split, b, 73);
        same(a, b, 0.000003f);
        for (float x : a) CHECK(std::isfinite(x));
        CHECK(!one.faulted());
        if (p != 0) CHECK(peak(a, 2000) > 0.00001f);
        if (p != 0) {
            // Every canonical single stage (and pair/full preset) must be
            // recognized as active; a zero wet gain then really mutes it.
            s.outputGain = 0;
            CHECK(one.publish(s));
            auto muted = tone(3000, rate);
            process(one, muted);
            CHECK_EQ(peak(muted, static_cast<int>(std::ceil(rate * 0.020))), 0.0f);
        }
    }
}

TEST(performance_invalid_controls_and_a_full_queue_refuse_without_replacing_desired_state) {
    PerformanceBus bus;
    auto s = flat();
    ready(bus, s);
    auto bad = s;
    bad.model = 2;
    CHECK(!bus.publish(bad));
    bad = s; bad.bpm = 1000.00001;
    CHECK(!bus.publish(bad));
    bad = s; bad.outputGain = std::numeric_limits<float>::quiet_NaN();
    CHECK(!bus.publish(bad));
    bad = s; bad.outputGain = -0.01f;
    CHECK(!bus.publish(bad));
    bad = s; bad.parameters.values[0] = std::numeric_limits<float>::infinity();
    CHECK(!bus.publish(bad));
    bad = s; bad.parameters.values[18] = 0.5f;  // canonical Tide selector
    CHECK(!bus.publish(bad));
    CHECK(bus.controlSettings().parameters.values == s.parameters.values);
    for (int i = 0; i < PerformanceBus::kQueueCapacity - 1; ++i) {
        s.bpm = 80 + i;
        CHECK(bus.publish(s));
    }
    bad = s; bad.bpm = 999;
    CHECK(!bus.publish(bad));
    CHECK_EQ(bus.controlSettings().bpm, s.bpm);
    bus.beginCallback();
    CHECK(bus.publish(bad));  // draining releases bounded capacity
}

TEST(performance_reset_requests_survive_latest_settings_coalescing) {
    PerformanceSettings s;
    s.parameters = parametersForPreset(Preset::Reef);
    s.enabled = true;
    PerformanceBus bus;
    ready(bus, s);
    auto excitation = tone(kRate);
    process(bus, excitation);
    Audio ringing(4096 * 2, 0);
    process(bus, ringing);
    CHECK(peak(ringing) > 0.00001f);
    CHECK(bus.publish(s, true));
    s.outputGain = 0.25f;
    CHECK(bus.publish(s, false));
    Audio cleared(6000 * 2, 0);
    process(bus, cleared);
    const auto idle = freshIdle(s, 6000);
    // Reef deliberately injects 1e-25 into its FDN. Clearing must reach
    // the accepted fresh idle floor, not invent an exact-zero DSP law.
    std::printf("  Reef fresh idle %.9g; cleared tail %.9g\n", peak(idle), peak(cleared, 2 * kRamp));
    CHECK(peak(cleared, 2 * kRamp) <= peak(idle) + 1e-30f);
}

TEST(performance_sound_edits_never_expose_the_untrimmed_dry_bus) {
    auto s = surface(0.1f);
    PerformanceBus bus;
    ready(bus, s);
    auto warm = constant(8000);
    process(bus, warm);
    CHECK_NEAR(warm.back(), 0.02f, 0.00001f);
    CHECK(setParameter(s.parameters, "aqua_surface_freq", 6000));
    CHECK(bus.publish(s));
    auto edit = constant(4096);
    process(bus, edit);
    CHECK(peak(edit) < 0.041f);  // dry is 0.4: a fade through dry would surge tenfold
    CHECK_NEAR(edit[edit.size() - 2], 0.04f, 0.00001f);
    CHECK_NEAR(edit[kRamp * 2 - 2], 0.0f, 0.00001f);
    s.outputGain = 0.2f;
    CHECK(bus.publish(s));
    auto gain = constant(2048);
    process(bus, gain);
    for (int i = 0; i < kRamp; ++i)
        CHECK_NEAR(gain[2 * i], 0.4 * (0.1 + 0.1 * (i + 1) / kRamp), 0.00001);
    CHECK_NEAR(gain[gain.size() - 2], 0.08f, 0.00001f);
}

TEST(performance_reversing_a_pending_sound_edit_restores_audible_wet_output) {
    auto original = surface(0.1f);
    PerformanceBus bus;
    ready(bus, original);
    auto warm = constant(4000);
    process(bus, warm);
    auto edit = original;
    CHECK(setParameter(edit.parameters, "aqua_surface_gain", 6));
    CHECK(bus.publish(edit));
    auto partial = constant(100);
    process(bus, partial);
    CHECK(bus.publish(original));
    auto restored = constant(3000);
    process(bus, restored);
    CHECK_NEAR(restored[restored.size() - 2], 0.04f, 0.00001f);
}

TEST(performance_bypass_and_dry_preset_transitions_arrive_without_a_boundary_jump) {
    auto s = flat(0.1f);
    PerformanceBus bus;
    ready(bus, s);
    auto warm = constant(4000);
    process(bus, warm);
    s.enabled = false;
    CHECK(bus.publish(s));
    auto bypass = constant(2000);
    process(bus, bypass);
    CHECK(std::abs(bypass[0] - 0.04f) < 0.001f);
    CHECK_EQ(bypass[2 * kRamp - 2], 0.4f);
    CHECK_EQ(bypass.back(), 0.2f);
    s.enabled = true;
    CHECK(bus.publish(s));
    process(bus, warm);
    s.parameters = parametersForPreset(Preset::Dry);
    CHECK(bus.publish(s));
    auto dry = constant(4096);
    process(bus, dry);
    float maxJump = 0;
    for (std::size_t i = 2; i < dry.size(); i += 2)
        maxJump = std::max(maxJump, std::abs(dry[i] - dry[i - 2]));
    CHECK(maxJump < 0.001f);
    CHECK_EQ(dry[dry.size() - 2], 0.4f);
}

TEST(performance_peak_guard_is_stereo_linked_and_releases_after_hot_input) {
    auto s = surface(1.0f);
    CHECK(setParameter(s.parameters, "aqua_surface_gain", 18));
    CHECK(setParameter(s.parameters, "aqua_surface_freq", 200));
    PerformanceBus bus;
    ready(bus, s);
    auto hot = tone(12000, kRate, 8000, 0.95f);
    process(bus, hot);
    CHECK(bus.limitedFrames() > 0);
    CHECK(peak(hot, kRamp) <= 0.99f);
    CHECK(peak(hot, kRamp) > 0.98f);
    for (std::size_t i = kRamp * 2; i < hot.size(); i += 2)
        CHECK_NEAR(hot[i + 1], hot[i] * 0.25f, 0.00002f);
    auto quiet = tone(kRate / 2, kRate, 8000, 0.005f);
    process(bus, quiet);
    // +18 dB high shelf approaches 7.94x here. The host gain must recover,
    // rather than normalizing this entire quiet half-second after the hot one.
    CHECK(peak(quiet, kRate / 3) > 0.03f);
    CHECK(peak(quiet, kRate / 3) < 0.05f);
    const auto before = bus.limitedFrames();
    s.parameters = parametersForPreset(Preset::Dry);
    CHECK(bus.publish(s));
    auto ending = tone(5000, kRate, 8000, 0.95f);
    bus.beginCallback();
    bus.process(ending.data(), 5000);  // dry fast-path must flush this block's diagnostics
    CHECK(bus.limitedFrames() > before);
}

TEST(performance_panic_suspends_wet_until_a_new_note_and_clears_old_room_history) {
    PerformanceSettings s;
    s.parameters = parametersForPreset(Preset::Reef);
    s.enabled = true;
    PerformanceBus bus;
    ready(bus, s);
    auto excitation = tone(20000);
    process(bus, excitation);
    bus.panic();
    Audio silence(5000 * 2, 0);
    process(bus, silence);
    CHECK_EQ(peak(silence, kRamp), 0.0f);
    auto dry = constant(4000);
    process(bus, dry);
    CHECK_EQ(peak(dry), 0.0f);  // no untrimmed dry leak from a suspended wet bus
    bus.resumeAfterPanic();
    Audio cleared(4000 * 2, 0);
    process(bus, cleared);
    same(cleared, freshIdle(s, 4000));
}

TEST(performance_stream_rates_reprepare_fresh_state_and_retain_desired_controls) {
    PerformanceBus bus;
    const auto s = flat(0.25f);
    CHECK(bus.publish(s));
    for (int rate : {8000, 44100, 48000, 192000}) {
        CHECK(bus.prepare(rate));
        const int ramp = static_cast<int>(std::ceil(rate * 0.020));
        auto a = constant(ramp + 100, 0.2f, 0.1f);
        process(bus, a);
        CHECK_NEAR(a[0], 0.05 / ramp, 0.000001);
        CHECK_NEAR(a[a.size() - 2], 0.05f, 0.000001f);
        CHECK(bus.controlSettings().parameters.values == s.parameters.values);
        bus.release();
    }
    CHECK(!bus.prepare(7999));
    CHECK(!bus.prepare(std::numeric_limits<double>::quiet_NaN()));
    CHECK(!bus.prepare(384000));
    CHECK(!bus.isPrepared());
}

TEST(performance_corrupt_input_is_sanitized_before_feedback_and_never_poisoned) {
    PerformanceBus bus;
    ready(bus, flat());
    auto warm = constant(3000);
    process(bus, warm);
    auto a = constant(4000);
    a[400] = std::numeric_limits<float>::quiet_NaN();
    a[401] = std::numeric_limits<float>::infinity();
    process(bus, a);
    for (float x : a) CHECK(std::isfinite(x));
    CHECK_EQ(a[400], 0.0f);
    CHECK_EQ(a[401], 0.0f);
    CHECK_NEAR(a.back(), 0.02f, 0.000001f);
    CHECK(!bus.faulted());
}

TEST(performance_callback_adoption_reset_panic_and_oversized_blocks_allocate_and_free_nothing) {
    PerformanceBus bus;
    auto s = flat();
    ready(bus, s);
    auto a = tone(20000);
    s.parameters = parametersForPreset(Preset::Full);
    CHECK(bus.publish(s, true));
    performance_allocation::allocations.store(0);
    performance_allocation::frees.store(0);
    performance_allocation::enabled = true;
    bus.beginCallback();
    bus.process(a.data(), static_cast<int>(a.size() / 2));
    bus.panic();
    bus.process(a.data(), 3000);
    bus.resumeAfterPanic();
    bus.process(a.data(), 3000);
    performance_allocation::enabled = false;
    CHECK_EQ(performance_allocation::allocations.load(), 0u);
    CHECK_EQ(performance_allocation::frees.load(), 0u);
    CHECK(!bus.faulted());
}
}  // namespace

int main() { return check::runAll(); }
