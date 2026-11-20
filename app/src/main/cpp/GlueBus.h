// SPDX-License-Identifier: MIT
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

#include "SpscRing.h"

namespace snipsnap {

/**
 * The whole-kit "glue": one compressor and one lookahead limiter across the
 * pads' mix, the chain BOROUGH's listening page previewed (prototype/borough
 * build.py `glue_bus` then `limit`, round 1.4) and the owner asked for live.
 *
 * A complete control-thread message. Off is the default and is the original
 * mix bit for bit.
 */
struct GlueSettings {
    bool enabled = false;
    /** Where the compressor starts, in dBFS. The preview sat 10 dB under its groove's peak. */
    float thresholdDbfs = -10.0f;
    float ratio = 4.0f;
    /** Gain after the compressor, before the limiter. */
    float makeupDb = 0.0f;
    /** How hard the compressed mix is pushed into the limiter (the preview: 5.0 to 7.5 dB). */
    float driveDb = 0.0f;
    /** The limiter's sample-peak ceiling. -1 dBFS is 0.891, the kits' own 0.89 peak. */
    float ceilingDbfs = -1.0f;
};

inline bool validGlueSettings(const GlueSettings& s) noexcept {
    auto in = [](float v, float lo, float hi) { return std::isfinite(v) && v >= lo && v <= hi; };
    return in(s.thresholdDbfs, -40.0f, 0.0f) && in(s.ratio, 1.0f, 20.0f) && in(s.makeupDb, 0.0f, 24.0f) &&
           in(s.driveDb, 0.0f, 24.0f) && in(s.ceilingDbfs, -12.0f, 0.0f);
}

/**
 * Glue compressor then lookahead peak limiter, stereo-linked, for one owning
 * audio callback.
 *
 * Compressor (as the preview's `glue_bus`): a peak detector on max(|L|,|R|)
 * with a 10 ms attack and 120 ms release (one-pole each way), ratio 4 above
 * the threshold by default, makeup after. Causal, like a hardware bus
 * compressor: its attack lets the crack of each hit through.
 *
 * Limiter (as the preview's `limit`): the gain each frame needs to stay under
 * the ceiling, its minimum over the 3 ms lookahead, a 50 ms release, then a
 * 3 ms moving average. Every value averaged is a minimum over a window that
 * holds the frame being released from the delay line, so the average cannot
 * overshoot; a final clamp at the ceiling is a guard that counts if it ever
 * acts. The release stays 50 ms: a 3 ms recovery moves the gain inside one
 * cycle of a 50-100 Hz kick, the distortion the owner heard as an overblown
 * speaker.
 *
 * Latency: while enabled the whole mix is delayed by the lookahead, 3 ms
 * ([latencyFrames]). Bypass adds none and touches no sample.
 *
 * Threading (PerformanceBus's rules): prepare/release need stopped callbacks
 * and are the only calls that allocate; publish belongs to one serialized
 * control producer (an SPSC ring of complete settings); beginCallback and
 * process belong to the audio consumer and never allocate, lock or log.
 * Enabling and disabling crossfade over 20 ms; while enabled, setting changes
 * glide over 20 ms.
 */
class GlueBus {
public:
    static constexpr double kAttackSeconds = 0.010;
    static constexpr double kReleaseSeconds = 0.120;
    static constexpr double kLookaheadSeconds = 0.003;
    static constexpr double kLimiterReleaseSeconds = 0.050;
    static constexpr double kTransitionSeconds = 0.020;
    static constexpr double kMinimumSampleRate = 8000.0;
    static constexpr double kMaximumSampleRate = 192000.0;
    /** Inputs are held to this magnitude inside the stage (the Aquarium processor's bound). */
    static constexpr float kMaximumInputMagnitude = 32.0f;
    static constexpr int kQueueCapacity = 8;

