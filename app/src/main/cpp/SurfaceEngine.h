#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "Grain.h"
#include "ParameterSmoother.h"
#include "PrintBuffer.h"
#include "SpscRing.h"

namespace snipsnap {

/** What the UI sends, one per screen frame: which mode, where the fingers are, is a finger down. */
struct ControlFrame {
    int32_t mode = 0;  // 0 XY, 1 XYZ, 2 MORPH, 3 VECTOR, 4 GRAIN - TouchSurface.Mode.ordinal
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
 * GRAIN mode's own three knobs, each 0..1: the cloud's texture, set from
 * the UI the way a corner is and held there, not swept by the finger -
 * the finger is POSITION (x) and the pitch axis (y). See Grain.h for what
 * each maps to.
 */
struct GrainSettings {
    float size = 0.5f;     // grain length, grain::lengthMs
    float density = 0.5f;  // grains per second, grain::rateHz
    float spray = 0.15f;   // scatter of each grain's start around POSITION, grain::startFraction
};

/**
 * The kit's key as GRAIN snaps to it: a root pitch class (0..11 above C),
 * a 12-bit mask of the scale's degrees above that root, and the loaded
 * pad's own note as a (possibly fractional) MIDI number, so the snap is
 * to real notes in the key rather than to intervals from wherever the pad
 * happens to sit. No key is a chromatic mask; no known source note is
 * root 0 and source 0, which turns the degrees into intervals from the
 * pad itself - see grain::pitchRatio.
 */
struct KeySnap {
    int32_t rootSemitone = 0;
    uint32_t scaleMask = grain::kChromaticMask;
    float sourceMidi = 0.0f;
};

/**
 * The tactile surface's voice: one looping sample through pitch, a
 * bitcrusher, a drive stage, a state-variable lowpass, a fixed-time echo
 * and a fixed-room spring reverb, every macro fed from the UI through a
 * lock-free ring and de-zippered per sample. Oboe owns the thread; this
 * class owns nothing that allocates on it.
 *
 * GRAIN (mode 4) swaps the *source* only: instead of the four loops
 * reading through at pitch, a cloud of short windowed grains is
 * retriggered around the finger's POSITION, each at a pitch snapped to
 * the kit's key, and blended across the same four sample slots by the
 * same vertex weights. Everything downstream - crush, drive, the filter,
 * echo, spring, the gate - is the chain the loops already run through,
 * with the macros at XY's defaults (as recorded, wide open, the roll as
 * resonance), so tilt still does on GRAIN what it does everywhere else.
 *
 * Threading, in one place:
 *  - UI thread: `start`/`stop`, `loadSample`, `pushControl`, `setCorner`,
 *    `setGrain`, `setKey`, the print arm/stop/take calls.
 *  - Audio thread: `onAudioReady` only. It reads the ring, the corner,
 *    grain and key atomics, the pending-sample pointers and the print
 *    state; it never calls anything that can block or allocate.
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

    /** UI thread. GRAIN's three knobs; a non-finite one reads as its default, the same door the corners have. Takes effect on the next grain triggered. */
    void setGrain(const GrainSettings& settings);

    /** UI thread. The key GRAIN snaps to (see KeySnap). A root outside 0..11 wraps, an empty mask reads as chromatic, a non-finite source note as 0. */
    void setKey(const KeySnap& key);

    /**
     * UI thread. KEY: snap the *loop's* pitch - every mode but GRAIN,
     * which always snaps - to the key setKey holds, through the very same
     * grain::pitchRatio the cloud uses, so a note the loop lands on is a
     * note the cloud would land on. With no key that is a semitone ladder
     * around the pad's own note; with no known note, the key's intervals
     * from the pad itself. Off by default: the surface plays exactly as it
     * did until this is turned on. Takes effect within a control interval.
     */
    void setKeySnap(bool on);

    /**
     * How many things a modulator can move: the seven macros in MacroState
     * order, then GRAIN's SIZE, DENSITY, SPRAY and POSITION - the order
     * `Modulator.Target` in :shell declares, by ordinal.
     */
    static constexpr int32_t kModTargets = 11;

    /**
     * UI thread. The modulators' signed offsets, one per target (see
     * kModTargets), added to whatever the mode and the finger say for that
     * target before its own 0..1 door in applyControl - so a modulator
     * nudges the finger rather than replacing it, in every mode. Fewer than
     * kModTargets values leaves the rest at 0; a non-finite one reads as 0;
     * each is clamped to -1..1. Takes effect on the next control frame.
     */
    void setModulation(const float* offsets, int32_t count);

