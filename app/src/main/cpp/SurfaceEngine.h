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
 *    atomics, the pending-sample pointer and the print state; it never
 *    calls anything that can block or allocate.
 *  - A sample swap is a pointer handshake: the UI parks the new buffer
 *    in `pending_`, the callback adopts it and parks the old one in
 *    `retired_`, and the UI frees `retired_` on its next call. The audio
 *    thread frees nothing.
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

    /** UI thread. `mono` is copied; `sourceRate` is the file's own rate (the engine repitches). */
    void loadSample(const float* mono, size_t frames, int32_t sourceRate);

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
    void clearPrint() { print_.clear(); }

    // Oboe callbacks (audio thread).
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Sample {
        std::vector<float> frames;
        int32_t rate = 44100;
    };

    void adoptPendingSample();
    void applyControl(const ControlFrame& frame);
    MacroState morphed(const ControlFrame& frame) const;
    void renderMono(float* out, int32_t numFrames);

    std::shared_ptr<oboe::AudioStream> stream_;
    int32_t preferredRate_;
    int32_t sampleRate_ = 48000;
    std::atomic<bool> restartNeeded_{false};
    std::atomic<bool> sharedMode_{false};

    // The sample handshake.
    std::atomic<Sample*> pending_{nullptr};
    std::atomic<Sample*> retired_{nullptr};
    Sample* current_ = nullptr;  // audio thread only
    double phase_ = 0.0;

    // Controls.
    SpscRing<ControlFrame, 64> controls_;
    ControlFrame latest_;
    std::atomic<float> corners_[4][4];  // [corner][pitch, cutoff, resonance, drive]

    // Per-sample smoothing of every macro plus the gate.
    ParameterSmoother pitch_, cutoff_, resonance_, drive_, gain_;

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