    bool prepare(double sampleRate) {
        prepared_ = false;
        if (!std::isfinite(sampleRate) || sampleRate < kMinimumSampleRate || sampleRate > kMaximumSampleRate) return false;
        const int lookahead = std::max(1, static_cast<int>(std::lround(sampleRate * kLookaheadSeconds)));
        try {
            delayL_.assign(static_cast<size_t>(lookahead), 0.0f);
            delayR_.assign(static_cast<size_t>(lookahead), 0.0f);
            boxed_.assign(static_cast<size_t>(lookahead), 1.0);
            minValue_.assign(static_cast<size_t>(lookahead) + 2, 1.0);
            minIndex_.assign(static_cast<size_t>(lookahead) + 2, 0);
        } catch (...) {
            return false;
        }
        lookahead_ = lookahead;
        attack_ = std::exp(-1.0 / (sampleRate * kAttackSeconds));
        release_ = std::exp(-1.0 / (sampleRate * kReleaseSeconds));
        limiterRelease_ = 1.0 - std::exp(-1.0 / (sampleRate * kLimiterReleaseSeconds));
        transitionFrames_ = std::max(1, static_cast<int>(std::ceil(sampleRate * kTransitionSeconds)));
        GlueSettings discarded;
        for (int i = 0; i < kQueueCapacity - 1 && updates_.pop(discarded); ++i) {}
        current_ = control_;
        snapTo(current_);
        mix_ = mixGoal_ = current_.enabled ? 1.0 : 0.0;
        mixRemaining_ = 0;
        clearState();
        clamped_.store(0, std::memory_order_release);
        prepared_ = true;
        return true;
    }

    void release() noexcept {
        prepared_ = false;
        mix_ = mixGoal_ = 0.0;
        mixRemaining_ = rampRemaining_ = 0;
    }

    /** Control producer. Refuses invalid or overflowing settings without changing what it last asked for. */
    bool publish(const GlueSettings& settings) noexcept {
        if (!validGlueSettings(settings)) return false;
        if (!updates_.push(settings)) return false;
        control_ = settings;
        return true;
    }

    /** Audio consumer, once per callback: adopt the newest settings published. */
    void beginCallback() noexcept {
        GlueSettings update;
        bool received = false;
        for (int i = 0; i < kQueueCapacity - 1 && updates_.pop(update); ++i) received = true;
        if (!received || !prepared_) {
            if (received) current_ = update;
            return;
        }
        const bool wasSilent = mix_ == 0.0 && mixGoal_ == 0.0;
        current_ = update;
        if (wasSilent) {
            // Nothing of the stage is audible: take the sound at once, and
            // start from a clear detector and an empty delay line.
            snapTo(current_);
            if (current_.enabled) clearState();
        } else {
            glideTo(current_);
        }
        setMixGoal(current_.enabled ? 1.0 : 0.0);
    }

