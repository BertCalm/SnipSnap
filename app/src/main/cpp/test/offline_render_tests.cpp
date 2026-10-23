// SPDX-License-Identifier: MIT
// Offline rendering exercises the production core; it is not a callback test.
#include "aquarium/OfflineRender.h"
#include "check.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

namespace offline_allocation_probe {
thread_local long failAfter = -1;
void beforeAllocation() {
    if (failAfter >= 0 && failAfter-- == 0) throw std::bad_alloc{};
}
}
void* operator new(std::size_t size) {
    offline_allocation_probe::beforeAllocation();
    if (void* p = std::malloc(size ? size : 1)) return p;
    throw std::bad_alloc{};
}
void* operator new[](std::size_t size) { return ::operator new(size); }
// Keep the replacement deallocator out of optimizer call-site folding; its
// malloc/free pairing is deliberate for deterministic allocation-failure probes.
[[gnu::noinline]] void operator delete(void* p) noexcept { std::free(p); }
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
    offline_allocation_probe::beforeAllocation();
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
constexpr double kPi = 3.14159265358979323846;
constexpr std::uint32_t kSeed = 0xCAFEBABEu;

bool identical(const std::vector<float>& a, const std::vector<float>& b) {
    return a.size() == b.size() && (a.empty() || std::memcmp(a.data(), b.data(), a.size() * sizeof(float)) == 0);
}
std::vector<float> source(int channels = 2, int frames = 6000, int rate = 44100) {
    std::vector<float> out(static_cast<std::size_t>(frames) * channels);
    for (int i = 0; i < frames; ++i) {
        out[static_cast<std::size_t>(i) * channels] = .14f * std::sin(2 * kPi * 237 * i / rate);
        if (channels == 2) out[static_cast<std::size_t>(i) * 2 + 1] = .08f * std::cos(2 * kPi * 463 * i / rate);
    }
    return out;
}
std::vector<float> render(const std::vector<float>& input, int channels,
                          const ParameterSnapshot& snapshot, const OfflineRenderOptions& options,
                          double sampleRate = 44100, std::uint32_t seed = kSeed) {
    std::vector<float> result;
    CHECK(renderOffline(input.data(), input.size(), channels, sampleRate, snapshot, seed, options, result) == OfflineRenderStatus::Ok);
    return result;
}
std::vector<float> direct(const std::vector<float>& input, int channels,
                         const ParameterSnapshot& snapshot, const OfflineRenderOptions& options,
                         double sampleRate = 44100) {
    const std::size_t inputFrames = input.size() / channels;
    const std::size_t totalFrames = inputFrames + options.tailFrames;
    std::vector<float> left(totalFrames), right(totalFrames);
    for (std::size_t i = 0; i < inputFrames; ++i) {
        left[i] = input[i * channels];
        right[i] = channels == 1 ? input[i] : input[i * 2 + 1];
    }
    Processor processor;
    CHECK(processor.applySnapshot(snapshot));
    processor.reset(kSeed);
    CHECK(processor.prepare(sampleRate, options.blockSize));
    for (std::size_t i = 0; i < totalFrames; i += options.blockSize) {
        const int count = static_cast<int>(std::min<std::size_t>(options.blockSize, totalFrames - i));
        CHECK(processor.process(left.data() + i, right.data() + i, count, options.bpm) == ProcessResult::Ok);
    }
    std::vector<float> output(totalFrames * 2);
    for (std::size_t i = 0; i < totalFrames; ++i) {
        float gain = options.outputGain;
        if (options.fadeFrames && i >= totalFrames - options.fadeFrames) {
            const auto remaining = totalFrames - i - 1;
            gain *= options.fadeFrames == 1 ? 0.0f : static_cast<float>(remaining) / static_cast<float>(options.fadeFrames - 1);
        }
        output[i * 2] = left[i] * gain;
        output[i * 2 + 1] = right[i] * gain;
    }
    return output;
}
void reject(const std::vector<float>& input, int channels, double sampleRate,
            const ParameterSnapshot& snapshot, const OfflineRenderOptions& options,
            OfflineRenderStatus expected) {
    const auto inputBefore = input;
    std::vector<float> output{42.f, -3.f, 99.f};
    const auto outputBefore = output;
    const auto status = renderOffline(input.data(), input.size(), channels, sampleRate,
                                      snapshot, kSeed, options, output);
    CHECK(status == expected);
    CHECK(identical(input, inputBefore));
    CHECK(identical(output, outputBefore));
}
}  // namespace

