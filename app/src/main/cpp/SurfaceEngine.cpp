#include "SurfaceEngine.h"

#include "OboeOutput.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>

#define LOG_TAG "SurfaceEngine"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace snipsnap {

namespace {
constexpr float kPi = 3.14159265358979f;

// ECHO's free delay time: 220 ms (a short-to-medium slapback-to-echo
// range) at 35% feedback (several audible repeats before it fades under
// the noise floor, not a runaway loop) - what ECHO always was, and what
// it is until a kit asks for a division of its bar (see setEchoTime).
// Only the corner-blended `echo` macro ever changes what you hear of it
// (the wet mix) - see renderMono.
constexpr float kDelayTimeMs = 220.0f;
constexpr float kDelayFeedback = 0.35f;

/**
 * 0..1, and NaN-safe: the test is written `!(v > 0)` rather than `v < 0`
 * so a NaN lands on 0 instead of sailing through - `NaN < 0` and
 * `NaN > 1` are both false, so the obvious spelling passes NaN on.
 */
inline float clamp01(float v) { return !(v > 0.0f) ? 0.0f : (v > 1.0f ? 1.0f : v); }

/**
 * A macro arriving from outside (a control frame over JNI, a corner from
 * the sidecar). A non-finite reading is not a value, so it reads as
 * `fallback`; everything else is clamped.
 *
 * This is the door, and it matters more here than in most places: the
 * smoothers and the filter carry state across callbacks, so one NaN does
 * not pass through and leave - `value_ += k * (NaN - value_)` is NaN for
 * ever, `ic1eq_`/`ic2eq_` go with it, and the surface plays NaN for the
 * rest of the session even after the reading comes back. There is a live
 * way in: a gravity sensor that reports NaN feeds TILT, and TILT is
 * resonance in XYZ.
 */
inline float control01(float v, float fallback) { return std::isfinite(v) ? clamp01(v) : fallback; }

/** 0..1 → 80 Hz .. 16 kHz, exponential, the way a filter knob reads. */
inline float cutoffHz(float macro) { return 80.0f * std::exp2(clamp01(macro) * 7.6439f); }

/** 0..1 → ±1 octave around 0.5. */
inline float pitchRatio(float macro) { return std::exp2((clamp01(macro) - 0.5f) * 2.0f); }
}  // namespace

SurfaceEngine::SurfaceEngine(int32_t preferredSampleRate)
    : preferredRate_(preferredSampleRate), scratch_(kScratchFrames, 0.0f) {
    // Sized here too, not only in start(), at the member's own default
    // sampleRate_ (48000) - renderMono reads and writes delayBuffer_
    // unconditionally on every sample, so onAudioReady must never see it
    // empty. start() sizes it again once the device's real rate is
    // known; nothing here needs to survive that. The host test suite
    // calls onAudioReady directly and never start() at all, which is
    // exactly the case this guards. The free time first, so the tap
    // configureEcho sets is the 220 ms ECHO always had.
    setEchoTime(0.0f);
    configureEcho(static_cast<float>(sampleRate_));
    // Same reasoning, same place - see configureSpring's own comment.
    configureSpring(static_cast<float>(sampleRate_));
    // std::atomic's default constructor is trivial in C++17 and does not
    // reliably value-initialize the contained pointer - set every slot to
    // nullptr explicitly rather than trust an array member initializer.
    for (int32_t i = 0; i < kMaxSources; ++i) {
        pending_[i].store(nullptr, std::memory_order_relaxed);
        retired_[i].store(nullptr, std::memory_order_relaxed);
    }
    // GRAIN's atomics, for the same reason; the defaults are GrainSettings'
    // and KeySnap's own, so an engine nobody has called setGrain/setKey on
    // plays a chromatic mid-sized cloud rather than reading garbage.
    setGrain(GrainSettings{});
    setKey(KeySnap{});
    setKeySnap(false);  // the loop plays as recorded until KEY is turned on
    setSwarm(SwarmSettings{});  // one voice: the plain loop until SWARM is turned up
    setModulation(nullptr, 0);  // every target at 0: nothing moves until a slot has depth
    // The one Hann window every grain reads through, whatever its length.
    // kGrainWindowTable + 1 points so a grain at its very last frame
    // (pos/length just under 1) still lands inside the table.
    hann_.resize(static_cast<size_t>(kGrainWindowTable) + 1);
    for (int32_t i = 0; i <= kGrainWindowTable; ++i) {
        hann_[static_cast<size_t>(i)] = 0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(i) / static_cast<float>(kGrainWindowTable));
    }
    grainPosition_.snap(0.5f);
    grainPitchAxis_.snap(0.5f);
    // The four corners of the morph pad, before the UI says otherwise:
    // A clean, B dark, C low and thick, D hot - none crushed, echoed or sprung.
    const MacroState defaults[4] = {
        {0.5f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
        {0.5f, 0.25f, 0.3f, 0.1f, 0.0f, 0.0f, 0.0f},
        {0.25f, 0.6f, 0.5f, 0.4f, 0.0f, 0.0f, 0.0f},
        {0.75f, 0.85f, 0.2f, 0.9f, 0.0f, 0.0f, 0.0f},
    };
    for (int i = 0; i < 4; ++i) setCorner(i, defaults[i]);
}