    void process(float* interleaved, int frames) noexcept {
        if (!prepared_ || !interleaved || frames <= 0) return;
        if (mix_ == 0.0 && mixGoal_ == 0.0) return;  // bypass: not one sample touched
        uint64_t clamped = 0;
        for (int i = 0; i < frames; ++i) {
            float& outL = interleaved[2 * i];
            float& outR = interleaved[2 * i + 1];
            const float dryL = std::isfinite(outL) ? outL : 0.0f;
            const float dryR = std::isfinite(outR) ? outR : 0.0f;
            advanceParameters();
            const double xL = std::clamp(dryL, -kMaximumInputMagnitude, kMaximumInputMagnitude);
            const double xR = std::clamp(dryR, -kMaximumInputMagnitude, kMaximumInputMagnitude);

            // Compressor: peak detector, gain from the curve above threshold.
            const double level = std::max(std::fabs(xL), std::fabs(xR));
            envelope_ = level > envelope_ ? attack_ * envelope_ + (1.0 - attack_) * level
                                          : release_ * envelope_ + (1.0 - release_) * level;
            if (envelope_ < 1e-30) envelope_ = 0.0;  // no denormals in silence
            const double reduction = envelope_ > threshold_ ? std::pow(envelope_ / threshold_, 1.0 / ratio_ - 1.0) : 1.0;
            // Rounded to the delay line's floats now, so the gain below is
            // worked out for exactly the samples that will come out of it.
            const float yL = static_cast<float>(xL * reduction * makeup_ * drive_);
            const float yR = static_cast<float>(xR * reduction * makeup_ * drive_);

            // Limiter: the gain this frame needs, its minimum over the lookahead,
            // the release, then the average over the lookahead.
            const double peak = std::max(std::fabs(static_cast<double>(yL)), std::fabs(static_cast<double>(yR)));
            const double needed = peak > ceiling_ ? ceiling_ / peak : 1.0;
            pushMinimum(needed);
            const double windowMinimum = minValue_[static_cast<size_t>(minHead_)];
            held_ = std::min(windowMinimum, held_ + (1.0 - held_) * limiterRelease_);
            boxSum_ += held_ - boxed_[static_cast<size_t>(boxAt_)];
            boxed_[static_cast<size_t>(boxAt_)] = held_;
            if (++boxAt_ == lookahead_) {
                boxAt_ = 0;
                // Re-add from the values once per window so rounding cannot drift.
                boxSum_ = 0.0;
                for (double v : boxed_) boxSum_ += v;
            }
            const double gain = boxSum_ / static_cast<double>(lookahead_);

            const size_t at = static_cast<size_t>(delayAt_);
            double wetL = static_cast<double>(delayL_[at]) * gain;
            double wetR = static_cast<double>(delayR_[at]) * gain;
            delayL_[at] = yL;
            delayR_[at] = yR;
            if (++delayAt_ == lookahead_) delayAt_ = 0;
            // The guard: the gain above keeps every sample at the ceiling up to
            // double rounding (the average of ceiling/peak values). Past that
            // margin would be the limiter failing, which is what it counts.
            const double over = std::max(std::fabs(wetL), std::fabs(wetR)) - ceiling_;
            if (over > 0.0) {
                if (over > ceiling_ * 1e-9) ++clamped;
                wetL = std::clamp(wetL, -ceiling_, ceiling_);
                wetR = std::clamp(wetR, -ceiling_, ceiling_);
            }

            advanceMix();
            outL = static_cast<float>((1.0 - mix_) * dryL + mix_ * wetL);
            outR = static_cast<float>((1.0 - mix_) * dryR + mix_ * wetR);
        }
        if (clamped != 0) clamped_.fetch_add(clamped, std::memory_order_release);
    }

    bool isPrepared() const noexcept { return prepared_; }
    /** The delay the stage adds while enabled: the limiter's lookahead. */
    int latencyFrames() const noexcept { return prepared_ ? lookahead_ : 0; }
    /** Samples the final guard had to clamp: the limiter's own gain should leave it at zero. */
    uint64_t clampedSamples() const noexcept { return clamped_.load(std::memory_order_acquire); }
    /** Control-thread desired state; a refused publication never changes it. */
    const GlueSettings& controlSettings() const noexcept { return control_; }

private:
    static double fromDb(double db) noexcept { return std::pow(10.0, db / 20.0); }

    void snapTo(const GlueSettings& s) noexcept {
        thresholdDb_ = s.thresholdDbfs;
        ratio_ = s.ratio;
        makeupDb_ = s.makeupDb;
        driveDb_ = s.driveDb;
        ceilingDb_ = s.ceilingDbfs;
        rampRemaining_ = 0;
        refreshLinear();
    }

    void glideTo(const GlueSettings& s) noexcept {
        const double n = static_cast<double>(transitionFrames_);
        thresholdStep_ = (s.thresholdDbfs - thresholdDb_) / n;
        ratioStep_ = (s.ratio - ratio_) / n;
        makeupStep_ = (s.makeupDb - makeupDb_) / n;
        driveStep_ = (s.driveDb - driveDb_) / n;
        ceilingStep_ = (s.ceilingDbfs - ceilingDb_) / n;
        rampRemaining_ = transitionFrames_;
    }

