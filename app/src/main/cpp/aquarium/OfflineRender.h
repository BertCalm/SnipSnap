// SPDX-License-Identifier: MIT
#pragma once

#include "AquariumProcessor.h"

#include <cstddef>
#include <cstdint>
#include <vector>

namespace snipsnap::aquarium {

/** Includes explicit tail frames; bounds all native/JVM PCM allocations. */
inline constexpr std::size_t kMaxOfflineOutputFrames = 8'000'000;

struct OfflineRenderOptions {
    double bpm = 0.0;
    int tailFrames = 0;
    int fadeFrames = 0;
    float outputGain = 1.0f;
    float peakCeiling = 1.0f;
    int blockSize = 256;
};

enum class OfflineRenderStatus {
    Ok,
    InvalidInput,
    InvalidChannels,
    InvalidSampleRate,
    InvalidParameters,
    InvalidOptions,
    TooManyFrames,
    AllocationFailure,
    ProcessingFailure,
    NonFiniteOutput,
    PeakExceeded
};

const char* offlineRenderStatusMessage(OfflineRenderStatus status) noexcept;

/** Shape/options/snapshot validation without allocating or reading source PCM.
 * Used by JNI to reject an oversized request before copying a Java source array. */
OfflineRenderStatus validateOfflineRender(
    std::size_t sampleCount, int channels, double sampleRate,
    const ParameterSnapshot& parameters, const OfflineRenderOptions& options) noexcept;

/**
 * Offline-only interleaved PCM rendering. A fresh Processor starts at origin 0
 * for every call, with the full parameter snapshot and explicit uint32 seed.
 * Mono is duplicated into L/R; stereo channels remain separate before effects.
 * Output is always interleaved stereo, including the requested zero-input tail.
 *
 * Source is nonempty, whole mono/stereo frames, finite and abs(sample)<=32.
 * Options require BPM [0,1000], tail/fade>=0, fade<=output frames, finite gain>=0,
 * finite peakCeiling>0, blockSize [1,8192]. Output frames may not exceed 8,000,000.
 * Input must not alias storage owned by output; actual pointer storage remains a
 * caller precondition. No source writes, normalization, clipping or resampling.
 *
 * Fixed outputGain is followed by a linear end fade: fadeFrames=0 disables it;
 * fadeFrames=1 zeroes the last frame; otherwise the last fadeFrames ramp 1→0.
 * Non-finite or above-ceiling final samples reject the entire render.
 *
 * This function may allocate. Any failure leaves output and source untouched;
 * only a fully validated render swaps into output. No native exception escapes.
 */
OfflineRenderStatus renderOffline(
    const float* input, std::size_t sampleCount, int channels, double sampleRate,
    const ParameterSnapshot& parameters, std::uint32_t seed,
    const OfflineRenderOptions& options, std::vector<float>& output) noexcept;

}  // namespace snipsnap::aquarium
