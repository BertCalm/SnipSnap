// SPDX-License-Identifier: MIT
#include "AquariumProcessor.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <new>

#include "private/DSP/Effects/AquaticFXSuite.h"

namespace snipsnap::aquarium {
namespace {
#include "private/ParameterTables.inc"

bool validSnapshot(const ParameterSnapshot& snapshot) noexcept {
    for (std::size_t i = 0; i < kParameterCount; ++i) {
        const float value = snapshot.values[i];
        if (!std::isfinite(value) || value < kParameterInfo[i].minimum ||
            value > kParameterInfo[i].maximum) return false;
        if (kParameterInfo[i].step > 0.0f) {
            const float position = (value - kParameterInfo[i].minimum) / kParameterInfo[i].step;
            if (position != std::floor(position)) return false;
        }
    }
    return true;
}

bool overlapping(const float* left, const float* right, int frameCount) noexcept {
    const auto a = reinterpret_cast<std::uintptr_t>(left);
    const auto b = reinterpret_cast<std::uintptr_t>(right);
    const auto bytes = static_cast<std::uintptr_t>(frameCount) * sizeof(float);
    return a <= b ? b - a < bytes : a - b < bytes;
}
}  // namespace

ParameterSnapshot::ParameterSnapshot() noexcept {
    values = kPresetValues[static_cast<std::size_t>(Preset::Dry)];
}

const std::array<ParameterInfo, kParameterCount>& parameterInfo() noexcept {
    return kParameterInfo;
}

bool setParameter(ParameterSnapshot& snapshot, std::string_view name, float value) noexcept {
    if (!std::isfinite(value)) return false;
    for (std::size_t i = 0; i < kParameterCount; ++i) {
        if (kParameterInfo[i].name == name) {
            const float clamped = std::clamp(value, kParameterInfo[i].minimum, kParameterInfo[i].maximum);
            if (kParameterInfo[i].step > 0.0f) {
                const float position = (clamped - kParameterInfo[i].minimum) / kParameterInfo[i].step;
                if (position != std::floor(position)) return false;
            }
            snapshot.values[i] = clamped;
            return true;
        }
    }
    return false;
}

std::string_view presetName(Preset preset) noexcept {
    const auto index = static_cast<std::size_t>(preset);
    return index < kPresetCount ? kPresetNames[index] : std::string_view{};
}

bool parsePreset(std::string_view name, Preset& result) noexcept {
    for (std::size_t i = 0; i < kPresetCount; ++i) {
        if (kPresetNames[i] == name) {
            result = static_cast<Preset>(i);
            return true;
        }
    }
    return false;
}

ParameterSnapshot parametersForPreset(Preset preset) noexcept {
    ParameterSnapshot result;
    const auto index = static_cast<std::size_t>(preset);
    if (index < kPresetCount) result.values = kPresetValues[index];
    return result;
}

struct Processor::Impl {
    detail::AquaticFXSuite core;
    double sampleRate = 0.0;
    int maximumBlockSize = 0;
};

Processor::Processor() noexcept = default;
Processor::~Processor() = default;

bool Processor::prepare(double sampleRate, int maximumBlockSize) noexcept {
    if (!std::isfinite(sampleRate) || sampleRate < kMinimumSampleRate ||
        sampleRate > kMaximumSampleRate || maximumBlockSize < 1 ||
        maximumBlockSize > kMaximumBlockSize) return false;
    try {
        auto replacement = std::make_unique<Impl>();
        replacement->core.setSeed(seed_);
        replacement->core.prepare(sampleRate, maximumBlockSize);
        replacement->core.applyParameterValues(snapshot_.values.data());
        replacement->sampleRate = sampleRate;
        replacement->maximumBlockSize = maximumBlockSize;
        impl_ = std::move(replacement);
        return true;
    } catch (...) {
        return false;  // Allocation failure keeps the old, still prepared processor.
    }
}

void Processor::release() noexcept { impl_.reset(); }
bool Processor::isPrepared() const noexcept { return static_cast<bool>(impl_); }
double Processor::sampleRate() const noexcept { return impl_ ? impl_->sampleRate : 0.0; }
int Processor::maximumBlockSize() const noexcept { return impl_ ? impl_->maximumBlockSize : 0; }

bool Processor::applySnapshot(const ParameterSnapshot& snapshot) noexcept {
    if (!validSnapshot(snapshot)) return false;
    snapshot_ = snapshot;
    if (impl_) impl_->core.applyParameterValues(snapshot_.values.data());
    return true;
}

const ParameterSnapshot& Processor::snapshot() const noexcept { return snapshot_; }
void Processor::reset() noexcept { reset(seed_); }
void Processor::reset(std::uint32_t seed) noexcept {
    seed_ = seed;
    if (impl_) {
        impl_->core.setSeed(seed_);
        impl_->core.reset();
    }
}
std::uint32_t Processor::seed() const noexcept { return seed_; }

ProcessResult Processor::process(float* left, float* right, int frameCount, double bpm) noexcept {
    if (!impl_) return ProcessResult::NotPrepared;
    if (frameCount < 0 || frameCount > impl_->maximumBlockSize) return ProcessResult::InvalidFrameCount;
    if (!std::isfinite(bpm) || bpm < 0.0 || bpm > 1000.0) return ProcessResult::InvalidTempo;
    if (frameCount == 0) return ProcessResult::Ok;
    if (!left || !right || overlapping(left, right, frameCount)) return ProcessResult::InvalidBuffer;
    for (int i = 0; i < frameCount; ++i) {
        if (!std::isfinite(left[i]) || !std::isfinite(right[i]) ||
            std::abs(left[i]) > kMaximumInputMagnitude || std::abs(right[i]) > kMaximumInputMagnitude)
            return ProcessResult::InvalidInput;
    }
    impl_->core.processBlock(left, right, frameCount, bpm);
    return ProcessResult::Ok;
}

}  // namespace snipsnap::aquarium
