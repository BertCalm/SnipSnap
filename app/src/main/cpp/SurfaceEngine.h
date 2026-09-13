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
    int32_t mode = 0;  // 0 XY, 1 XYZ, 2 MORPH, 3 VECTOR - TouchSurface.Mode.ordinal
    float x = 0.5f, y = 0.5f, z = 0.0f, tilt = 0.5f;
    float a = 0.25f, b = 0.25f, c = 0.25f, d = 0.25f;
    // Weights for source slots 0/1/2/3 - a barycentric blend across the
    // pad's four sample vertices (see TouchSurface.sampleWeights in
    // :shell), independent of mode/corners. Not required to sum to 1;
    // renderMono renormalises every frame. Default is slot 0 alone, so a
    // caller that never sets these plays exactly as before this blend
    // existed.
    float sampleA = 1.0f, sampleB = 0.0f, sampleC = 0.0f, sampleD = 0.0f;
    bool gate = false;
};

/** One DSP state: every macro normalised 0..1. A morph corner is one of these. */
struct MacroState {
    float pitch = 0.5f;      // 0.5 = as recorded, ±1 octave across the range
    float cutoff = 1.0f;     // 80 Hz .. 16 kHz, exponential
    float resonance = 0.0f;
    float drive = 0.0f;
    float crush = 0.0f;      // 0 = transparent, 1 = heavily bit/rate-reduced
    float echo = 0.0f;       // 0 = dry, 1 = fully wet - a fixed-time delay's own mix, never its time
    float spring = 0.0f;     // 0 = dry, 1 = fully wet - a fixed-room reverb's own mix, never its size or tone
};

/**
 * The tactile surface's voice: one looping sample through pitch, a
 * bitcrusher, a drive stage, a state-variable lowpass, a fixed-time echo
 * and a fixed-room spring reverb, every macro fed from the UI through a
 * lock-free ring and de-zippered per sample. Oboe owns the thread; this
 * class owns nothing that allocates on it.
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
 * Up to `kMaxSources` samples can be loaded at once. Slots 0/1/2/3 are the
 * four vertices of `ControlFrame`'s barycentric `sampleA/B/C/D` blend (see
 * TouchSurface.sampleWeights in :shell) - apex, base-left, base-right,
 * base-mid.
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

    /** How many source slots exist - all four are mixed (see `ControlFrame::sampleA/B/C/D`). */
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
    // [corner][pitch, cutoff, resonance, drive, crush, echo, spring] - see MacroState.
    static constexpr int32_t kMacroCount = 7;
    std::atomic<float> corners_[4][kMacroCount];

    // Per-sample smoothing of every macro plus the gate and the four
    // sample-blend weights (index i glides toward sampleA/B/C/D for slot i).
    ParameterSmoother pitch_, cutoff_, resonance_, drive_, crush_, echo_, spring_, gain_;
    ParameterSmoother sampleWeight_[kMaxSources];

    // The filter (Cytomic trapezoidal SVF), coefficients refreshed every kControlInterval samples.
    static constexpr int32_t kControlInterval = 32;
    float ic1eq_ = 0.0f, ic2eq_ = 0.0f;
    float svfA1_ = 0.0f, svfA2_ = 0.0f, svfA3_ = 0.0f;
    int32_t untilCoefficients_ = 0;

    // CRUSH's own state: a sample-and-hold accumulator (crushPhase_ crosses
    // a continuously-variable threshold rather than an integer downsample
    // count, so gliding the macro never snaps the hold length) and the
    // currently-held, currently-quantised sample. crushRetrigger_ is set on
    // every touch-down (see applyControl) and consumed by the very next
    // renderMono sample, forcing an immediate re-latch from that note's own
    // first sample rather than a hold still carrying the previous note's
    // value - the same reasoning phase_'s own touch-down reset already
    // applies to sample playback (Copilot review, PR #197).
    float crushPhase_ = 0.0f;
    float heldCrush_ = 0.0f;
    bool crushRetrigger_ = false;

    // ECHO's delay line: a fixed-length ring buffer, sized to kDelayTimeMs
    // in the constructor (at the default sampleRate_, so it is never empty
    // for a caller that renders without ever starting a real stream - see
    // the constructor's own comment) and again in start() once the device's
    // real rate is known. Reading and writing through the same rotating
    // index is the whole delay - the buffer's own length is the time, so
    // there is no separate "how far back" offset to keep in sync with it.
    // See renderMono.
    std::vector<float> delayBuffer_;
    size_t delayWrite_ = 0;

    // SPRING's fixed room: a Schroeder (1962) network ported straight from
    // synth/Spring.kt's offline design - four parallel combs (mutually
    // prime delays) build the density, two series allpasses smear it into
    // a tail, each comb's feedback running through a shared one-pole
    // lowpass (TONE) so the room darkens as it rings. SIZE and TONE are
    // baked in at that file's own defaults (0.35, 0.55) rather than
    // exposed - continuously stretching a comb's own delay length while a
    // finger drags a corner blend would detune its resonance the same way
    // stretching ECHO's delay time would pitch-warble a repeat (see
    // kDelayTimeMs above), so only the wet MIX - the `spring` macro - is
    // ever corner-blended. The *Ms arrays are each comb/allpass's own
    // fixed delay in milliseconds, not samples, because a comb's length
    // as a *time* is what has to stay fixed across a sample-rate change,
    // not its length in samples (see configureSpring). kSpringRt60Seconds
    // is the shared decay time every comb's own feedback gain is derived
    // from; the derivation (see configureSpring) works out sample-rate
    // independent for the same reason the *Ms arrays are in milliseconds.
    static constexpr int32_t kSpringCombCount = 4;
    static constexpr int32_t kSpringAllpassCount = 2;
    static constexpr float kSpringCombMs[kSpringCombCount] = {32.516f, 30.858f, 39.570f, 43.387f};
    static constexpr float kSpringAllpassMs[kSpringAllpassCount] = {6.688f, 2.178f};
    static constexpr float kSpringAllpassGain = 0.7f;
    static constexpr float kSpringRt60Seconds = 0.457f;
    static constexpr float kSpringToneHz = 4495.0f;

    /** Sizes/clears every comb+allpass buffer and recomputes their feedback/lowpass coefficients for `fs` - the constructor and start() both call it, the same reason delayBuffer_ is sized in both (see the constructor's own comment). */
    void configureSpring(float fs);

    std::vector<float> springCombBuf_[kSpringCombCount];
    size_t springCombWrite_[kSpringCombCount] = {};
    float springCombLp_[kSpringCombCount] = {};  // one-pole feedback-path state, per comb
    float springCombFb_[kSpringCombCount] = {};  // feedback gain per comb, from kSpringRt60Seconds
    std::vector<float> springApBuf_[kSpringAllpassCount];
    size_t springApWrite_[kSpringAllpassCount] = {};
    float springLpA_ = 0.0f;  // the one-pole coefficient every comb's feedback path filters through

    // Pre-sized scratch so the callback never allocates; larger bursts render in chunks.
    static constexpr size_t kScratchFrames = 4096;
    std::vector<float> scratch_;

    PrintBuffer print_;
};

}  // namespace snipsnap