TEST(offline_dry_stereo_is_exact_and_mono_duplicates_without_source_mutation) {
    const ParameterSnapshot dry;
    OfflineRenderOptions options;
    const auto stereo = source();
    const auto before = stereo;
    CHECK(identical(render(stereo, 2, dry, options), stereo));
    CHECK(identical(stereo, before));
    const std::vector<float> signedZeros{0.f, -0.f, .25f, -.5f};
    CHECK(identical(render(signedZeros, 2, dry, options), signedZeros));
    const auto mono = source(1);
    const auto duplicated = render(mono, 1, dry, options);
    CHECK_EQ(duplicated.size(), mono.size() * 2);
    for (std::size_t i = 0; i < mono.size(); ++i) {
        CHECK_EQ(duplicated[i * 2], mono[i]);
        CHECK_EQ(duplicated[i * 2 + 1], mono[i]);
    }
}

TEST(offline_matches_continuous_direct_processor_with_seed_tempo_and_tail) {
    const auto input = source();
    OfflineRenderOptions options;
    options.bpm = 123.0;
    options.tailFrames = 8193;
    options.blockSize = 257;
    options.peakCeiling = 8.f;
    const auto snapshot = parametersForPreset(Preset::Full);
    const auto actual = render(input, 2, snapshot, options);
    CHECK(identical(actual, direct(input, 2, snapshot, options)));
    CHECK(identical(actual, render(input, 2, snapshot, options)));
    bool tailHasEnergy = false;
    for (std::size_t i = input.size(); i < actual.size(); ++i) tailHasEnergy |= actual[i] != 0.f;
    CHECK(tailHasEnergy);
    const auto mono = source(1);
    CHECK(identical(render(mono, 1, snapshot, options), direct(mono, 1, snapshot, options)));
}

TEST(offline_modulation_is_independent_of_block_partition_for_actual_sample_rates) {
    const auto snapshot = parametersForPreset(Preset::Full);
    for (int rate : {8000, 44100, 48000, 96000, 192000}) {
        const auto input = source(2, 2500, rate);
        OfflineRenderOptions options;
        options.bpm = 137;
        options.tailFrames = 300;
        options.peakCeiling = 8;
        options.blockSize = 1;
        const auto one = render(input, 2, snapshot, options, rate);
        options.blockSize = 257;
        CHECK(identical(one, render(input, 2, snapshot, options, rate)));
        options.blockSize = 8192;
        CHECK(identical(one, render(input, 2, snapshot, options, rate)));
    }
}

TEST(offline_gain_linear_fade_and_tail_frame_count_are_explicit) {
    const std::vector<float> input{.25f, -.5f, .5f, -.25f, 1.f, -.75f, -.5f, 1.f};
    OfflineRenderOptions options;
    options.outputGain = .5f;
    options.fadeFrames = 3;
    const auto out = render(input, 2, ParameterSnapshot{}, options);
    const std::vector<float> expected{.125f, -.25f, .25f, -.125f, .25f, -.1875f, 0.f, 0.f};
    CHECK(out == expected);
    options.tailFrames = 2;
    options.fadeFrames = 0;
    const auto withTail = render(input, 2, ParameterSnapshot{}, options);
    CHECK_EQ(withTail.size(), input.size() + 4);
    for (std::size_t i = 0; i < input.size(); ++i) CHECK_EQ(withTail[i], input[i] * .5f);
    for (std::size_t i = input.size(); i < withTail.size(); ++i) CHECK_EQ(withTail[i], 0.f);
    options.tailFrames = 0;
    options.fadeFrames = 1;
    const auto last = render(input, 2, ParameterSnapshot{}, options);
    CHECK_EQ(last[last.size() - 2], 0.f);
    CHECK_EQ(last.back(), 0.f);
    for (std::size_t i = 0; i < input.size() - 2; ++i) CHECK_EQ(last[i], input[i] * .5f);
    options.fadeFrames = 4;
    const auto entire = render(input, 2, ParameterSnapshot{}, options);
    CHECK_EQ(entire[0], .125f);
    CHECK_EQ(entire.back(), 0.f);
}