    void advanceParameters() noexcept {
        if (rampRemaining_ <= 0) return;
        if (--rampRemaining_ == 0) {
            thresholdDb_ = current_.thresholdDbfs;
            ratio_ = current_.ratio;
            makeupDb_ = current_.makeupDb;
            driveDb_ = current_.driveDb;
            ceilingDb_ = current_.ceilingDbfs;
        } else {
            thresholdDb_ += thresholdStep_;
            ratio_ += ratioStep_;
            makeupDb_ += makeupStep_;
            driveDb_ += driveStep_;
            ceilingDb_ += ceilingStep_;
        }
        refreshLinear();
    }

    void refreshLinear() noexcept {
        threshold_ = fromDb(thresholdDb_);
        makeup_ = fromDb(makeupDb_);
        drive_ = fromDb(driveDb_);
        ceiling_ = fromDb(ceilingDb_);
    }

    void setMixGoal(double goal) noexcept {
        if (mixGoal_ == goal) return;
        mixGoal_ = goal;
        mixRemaining_ = transitionFrames_;
        mixStep_ = (mixGoal_ - mix_) / transitionFrames_;
    }

    void advanceMix() noexcept {
        if (mixRemaining_ <= 0) return;
        mix_ += mixStep_;
        if (--mixRemaining_ == 0) mix_ = mixGoal_;
    }

    /** Sliding minimum over the last lookahead + 1 frames, a monotonic queue in fixed arrays. */
    void pushMinimum(double value) noexcept {
        const int capacity = static_cast<int>(minValue_.size());
        while (minCount_ > 0) {
            const int last = (minHead_ + minCount_ - 1) % capacity;
            if (minValue_[static_cast<size_t>(last)] < value) break;
            --minCount_;
        }
        const int slot = (minHead_ + minCount_) % capacity;
        minValue_[static_cast<size_t>(slot)] = value;
        minIndex_[static_cast<size_t>(slot)] = frame_;
        ++minCount_;
        while (minIndex_[static_cast<size_t>(minHead_)] <= frame_ - (lookahead_ + 1)) {
            minHead_ = (minHead_ + 1) % capacity;
            --minCount_;
        }
        ++frame_;
    }

    void clearState() noexcept {
        std::fill(delayL_.begin(), delayL_.end(), 0.0f);
        std::fill(delayR_.begin(), delayR_.end(), 0.0f);
        std::fill(boxed_.begin(), boxed_.end(), 1.0);
        boxSum_ = static_cast<double>(lookahead_);
        boxAt_ = delayAt_ = 0;
        minHead_ = minCount_ = 0;
        frame_ = 0;
        held_ = 1.0;
        envelope_ = 0.0;
    }

    SpscRing<GlueSettings, kQueueCapacity> updates_;
    GlueSettings control_;  // producer only
    GlueSettings current_;  // consumer, or stopped preparation
    bool prepared_ = false;

    double attack_ = 0.0, release_ = 0.0, limiterRelease_ = 0.0;
    int transitionFrames_ = 1;
    double thresholdDb_ = -10.0, ratio_ = 4.0, makeupDb_ = 0.0, driveDb_ = 0.0, ceilingDb_ = -1.0;
    double thresholdStep_ = 0.0, ratioStep_ = 0.0, makeupStep_ = 0.0, driveStep_ = 0.0, ceilingStep_ = 0.0;
    int rampRemaining_ = 0;
    double threshold_ = 1.0, makeup_ = 1.0, drive_ = 1.0, ceiling_ = 1.0;
    double mix_ = 0.0, mixGoal_ = 0.0, mixStep_ = 0.0;
    int mixRemaining_ = 0;

    int lookahead_ = 1;
    double envelope_ = 0.0;
    std::vector<float> delayL_, delayR_;
    int delayAt_ = 0;
    std::vector<double> boxed_;
    double boxSum_ = 1.0;
    int boxAt_ = 0;
    std::vector<double> minValue_;
    std::vector<int64_t> minIndex_;
    int minHead_ = 0, minCount_ = 0;
    int64_t frame_ = 0;
    double held_ = 1.0;
    std::atomic<uint64_t> clamped_{0};
    static_assert(std::atomic<uint64_t>::is_always_lock_free, "glue diagnostics must be lock-free on every Android ABI");
};

}  // namespace snipsnap
