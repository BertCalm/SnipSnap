// SPDX-License-Identifier: MIT
// The production public API, compared with a frozen accepted-audition reference.
#include "aquarium/AquariumProcessor.h"
#include "aquarium/reference/Presets.h"
#include "check.h"

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <type_traits>

// These hooks observe the actual production implementation, including pimpl and
// vector allocation. Only the explicit callback-boundary scope is measured;
// fixture construction, assertions, and prepare/release run outside it.
namespace allocation_probe {
thread_local bool enabled = false;
thread_local long failAfter = -1;
std::atomic<std::size_t> allocations{0};
std::atomic<std::size_t> frees{0};

void beforeAllocation() {
    if (enabled) ++allocations;
    if (failAfter >= 0 && failAfter-- == 0) throw std::bad_alloc{};
}
void beforeFree(void* p) noexcept { if (enabled && p) ++frees; }
}  // namespace allocation_probe

void* operator new(std::size_t size) {
    allocation_probe::beforeAllocation();
    if (void* p = std::malloc(size ? size : 1)) return p;
    throw std::bad_alloc{};
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* p) noexcept { allocation_probe::beforeFree(p); std::free(p); }
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
    allocation_probe::beforeAllocation();
    void* p = nullptr;
    if (posix_memalign(&p, static_cast<std::size_t>(alignment), size ? size : 1) == 0) return p;
    throw std::bad_alloc{};
}
void* operator new[](std::size_t size, std::align_val_t alignment) { return ::operator new(size, alignment); }
void operator delete(void* p, std::align_val_t) noexcept { ::operator delete(p); }
void operator delete[](void* p, std::align_val_t) noexcept { ::operator delete(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { ::operator delete(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { ::operator delete(p); }

namespace {
using namespace snipsnap::aquarium;
constexpr int kRate = 48000;
constexpr double kBpm = 110.0;
constexpr double kPi = 3.14159265358979323846;
struct Audio { std::vector<float> left, right; };

static_assert(noexcept(std::declval<Processor&>().prepare(48000, 256)));
static_assert(noexcept(std::declval<Processor&>().applySnapshot(std::declval<const ParameterSnapshot&>())));
static_assert(noexcept(std::declval<Processor&>().reset()));
static_assert(noexcept(std::declval<Processor&>().process(nullptr, nullptr, 0, 110)));

Audio source(int rate = kRate, double seconds = 0.3) {
    const auto frames = static_cast<std::size_t>(rate * seconds);
    Audio result{std::vector<float>(frames), std::vector<float>(frames)};
    std::uint32_t state = 0x13579u;
    for (std::size_t i = 0; i < frames; ++i) {
        state ^= state << 13; state ^= state >> 17; state ^= state << 5;
        const float noise = static_cast<float>(static_cast<std::int32_t>(state)) / 2147483648.0f;
        const float envelope = i < frames / 3 ? 1.0f : (i < frames * 2 / 3 ? 0.12f : 0.65f);
        result.left[i] = envelope * (0.11f * std::sin(2 * kPi * 220 * i / rate) + 0.025f * noise);
        result.right[i] = envelope * (0.08f * std::sin(2 * kPi * 337 * i / rate) + 0.018f * noise);
    }
    return result;
}

bool identical(const Audio& a, const Audio& b) {
    return a.left.size() == b.left.size() && a.right.size() == b.right.size() &&
           std::memcmp(a.left.data(), b.left.data(), a.left.size() * sizeof(float)) == 0 &&
           std::memcmp(a.right.data(), b.right.data(), a.right.size() * sizeof(float)) == 0;
}
bool sameSnapshot(const ParameterSnapshot& a, const ParameterSnapshot& b) {
    return a.values == b.values;
}
float maximumError(const Audio& a, const Audio& b) {
    float result = 0;
    for (std::size_t i = 0; i < a.left.size(); ++i)
        result = std::max({result, std::abs(a.left[i] - b.left[i]), std::abs(a.right[i] - b.right[i])});
    return result;
}
float peak(const Audio& a, std::size_t start = 0) {
    float result = 0;
    for (std::size_t i = start; i < a.left.size(); ++i)
        result = std::max({result, std::abs(a.left[i]), std::abs(a.right[i])});
    return result;
}
bool finite(const Audio& a) {
    for (float x : a.left) if (!std::isfinite(x)) return false;
    for (float x : a.right) if (!std::isfinite(x)) return false;
    return true;
}
double rms(const std::vector<float>& values, std::size_t start, std::size_t length) {
    double sum = 0;
    for (std::size_t i = start; i < start + length; ++i) sum += double(values[i]) * values[i];
    return std::sqrt(sum / length);
}
void set(ParameterSnapshot& values, std::string_view name, float value) {
    CHECK(setParameter(values, name, value));
}

void process(Processor& processor, Audio& audio, int block, double bpm = kBpm) {
    for (std::size_t i = 0; i < audio.left.size(); i += block) {
        const int count = static_cast<int>(std::min<std::size_t>(block, audio.left.size() - i));
        CHECK(processor.process(audio.left.data() + i, audio.right.data() + i, count, bpm) == ProcessResult::Ok);
    }
}
Audio render(const Audio& input, const ParameterSnapshot& values, int rate = kRate,
             int block = 257, std::uint32_t seed = kDefaultSeed, double bpm = kBpm) {
    Processor processor;
    CHECK(processor.applySnapshot(values));
    processor.reset(seed);
    CHECK(processor.prepare(rate, block));
    Audio output = input;
    process(processor, output, block, bpm);
    return output;
}
Audio accepted(const Audio& input, Preset preset, int rate, int block, std::uint32_t seed = kDefaultSeed) {
    accepted_audition::AquaticFXSuite processor;
    processor.setSeed(seed);
    audition::applyPreset(processor, std::string(presetName(preset)));
    processor.prepare(rate, block);
    Audio output = input;
    for (std::size_t i = 0; i < output.left.size(); i += block) {
        const int count = static_cast<int>(std::min<std::size_t>(block, output.left.size() - i));
        processor.processBlock(output.left.data() + i, output.right.data() + i, count, kBpm);
    }
    return output;
}
double surfaceResponse(float frequency, int mode, float gain, float cutoff = 3200.0f, float resonance = 0.1f) {
    Audio input{std::vector<float>(kRate), std::vector<float>(kRate)};
    for (int i = 0; i < kRate; ++i) input.left[i] = input.right[i] = 0.1f * std::sin(2 * kPi * frequency * i / kRate);
    ParameterSnapshot values;
    set(values, "aqua_surface_mix", 1); set(values, "aqua_surface_mode", float(mode));
    set(values, "aqua_surface_gain", gain); set(values, "aqua_surface_freq", cutoff);
    set(values, "aqua_surface_resonance", resonance);
    const auto output = render(input, values);
    return 20 * std::log10(rms(output.left, kRate / 2, kRate / 2) / rms(input.left, kRate / 2, kRate / 2));
}
}  // namespace

TEST(registry_defaults_and_control_helpers) {
    CHECK_EQ(kRenderModelVersion, 1u);
    CHECK_EQ(parameterInfo().size(), 52u);
    const ParameterSnapshot defaults;
    int stages = 0, selectors = 0;
    for (std::size_t i = 0; i < kParameterCount; ++i) {
        const auto& info = parameterInfo()[i];
        CHECK(!info.name.empty());
        CHECK(info.minimum <= info.defaultValue && info.defaultValue <= info.maximum);
        CHECK_EQ(defaults.values[i], info.defaultValue);
        CHECK(info.step == 0.0f || info.step == 1.0f);
        for (std::size_t j = 0; j < i; ++j) CHECK(info.name != parameterInfo()[j].name);
        if (info.name.substr(info.name.size() - 4) == "_mix") { ++stages; CHECK_EQ(info.defaultValue, 0.0f); }
        auto values = defaults;
        CHECK(setParameter(values, info.name, info.minimum - 1));
        CHECK_EQ(values.values[i], info.minimum);
        CHECK(setParameter(values, info.name, info.maximum + 1));
        CHECK_EQ(values.values[i], info.maximum);
        if (info.step != 0) {
            ++selectors;
            const auto before = values;
            CHECK(!setParameter(values, info.name, info.minimum + 0.5f));
            CHECK(sameSnapshot(before, values));
        }
        for (float invalid : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity(), -std::numeric_limits<float>::infinity()}) {
            const auto before = values;
            CHECK(!setParameter(values, info.name, invalid));
            CHECK(sameSnapshot(before, values));
        }
    }
    CHECK_EQ(stages, 6); CHECK_EQ(selectors, 8);
    auto values = defaults;
    CHECK(!setParameter(values, "aqua_does_not_exist", 0.5f));
    CHECK(sameSnapshot(defaults, values));
    CHECK(sameSnapshot(defaults, parametersForPreset(static_cast<Preset>(255))));
    CHECK(presetName(static_cast<Preset>(255)).empty());
    for (std::size_t i = 0; i < kPresetCount; ++i) {
        const auto preset = static_cast<Preset>(i);
        Preset decoded = Preset::Full;
        CHECK(parsePreset(presetName(preset), decoded)); CHECK(decoded == preset);
        Processor processor;
        CHECK(processor.applySnapshot(parametersForPreset(preset)));
    }
    Preset decoded = Preset::Full;
    CHECK(!parsePreset("FULL", decoded)); CHECK(decoded == Preset::Full);
    CHECK(!parsePreset("", decoded)); CHECK(decoded == Preset::Full);
}

TEST(prepare_bounds_release_and_failed_reprepare_preserve_state) {
    Processor first, second;
    CHECK(!first.isPrepared()); CHECK_EQ(first.sampleRate(), 0.0); CHECK_EQ(first.maximumBlockSize(), 0);
    const auto values = parametersForPreset(Preset::Full);
    CHECK(first.applySnapshot(values)); CHECK(second.applySnapshot(values));
    first.reset(456); second.reset(456);
    CHECK(first.prepare(kRate, 257)); CHECK(second.prepare(kRate, 257));
    auto warmFirst = source(), warmSecond = warmFirst;
    process(first, warmFirst, 257); process(second, warmSecond, 257);
    for (double rate : {0.0, -1.0, 7999.0, 192001.0, std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()})
        CHECK(!first.prepare(rate, 257));
    for (int block : {-1, 0, 8193, std::numeric_limits<int>::max()}) CHECK(!first.prepare(kRate, block));
    // Fail both pimpl allocation and the first owned delay allocation.
    for (long failAfter : {0L, 1L}) {
        allocation_probe::failAfter = failAfter;
        const bool prepared = first.prepare(96000, 512);
        allocation_probe::failAfter = -1;
        CHECK(!prepared);
    }
    CHECK(first.isPrepared()); CHECK_EQ(first.sampleRate(), double(kRate)); CHECK_EQ(first.maximumBlockSize(), 257);
    auto nextFirst = source(), nextSecond = nextFirst;
    process(first, nextFirst, 257); process(second, nextSecond, 257);
    CHECK(identical(nextFirst, nextSecond));
    first.release(); first.release();
    CHECK(!first.isPrepared()); CHECK_EQ(first.sampleRate(), 0.0); CHECK_EQ(first.maximumBlockSize(), 0);
    CHECK(sameSnapshot(first.snapshot(), values)); CHECK_EQ(first.seed(), 456u);
    CHECK(first.prepare(8000, 1)); CHECK_EQ(first.maximumBlockSize(), 1);
    CHECK(first.prepare(192000, 8192)); CHECK_EQ(first.sampleRate(), 192000.0);
    CHECK(first.prepare(kRate, 257));
    auto afterRelease = source(); process(first, afterRelease, 257);
    CHECK(identical(afterRelease, render(source(), values, kRate, 257, 456)));
}

TEST(snapshot_rejection_is_atomic_and_keeps_delay_and_clock_state) {
    Processor first, second;
    const auto full = parametersForPreset(Preset::Full);
    CHECK(first.applySnapshot(full)); CHECK(second.applySnapshot(full));
    CHECK(first.prepare(kRate, 257)); CHECK(second.prepare(kRate, 257));
    auto warmFirst = source(), warmSecond = warmFirst;
    process(first, warmFirst, 257); process(second, warmSecond, 257);
    for (std::size_t i = 0; i < kParameterCount; ++i) {
        const auto& info = parameterInfo()[i];
        for (float invalid : {std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity(),
                              -std::numeric_limits<float>::infinity(), info.minimum - 1, info.maximum + 1}) {
            auto values = full;
            values.values[0] = 0.87f;  // Detect partial application before a later invalid field.
            values.values[i] = invalid;
            CHECK(!first.applySnapshot(values)); CHECK(sameSnapshot(first.snapshot(), full));
        }
        if (info.step > 0) {
            auto values = full; values.values[i] = info.minimum + info.step * 0.5f;
            CHECK(!first.applySnapshot(values)); CHECK(sameSnapshot(first.snapshot(), full));
        }
    }
    // Reapplying a valid snapshot is a block-boundary update, not a transport reset.
    CHECK(first.applySnapshot(full));
    auto nextFirst = source(), nextSecond = nextFirst;
    process(first, nextFirst, 257); process(second, nextSecond, 257);
    CHECK(identical(nextFirst, nextSecond));
    Processor unprepared;
    auto invalid = full; invalid.values.back() = std::numeric_limits<float>::quiet_NaN();
    CHECK(!unprepared.applySnapshot(invalid)); CHECK(sameSnapshot(unprepared.snapshot(), ParameterSnapshot{}));
}

TEST(invalid_callback_inputs_leave_buffers_and_processor_state_untouched) {
    Processor first, second;
    auto input = source(kRate, 0.01);
    auto left = input.left, right = input.right;
    CHECK(first.process(left.data(), right.data(), 1, kBpm) == ProcessResult::NotPrepared);
    CHECK(first.process(nullptr, nullptr, 0, kBpm) == ProcessResult::NotPrepared);
    const auto values = parametersForPreset(Preset::Full);
    CHECK(first.applySnapshot(values)); CHECK(second.applySnapshot(values));
    CHECK(first.prepare(kRate, 256)); CHECK(second.prepare(kRate, 256));
    CHECK(first.process(left.data(), right.data(), -1, kBpm) == ProcessResult::InvalidFrameCount);
    CHECK(first.process(left.data(), right.data(), 257, kBpm) == ProcessResult::InvalidFrameCount);
    for (double bpm : {-1.0, 1000.1, std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()})
        CHECK(first.process(left.data(), right.data(), 256, bpm) == ProcessResult::InvalidTempo);
    CHECK(first.process(nullptr, right.data(), 256, kBpm) == ProcessResult::InvalidBuffer);
    CHECK(first.process(left.data(), nullptr, 256, kBpm) == ProcessResult::InvalidBuffer);
    CHECK(first.process(left.data(), left.data(), 256, kBpm) == ProcessResult::InvalidBuffer);
    CHECK(first.process(left.data(), left.data() + 1, 256, kBpm) == ProcessResult::InvalidBuffer);
    CHECK(first.process(left.data() + 1, left.data(), 256, kBpm) == ProcessResult::InvalidBuffer);
    CHECK(first.process(nullptr, nullptr, 0, kBpm) == ProcessResult::Ok);
    CHECK(left == input.left); CHECK(right == input.right);
    for (bool corruptRight : {false, true}) for (float invalid : {
             std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity(),
             -std::numeric_limits<float>::infinity(), 32.001f, -32.001f}) {
        auto corrupted = input;
        (corruptRight ? corrupted.right : corrupted.left)[255] = invalid;
        const auto before = corrupted;
        CHECK(first.process(corrupted.left.data(), corrupted.right.data(), 256, kBpm) == ProcessResult::InvalidInput);
        CHECK(identical(corrupted, before));
    }
    // Even a bad value at the final frame must be detected before any DSP runs.
    auto nextFirst = source(), nextSecond = nextFirst;
    process(first, nextFirst, 256); process(second, nextSecond, 256);
    CHECK(identical(nextFirst, nextSecond));
    float boundary[] = {-32.0f, 32.0f, -0.0f, 0.0f};
    Processor dry; CHECK(dry.prepare(kRate, 2));
    CHECK(dry.process(boundary, boundary + 2, 2, 0) == ProcessResult::Ok);  // Adjacent, disjoint arrays.
    CHECK(dry.process(boundary, boundary + 2, 2, 1000) == ProcessResult::Ok);
}

TEST(defaults_are_bit_exact_dry_and_all_effects_and_pairs_are_active) {
    auto input = source(); input.left[0] = -0.0f; input.right[0] = -0.0f;
    CHECK(identical(input, render(input, ParameterSnapshot{})));
    CHECK(identical(input, render(input, parametersForPreset(Preset::Dry), kRate, 1)));
    for (std::size_t i = 1; i < kPresetCount; ++i) {
        const auto preset = static_cast<Preset>(i);
        CHECK(maximumError(input, render(input, parametersForPreset(preset))) > 1e-5f);
    }
    const std::array<std::array<std::string_view, 2>, 3> members{{
        {{"aqua_fathom_mix", "aqua_surface_mix"}},
        {{"aqua_drift_mix", "aqua_tide_mix"}},
        {{"aqua_reef_mix", "aqua_biolume_mix"}}}};
    for (std::size_t pair = 0; pair < 3; ++pair) {
        const auto values = parametersForPreset(static_cast<Preset>(static_cast<int>(Preset::Depth) + pair));
        int active = 0;
        for (std::size_t i = 0; i < kParameterCount; ++i) {
            const auto name = parameterInfo()[i].name;
            if (name.substr(name.size() - 4) != "_mix" || values.values[i] == 0) continue;
            ++active; CHECK(name == members[pair][0] || name == members[pair][1]);
        }
        CHECK_EQ(active, 2);
    }
}

TEST(public_processor_matches_frozen_accepted_audio_for_every_preset) {
    for (int rate : {44100, 48000, 96000, 192000}) {
        const auto input = source(rate, 0.18);
        for (std::size_t i = 0; i < kPresetCount; ++i) {
            const auto preset = static_cast<Preset>(i);
            const auto actual = render(input, parametersForPreset(preset), rate, 257);
            const auto expected = accepted(input, preset, rate, 257);
            // Same compiler/FP settings run both paths in one host executable.
            CHECK(identical(actual, expected));
        }
    }
}

TEST(continuous_clock_is_block_independent_and_reset_is_repeatable) {
    const auto input = source(kRate, 0.3);
    for (std::size_t i = 0; i < kPresetCount; ++i) {
        const auto preset = static_cast<Preset>(i);
        const auto values = parametersForPreset(preset);
        const auto single = render(input, values, kRate, 1);
        CHECK(identical(single, render(input, values, kRate, 257)));
        CHECK(identical(single, render(input, values, kRate, 8192)));
        Processor processor; CHECK(processor.applySnapshot(values)); CHECK(processor.prepare(kRate, 8192));
        auto irregular = input;
        const int pattern[] = {1, 17, 511, 4, 8192, 3};
        std::size_t start = 0, count = 0;
        while (start < irregular.left.size()) {
            const int frames = static_cast<int>(std::min<std::size_t>(pattern[count++ % 6], irregular.left.size() - start));
            CHECK(processor.process(irregular.left.data() + start, irregular.right.data() + start, frames, kBpm) == ProcessResult::Ok);
            start += frames;
        }
        CHECK(identical(single, irregular));
        processor.reset(4242); auto first = input; process(processor, first, 257);
        processor.reset(); auto again = input; process(processor, again, 257);
        CHECK(identical(first, again)); CHECK_EQ(processor.seed(), 4242u);
        CHECK(processor.prepare(kRate, 8192)); again = input; process(processor, again, 257);
        CHECK(identical(first, again));
    }
    const auto values = parametersForPreset(Preset::Drift);
    CHECK(!identical(render(input, values, kRate, 257, 1), render(input, values, kRate, 257, 2)));
    CHECK(identical(render(input, values, kRate, 257, 0), accepted(input, Preset::Drift, kRate, 257, 0)));
    // A transport reset between pieces must differ from a continuously running bus.
    Processor continuous; CHECK(continuous.applySnapshot(parametersForPreset(Preset::Motion)));
    CHECK(continuous.prepare(kRate, 8192));
    auto firstHalf = source(), secondHalf = firstHalf;
    process(continuous, firstHalf, 257); process(continuous, secondHalf, 257);
    CHECK(!identical(firstHalf, secondHalf));
}

TEST(surface_transfer_and_fathom_stereo_release_survive_the_public_boundary) {
    const auto input = source();
    for (int mode = 0; mode < 3; ++mode) {
        ParameterSnapshot neutral; set(neutral, "aqua_surface_mix", 1); set(neutral, "aqua_surface_mode", float(mode));
        set(neutral, "aqua_surface_resonance", 1);
        CHECK(identical(input, render(input, neutral)));
    }
    for (float gain : {-6.0f, 6.0f}) {
        CHECK_NEAR(surfaceResponse(100, 0, gain), 0, 0.12);
        CHECK_NEAR(surfaceResponse(15000, 0, gain), gain, 0.35);
        CHECK_NEAR(surfaceResponse(100, 1, gain), gain, 0.12);
        CHECK_NEAR(surfaceResponse(15000, 1, gain), 0, 0.35);
        CHECK_NEAR(surfaceResponse(3200, 2, gain), gain, 0.02);
    }
    for (float gain : {-18.0f, -6.0f, 6.0f, 18.0f}) for (int mode : {0, 1})
        CHECK_NEAR(surfaceResponse(3200, mode, gain), gain * 0.5, 0.03);
    CHECK(surfaceResponse(15000, 0, 6, 12000) - surfaceResponse(15000, 0, 6, 16000) > 1);
    ParameterSnapshot neutral;
    set(neutral, "aqua_fathom_mix", 1); set(neutral, "aqua_fathom_depth", 0);
    set(neutral, "aqua_fathom_lowGain", 1.0f / 1.05f); set(neutral, "aqua_fathom_highGain", 1.0f / 1.05f);
    CHECK(maximumError(input, render(input, neutral)) < 1e-6f);
    auto linked = input;
    for (std::size_t i = 0; i < linked.left.size(); ++i) linked.right[i] = 0.5f * linked.left[i];
    const auto output = render(linked, parametersForPreset(Preset::Fathom));
    float linkError = 0;
    for (std::size_t i = 0; i < output.left.size(); ++i) linkError = std::max(linkError, std::abs(output.right[i] - 0.5f * output.left[i]));
    CHECK(linkError < 1e-7f);
    Audio step{std::vector<float>(kRate), std::vector<float>(kRate)};
    for (int i = 0; i < kRate; ++i) step.left[i] = step.right[i] = (i < 12000 ? 0.5f : 0.002f) * std::sin(2 * kPi * 1000 * i / kRate);
    ParameterSnapshot compression;
    set(compression, "aqua_fathom_mix", 1); set(compression, "aqua_fathom_depth", 1);
    set(compression, "aqua_fathom_lowGain", 1.0f / 1.05f); set(compression, "aqua_fathom_highGain", 1.0f / 1.05f);
    const auto compressed = render(step, compression);
    CHECK(rms(compressed.left, 12000 + 9600, 480) > 1.5 * rms(compressed.left, 12000 + 480, 480));
}

TEST(movement_controls_and_sample_hold_have_real_effect_and_continuous_timing) {
    const auto input = source(kRate, 0.3);
    auto slow = parametersForPreset(Preset::Drift), fast = slow;
    set(slow, "aqua_drift_rate", 0.001f); set(fast, "aqua_drift_rate", 2);
    CHECK(!identical(render(input, slow), render(input, fast)));
    auto mono = parametersForPreset(Preset::Tide), wide = mono;
    set(mono, "aqua_tide_stereoPhase", 0); set(wide, "aqua_tide_stereoPhase", 180);
    CHECK(!identical(render(input, mono), render(input, wide)));
    auto hold = parametersForPreset(Preset::Tide);
    set(hold, "aqua_tide_shape", 2); set(hold, "aqua_tide_syncDiv", 7);
    CHECK(identical(render(input, hold, kRate, 17), render(input, hold, kRate, 8192)));
    CHECK(!identical(render(input, hold, kRate, 257, 11), render(input, hold, kRate, 257, 12)));
    set(mono, "aqua_tide_syncEnabled", 0); set(wide, "aqua_tide_syncEnabled", 0);
    set(mono, "aqua_tide_rate", 0.01f); set(wide, "aqua_tide_rate", 8);
    CHECK(!identical(render(input, mono), render(input, wide)));
}

TEST(impulses_and_sustained_drive_remain_finite_with_decaying_tails_at_multiple_rates) {
    for (int rate : {8000, 44100, 48000, 96000, 192000}) {
        Audio impulse{std::vector<float>(rate * 5 / 2), std::vector<float>(rate * 5 / 2)};
        impulse.left[rate / 4] = 0.7f; impulse.right[rate / 4] = 0.35f;
        for (std::size_t i = 0; i < kPresetCount; ++i) {
            const auto result = render(impulse, parametersForPreset(static_cast<Preset>(i)), rate);
            CHECK(finite(result)); CHECK(peak(result) < 4);
            CHECK(peak(result, rate * 9 / 4) < 0.0001f);
        }
    }
    auto driven = source(kRate, 0.3);
    for (std::size_t i = 0; i < driven.left.size(); ++i) { driven.left[i] *= 6; driven.right[i] *= 6; }
    for (int mode : {0, 1, 2}) {
        auto values = parametersForPreset(Preset::Biolume);
        set(values, "aqua_biolume_mix", 1); set(values, "aqua_biolume_drive", 1);
        set(values, "aqua_biolume_character", 1); set(values, "aqua_biolume_shimmer", 1);
        set(values, "aqua_biolume_threshold", -60); set(values, "aqua_biolume_satMode", float(mode));
        const auto result = render(driven, values);
        CHECK(finite(result)); CHECK(peak(result) < 4);
    }
}

TEST(callback_operations_do_not_allocate_or_free) {
    Processor processor;
    const auto full = parametersForPreset(Preset::Full);
    auto invalid = full; invalid.values.back() = std::numeric_limits<float>::quiet_NaN();
    CHECK(processor.applySnapshot(full)); CHECK(processor.prepare(kRate, 1024));
    auto input = source(kRate, 1024.0 / kRate);
    allocation_probe::allocations = 0; allocation_probe::frees = 0;
    bool applied = true, rejected = true, processed = true;
    allocation_probe::enabled = true;
    for (int i = 0; i < 32; ++i) {
        applied &= processor.applySnapshot(full);
        rejected &= !processor.applySnapshot(invalid);
        processed &= processor.process(input.left.data(), input.right.data(), 1024, kBpm) == ProcessResult::Ok;
        processed &= processor.process(nullptr, nullptr, 0, kBpm) == ProcessResult::Ok;
        rejected &= processor.process(input.left.data(), input.left.data(), 1024, kBpm) == ProcessResult::InvalidBuffer;
        processor.reset(static_cast<std::uint32_t>(i));
        processor.reset();
    }
    allocation_probe::enabled = false;
    CHECK(applied); CHECK(rejected); CHECK(processed);
    CHECK_EQ(allocation_probe::allocations.load(), 0u);
    CHECK_EQ(allocation_probe::frees.load(), 0u);
}

int main() { return check::runAll(); }
