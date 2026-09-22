#include "LiveSnapEngine.h"

#include "OboeOutput.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>

#define LOG_TAG "LiveSnapEngine"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace snipsnap {

namespace {
// Snap.TUNE_SEMITONES and Snap.ROOT_HZ, mirrored: two octaves above A2,
// snapped to the nearest semitone, the same note TUNE lands on through a
// one-shot render.
constexpr int32_t kTuneSemitones = 24;
constexpr float kRootHz = 110.0f;

/**
 * 0..1, and NaN-safe: written `!(v > 0)` rather than `v < 0` so a NaN
 * lands on 0 instead of sailing through - see SurfaceEngine.cpp's own
 * `clamp01` for why this matters more here than almost anywhere: the
 * smoothers and the filter carry state across callbacks, so one NaN
 * never leaves once it's in.
 */
inline float clamp01(float v) { return !(v > 0.0f) ? 0.0f : (v > 1.0f ? 1.0f : v); }

/** A macro arriving from outside (JNI). Non-finite reads as `fallback`. */
inline float control01(float v, float fallback) { return std::isfinite(v) ? clamp01(v) : fallback; }

/** The note TUNE lands on - Snap.frequencyFor, ported. */
inline float frequencyForTune(float tune) {
    const float semis = std::round(clamp01(tune) * kTuneSemitones);
    return kRootHz * std::exp2(semis / 12.0f);
}

/** 0..1 -> [lo, hi], exponential - Dsp.expMap's own curve, so BRIGHT reads the same live as it does through a one-shot render. */
inline float expMap(float macro, float lo, float hi) {
    return lo * std::exp(std::log(hi / lo) * clamp01(macro));
}
}  // namespace

LiveSnapEngine::LiveSnapEngine(int32_t preferredSampleRate)
    : preferredRate_(preferredSampleRate), scratch_(kScratchFrames, 0.0f) {
    // At the member's own default sampleRate_ (48000) - renderMono reads
    // freqHz_ and the filter's own coefficients unconditionally on every
    // sample, so onAudioReady must never see them unconfigured. start()
    // configures again once the device's real rate is known; nothing
    // here needs to survive that. The host test suite calls onAudioReady
    // directly and never start() at all, which is exactly the case this
    // guards - SurfaceEngine's own constructor does the same for its own
    // rate-dependent state.
    configureForRate(static_cast<float>(sampleRate_));
}

void LiveSnapEngine::configureForRate(float fs) {
    // ~15 ms glides for the macros - SurfaceEngine's own corner rate - so
    // a fresh camera reading eases in rather than stepping.
    tuneSmoother_.configure(10.0f, fs);
    brightSmoother_.configure(10.0f, fs);
    gritSmoother_.configure(10.0f, fs);
    tuneSmoother_.snap(control01(targetTune_.load(std::memory_order_relaxed), 0.5f));
    brightSmoother_.snap(control01(targetBright_.load(std::memory_order_relaxed), 0.6f));
    gritSmoother_.snap(control01(targetGrit_.load(std::memory_order_relaxed), 0.15f));
    filter_.configure(expMap(brightSmoother_.value(), 250.0f, 12000.0f), fs);
    filter_.snap(0.0f);
    untilCoefficients_ = 0;
    freqHz_ = frequencyForTune(tuneSmoother_.value());
    crossfadeFrames_ = std::max<int32_t>(1, static_cast<int32_t>(std::round(0.05f * fs)));
}

LiveSnapEngine::~LiveSnapEngine() {
    stop();
    // With the stream closed the audio thread is gone; every slot is ours.
    delete pending_.exchange(nullptr);
    delete retired_.exchange(nullptr);
    delete current_;
    delete previous_;
    current_ = previous_ = nullptr;
}

bool LiveSnapEngine::start() {
    stop();
    restartNeeded_.store(false, std::memory_order_release);
    sharedMode_.store(false, std::memory_order_release);

    // A restart never carries a previous session's picture over - LIVE
    // opens silent until the very next camera frame, the same reasoning
    // SurfaceEngine's own start() empties its grain pool.
    delete pending_.exchange(nullptr);
    delete retired_.exchange(nullptr);
    delete current_;
    delete previous_;
    current_ = previous_ = nullptr;
    crossfadeFrame_ = 0;
    phase_ = 0.0;

    const OpenedOutput opened = openStereoFloatOutput(preferredRate_, this, this, stream_, LOG_TAG);
    if (!opened.ok) return false;
    sampleRate_ = opened.sampleRate;
    sharedMode_.store(opened.shared, std::memory_order_release);

    // At the rate the device actually gave us, not preferredRate_ -
    // reconfigured every time start() runs, same reasoning SurfaceEngine
    // resizes its own rate-dependent buffers in start() rather than once.
    configureForRate(static_cast<float>(sampleRate_));

    const oboe::Result started = stream_->requestStart();
    if (started != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(started));
        stream_->close();
        stream_.reset();
        return false;
    }
    return true;
}

double LiveSnapEngine::latencyMillis() const {
    return latencyMillisOf(stream_);
}

void LiveSnapEngine::stop() {
    if (!stream_) return;
    stream_->stop();
    stream_->close();
    stream_.reset();
}

void LiveSnapEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // A route change closes the stream under us. The UI polls
    // needsRestart() and reopens; the audio thread cannot do it from
    // inside its own error callback.
    LOGW("stream closed: %s", oboe::convertToText(error));
    restartNeeded_.store(true, std::memory_order_release);
}

