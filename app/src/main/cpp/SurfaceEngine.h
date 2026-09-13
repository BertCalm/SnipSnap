#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "ParameterSmoother.h"
#include "PrintBuffer.h"
#include "SpscRing.h"

namespace snipsnap {

/** What the UI sends, one per screen frame: which mode, where the fingers are, is a finger down. */
struct ControlFrame {
    int32_t mode = 0;  // 0 XY, 1 XYZ, 2 MORPH - TouchSurface.Mode.ordinal
    float x = 0.5f, y = 0.5f, z = 0.0f, tilt = 0.5f;
    float a = 0.25f, b = 0.25f, c = 0.25f, d = 0.25f;
    // Weights for source slots 0/1/2 - a barycentric blend across the
    // pad's inscribed sample triangle (see TouchSurface.sampleWeights in
    // :shell), independent of mode/corners. Not required to sum to 1;
    // renderMono renormalises every frame. Default is slot 0 alone, so a
    // caller that never sets these plays exactly as before this blend
    // existed. Slot 3 is not mixed yet - see SurfaceEngine::kMaxSources.
    float sampleA = 1.0f, sampleB = 0.0f, sampleC = 0.0f;
    bool gate = false;
};

/** One DSP state: every macro normalised 0..1. A morph corner is one of these. */
struct MacroState {
    float pitch = 0.5f;      // 0.5 = as recorded, ±1 octave across the range
    float cutoff = 1.0f;     // 80 Hz .. 16 kHz, exponential
    float resonance = 0.0f;
    float drive = 0.0f;
};

/**
 * The tactile surface's voice: one looping sample through pitch, a
 * state-variable lowpass and a drive stage, every macro fed from the UI
 * through a lock-free ring and de-zippered per sample. Oboe owns the
 * thread; this class owns nothing that allocates on it.
 *
 * Threading, in one place:
 *  - UI thread: `start`/`stop`, `loadSample`, `pushControl`, `setCorner`,
 *    the print arm/stop/take calls.
 *  - Audio thread: `onAudioReady` only. It reads the ring, the corner
 *    atomics, the pending-sample pointers and the print state; it never
 *    calls anything that can block or allocate.
 *  - A sample swap is a pointer handshake, one per source slot: the UI
 *    parks the new buffer in `pending_[slot]`, the callback adopts it and
 *    parks the old one in `retired_[slot]`, and the UI frees
 *    `retired_[slot]` on its next call for that slot. The audio thread
 *    frees nothing.
 *
 * Up to `kMaxSources` samples can be loaded at once. Slots 0/1/2 are the
 * three vertices of `ControlFrame`'s barycentric `sampleA/B/C` blend;
 * slot 3 exists so a later stage doesn't have to resize this array
 * again, but nothing mixes it in yet.
 */
class SurfaceEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit SurfaceEngine(int32_t preferredSampleRate);
    ~SurfaceEngine() override;

    bool start();
    void stop();
    int32_t sampleRate() const { return sampleRate_; }
    bool needsRestart() const { return restartNeeded_.load(std::memory_order_acquire); }
    /** True when the device refused an exclusive stream and the shared fallback is playing. */
    bool isShared() const { return sharedMode_.load(std::memory_order_acquire); }
    /** Round-trip latency in ms, or -1 when unknown. UI thread only (see OboeOutput.h). */
    double latencyMillis() const;

    /** How many source slots exist. Only 0/1/2 are mixed today (see `ControlFrame::sampleA/B/C`). */
    static constexpr int32_t kMaxSources = 4;

    /** UI thread. `mono` is copied; `sourceRate` is the file's own rate (the engine repitches). `slot` is 0..kMaxSources-1. */
    void loadSample(const float* mono, size_t frames, int32_t sourceRate, int32_t slot = 0);

    /** UI thread. Never blocks; a full ring drops the frame (the next one is milliseconds away). */
    void pushControl(const ControlFrame& frame) { controls_.push(frame); }

    /** UI thread. Corner 0..3 = A, B, C, D of the morph pad. */
    void setCorner(int index, const MacroState& state);

    // The resample tap (see PrintBuffer for the ownership rules).
    bool armPrint(size_t maxFrames) { return print_.arm(maxFrames); }
    void requestStopPrint() { print_.requestStop(); }
    PrintBuffer::State printState() const { return print_.state(); }
    size_t printFrames() const { return print_.framesWritten(); }
    const float* printData() const { return print_.data(); }
    bool clearPrint() { return print_.clear(); }

    // Oboe callbacks (audio thread).
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Sample {
        std::vector<float> frames;
        int32_t rate = 44100;
    };

    void adoptPendingSample(int32_t slot);
    void applyControl(const ControlFrame& frame);
    MacroState morphed(const ControlFrame& frame) const;
    void renderMono(float* out, int32_t numFrames);
    /** Audio thread. One source's interpolated read, advancing its own phase_[slot]. Silence if slot is empty. */
    float readSlot(int32_t slot, double fs, float pitchRatioValue);
    /** Audio thread. Whether slot has an adopted sample worth reading - the same test readSlot itself applies. */
    bool slotLoaded(int32_t slot) const;

    std::shared_ptr<oboe::AudioStream> stream_;
    int32_t preferredRate_;
    int32_t sampleRate_ = 48000;
    std::atomic<bool> restartNeeded_{false};
    std::atomic<bool> sharedMode_{false};

    // The sample handshake, one per source slot (see kMaxSources above).
    // pending_/retired_ are set to nullptr explicitly in the constructor
    // body, not via a member initializer - std::atomic's default
    // constructor is trivial in C++17 and does not reliably
    // value-initialize the contained pointer through an array's `{}`.
    std::atomic<Sample*> pending_[kMaxSources];
    std::atomic<Sample*> retired_[kMaxSources];
    Sample* current_[kMaxSources] = {};  // audio thread only; plain pointers zero-init fine
    double phase_[kMaxSources] = {};

    // Controls.
    SpscRing<ControlFrame, 64> controls_;
    ControlFrame latest_;
    // The gate as of the last control frame applied, so a touch-down (the
    // false -> true edge) is told apart from a touch merely held - see
    // applyControl.
    bool gated_ = false;
    std::atomic<float> corners_[4][4];  // [corner][pitch, cutoff, resonance, drive]

    // Per-sample smoothing of every macro plus the gate and the three
    // sample-blend weights (index i glides toward sampleA/B/C for slot i).
    ParameterSmoother pitch_, cutoff_, resonance_, drive_, gain_;
    ParameterSmoother sampleWeight_[3];

    // The filter (Cytomic trapezoidal SVF), coefficients refreshed every kControlInterval samples.
    static constexpr int32_t kControlInterval = 32;
    float ic1eq_ = 0.0f, ic2eq_ = 0.0f;
    float svfA1_ = 0.0f, svfA2_ = 0.0f, svfA3_ = 0.0f;
    int32_t untilCoefficients_ = 0;

    // Pre-sized scratch so the callback never allocates; larger bursts render in chunks.
    static constexpr size_t kScratchFrames = 4096;
    std::vector<float> scratch_;

    PrintBuffer print_;
};

}  // namespace snipsnap
