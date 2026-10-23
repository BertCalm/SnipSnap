// SPDX-License-Identifier: MIT
#include "OfflineRender.h"

#include <algorithm>
#include <cmath>
#include <new>
#include <stdexcept>

namespace snipsnap::aquarium {
namespace {
bool aliasesOutput(const float* input, std::size_t sampleCount, const std::vector<float>& output) noexcept {
    if (output.empty()) return false;
    const auto a = reinterpret_cast<std::uintptr_t>(input);
    const auto b = reinterpret_cast<std::uintptr_t>(output.data());
    // sampleCount is already bounded by the output-frame preflight. A valid
    // vector's size*sizeof(float) cannot exceed its addressable object storage.
    return a <= b ? b - a < sampleCount * sizeof(float) : a - b < output.size() * sizeof(float);
}
}  // namespace

const char* offlineRenderStatusMessage(OfflineRenderStatus status) noexcept {
    switch (status) {
        case OfflineRenderStatus::Ok: return "Aquarium render completed";
        case OfflineRenderStatus::InvalidInput: return "Source must be nonempty whole frames, finite, within +/-32, and separate from output storage";
        case OfflineRenderStatus::InvalidChannels: return "Source channels must be 1 or 2";
        case OfflineRenderStatus::InvalidSampleRate: return "Source sample rate must be finite and between 8000 and 192000 Hz";
        case OfflineRenderStatus::InvalidParameters: return "Aquarium requires all 52 finite in-range controls with integer selectors";
        case OfflineRenderStatus::InvalidOptions: return "Invalid Aquarium BPM, tail, fade, gain, peak ceiling or block size";
        case OfflineRenderStatus::TooManyFrames: return "Aquarium output exceeds the 8000000-frame allocation bound";
        case OfflineRenderStatus::AllocationFailure: return "Cannot allocate Aquarium offline render buffers";
        case OfflineRenderStatus::ProcessingFailure: return "Aquarium processor could not complete the offline render";
        case OfflineRenderStatus::NonFiniteOutput: return "Aquarium produced non-finite output";
        case OfflineRenderStatus::PeakExceeded: return "Aquarium output exceeds the explicit peak ceiling; lower outputGain";
    }
    return "Unknown Aquarium offline render status";
}

OfflineRenderStatus validateOfflineRender(
    std::size_t sampleCount, int channels, double sampleRate,
    const ParameterSnapshot& parameters, const OfflineRenderOptions& options) noexcept {
    if (channels != 1 && channels != 2) return OfflineRenderStatus::InvalidChannels;
    if (!std::isfinite(sampleRate) || sampleRate < kMinimumSampleRate || sampleRate > kMaximumSampleRate)
        return OfflineRenderStatus::InvalidSampleRate;
    if (!std::isfinite(options.bpm) || options.bpm < 0.0 || options.bpm > 1000.0 ||
        options.tailFrames < 0 || options.fadeFrames < 0 || !std::isfinite(options.outputGain) ||
        options.outputGain < 0.0f || !std::isfinite(options.peakCeiling) || options.peakCeiling <= 0.0f ||
        options.blockSize < 1 || options.blockSize > kMaximumBlockSize)
        return OfflineRenderStatus::InvalidOptions;
    if (sampleCount == 0 || sampleCount % static_cast<std::size_t>(channels) != 0)
        return OfflineRenderStatus::InvalidInput;
    const std::size_t sourceFrames = sampleCount / static_cast<std::size_t>(channels);
    const std::size_t tailFrames = static_cast<std::size_t>(options.tailFrames);
    // Subtract before adding, so malformed size_t counts cannot wrap around.
    if (sourceFrames > kMaxOfflineOutputFrames || tailFrames > kMaxOfflineOutputFrames - sourceFrames)
        return OfflineRenderStatus::TooManyFrames;
    if (static_cast<std::size_t>(options.fadeFrames) > sourceFrames + tailFrames)
        return OfflineRenderStatus::InvalidOptions;
    Processor validator;
    if (!validator.applySnapshot(parameters)) return OfflineRenderStatus::InvalidParameters;
    return OfflineRenderStatus::Ok;
}

OfflineRenderStatus renderOffline(
    const float* input, std::size_t sampleCount, int channels, double sampleRate,
    const ParameterSnapshot& parameters, std::uint32_t seed,
    const OfflineRenderOptions& options, std::vector<float>& output) noexcept {
    const auto validation = validateOfflineRender(sampleCount, channels, sampleRate, parameters, options);
    if (validation != OfflineRenderStatus::Ok) return validation;
    if (!input || aliasesOutput(input, sampleCount, output)) return OfflineRenderStatus::InvalidInput;
    for (std::size_t i = 0; i < sampleCount; ++i)
        if (!std::isfinite(input[i]) || std::abs(input[i]) > kMaximumInputMagnitude)
            return OfflineRenderStatus::InvalidInput;
    try {
        const std::size_t sourceFrames = sampleCount / static_cast<std::size_t>(channels);
        const std::size_t totalFrames = sourceFrames + static_cast<std::size_t>(options.tailFrames);
        std::vector<float> rendered(totalFrames * 2);
        std::vector<float> left(static_cast<std::size_t>(options.blockSize));
        std::vector<float> right(static_cast<std::size_t>(options.blockSize));
        Processor processor;
        if (!processor.applySnapshot(parameters)) return OfflineRenderStatus::InvalidParameters;
        processor.reset(seed);
        if (!processor.prepare(sampleRate, options.blockSize)) return OfflineRenderStatus::AllocationFailure;
        const auto fadeFrames = static_cast<std::size_t>(options.fadeFrames);
        const std::size_t fadeStart = totalFrames - fadeFrames;
        for (std::size_t start = 0; start < totalFrames; start += static_cast<std::size_t>(options.blockSize)) {
            const int count = static_cast<int>(std::min(static_cast<std::size_t>(options.blockSize), totalFrames - start));
            for (int i = 0; i < count; ++i) {
                const auto frame = start + static_cast<std::size_t>(i);
                if (frame < sourceFrames) {
                    left[i] = input[frame * static_cast<std::size_t>(channels)];
                    right[i] = channels == 1 ? left[i] : input[frame * 2 + 1];
                } else {
                    left[i] = right[i] = 0.0f;
                }
            }
            if (processor.process(left.data(), right.data(), count, options.bpm) != ProcessResult::Ok)
                return OfflineRenderStatus::ProcessingFailure;
            for (int i = 0; i < count; ++i) {
                const auto frame = start + static_cast<std::size_t>(i);
                if (!std::isfinite(left[i]) || !std::isfinite(right[i])) return OfflineRenderStatus::NonFiniteOutput;
                float fade = 1.0f;
                if (fadeFrames != 0 && frame >= fadeStart)
                    fade = fadeFrames == 1 ? 0.0f : static_cast<float>(totalFrames - 1 - frame) / static_cast<float>(fadeFrames - 1);
                const float l = (left[i] * options.outputGain) * fade;
                const float r = (right[i] * options.outputGain) * fade;
                if (!std::isfinite(l) || !std::isfinite(r)) return OfflineRenderStatus::NonFiniteOutput;
                if (std::abs(l) > options.peakCeiling || std::abs(r) > options.peakCeiling)
                    return OfflineRenderStatus::PeakExceeded;
                rendered[frame * 2] = l;
                rendered[frame * 2 + 1] = r;
            }
        }
        output.swap(rendered);
        return OfflineRenderStatus::Ok;
    } catch (const std::bad_alloc&) {
        return OfflineRenderStatus::AllocationFailure;
    } catch (const std::length_error&) {
        return OfflineRenderStatus::AllocationFailure;
    } catch (...) {
        return OfflineRenderStatus::ProcessingFailure;
    }
}

}  // namespace snipsnap::aquarium
