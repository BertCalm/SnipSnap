// SPDX-License-Identifier: MIT
#pragma once

#include "AquariumProcessor.h"
#include "../SpscRing.h"

#include <array>
#include <atomic>
#include <cstdint>

namespace snipsnap::aquarium {

/** A complete control-thread message. Tempo is the host's clock, outside the DSP recipe. */
struct PerformanceSettings {
    ParameterSnapshot parameters;
    std::uint32_t seed = kDefaultSeed;
    std::uint32_t model = kRenderModelVersion;
    double bpm = 0.0;
    float outputGain = 0.5f;
    bool enabled = false;
};

bool validPerformanceSettings(const PerformanceSettings& settings) noexcept;

/**
 * One continuous stereo effect after a PadEngine's existing summed/clamped mix.
 * prepare/release and publish belong to one serialized control producer.
 * beginCallback/process/clearTail belong to the audio consumer. Preparation,
 * release and destruction require stopped callbacks; only preparation allocates.
 *
 * The seven-message queue refuses overflow without changing the desired state.
 * A callback consumes at most seven messages, coalescing to the latest settings
 * while retaining any reset request. Sound changes fade the wet branch to
 * silence in 20 ms, adopt the snapshot, then fade back. Output gain slews in
 * 20 ms without exposing the untrimmed dry signal. Bypass alone ramps 20 ms
 * and keeps the configured processor clocking, including quiet gaps.
 *
 * Output gain precedes a stereo-linked, instantaneous-attack sample-peak guard,
 * with a 50 ms exponential release. It is outside every DSP feedback loop.
 * Static bypass is the original finite stereo bus, bit for bit. During a
 * transition the dry branch can reach its existing +/-1 ceiling; fully wet
 * output is bounded by kOutputCeiling. This is not true-peak protection.
 */
class PerformanceBus {
public:
    static constexpr int kBlockFrames = 256;
    static constexpr double kTransitionSeconds = 0.020;
    static constexpr double kLimiterReleaseSeconds = 0.050;
    static constexpr float kOutputCeiling = 0.99f;
    static constexpr int kQueueCapacity = 8;

    bool prepare(double sampleRate) noexcept;
    void release() noexcept;
    bool publish(const PerformanceSettings& settings, bool reset = false) noexcept;
    void beginCallback() noexcept;
    void process(float* interleaved, int frames) noexcept;
    /** Panic/bank replacement: fade the wet path down, clear history, then rejoin. */
    void clearTail() noexcept;
    /** All-off keeps wet output down until a valid new note explicitly resumes. */
    void panic() noexcept;
    void resumeAfterPanic() noexcept;

    bool isPrepared() const noexcept { return prepared_; }
    bool faulted() const noexcept { return faulted_.load(std::memory_order_acquire); }
    std::uint64_t limitedFrames() const noexcept { return limitedFrames_.load(std::memory_order_acquire); }
    /** Control-thread desired state; a refused publication never changes it. */
    const PerformanceSettings& controlSettings() const noexcept { return control_; }

private:
    struct Update { PerformanceSettings settings; bool reset = false; };
    static bool hasMix(const ParameterSnapshot& parameters) noexcept;
    static bool sameSound(const PerformanceSettings& a, const PerformanceSettings& b) noexcept;
    void setGoal(double goal) noexcept;
    void advanceWeight() noexcept;
    void setMixGoal(double goal) noexcept;
    void advanceMix() noexcept;
    void setGainGoal(double goal) noexcept;
    void advanceGain() noexcept;
    void renderWithoutWet(float* out, int count) noexcept;
    void commitAtDry() noexcept;
    void failSilent() noexcept;

    Processor processor_;
    SpscRing<Update, kQueueCapacity> updates_;
    PerformanceSettings control_;  // producer only
    PerformanceSettings current_, desired_;  // consumer, or stopped preparation
    std::array<float, kBlockFrames> left_{}, right_{};
    bool prepared_ = false;
    bool pending_ = false, resetPending_ = false;
    bool suspended_ = false;
    int transitionFrames_ = 1, rampRemaining_ = 0;
    double wet_ = 0.0, goal_ = 0.0, wetStep_ = 0.0;
    int mixRemaining_ = 0, gainRemaining_ = 0;
    double mix_ = 0.0, mixGoal_ = 0.0, mixStep_ = 0.0;
    double outputGain_ = 0.5, gainGoal_ = 0.5, gainStep_ = 0.0;
    double limiterGain_ = 1.0, limiterRelease_ = 0.0;
    std::atomic<bool> faulted_{false};
    std::atomic<std::uint64_t> limitedFrames_{0};
    static_assert(std::atomic<std::uint64_t>::is_always_lock_free,
                  "performance diagnostics must be lock-free on every Android ABI");
};

}  // namespace snipsnap::aquarium