void SurfaceEngine::configureSpring(float fs) {
    for (int32_t c = 0; c < kSpringCombCount; ++c) {
        springCombBuf_[c].assign(std::max<size_t>(static_cast<size_t>(kSpringCombMs[c] * 0.001f * fs), 1), 0.0f);
        springCombWrite_[c] = 0;
        springCombLp_[c] = 0.0f;
        // -60 dB at kSpringRt60Seconds, whatever this comb's own length -
        // synth/Spring.kt's own combFb formula is `10^(-3d/(rt60*fs))`,
        // where d is the comb's length in samples; d/fs is this comb's
        // delay in *seconds* (kSpringCombMs[c] * 0.001), which the sample
        // rate cancels out of entirely, so the same feedback gain applies
        // whatever fs turns out to be.
        springCombFb_[c] = std::pow(10.0f, -3.0f * (kSpringCombMs[c] * 0.001f) / kSpringRt60Seconds);
    }
    for (int32_t a = 0; a < kSpringAllpassCount; ++a) {
        springApBuf_[a].assign(std::max<size_t>(static_cast<size_t>(kSpringAllpassMs[a] * 0.001f * fs), 1), 0.0f);
        springApWrite_[a] = 0;
    }
    springLpA_ = 1.0f - std::exp(-2.0f * kPi * std::min(kSpringToneHz, 0.45f * fs) / fs);
}

SurfaceEngine::~SurfaceEngine() {
    stop();
    // With the stream closed the audio thread is gone; every slot is ours.
    for (int32_t i = 0; i < kMaxSources; ++i) {
        delete pending_[i].exchange(nullptr);
        delete retired_[i].exchange(nullptr);
        delete current_[i];
        current_[i] = nullptr;
    }
}

bool SurfaceEngine::start() {
    stop();
    restartNeeded_.store(false, std::memory_order_release);
    sharedMode_.store(false, std::memory_order_release);

    const OpenedOutput opened = openStereoFloatOutput(preferredRate_, this, this, stream_, LOG_TAG);
    if (!opened.ok) return false;
    sampleRate_ = opened.sampleRate;
    sharedMode_.store(opened.shared, std::memory_order_release);

    // ~15 ms glides for the macros, ~3 ms for the gate so a finger-down is a
    // click-free attack rather than a slow swell. Recomputed here because the
    // device may have handed back a rate other than the one asked for.
    const float fs = static_cast<float>(sampleRate_);
    pitch_.configure(10.0f, fs);
    cutoff_.configure(10.0f, fs);
    resonance_.configure(10.0f, fs);
    drive_.configure(10.0f, fs);
    crush_.configure(10.0f, fs);
    echo_.configure(10.0f, fs);
    spring_.configure(10.0f, fs);
    for (auto& w : sampleWeight_) w.configure(10.0f, fs);
    gain_.configure(50.0f, fs);
    gain_.snap(0.0f);
    untilCoefficients_ = 0;
    ic1eq_ = ic2eq_ = 0.0f;
    crushPhase_ = 0.0f;
    heldCrush_ = 0.0f;
    // GRAIN: the finger's two axes glide like the other macros; the pool
    // starts empty and the clock armed, so a restart never carries a
    // previous session's grains (or its wait-for-the-next-trigger) over.
    grainPosition_.configure(10.0f, fs);
    grainPitchAxis_.configure(10.0f, fs);
    for (auto& g : grains_) g.active = false;
    grainClock_ = 1.0;
    // Sized to the rate the device actually gave us, not preferredRate_ -
    // resized (and zeroed, so a restart never plays back the previous
    // session's tail) every time start() runs, same as the filter state above.
    configureEcho(fs);
    configureSpring(fs);

    const oboe::Result started = stream_->requestStart();
    if (started != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(started));
        stream_->close();
        stream_.reset();
        return false;
    }
    return true;
}

double SurfaceEngine::latencyMillis() const {
    return latencyMillisOf(stream_);
}

void SurfaceEngine::stop() {
    if (!stream_) return;
    stream_->stop();
    stream_->close();
    stream_.reset();
}

void SurfaceEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // A route change (headphones out, a Bluetooth speaker in) closes the
    // stream under us. The UI polls needsRestart() and reopens; the audio
    // thread cannot do it from inside its own error callback.
    LOGW("stream closed: %s", oboe::convertToText(error));
    restartNeeded_.store(true, std::memory_order_release);
}

void SurfaceEngine::loadSample(const float* mono, size_t frames, int32_t sourceRate, int32_t slot) {
    if (slot < 0 || slot >= kMaxSources) return;
    auto* sample = new Sample();
    sample->frames.assign(mono, mono + frames);
    sample->rate = sourceRate > 0 ? sourceRate : 44100;
    // A sample the callback never got round to adopting is ours to free.
    delete pending_[slot].exchange(sample, std::memory_order_acq_rel);
    // And the one it retired last time.
    delete retired_[slot].exchange(nullptr, std::memory_order_acq_rel);
}

