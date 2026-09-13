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
    // std::atomic's default constructor is trivial in C++17 and does not
    // reliably value-initialize the contained pointer - set every slot to
    // nullptr explicitly rather than trust an array member initializer.
    for (int32_t i = 0; i < kMaxSources; ++i) {
        pending_[i].store(nullptr, std::memory_order_relaxed);
        retired_[i].store(nullptr, std::memory_order_relaxed);
    }
    // The four corners of the morph pad, before the UI says otherwise:
    // A clean, B dark, C low and thick, D hot.
    const MacroState defaults[4] = {
        {0.5f, 1.0f, 0.0f, 0.0f},
        {0.5f, 0.25f, 0.3f, 0.1f},
        {0.25f, 0.6f, 0.5f, 0.4f},
        {0.75f, 0.85f, 0.2f, 0.9f},
    };
    for (int i = 0; i < 4; ++i) setCorner(i, defaults[i]);
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
    for (auto& w : sampleWeight_) w.configure(10.0f, fs);
    gain_.configure(50.0f, fs);
    gain_.snap(0.0f);
    untilCoefficients_ = 0;
    ic1eq_ = ic2eq_ = 0.0f;

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
    phase_[slot] = 0.0;
}

MacroState SurfaceEngine::morphed(const ControlFrame& f) const {
    const float w[4] = {f.a, f.b, f.c, f.d};
    MacroState out{0.0f, 0.0f, 0.0f, 0.0f};
    for (int i = 0; i < 4; ++i) {
        out.pitch += w[i] * corners_[i][0].load(std::memory_order_relaxed);
        out.cutoff += w[i] * corners_[i][1].load(std::memory_order_relaxed);
        out.resonance += w[i] * corners_[i][2].load(std::memory_order_relaxed);
        out.drive += w[i] * corners_[i][3].load(std::memory_order_relaxed);
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
        case 2:  // MORPH: the puck weights four states, tilt nudges resonance
            target = morphed(f);
            break;
        case 1:  // XYZ: X pitch, Y cutoff, Z drive, tilt resonance
            target = {f.x, f.y, f.tilt, f.z};
            break;
        default:  // XY: X pitch, Y cutoff, tilt a little resonance
            target = {f.x, f.y, f.tilt * 0.5f, 0.0f};
            break;
    }
    // Through the door before the DSP sees any of it: the defaults are
    // "as recorded, wide open, dry", so a reading that is not a number
    // leaves the surface playing rather than stuck.
    pitch_.setTarget(control01(target.pitch, 0.5f));
    cutoff_.setTarget(control01(target.cutoff, 1.0f));
    resonance_.setTarget(control01(target.resonance, 0.0f));
    drive_.setTarget(control01(target.drive, 0.0f));
    sampleWeight_[0].setTarget(control01(f.sampleA, 1.0f));
    sampleWeight_[1].setTarget(control01(f.sampleB, 0.0f));
    sampleWeight_[2].setTarget(control01(f.sampleC, 0.0f));
    // A touch-down restarts every loaded source from its head, so a tapped
    // rhythm triggers like a drum hit regardless of where the crossfade
    // sits; a held note still rides wherever each loop has turned to
    // since. Without this, phase_ keeps advancing even while ungated (the
    // loop is muted, not paused - renderMono reads and advances it
    // regardless of gain), so the next touch would land wherever the loop
    // happened to drift to, not at its head.
    if (f.gate && !gated_) {
        for (int32_t i = 0; i < kMaxSources; ++i) phase_[i] = 0.0;
    }
    gated_ = f.gate;
    gain_.setTarget(f.gate ? 1.0f : 0.0f);
}

float SurfaceEngine::readSlot(int32_t slot, double fs, float pitchRatioValue) {
    const Sample* s = current_[slot];
    if (!s || s->frames.size() < 2) return 0.0f;
    const size_t n = s->frames.size();
    const size_t i0 = static_cast<size_t>(phase_[slot]);
    const size_t i1 = (i0 + 1) % n;
    const float frac = static_cast<float>(phase_[slot] - static_cast<double>(i0));
    const float v = s->frames[i0] + (s->frames[i1] - s->frames[i0]) * frac;
    phase_[slot] += (static_cast<double>(s->rate) / fs) * static_cast<double>(pitchRatioValue);
    while (phase_[slot] >= static_cast<double>(n)) phase_[slot] -= static_cast<double>(n);
    return v;
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
        }
        const float pitch = pitch_.next();
        cutoff_.next();
        resonance_.next();
        const float drive = drive_.next();
        const float gain = gain_.next();
        const float w0 = sampleWeight_[0].next();
        const float w1 = sampleWeight_[1].next();
        const float w2 = sampleWeight_[2].next();

        // The source: slots 0/1/2 each loop independently (their own
        // phase_[slot], the same linear-interpolation read as always),
        // then blend by sampleA/B/C before drive/filter ever sees the
        // result. Each weight glides on its own smoother, so their sum can
        // drift a little off 1 mid-glide (unlike stage 1's paired
        // mix/1-mix) - renormalising here keeps the blend from ever
        // spiking or dipping in loudness while a weight is still catching
        // up, and reads as silence rather than dividing by ~0 if all three
        // ever glide through together near zero. Slot 3 isn't mixed in yet
        // (see kMaxSources).
        const float wSum = w0 + w1 + w2;
        const float wInv = wSum > 1e-6f ? 1.0f / wSum : 0.0f;
        const float pr = pitchRatio(pitch);
        const float v0 = (w0 * wInv) * readSlot(0, fs, pr) +
                         (w1 * wInv) * readSlot(1, fs, pr) +
                         (w2 * wInv) * readSlot(2, fs, pr);

        // Drive: a soft clip with its make-up baked in, so DRIVE is a colour and not a volume knob.
        const float pre = 1.0f + drive * 15.0f;
        const float driven = std::tanh(v0 * pre) / std::tanh(pre * 0.5f + 0.5f);

        // The lowpass.
        const float v3 = driven - ic2eq_;
        const float v1 = svfA1_ * ic1eq_ + svfA2_ * v3;
        const float v2 = ic2eq_ + svfA2_ * ic1eq_ + svfA3_ * v3;
        ic1eq_ = 2.0f * v1 - ic1eq_;
        ic2eq_ = 2.0f * v2 - ic2eq_;

        out[i] = v2 * gain * 0.8f;
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