TEST(offline_peak_is_checked_after_gain_and_fade_and_never_clipped_or_normalized) {
    OfflineRenderOptions options;
    const std::vector<float> input{.75f, -.75f};
    options.outputGain = 2.f;
    reject(input, 2, 44100, ParameterSnapshot{}, options, OfflineRenderStatus::PeakExceeded);
    options.outputGain = .5f;
    const auto lowered = render(input, 2, ParameterSnapshot{}, options);
    CHECK_EQ(lowered[0], .375f);
    CHECK_EQ(lowered[1], -.375f);
    options.outputGain = 2.f;
    options.fadeFrames = 1;
    const auto faded = render(input, 2, ParameterSnapshot{}, options);
    CHECK_EQ(faded[0], 0.f);
    CHECK_EQ(faded[1], 0.f);
    options.outputGain = 1.f;
    options.fadeFrames = 0;
    options.peakCeiling = .75f;
    CHECK(identical(render(input, 2, ParameterSnapshot{}, options), input));
    options.peakCeiling = .749f;
    reject(input, 2, 44100, ParameterSnapshot{}, options, OfflineRenderStatus::PeakExceeded);
    options.outputGain = std::numeric_limits<float>::max();
    options.peakCeiling = std::numeric_limits<float>::max();
    reject({2.f, -2.f}, 2, 44100, ParameterSnapshot{}, options, OfflineRenderStatus::NonFiniteOutput);
    options.outputGain = 0.f;
    const auto muted = render(input, 2, ParameterSnapshot{}, options);
    CHECK_EQ(muted[0], 0.f);
    CHECK_EQ(muted[1], 0.f);
}