void SurfaceEngine::setCorner(int index, const MacroState& state) {
    if (index < 0 || index > 3) return;
    corners_[index][0].store(control01(state.pitch, 0.5f), std::memory_order_relaxed);
    corners_[index][1].store(control01(state.cutoff, 1.0f), std::memory_order_relaxed);
    corners_[index][2].store(control01(state.resonance, 0.0f), std::memory_order_relaxed);
    corners_[index][3].store(control01(state.drive, 0.0f), std::memory_order_relaxed);
    corners_[index][4].store(control01(state.crush, 0.0f), std::memory_order_relaxed);
    corners_[index][5].store(control01(state.echo, 0.0f), std::memory_order_relaxed);
    corners_[index][6].store(control01(state.spring, 0.0f), std::memory_order_relaxed);
}

void SurfaceEngine::setGrain(const GrainSettings& settings) {
    const GrainSettings defaults;
    grainSize_.store(control01(settings.size, defaults.size), std::memory_order_relaxed);
    grainDensity_.store(control01(settings.density, defaults.density), std::memory_order_relaxed);
    grainSpray_.store(control01(settings.spray, defaults.spray), std::memory_order_relaxed);
}

void SurfaceEngine::setKey(const KeySnap& key) {
    keyRoot_.store(((key.rootSemitone % 12) + 12) % 12, std::memory_order_relaxed);
    const uint32_t mask = key.scaleMask & grain::kChromaticMask;
    keyMask_.store(mask == 0u ? grain::kChromaticMask : mask, std::memory_order_relaxed);
    keySourceMidi_.store(std::isfinite(key.sourceMidi) ? key.sourceMidi : 0.0f, std::memory_order_relaxed);
}

void SurfaceEngine::setKeySnap(bool on) {
    keySnapLoop_.store(on, std::memory_order_relaxed);
}

void SurfaceEngine::setSwarm(const SwarmSettings& settings) {
    const int32_t voices = settings.voices < 1 ? 1 : (settings.voices > kMaxSwarm ? kMaxSwarm : settings.voices);
    swarmVoices_.store(voices, std::memory_order_relaxed);
    swarmDetune_.store(control01(settings.detune, 0.0f), std::memory_order_relaxed);
}

void SurfaceEngine::setEchoTime(float seconds) {
    // Not a positive number, or past the ceiling: the free time. The
    // ceiling is not a clamp on purpose - a kit asking for more than the
    // line holds gets the echo it always had, not a different sync.
    const bool synced = std::isfinite(seconds) && seconds > 0.0f && seconds <= kMaxEchoSeconds;
    echoSeconds_.store(synced ? seconds : kDelayTimeMs * 0.001f, std::memory_order_relaxed);
}

size_t SurfaceEngine::echoDelaySamples(float seconds, float fs) const {
    // Truncated, not rounded: the free time at 48 kHz is the 10560 samples
    // the fixed line always was, sample for sample.
    const size_t n = static_cast<size_t>(seconds * fs);
    const size_t most = delayBuffer_.empty() ? 1 : delayBuffer_.size() - 1;
    return n < 1 ? 1 : (n > most ? most : n);
}

void SurfaceEngine::configureEcho(float fs) {
    delayBuffer_.assign(std::max<size_t>(static_cast<size_t>(kMaxEchoSeconds * fs) + 1, 2), 0.0f);
    delayWrite_ = 0;
    echoDelayC_ = echoDelaySamples(echoSeconds_.load(std::memory_order_relaxed), fs);
    echoDelayFrom_ = echoDelayC_;
    echoFadeLeft_ = 0;
    echoFadeSamples_ = std::max<int32_t>(static_cast<int32_t>(kEchoFadeMs * 0.001f * fs), 1);
}

void SurfaceEngine::setModulation(const float* offsets, int32_t count) {
    for (int32_t i = 0; i < kModTargets; ++i) {
        float v = 0.0f;
        if (offsets != nullptr && i < count) {
            const float raw = offsets[i];
            // Signed, so control01's 0..1 door is the wrong shape: -1..1,
            // and a NaN is 0 - "no offset" - rather than either rail.
            v = std::isfinite(raw) ? (raw < -1.0f ? -1.0f : (raw > 1.0f ? 1.0f : raw)) : 0.0f;
        }
        modulation_[i].store(v, std::memory_order_relaxed);
    }
}

// ---- audio thread from here down ---------------------------------------------

void SurfaceEngine::adoptPendingSample(int32_t slot) {
    // Only take a pending sample when there is room to retire the current
    // one. Checking first means nothing is ever handed *back* to pending_ -
    // a store there could overwrite a newer sample the UI parked meanwhile
    // and leak it. retired_ only goes null -> non-null on this thread, so
    // the check-then-store below cannot race the UI, which only ever
    // exchanges it to null.
    if (pending_[slot].load(std::memory_order_acquire) == nullptr) return;
    if (retired_[slot].load(std::memory_order_acquire) != nullptr) return;  // next callback
    Sample* incoming = pending_[slot].exchange(nullptr, std::memory_order_acq_rel);
    if (!incoming) return;
    retired_[slot].store(current_[slot], std::memory_order_release);
    current_[slot] = incoming;
    for (int32_t v = 0; v < kMaxSwarm; ++v) phase_[slot][v] = 0.0;
}