void LiveSnapEngine::pushFrame(const float* table, int32_t count) {
    auto* t = new LiveTable();
    const int32_t n = std::clamp(count, 0, kTableSize);
    for (int32_t i = 0; i < n; ++i) t->points[i] = table[i];
    // A short push leaves the rest at rest (0), never whatever `new` left there.
    for (int32_t i = n; i < kTableSize; ++i) t->points[i] = 0.0f;
    // A table the callback never got round to adopting is ours to free.
    delete pending_.exchange(t, std::memory_order_acq_rel);
    // And the one it retired last time.
    delete retired_.exchange(nullptr, std::memory_order_acq_rel);
}

void LiveSnapEngine::setMacros(float tune, float bright, float grit) {
    targetTune_.store(control01(tune, 0.5f), std::memory_order_relaxed);
    targetBright_.store(control01(bright, 0.6f), std::memory_order_relaxed);
    targetGrit_.store(control01(grit, 0.15f), std::memory_order_relaxed);
}

void LiveSnapEngine::adoptPendingTable() {
    // Only take a pending table when there is room to retire the current
    // one - the exact backpressure SurfaceEngine::adoptPendingSample
    // documents for its own sample handshake, so a store into `retired_`
    // never overwrites (and leaks) a retirement the UI has not reclaimed
    // yet. `retired_` only goes null -> non-null on this thread, so this
    // check-then-store cannot race the UI, which only ever exchanges it
    // to null.
    if (pending_.load(std::memory_order_acquire) == nullptr) return;
    if (retired_.load(std::memory_order_acquire) != nullptr) return;
    LiveTable* incoming = pending_.exchange(nullptr, std::memory_order_acq_rel);
    if (!incoming) return;
    if (previous_ != nullptr) retired_.store(previous_, std::memory_order_release);
    previous_ = current_;
    current_ = incoming;
    crossfadeFrame_ = 0;
}

float LiveSnapEngine::readTable(const LiveTable* t, float phase) {
    if (t == nullptr) return 0.0f;
    const int32_t idx = static_cast<int32_t>(phase);
    const float frac = phase - static_cast<float>(idx);
    const float a = t->points[idx % kTableSize];
    const float b = t->points[(idx + 1) % kTableSize];
    return a + (b - a) * frac;
}

void LiveSnapEngine::renderMono(float* out, int32_t numFrames) {
    const float fs = static_cast<float>(sampleRate_);
    for (int32_t i = 0; i < numFrames; ++i) {
        // Every smoother advances every sample - the click-free glide
        // depends on that - but the trig/exp behind the derived values
        // below only needs refreshing every kControlInterval samples,
        // SurfaceEngine's own split between cheap and expensive work.
        const float tune = tuneSmoother_.next();
        const float bright = brightSmoother_.next();
        const float grit = gritSmoother_.next();
        if (--untilCoefficients_ <= 0) {
            untilCoefficients_ = kControlInterval;
            freqHz_ = frequencyForTune(tune);
            filter_.configure(expMap(bright, 250.0f, 12000.0f), fs);
        }

        const float step = freqHz_ * static_cast<float>(kTableSize) / fs;
        phase_ += step;
        while (phase_ >= kTableSize) phase_ -= kTableSize;
        while (phase_ < 0.0) phase_ += kTableSize;
        const float phaseF = static_cast<float>(phase_);

        float sample = readTable(current_, phaseF);
        if (previous_ != nullptr && crossfadeFrame_ < crossfadeFrames_) {
            // Both tables read at the SAME phase, so the blend is of two
            // comparable cycles, not two waves drifting apart in time.
            const float from = readTable(previous_, phaseF);
            const float mix = static_cast<float>(crossfadeFrame_) / static_cast<float>(crossfadeFrames_);
            sample = from + (sample - from) * mix;
            ++crossfadeFrame_;
        }

        // filter_ doubles as the audio-rate tone filter BRIGHT drives -
        // see this class's own header comment for why that's exact, not
        // an approximation.
        filter_.setTarget(sample);
        const float filtered = filter_.next();

        // Soft saturation with unity-ish make-up - Dsp.drive's own shape,
        // so GRIT changes tone here the same way it does through a
        // one-shot render.
        if (grit > 0.0f) {
            const float g = 1.0f + 6.0f * grit;
            out[i] = static_cast<float>(std::tanh(filtered * g) / std::tanh(g));
        } else {
            out[i] = filtered;
        }
    }
}

oboe::DataCallbackResult LiveSnapEngine::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    adoptPendingTable();
    // The macros are a position, not an event - reading the latest one
    // before rendering is enough, the same reasoning SurfaceEngine reads
    // its own corner atomics once per callback rather than per sample.
    // setMacros already ran every value through control01 before storing
    // it, so what lands here is always finite and 0..1.
    tuneSmoother_.setTarget(targetTune_.load(std::memory_order_relaxed));
    brightSmoother_.setTarget(targetBright_.load(std::memory_order_relaxed));
    gritSmoother_.setTarget(targetGrit_.load(std::memory_order_relaxed));

    auto* out = static_cast<float*>(audioData);
    int32_t done = 0;
    while (done < numFrames) {
        const int32_t chunk = std::min<int32_t>(numFrames - done, static_cast<int32_t>(kScratchFrames));
        float* mono = scratch_.data();
        renderMono(mono, chunk);
        // The master bus is mono to both channels, the same convention
        // SurfaceEngine's own onAudioReady follows.
        for (int32_t i = 0; i < chunk; ++i) {
            out[(done + i) * 2] = mono[i];
            out[(done + i) * 2 + 1] = mono[i];
        }
        done += chunk;
    }
    return oboe::DataCallbackResult::Continue;
}

}  // namespace snipsnap