TEST(offline_rejects_invalid_sources_and_configs_without_mutation) {
    const auto good = source(2, 16);
    const ParameterSnapshot defaults;
    const OfflineRenderOptions options;
    reject({}, 2, 44100, defaults, options, OfflineRenderStatus::InvalidInput);
    reject(good, 0, 44100, defaults, options, OfflineRenderStatus::InvalidChannels);
    reject(good, 3, 44100, defaults, options, OfflineRenderStatus::InvalidChannels);
    reject({.1f, .2f, .3f}, 2, 44100, defaults, options, OfflineRenderStatus::InvalidInput);
    for (double rate : {7999.0, 192001.0, 0.0, -44100.0, std::numeric_limits<double>::quiet_NaN(), std::numeric_limits<double>::infinity()})
        reject(good, 2, rate, defaults, options, OfflineRenderStatus::InvalidSampleRate);
    for (float bad : {32.01f, -32.01f, std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
        auto input = good; input.back() = bad;
        reject(input, 2, 44100, defaults, options, OfflineRenderStatus::InvalidInput);
    }
    std::vector<float> output{9};
    CHECK(renderOffline(nullptr, 4, 2, 44100, defaults, kSeed, options, output) == OfflineRenderStatus::InvalidInput);
    CHECK(identical(output, std::vector<float>{9}));
    std::vector<float> aliased{.1f, .2f, .3f, .4f};
    const auto aliasedBefore = aliased;
    CHECK(renderOffline(aliased.data(), aliased.size(), 2, 44100, defaults, kSeed, options, aliased) == OfflineRenderStatus::InvalidInput);
    CHECK(identical(aliased, aliasedBefore));
}

TEST(offline_invalid_snapshots_and_options_reject_atomically) {
    const auto good = source(2, 16);
    const ParameterSnapshot defaults;
    const OfflineRenderOptions options;
    for (float invalid : {-1.f, 2.f, std::numeric_limits<float>::quiet_NaN(), std::numeric_limits<float>::infinity()}) {
        auto snapshot = defaults; snapshot.values[0] = invalid;
        reject(good, 2, 44100, snapshot, options, OfflineRenderStatus::InvalidParameters);
    }
    auto fractional = defaults;
    for (std::size_t i = 0; i < kParameterCount; ++i) if (parameterInfo()[i].step != 0) {
        fractional = defaults;
        fractional.values[i] = parameterInfo()[i].minimum + .5f;
        reject(good, 2, 44100, fractional, options, OfflineRenderStatus::InvalidParameters);
    }
    std::vector<OfflineRenderOptions> invalid;
    auto add = [&](auto mutate) { auto changed = options; mutate(changed); invalid.push_back(changed); };
    add([](auto& o) { o.bpm = -1; });
    add([](auto& o) { o.bpm = 1001; });
    add([](auto& o) { o.bpm = std::numeric_limits<double>::quiet_NaN(); });
    add([](auto& o) { o.tailFrames = -1; });
    add([](auto& o) { o.fadeFrames = -1; });
    add([](auto& o) { o.fadeFrames = 17; });
    add([](auto& o) { o.outputGain = -1; });
    add([](auto& o) { o.outputGain = std::numeric_limits<float>::quiet_NaN(); });
    add([](auto& o) { o.outputGain = std::numeric_limits<float>::infinity(); });
    add([](auto& o) { o.peakCeiling = 0; });
    add([](auto& o) { o.peakCeiling = -1; });
    add([](auto& o) { o.peakCeiling = std::numeric_limits<float>::quiet_NaN(); });
    add([](auto& o) { o.blockSize = 0; });
    add([](auto& o) { o.blockSize = 8193; });
    for (const auto& bad : invalid) reject(good, 2, 44100, defaults, bad, OfflineRenderStatus::InvalidOptions);
}

TEST(offline_hard_frame_bound_and_integer_overflow_reject_before_read_or_allocation) {
    const float one = .1f;
    std::vector<float> output{7.f, 8.f};
    const auto before = output;
    const ParameterSnapshot defaults;
    OfflineRenderOptions options;
    CHECK_EQ(kMaxOfflineOutputFrames, 8000000u);
    CHECK(validateOfflineRender(kMaxOfflineOutputFrames * 2, 2, 44100, defaults, options) == OfflineRenderStatus::Ok);
    CHECK(renderOffline(&one, (kMaxOfflineOutputFrames + 1) * 2, 2, 44100, defaults, kSeed, options, output) == OfflineRenderStatus::TooManyFrames);
    CHECK(identical(output, before));
    CHECK(renderOffline(&one, std::numeric_limits<std::size_t>::max() - 1, 2, 44100, defaults, kSeed, options, output) == OfflineRenderStatus::TooManyFrames);
    CHECK(identical(output, before));
    options.tailFrames = std::numeric_limits<int>::max();
    CHECK(renderOffline(&one, 1, 1, 44100, defaults, kSeed, options, output) == OfflineRenderStatus::TooManyFrames);
    CHECK(identical(output, before));
    options.tailFrames = static_cast<int>(kMaxOfflineOutputFrames);
    CHECK(renderOffline(&one, 1, 1, 44100, defaults, kSeed, options, output) == OfflineRenderStatus::TooManyFrames);
    CHECK(identical(output, before));
}

TEST(offline_allocation_failure_reports_status_and_leaves_source_and_destination_unchanged) {
    const auto input = source(2, 32);
    const auto inputBefore = input;
    const auto snapshot = parametersForPreset(Preset::Full);
    OfflineRenderOptions options;
    options.peakCeiling = 8;
    int failed = 0;
    for (long failAfter = 0; failAfter < 12; ++failAfter) {
        std::vector<float> output{5.f, -9.f};
        const auto outputBefore = output;
        offline_allocation_probe::failAfter = failAfter;
        const auto status = renderOffline(input.data(), input.size(), 2, 44100, snapshot, kSeed, options, output);
        offline_allocation_probe::failAfter = -1;
        CHECK(status == OfflineRenderStatus::AllocationFailure);
        CHECK(identical(input, inputBefore));
        CHECK(identical(output, outputBefore));
        if (status == OfflineRenderStatus::AllocationFailure) ++failed;
    }
    CHECK_EQ(failed, 12);
}

int main() { return check::runAll(); }