MacroState SurfaceEngine::morphed(const ControlFrame& f) const {
    const float w[4] = {f.a, f.b, f.c, f.d};
    MacroState out{0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    for (int i = 0; i < 4; ++i) {
        out.pitch += w[i] * corners_[i][0].load(std::memory_order_relaxed);
        out.cutoff += w[i] * corners_[i][1].load(std::memory_order_relaxed);
        out.resonance += w[i] * corners_[i][2].load(std::memory_order_relaxed);
        out.drive += w[i] * corners_[i][3].load(std::memory_order_relaxed);
        out.crush += w[i] * corners_[i][4].load(std::memory_order_relaxed);
        out.echo += w[i] * corners_[i][5].load(std::memory_order_relaxed);
        out.spring += w[i] * corners_[i][6].load(std::memory_order_relaxed);
    }
    // Tilt nudges resonance on top of the blend, the same half-weighted
    // amount XY gives it (see the XY case below) - a flat phone (tilt
    // 0.5) is a no-op, so every corner the pad already saved still sounds
    // exactly as captured. A non-finite f.tilt carries into out.resonance
    // and out through the door in applyControl below, same as everywhere
    // else a reading arrives; SurfaceStore.Corner.from's MORPH branch
    // mirrors this exactly, so SET A..D captures what you'd actually hear.
    out.resonance += (f.tilt - 0.5f) * 0.5f;
    return out;
}

void SurfaceEngine::applyControl(const ControlFrame& f) {
    MacroState target;
    switch (f.mode) {
        case 2:   // MORPH: the puck weights four states, tilt nudges resonance
        case 3:   // VECTOR: MORPH's exact corner blend - the sample triangle
                  // (sampleA/B/C, applied below) reads the same touch at
                  // once, independently; there is no separate VECTOR macro
                  // formula to have.
            target = morphed(f);
            break;
        case 1:  // XYZ: X pitch, Y cutoff, Z drive, tilt resonance
            target = {f.x, f.y, f.tilt, f.z};
            break;
        case 4:  // GRAIN: the finger is the cloud's POSITION and pitch (see
                 // below), not these macros - the chain sits at XY's
                 // defaults, as recorded and wide open, with the roll as
                 // resonance so tilt still does here what it does everywhere.
            target = {0.5f, 1.0f, f.tilt * 0.5f, 0.0f};
            break;
        default:  // XY: X pitch, Y cutoff, tilt a little resonance
            target = {f.x, f.y, f.tilt * 0.5f, 0.0f};
            break;
    }
    // The modulators, on top of whatever the mode just decided: a signed
    // nudge per macro, read once here so one frame sees one consistent
    // set. Added *before* the door below, so a swing past a rail clamps
    // there rather than wrapping or escaping - and a NaN macro plus a
    // finite offset is still a NaN, still caught below, since NaN + x is
    // NaN. GRAIN's four ride into grainMod_ for triggerGrain and the
    // position target just below - read here, ahead of that block, so
    // POSITION's nudge is this frame's, not last frame's.
    float mod[kModTargets];
    for (int32_t i = 0; i < kModTargets; ++i) mod[i] = modulation_[i].load(std::memory_order_relaxed);
    target.pitch += mod[0];
    target.cutoff += mod[1];
    target.resonance += mod[2];
    target.drive += mod[3];
    target.crush += mod[4];
    target.echo += mod[5];
    target.spring += mod[6];
    for (int32_t i = 0; i < 4; ++i) grainMod_[i] = mod[7 + i];
    // GRAIN's own two axes, through the same door. Entering or leaving the
    // mode empties the pool and snaps both axes: a mode change is a
    // different instrument, not a glide between two (the UI snaps its own
    // smoother on the same event), and a grain triggered under the old
    // mode has no business finishing under the new one.
    const bool grainMode = f.mode == 4;
    // POSITION's own modulator lands here, before the door, the same way
    // the macros' do above: a ramp on POSITION walks the cloud through the
    // sample, gliding through the same smoother the finger does.
    const float position = control01(f.x + grainMod_[3], 0.5f);
    const float pitchAxis = control01(f.y, 0.5f);
    if (grainMode != grainMode_) {
        for (auto& g : grains_) g.active = false;
        grainMode_ = grainMode;
        grainPosition_.snap(position);
        grainPitchAxis_.snap(pitchAxis);
        grainClock_ = 1.0;
    }
    grainPosition_.setTarget(position);
    grainPitchAxis_.setTarget(pitchAxis);
    // Through the door before the DSP sees any of it: the defaults are
    // "as recorded, wide open, dry", so a reading that is not a number
    // leaves the surface playing rather than stuck.
    pitch_.setTarget(control01(target.pitch, 0.5f));
    cutoff_.setTarget(control01(target.cutoff, 1.0f));
    resonance_.setTarget(control01(target.resonance, 0.0f));
    drive_.setTarget(control01(target.drive, 0.0f));
    crush_.setTarget(control01(target.crush, 0.0f));
    echo_.setTarget(control01(target.echo, 0.0f));
    spring_.setTarget(control01(target.spring, 0.0f));
    sampleWeight_[0].setTarget(control01(f.sampleA, 1.0f));
    sampleWeight_[1].setTarget(control01(f.sampleB, 0.0f));
    sampleWeight_[2].setTarget(control01(f.sampleC, 0.0f));
    sampleWeight_[3].setTarget(control01(f.sampleD, 0.0f));
    // A touch-down restarts every loaded source from its head, so a tapped
    // rhythm triggers like a drum hit regardless of where the crossfade
    // sits; a held note still rides wherever each loop has turned to
    // since. Without this, phase_ keeps advancing even while ungated (the
    // loop is muted, not paused - renderMono reads and advances it
    // regardless of gain), so the next touch would land wherever the loop
    // happened to drift to, not at its head.
    if (f.gate && !gated_) {
        for (int32_t i = 0; i < kMaxSources; ++i) {
            for (int32_t v = 0; v < kMaxSwarm; ++v) phase_[i][v] = 0.0;
        }
        crushRetrigger_ = true;
        // And GRAIN's first grain fires on the next sample, for the same
        // reason the loops restart from their head: a tap is a hit, not
        // the start of a wait (see grainClock_'s own declaration).
        grainClock_ = 1.0;
    }
    gated_ = f.gate;
    gain_.setTarget(f.gate ? 1.0f : 0.0f);
}

float SurfaceEngine::readSlotAt(int32_t slot, double frameIndex) const {
    const Sample* s = current_[slot];
    if (!s || s->frames.size() < 2) return 0.0f;
    const size_t n = s->frames.size();
    double idx = std::fmod(frameIndex, static_cast<double>(n));
    if (idx < 0.0) idx += static_cast<double>(n);
    const size_t i0 = static_cast<size_t>(idx) % n;
    const size_t i1 = (i0 + 1) % n;
    const float frac = static_cast<float>(idx - static_cast<double>(static_cast<size_t>(idx)));
    return s->frames[i0] + (s->frames[i1] - s->frames[i0]) * frac;
}

void SurfaceEngine::triggerGrain(double fs) {
    // The knobs as they are *now*, plus their modulators' nudges, each
    // through the same clamp the knob itself went through: a grain is
    // shaped once, at birth.
    const float size = clamp01(grainSize_.load(std::memory_order_relaxed) + grainMod_[0]);
    const float density = clamp01(grainDensity_.load(std::memory_order_relaxed) + grainMod_[1]);
    const float spray = clamp01(grainSpray_.load(std::memory_order_relaxed) + grainMod_[2]);
    const float lengthFrames = grain::lengthMs(size) * 0.001f * static_cast<float>(fs);
    const int32_t length = std::max<int32_t>(2, static_cast<int32_t>(lengthFrames));
    Grain* slot = nullptr;
    for (auto& g : grains_) {
        if (!g.active) {
            slot = &g;
            break;
        }
    }
    if (!slot) {
        // Every voice busy: the oldest gives way. `order` wraps after four
        // billion grains, which at 64 a second is about two years of
        // continuous touch - if it does, one steal picks the wrong grain.
        slot = &grains_[0];
        for (auto& g : grains_) {
            if (g.order < slot->order) slot = &g;
        }
    }
    slot->active = true;
    slot->startFraction = grain::startFraction(grainPosition_.value(), spray, grain::random01(grainRng_));
    slot->length = length;
    slot->pos = 0;
    slot->pitch = grain::pitchRatio(
        grainPitchAxis_.value(),
        keySourceMidi_.load(std::memory_order_relaxed),
        keyRoot_.load(std::memory_order_relaxed),
        keyMask_.load(std::memory_order_relaxed));
    slot->gain = grain::gainFor(static_cast<float>(length) * grain::rateHz(density) / static_cast<float>(fs));
    slot->order = ++grainOrder_;
}

float SurfaceEngine::grainSample(double fs, const float* weights) {
    // The finger glides every sample whether or not a grain is born on
    // it, so a grain triggered later starts from where the finger *is*.
    grainPosition_.next();
    grainPitchAxis_.next();
    // The clock: DENSITY in grains per second, as a phase toward the next
    // trigger. Read every sample so turning the knob changes the cadence
    // at once, not at the next grain.
    grainClock_ += static_cast<double>(grain::rateHz(clamp01(grainDensity_.load(std::memory_order_relaxed) + grainMod_[1]))) / fs;
    if (grainClock_ >= 1.0) {
        // One trigger per sample at most: a clock that somehow got ahead
        // (a touch-down arms it to exactly 1) does not fire twice.
        grainClock_ = std::min(grainClock_ - 1.0, 0.999);
        if (gated_) triggerGrain(fs);
    }
    float sum = 0.0f;
    for (auto& g : grains_) {
        if (!g.active) continue;
        const float window = hann_[static_cast<size_t>((static_cast<float>(g.pos) / static_cast<float>(g.length)) * static_cast<float>(kGrainWindowTable))];
        const float envelope = window * g.gain;
        for (int32_t slot = 0; slot < kMaxSources; ++slot) {
            if (weights[slot] <= 0.0f) continue;
            const Sample* s = current_[slot];
            if (!s || s->frames.size() < 2) continue;
            // This slot's own head: the grain's start as a fraction of *its*
            // length, advanced at the slot's own rate against ours and the
            // snapped pitch - the same arithmetic readSlot applies to a loop.
            const double head = static_cast<double>(g.startFraction) * static_cast<double>(s->frames.size()) +
                                static_cast<double>(g.pos) * (static_cast<double>(s->rate) / fs) * static_cast<double>(g.pitch);
            sum += weights[slot] * envelope * readSlotAt(slot, head);
        }
        if (++g.pos >= g.length) g.active = false;
    }
    return sum;
}

bool SurfaceEngine::slotLoaded(int32_t slot) const {
    const Sample* s = current_[slot];
    return s && s->frames.size() >= 2;
}

float SurfaceEngine::readSlot(int32_t slot, double fs, float pitchRatioValue) {
    const Sample* s = current_[slot];
    if (!s || s->frames.size() < 2) return 0.0f;
    const size_t n = s->frames.size();
    const double step = (static_cast<double>(s->rate) / fs) * static_cast<double>(pitchRatioValue);
    // With one voice this is the read it always was - voice 0 at the
    // ratio, gain 1 - so an untouched SWARM changes nothing, bit for bit.
    float sum = 0.0f;
    for (int32_t voice = 0; voice < swarmVoicesC_; ++voice) {
        double& phase = phase_[slot][voice];
        const size_t i0 = static_cast<size_t>(phase);
        const size_t i1 = (i0 + 1) % n;
        const float frac = static_cast<float>(phase - static_cast<double>(i0));
        sum += s->frames[i0] + (s->frames[i1] - s->frames[i0]) * frac;
        phase += step * static_cast<double>(swarmMult_[voice]);
        while (phase >= static_cast<double>(n)) phase -= static_cast<double>(n);
    }
    return sum * swarmGain_;
}

void SurfaceEngine::renderMono(float* out, int32_t numFrames) {
    const double fs = static_cast<double>(sampleRate_);
    for (int32_t i = 0; i < numFrames; ++i) {
        // Control-rate work: the filter's trig once per 32 samples, the
        // macros themselves glide per sample.
        if (--untilCoefficients_ <= 0) {
            untilCoefficients_ = kControlInterval;
            const float fc = std::min(cutoffHz(cutoff_.value()), 0.45f * static_cast<float>(sampleRate_));
            const float g = std::tan(kPi * fc / static_cast<float>(sampleRate_));
            const float k = 2.0f - 1.9f * resonance_.value();
            svfA1_ = 1.0f / (1.0f + g * (g + k));
            svfA2_ = g * svfA1_;
            svfA3_ = g * svfA2_;
            // KEY for the loop, and the key it snaps to, read here at
            // control rate (see keySnapLoop_'s own declaration).
            keySnapOn_ = keySnapLoop_.load(std::memory_order_relaxed);
            keyRootC_ = keyRoot_.load(std::memory_order_relaxed);
            keyMaskC_ = keyMask_.load(std::memory_order_relaxed);
            keySourceC_ = keySourceMidi_.load(std::memory_order_relaxed);
            // SWARM: n voices spread evenly over ±detune × kMaxDetuneCents
            // (one voice sits at 0, two at ±half, three at -1/0/+1, four at
            // -1/-1/3/+1/3/+1), summed at 1/sqrt(n) so a detuned swarm of
            // uncorrelated voices holds its level; a coherent one (detune
            // 0) is sqrt(n) louder, which is what a unison is.
            swarmVoicesC_ = swarmVoices_.load(std::memory_order_relaxed);
            const float cents = swarmDetune_.load(std::memory_order_relaxed) * kMaxDetuneCents;
            for (int32_t v = 0; v < kMaxSwarm; ++v) {
                const float spread = swarmVoicesC_ > 1
                    ? 2.0f * static_cast<float>(v) / static_cast<float>(swarmVoicesC_ - 1) - 1.0f
                    : 0.0f;
                swarmMult_[v] = std::exp2(cents * spread / 1200.0f);
            }
            swarmGain_ = 1.0f / std::sqrt(static_cast<float>(swarmVoicesC_));
            // ECHO's time: a new one starts a crossfade from the tap in use
            // to the new tap (see setEchoTime); one at a time, so a change
            // that lands mid-fade waits for the next interval.
            if (echoFadeLeft_ <= 0) {
                const size_t want = echoDelaySamples(echoSeconds_.load(std::memory_order_relaxed), static_cast<float>(sampleRate_));
                if (want != echoDelayC_) {
                    echoDelayFrom_ = echoDelayC_;
                    echoDelayC_ = want;
                    echoFadeLeft_ = echoFadeSamples_;
                }
            }
        }
        const float pitch = pitch_.next();
        cutoff_.next();
        resonance_.next();
        const float drive = drive_.next();
        const float crush = crush_.next();
        const float echo = echo_.next();
        const float spring = spring_.next();
        const float gain = gain_.next();
        const float w0 = sampleWeight_[0].next();
        const float w1 = sampleWeight_[1].next();
        const float w2 = sampleWeight_[2].next();

        const float w3 = sampleWeight_[3].next();

        // The source: slots 0/1/2/3 each loop independently (their own
        // phase_[slot], the same linear-interpolation read as always),
        // then blend by sampleA/B/C/D before drive/filter ever sees the
        // result. Each weight glides on its own smoother, so their sum can
        // drift a little off 1 mid-glide (unlike stage 1's paired
        // mix/1-mix) - renormalising here keeps the blend from ever
        // spiking or dipping in loudness while a weight is still catching
        // up. Normalising is over the *loaded* slots only, not every
        // weight the touch position sends: an empty vertex's share of the
        // blend would otherwise just vanish rather than fall to whichever
        // slots are actually loaded, quietly halving a single loaded
        // sample's level anywhere the puck sits closer to an empty vertex
        // than a full one - exactly the dead zone the vertex blend exists
        // to avoid. Reads as silence, rather than dividing by ~0, only
        // when every loaded slot's weight is near zero at once.
        const float w0Loaded = slotLoaded(0) ? w0 : 0.0f;
        const bool loaded1 = slotLoaded(1);
        const bool loaded2 = slotLoaded(2);
        const bool loaded3 = slotLoaded(3);
        float w1Loaded = loaded1 ? w1 : 0.0f;
        float w2Loaded = loaded2 ? w2 : 0.0f;
        const float w3Loaded = loaded3 ? w3 : 0.0f;
        if (!loaded3) {
            // PAD4 (slot 3) is not just another vertex: TouchSurface.
            // sampleWeights splits the pad into two half-triangles that
            // meet at PAD4's own vertex, so PAD2 and PAD3's raw weights
            // *both* fall to zero approaching it, the same way any single
            // vertex's neighbours do near it - but here there is no third
            // loaded neighbour left for the ordinary renormalisation above
            // to fall back on, so an unloaded PAD4 would otherwise leave a
            // real hole at the bottom-centre of the pad, not just the one
            // infinitesimal point its own vertex sits at. Handing its raw
            // share to whichever of PAD2/PAD3 are actually loaded recovers
            // the continuous PAD2/PAD3 crossfade this seam was before PAD4
            // existed.
            const int32_t sides = (loaded1 ? 1 : 0) + (loaded2 ? 1 : 0);
            if (sides > 0) {
                const float share = w3 / static_cast<float>(sides);
                if (loaded1) w1Loaded += share;
                if (loaded2) w2Loaded += share;
            }
        }
        const float wSum = w0Loaded + w1Loaded + w2Loaded + w3Loaded;
        const float wInv = wSum > 1e-6f ? 1.0f / wSum : 0.0f;
        // KEY on: the loop's pitch is snapped to the key after the glide,
        // exactly as a grain's is - so a slide across the pad steps through
        // the key's notes rather than sweeping between them, and a note the
        // loop lands on is a note the cloud would land on (one snap, in
        // Grain.h). Off: as recorded, ±1 octave across the pad, as always.
        const float pr = keySnapOn_ ? grain::pitchRatio(pitch, keySourceC_, keyRootC_, keyMaskC_) : pitchRatio(pitch);
        // GRAIN swaps the source and nothing else: the same four slots at
        // the same renormalised weights, read as a cloud of windowed grains
        // rather than four loops. The loops' phases simply hold while the
        // cloud plays; a touch-down still resets them for when it ends.
        float v0;
        if (grainMode_) {
            const float weights[kMaxSources] = {w0Loaded * wInv, w1Loaded * wInv, w2Loaded * wInv, w3Loaded * wInv};
            v0 = grainSample(fs, weights);
        } else {
            v0 = (w0Loaded * wInv) * readSlot(0, fs, pr) +
                 (w1Loaded * wInv) * readSlot(1, fs, pr) +
                 (w2Loaded * wInv) * readSlot(2, fs, pr) +
                 (w3Loaded * wInv) * readSlot(3, fs, pr);
        }

        // CRUSH: sample-and-hold downsampling plus shrinking quantisation
        // levels, both continuous functions of the macro rather than an
        // integer downsample count - crushPhase_ crosses a threshold that
        // itself glides with crush, so there is no added snap on top of
        // the crush's own stair-stepped texture as a corner blend moves
        // through it. crush = 0 bypasses the hold/quantise entirely
        // (exactly transparent, not just close to it - a corner that has
        // never touched this macro must reproduce the legacy waveform
        // bit-for-bit, since quantising even at "off" would quietly
        // change every corner shipped before crush existed); crush = 1
        // holds for 25 samples at as few as ~8 levels. crushRetrigger_
        // (see its own declaration) forces an immediate re-latch on a
        // fresh touch-down rather than carrying a hold in from whatever
        // the previous note last latched.
        if (crush <= 0.0f) {
            heldCrush_ = v0;
            crushPhase_ = 0.0f;
            crushRetrigger_ = false;
        } else {
            crushPhase_ += 1.0f;
            const float holdSamples = 1.0f + crush * 24.0f;
            if (crushRetrigger_ || crushPhase_ >= holdSamples) {
                crushPhase_ = crushRetrigger_ ? 0.0f : std::fmod(crushPhase_, holdSamples);
                crushRetrigger_ = false;
                const float levels = std::exp2(14.0f - crush * 11.0f);
                heldCrush_ = std::round(v0 * levels) / levels;
            }
        }

        // Drive: a soft clip with its make-up baked in, so DRIVE is a colour and not a volume knob.
        const float pre = 1.0f + drive * 15.0f;
        const float driven = std::tanh(heldCrush_ * pre) / std::tanh(pre * 0.5f + 0.5f);

        // The lowpass.
        const float v3 = driven - ic2eq_;
        const float v1 = svfA1_ * ic1eq_ + svfA2_ * v3;
        const float v2 = ic2eq_ + svfA2_ * ic1eq_ + svfA3_ * v3;
        ic1eq_ = 2.0f * v1 - ic1eq_;
        ic2eq_ = 2.0f * v2 - ic2eq_;

        // ECHO: a ring buffer written at delayWrite_ and read at the tap
        // echoDelayC_ samples behind it - that distance *is* the delay
        // time (see delayBuffer_'s own declaration and setEchoTime).
        // While a time change is in flight the old tap fades out as the
        // new fades in over echoFadeSamples_, and the mix of the two is
        // what feeds back, so the line never hears a step. Fed with
        // `gated`, not v2 directly: silence must stay silence going in,
        // so a released touch lets an already-ringing tail decay on its
        // own via kDelayFeedback rather than the loop echoing into itself
        // for ever while nobody is touching the pad. `echo` is only ever
        // the wet MIX read out here, never the time or feedback - see
        // kDelayTimeMs/kDelayFeedback's own comment.
        const float gated = v2 * gain;
        const size_t ring = delayBuffer_.size();
        float wet = delayBuffer_[(delayWrite_ + ring - echoDelayC_) % ring];
        if (echoFadeLeft_ > 0) {
            const float from = delayBuffer_[(delayWrite_ + ring - echoDelayFrom_) % ring];
            const float mix = static_cast<float>(echoFadeLeft_) / static_cast<float>(echoFadeSamples_);
            wet = wet + (from - wet) * mix;
            --echoFadeLeft_;
        }
        delayBuffer_[delayWrite_] = gated + wet * kDelayFeedback;
        delayWrite_ = (delayWrite_ + 1) % ring;

        // SPRING: SIZE and TONE are fixed (see kSpringCombMs's own
        // comment), so only the wet MIX - this macro - is ever
        // corner-blended, the same reasoning ECHO's own comment gives.
        // Four parallel combs (each summing its own delayed, TONE-
        // lowpassed feedback back into the loop) build the density; two
        // series allpasses smear it into a tail - synth/Spring.kt's
        // offline network, ported to run one sample at a time instead of
        // baking a whole buffer at once. Fed with `gated`, not v2
        // directly, for the same reason ECHO is: silence in stays silence,
        // and a released touch's tail rings down on its own via each
        // comb's own feedback rather than cutting off with the gate. The
        // network runs unconditionally every sample regardless of
        // `spring`'s own value (like the delay line above) - multiplying
        // its output by `spring` in the final sum is what makes spring = 0
        // exactly silent, not the network itself switching off, so a
        // corner that has never touched this macro reproduces the legacy
        // waveform bit-for-bit.
        float springWet = 0.0f;
        for (int32_t c = 0; c < kSpringCombCount; ++c) {
            const size_t idx = springCombWrite_[c];
            const float fed = springCombBuf_[c][idx];
            springWet += fed;
            springCombLp_[c] += springLpA_ * (fed - springCombLp_[c]);
            springCombBuf_[c][idx] = gated + springCombFb_[c] * springCombLp_[c];
            springCombWrite_[c] = (idx + 1) % springCombBuf_[c].size();
        }
        springWet *= 0.25f;
        for (int32_t a = 0; a < kSpringAllpassCount; ++a) {
            const size_t idx = springApWrite_[a];
            const float delayed = springApBuf_[a][idx];
            const float fedIn = springWet + kSpringAllpassGain * delayed;
            springApBuf_[a][idx] = fedIn;
            springWet = delayed - kSpringAllpassGain * fedIn;
            springApWrite_[a] = (idx + 1) % springApBuf_[a].size();
        }

        out[i] = (gated + wet * echo + springWet * spring) * 0.8f;
    }
}

oboe::DataCallbackResult SurfaceEngine::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    for (int32_t slot = 0; slot < kMaxSources; ++slot) adoptPendingSample(slot);

    // Drain the ring, applying every frame in the order it arrived: the
    // continuous macros (pitch/cutoff/...) are a position, not a history,
    // so applying several before rendering a sample is harmless - only the
    // last setTarget before renderMono's next() calls sticks. But the
    // gate's *edge* is an event, and a lift-then-retouch that lands in the
    // same drain (the ring holds up to 64 frames, and the UI can push
    // faster than one audio callback drains) is a real sequence, not a
    // single level: jumping straight to the newest frame would apply
    // gate=true against a gated_ that was never told about the
    // intervening false, and applyControl's touch-down check would miss
    // the retrigger entirely.
    ControlFrame frame;
    bool any = false;
    while (controls_.pop(frame)) {
        any = true;
        applyControl(frame);
    }
    if (any) latest_ = frame;

    auto* out = static_cast<float*>(audioData);
    int32_t done = 0;
    while (done < numFrames) {
        const int32_t chunk = std::min<int32_t>(numFrames - done, static_cast<int32_t>(kScratchFrames));
        float* mono = scratch_.data();
        renderMono(mono, chunk);
        // The master bus is mono to both channels; the print tap takes the
        // mono copy before it is duplicated.
        print_.record(mono, static_cast<size_t>(chunk));
        for (int32_t i = 0; i < chunk; ++i) {
            out[(done + i) * 2] = mono[i];
            out[(done + i) * 2 + 1] = mono[i];
        }
        done += chunk;
    }
    return oboe::DataCallbackResult::Continue;
}

}  // namespace snipsnap