    /** How many grains can sound at once; a new one past this steals the oldest. */
    static constexpr int32_t kMaxGrains = 16;

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

    /**
     * Audio thread. GRAIN's source for one output sample: advances the
     * trigger clock (starting a grain when it is time and a finger is
     * down), then sums every sounding grain across the loaded slots by
     * [weights] (already renormalised, one per slot, as renderMono has
     * them). Plays the part readSlot's blend plays in the other modes.
     */
    float grainSample(double fs, const float* weights);
    /** Audio thread. Start one grain at the current POSITION/pitch/knobs, stealing the oldest when all kMaxGrains are sounding. */
    void triggerGrain(double fs);
    /** Audio thread. One slot read at a fractional frame index, wrapping; silence if the slot is empty. Does not advance anything - grains keep their own position. */
    float readSlotAt(int32_t slot, double frameIndex) const;

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

    // GRAIN. The knobs and the key cross from the UI as atomics, read at
    // trigger time only (a knob turned mid-grain changes the *next* grain,
    // never one already sounding - the window is what keeps the cloud
    // click-free, and re-sizing a grain under its window would break it).
    // POSITION and the pitch axis are the finger, so they glide like the
    // other macros; the pitch is snapped per grain *after* the glide, so a
    // slide up the pad steps through the key's notes rather than sweeping
    // between them - a glide would undo the snap. The window is a Hann
    // table, sized once in the constructor; a grain's own position is
    // scaled onto it, so every length shares the one table.
    struct Grain {
        bool active = false;
        float startFraction = 0.0f;  // 0..1 of the source, per slot × that slot's own length
        int32_t length = 0;          // output frames
        int32_t pos = 0;             // output frames elapsed
        float pitch = 1.0f;          // playback ratio, already snapped
        float gain = 0.0f;           // grain::gainFor at trigger time
        uint32_t order = 0;          // trigger sequence, for stealing the oldest
    };
    Grain grains_[kMaxGrains];
    static constexpr int32_t kGrainWindowTable = 1024;
    std::vector<float> hann_;  // kGrainWindowTable + 1 points, so index kGrainWindowTable is valid
    std::atomic<float> grainSize_;
    std::atomic<float> grainDensity_;
    std::atomic<float> grainSpray_;
    std::atomic<int32_t> keyRoot_;
    std::atomic<uint32_t> keyMask_;
    std::atomic<float> keySourceMidi_;
    // KEY for the loop (see setKeySnap). The atomic crosses from the UI;
    // the audio thread copies it and the key's own three atomics into
    // plain fields once per kControlInterval, alongside the filter's
    // coefficients, rather than reading four atomics per sample.
    std::atomic<bool> keySnapLoop_;
    bool keySnapOn_ = false;
    int32_t keyRootC_ = 0;
    uint32_t keyMaskC_ = grain::kChromaticMask;
    float keySourceC_ = 0.0f;
    ParameterSmoother grainPosition_, grainPitchAxis_;
    // The modulators' offsets, UI -> audio as atomics like the corners and
    // the knobs; applyControl reads them into `mod_` once per control frame
    // so a frame sees one consistent set. The last four are GRAIN's, held
    // in grainMod_ for triggerGrain (SIZE, DENSITY, SPRAY) and folded into
    // grainPosition_'s target (POSITION) - a modulated position glides
    // through the same smoother the finger does.
    std::atomic<float> modulation_[kModTargets];
    float grainMod_[4] = {};  // audio thread only: size, density, spray, position offsets as of the last frame
    // 0..1 toward the next trigger. Starts (and is reset on every touch-
    // down) at 1 so the first grain fires on the very next sample rather
    // than a full period later - at DENSITY's floor that would be half a
    // second of silence after a tap, which is a drum pad that does not hit.
    double grainClock_ = 1.0;
    uint32_t grainRng_ = 0x67721A1Eu;  // GrainVoice.kt's own seed: reproducible scatter, nothing secret
    uint32_t grainOrder_ = 0;
    bool grainMode_ = false;  // audio thread's view of latest_.mode == 4; a change empties the pool

    // Pre-sized scratch so the callback never allocates; larger bursts render in chunks.
    static constexpr size_t kScratchFrames = 4096;
    std::vector<float> scratch_;

    PrintBuffer print_;
};

}  // namespace snipsnap
