// SPDX-License-Identifier: MIT
#include "PerformanceBus.h"

#include <algorithm>
#include <cmath>

namespace snipsnap::aquarium {

bool validPerformanceSettings(const PerformanceSettings& settings) noexcept {
    if (settings.model != kRenderModelVersion || !std::isfinite(settings.bpm) ||
        settings.bpm < 0.0 || settings.bpm > 1000.0 ||
        !std::isfinite(settings.outputGain) || settings.outputGain < 0.0f ||
        settings.outputGain > 1.0f) return false;
    const auto& info = parameterInfo();
    for (std::size_t i = 0; i < kParameterCount; ++i) {
        const float value = settings.parameters.values[i];
        if (!std::isfinite(value) || value < info[i].minimum || value > info[i].maximum)
            return false;
        if (info[i].step > 0.0f) {
            const float step = (value - info[i].minimum) / info[i].step;
            if (step != std::floor(step)) return false;
        }
    }
    return true;
}

bool PerformanceBus::hasMix(const ParameterSnapshot& parameters) noexcept {
    // Canonical stage order, pinned against the parameter registry in tests.
    constexpr std::size_t mixes[] = {0, 8, 17, 28, 36, 44};
    for (const auto index : mixes) if (parameters.values[index] > 0.0f) return true;
    return false;
}

bool PerformanceBus::sameSound(const PerformanceSettings& a, const PerformanceSettings& b) noexcept {
    return a.parameters.values == b.parameters.values && a.seed == b.seed &&
           a.bpm == b.bpm;
}

bool PerformanceBus::prepare(double sampleRate) noexcept {
    // The stream has not started; the producer owns both ends during preparation.
    prepared_ = false;
    if (!processor_.prepare(sampleRate, kBlockFrames)) return false;
    Update discarded;
    for (int i = 0; i < kQueueCapacity - 1 && updates_.pop(discarded); ++i) {}
    current_ = desired_ = control_;
    if (!processor_.applySnapshot(current_.parameters)) return false;
    processor_.reset(current_.seed);
    transitionFrames_ = std::max(1, static_cast<int>(std::ceil(sampleRate * kTransitionSeconds)));
    limiterRelease_ = std::exp(-1.0 / (sampleRate * kLimiterReleaseSeconds));
    limiterGain_ = 1.0;
    wet_ = goal_ = wetStep_ = 0.0;
    mix_ = mixGoal_ = mixStep_ = 0.0;
    outputGain_ = gainGoal_ = current_.outputGain;
    gainStep_ = 0.0;
    mixRemaining_ = gainRemaining_ = 0;
    rampRemaining_ = 0;
    pending_ = resetPending_ = false;
    suspended_ = false;
    faulted_.store(false, std::memory_order_release);
    limitedFrames_.store(0, std::memory_order_release);
    prepared_ = true;
    const bool configured = hasMix(current_.parameters);
    mix_ = mixGoal_ = current_.enabled && configured ? 1.0 : 0.0;
    // A resumed wet stream fades in from silence. Only an explicit user
    // bypass/enable crossfades to/from the untrimmed original signal.
    wet_ = goal_ = configured && !current_.enabled ? 1.0 : 0.0;
    setGoal(configured ? 1.0 : 0.0);
    return true;
}

void PerformanceBus::release() noexcept {
    prepared_ = false;
    processor_.release();
    wet_ = goal_ = wetStep_ = 0.0;
    mix_ = mixGoal_ = mixStep_ = 0.0;
    mixRemaining_ = gainRemaining_ = 0;
    rampRemaining_ = 0;
}

bool PerformanceBus::publish(const PerformanceSettings& settings, bool reset) noexcept {
    if (!validPerformanceSettings(settings)) return false;
    if (!updates_.push(Update{settings, reset})) return false;
    control_ = settings;
    return true;
}

void PerformanceBus::setGoal(double goal) noexcept {
    if (goal_ == goal) return;
    goal_ = goal;
    rampRemaining_ = transitionFrames_;
    wetStep_ = (goal_ - wet_) / transitionFrames_;
}

void PerformanceBus::advanceWeight() noexcept {
    if (rampRemaining_ <= 0) return;
    wet_ += wetStep_;
    if (--rampRemaining_ == 0) wet_ = goal_;
}

void PerformanceBus::setMixGoal(double goal) noexcept {
    if (mixGoal_ == goal) return;
    mixGoal_ = goal;
    mixRemaining_ = transitionFrames_;
    mixStep_ = (mixGoal_ - mix_) / transitionFrames_;
}

void PerformanceBus::advanceMix() noexcept {
    if (mixRemaining_ <= 0) return;
    mix_ += mixStep_;
    if (--mixRemaining_ == 0) mix_ = mixGoal_;
}

void PerformanceBus::setGainGoal(double goal) noexcept {
    if (gainGoal_ == goal) return;
    gainGoal_ = goal;
    gainRemaining_ = transitionFrames_;
    gainStep_ = (gainGoal_ - outputGain_) / transitionFrames_;
}

void PerformanceBus::advanceGain() noexcept {
    if (gainRemaining_ <= 0) return;
    outputGain_ += gainStep_;
    if (--gainRemaining_ == 0) outputGain_ = gainGoal_;
}

void PerformanceBus::renderWithoutWet(float* out, int count) noexcept {
    for (int i = 0; i < count; ++i) {
        advanceMix();
        advanceGain();
        if (mix_ > 0.0) {
            out[2 * i] = static_cast<float>((1.0 - mix_) * out[2 * i]);
            out[2 * i + 1] = static_cast<float>((1.0 - mix_) * out[2 * i + 1]);
        }
    }
}

void PerformanceBus::beginCallback() noexcept {
    Update update;
    bool received = false, reset = false;
    // Bound work even if the producer publishes concurrently with this drain.
    for (int i = 0; i < kQueueCapacity - 1 && updates_.pop(update); ++i) {
        desired_ = update.settings;
        reset = reset || update.reset;
        received = true;
    }
    if (!received) return;
    resetPending_ = resetPending_ || reset || faulted();
    pending_ = resetPending_ || !sameSound(current_, desired_);
    if (pending_) {
        setGoal(0.0);
    } else {
        current_.enabled = desired_.enabled;
        current_.outputGain = desired_.outputGain;
        setGainGoal(current_.outputGain);
        setGoal(!suspended_ && hasMix(current_.parameters) ? 1.0 : 0.0);
    }
    setMixGoal(desired_.enabled && hasMix(current_.parameters) ? 1.0 : 0.0);
}

void PerformanceBus::clearTail() noexcept {
    resetPending_ = pending_ = true;
    setGoal(0.0);
}

void PerformanceBus::panic() noexcept {
    suspended_ = true;
    clearTail();
}

void PerformanceBus::resumeAfterPanic() noexcept {
    if (!suspended_) return;
    suspended_ = false;
    clearTail();
}

void PerformanceBus::commitAtDry() noexcept {
    if (!pending_ || (wet_ != 0.0 && mix_ != 0.0)) return;
    const bool seedChanged = current_.seed != desired_.seed;
    if (!processor_.applySnapshot(desired_.parameters)) {
        failSilent();
        return;
    }
    current_ = desired_;
    setGainGoal(current_.outputGain);
    if (resetPending_ || seedChanged) {
        processor_.reset(current_.seed);
        limiterGain_ = 1.0;
    }
    pending_ = resetPending_ = false;
    faulted_.store(false, std::memory_order_release);
    if (mix_ == 0.0) {
        // A wholly bypassed branch can adopt immediately; only the global
        // bypass ramp needs to come back, without an unnecessary level dip.
        wet_ = goal_ = !suspended_ && hasMix(current_.parameters) ? 1.0 : 0.0;
        rampRemaining_ = 0;
    } else setGoal(!suspended_ && hasMix(current_.parameters) ? 1.0 : 0.0);
    setMixGoal(current_.enabled && hasMix(current_.parameters) ? 1.0 : 0.0);
}

void PerformanceBus::failSilent() noexcept {
    processor_.reset(current_.seed);
    wet_ = goal_ = wetStep_ = 0.0;
    rampRemaining_ = 0;
    pending_ = resetPending_ = false;
    faulted_.store(true, std::memory_order_release);
}

void PerformanceBus::process(float* interleaved, int frames) noexcept {
    if (!prepared_ || !interleaved || frames <= 0) return;
    int offset = 0;
    std::uint64_t limited = 0;
    while (offset < frames) {
        commitAtDry();
        if (!hasMix(current_.parameters) && mix_ == 0.0) {
            // No configured effect means no planar copies or DSP work. Gain
            // can settle immediately while inaudible on this inactive branch.
            outputGain_ = gainGoal_;
            gainRemaining_ = 0;
            if (limited != 0) limitedFrames_.fetch_add(limited, std::memory_order_release);
            return;
        }
        int count = std::min(kBlockFrames, frames - offset);
        if (pending_ && rampRemaining_ > 0) count = std::min(count, rampRemaining_);
        float* const out = interleaved + 2 * offset;
        // PadEngine already clamps its summed input. Sanitize corrupt samples
        // before they reach feedback; valid finite dry samples stay bit-exact.
        for (int i = 0; i < count; ++i) {
            out[2 * i] = std::isfinite(out[2 * i]) ? out[2 * i] : 0.0f;
            out[2 * i + 1] = std::isfinite(out[2 * i + 1]) ? out[2 * i + 1] : 0.0f;
            left_[i] = std::clamp(out[2 * i], -1.0f, 1.0f);
            right_[i] = std::clamp(out[2 * i + 1], -1.0f, 1.0f);
        }
        if (!hasMix(current_.parameters) || faulted()) {
            renderWithoutWet(out, count);
            offset += count;
            continue;
        }
        const auto result = processor_.process(left_.data(), right_.data(), count, current_.bpm);
        bool finite = result == ProcessResult::Ok;
        for (int i = 0; finite && i < count; ++i)
            finite = std::isfinite(left_[i]) && std::isfinite(right_[i]);
        if (!finite) {
            // A failed wet branch mutes, rather than exposing untrimmed dry
            // at a quiet output setting. An explicit bypass still works.
            failSilent();
            renderWithoutWet(out, count);
            offset += count;
            continue;
        }
        for (int i = 0; i < count; ++i) {
            advanceGain();
            const double l = static_cast<double>(left_[i]) * outputGain_;
            const double r = static_cast<double>(right_[i]) * outputGain_;
            const double peak = std::max(std::abs(l), std::abs(r));
            const double required = peak > kOutputCeiling ? kOutputCeiling / peak : 1.0;
            limiterGain_ = std::min(required, 1.0 - (1.0 - limiterGain_) * limiterRelease_);
            // The final clamp only catches floating-point rounding at the
            // sample ceiling; gain reduction is linked across both channels.
            const float wetL = std::clamp(static_cast<float>(l * limiterGain_), -kOutputCeiling, kOutputCeiling);
            const float wetR = std::clamp(static_cast<float>(r * limiterGain_), -kOutputCeiling, kOutputCeiling);
            advanceWeight();
            advanceMix();
            if (limiterGain_ < 0.999999 && wet_ > 0.0 && mix_ > 0.0) ++limited;
            if (mix_ == 1.0) {
                out[2 * i] = static_cast<float>(wet_ * wetL);
                out[2 * i + 1] = static_cast<float>(wet_ * wetR);
            } else if (mix_ > 0.0) {
                out[2 * i] = static_cast<float>((1.0 - mix_) * out[2 * i] + mix_ * wet_ * wetL);
                out[2 * i + 1] = static_cast<float>((1.0 - mix_) * out[2 * i + 1] + mix_ * wet_ * wetR);
            }
        }
        offset += count;
    }
    if (limited != 0) limitedFrames_.fetch_add(limited, std::memory_order_release);
}

}  // namespace snipsnap::aquarium
