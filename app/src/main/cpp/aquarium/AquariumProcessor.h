// SPDX-License-Identifier: MIT
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string_view>

namespace snipsnap::aquarium {

inline constexpr std::uint32_t kRenderModelVersion = 1;
inline constexpr std::size_t kParameterCount = 52;
inline constexpr std::uint32_t kDefaultSeed = 1337;
inline constexpr int kMaximumBlockSize = 8192;
inline constexpr double kMinimumSampleRate = 8000.0;
inline constexpr double kMaximumSampleRate = 192000.0;
inline constexpr float kMaximumInputMagnitude = 32.0f;

struct ParameterInfo {
    std::string_view name;
    float minimum;
    float maximum;
    float defaultValue;
    float step;  // 0 = continuous; 1 = upstream integer selector/voice count.
};

/**
 * Fixed-size value snapshot in parameters.json order; ordinary copying allocates
 * nothing. Default construction fills canonical defaults (all stage mixes off).
 * Name lookup/serialization belongs on the control thread. Transport the complete
 * snapshot to the owning audio thread using the host's existing handoff protocol.
 */
struct ParameterSnapshot {
    std::array<float, kParameterCount> values{};
    ParameterSnapshot() noexcept;
};

const std::array<ParameterInfo, kParameterCount>& parameterInfo() noexcept;

/** Control-thread helper. Rejects unknown/non-finite input without mutation;
 * finite values clamp to the canonical range. Non-integer selector values are
 * rejected; UI quantization belongs before building the complete snapshot. */
bool setParameter(ParameterSnapshot& snapshot, std::string_view name, float value) noexcept;

/** Stable accepted-audition names, independent of placement/routing. */
enum class Preset : std::uint8_t {
    Dry, Fathom, Drift, Tide, Reef, Surface, Biolume, Depth, Motion, Color, Full
};
inline constexpr std::size_t kPresetCount = 11;
std::string_view presetName(Preset preset) noexcept;
bool parsePreset(std::string_view name, Preset& result) noexcept;
/** Invalid enum values yield the dry/default snapshot. */
ParameterSnapshot parametersForPreset(Preset preset) noexcept;

enum class ProcessResult {
    Ok,
    NotPrepared,
    InvalidFrameCount,
    InvalidBuffer,
    InvalidTempo,
    InvalidInput
};

/**
 * Shared processing core for future offline pad baking and continuous stereo
 * buses. No routing, JNI, stream ownership, normalization or limiting is added.
 *
 * Ownership: one serialized caller owns each Processor. prepare/release/destruct
 * are non-audio-thread operations; reset/applySnapshot/process can run on that
 * owning audio thread. There is no internal cross-thread synchronization.
 *
 * prepare allocates all state. applySnapshot/process/reset never allocate, free,
 * acquire locks or perform named lookups. Applying a snapshot preserves delay,
 * envelope and LFO history. reset is the explicit new-render/transport restart;
 * continuous buses retain history across blocks and feed silence to hear tails.
 */
class Processor {
public:
    Processor() noexcept;
    ~Processor();
    Processor(const Processor&) = delete;
    Processor& operator=(const Processor&) = delete;
    Processor(Processor&&) = delete;
    Processor& operator=(Processor&&) = delete;

    /** Successful prepare resets DSP using current snapshot/seed. Invalid config
     * or allocation failure returns false and retains the previous configuration.
     * Valid rates 8..192 kHz; maximumBlockSize 1..8192 frames. */
    bool prepare(double sampleRate, int maximumBlockSize) noexcept;
    /** Non-audio thread, after stopping callbacks. Frees DSP; retains controls. */
    void release() noexcept;
    bool isPrepared() const noexcept;
    double sampleRate() const noexcept;
    int maximumBlockSize() const noexcept;

    /** Audio-thread boundary operation: atomically rejects any non-finite,
     * out-of-range or non-integer selector value. Leaves DSP state intact. */
    bool applySnapshot(const ParameterSnapshot& snapshot) noexcept;
    const ParameterSnapshot& snapshot() const noexcept;
    void reset() noexcept;
    /** Seed zero intentionally maps to a nonzero xorshift state, as auditioned. */
    void reset(std::uint32_t seed) noexcept;
    std::uint32_t seed() const noexcept;

    /**
     * In-place planar stereo. Left/right must be writable disjoint float arrays;
     * exact or partial aliasing is rejected. Frame count 0 is a no-op once
     * prepared (null pointers allowed); larger than prepared maximum is rejected.
     * BPM 0 means free-running modulation; otherwise finite (0,1000].
     * Input must be finite with absolute magnitude <=32; output is not limited.
     * Every validation failure leaves both buffers and DSP state untouched.
     */
    ProcessResult process(float* left, float* right, int frameCount, double bpm = 0.0) noexcept;

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    ParameterSnapshot snapshot_;
    std::uint32_t seed_ = kDefaultSeed;
};

}  // namespace snipsnap::aquarium
