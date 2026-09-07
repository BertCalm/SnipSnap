#include "SurfaceEngine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>

#define LOG_TAG "SurfaceEngine"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace snipsnap {

namespace {
constexpr float kPi = 3.14159265358979f;

inline float clamp01(float v) { return v < 0.0f ? 0.0f : (v > 1.0f ? 1.0f : v); }

/** 0..1 → 80 Hz .. 16 kHz, exponential, the way a filter knob reads. */
inline float cutoffHz(float macro) { return 80.0f * std::exp2(clamp01(macro) * 7.6439f); }

/** 0..1 → ±1 octave around 0.5. */
inline float pitchRatio(float macro) { return std::exp2((clamp01(macro) - 0.5f) * 2.0f); }
}  // namespace

SurfaceEngine::SurfaceEngine(int32_t preferredSampleRate)
    : preferredRate_(preferredSampleRate), scratch_(kScratchFrames, 0.0f) {
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
    delete pending_.exchange(nullptr);
    delete retired_.exchange(nullptr);
    delete current_;
    current_ = nullptr;
}

bool SurfaceEngine::start() {
    stop();
    restartNeeded_.store(false, std::memory_order_release);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(preferredRate_)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setUsage(oboe::Usage::Media)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);

    const oboe::Result opened = builder.openStream(stream_);
    if (opened != oboe::Result::OK) {
        LOGW("openStream failed: %s", oboe::convertToText(opened));
        stream_.reset();
        return false;
    }
    sampleRate_ = stream_->getSampleRate();

    // Two bursts is the usual low-latency sweet spot: one in flight, one
    // being filled. Oboe clamps to what the device allows.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);

    // ~15 ms glides for the macros, ~3 ms for the gate so a finger-down is a
    // click-free attack rather than a slow swell. Recomputed here because the
    // device may have handed back a rate other than the one asked for.
    const float fs = static_cast<float>(sampleRate_);
    pitch_.configure(10.0f, fs);
    cutoff_.configure(10.0f, fs);
    resonance_.configure(10.0f, fs);
    drive_.configure(10.0f, fs);
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

void SurfaceEngine::loadSample(const float* mono, size_t frames, int32_t sourceRate) {
    auto* sample = new Sample();
    sample->frames.assign(mono, mono + frames);
    sample->rate = sourceRate > 0 ? sourceRate : 44100;
    // A sample the callback never got round to adopting is ours to free.
    delete pending_.exchange(sample, std::memory_order_acq_rel);
    // And the one it retired last time.
    delete retired_.exchange(nullptr, std::memory_order_acq_rel);
}

void SurfaceEngine::setCorner(int index, const MacroState& state) {
    if (index < 0 || index > 3) return;
    corners_[index][0].store(clamp01(state.pitch), std::memory_order_relaxed);
    corners_[index][1].store(clamp01(state.cutoff), std::memory_order_relaxed);
    corners_[index][2].store(clamp01(state.resonance), std::memory_order_relaxed);
    corners_[index][3].store(clamp01(state.drive), std::memory_order_relaxed);
}

// ---- audio thread from here down ---------------------------------------------

void SurfaceEngine::adoptPendingSample() {
    Sample* incoming = pending_.exchange(nullptr, std::memory_order_acq_rel);
    if (!incoming) return;
    // Retire the old one for the UI to free. If the UI has not collected the
    // previous retiree yet, keep the newer one waiting in pending_ instead of
    // leaking either - it will be adopted on the next callback.
    Sample* expected = nullptr;
    if (!retired_.compare_exchange_strong(expected, current_, std::memory_order_acq_rel)) {
        pending_.store(incoming, std::memory_order_release);
        return;
    }
    current_ = incoming;
    phase_ = 0.0;
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
    return out;
}

void SurfaceEngine::applyControl(const ControlFrame& f) {
    MacroState target;
    switch (f.mode) {
        case 2:  // MORPH: the puck weights four states
            target = morphed(f);
            break;
        case 1:  // XYZ: X pitch, Y cutoff, Z drive, tilt resonance
            target = {f.x, f.y, f.tilt, f.z};
            break;
        default:  // XY: X pitch, Y cutoff, tilt a little resonance
            target = {f.x, f.y, f.tilt * 0.5f, 0.0f};
            break;
    }
    pitch_.setTarget(target.pitch);
    cutoff_.setTarget(target.cutoff);
    resonance_.setTarget(target.resonance);
    drive_.setTarget(target.drive);
    gain_.setTarget(f.gate ? 1.0f : 0.0f);
}

void SurfaceEngine::renderMono(float* out, int32_t numFrames) {
    const Sample* s = current_;
    const double fs = static_cast<double>(sampleRate_);
    for (int32_t i = 0; i < numFrames; ++i) {
        // Control-rate work: the filter's trig once per 32 samples, the
        // macros themselves glide per sample.
        if (untilCoefficients_-- <= 0) {
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

        // The source: a looping read with linear interpolation, repitched by
        // the file rate over the stream rate times the pitch macro.
        float v0 = 0.0f;
        if (s && s->frames.size() >= 2) {
            const size_t n = s->frames.size();
            const size_t i0 = static_cast<size_t>(phase_);
            const size_t i1 = (i0 + 1) % n;
            const float frac = static_cast<float>(phase_ - static_cast<double>(i0));
            v0 = s->frames[i0] + (s->frames[i1] - s->frames[i0]) * frac;
            phase_ += (static_cast<double>(s->rate) / fs) * static_cast<double>(pitchRatio(pitch));
            while (phase_ >= static_cast<double>(n)) phase_ -= static_cast<double>(n);
        }

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
    adoptPendingSample();

    // Drain the ring to the newest frame: a control stream is a position,
    // not a history, and the smoothers glide toward wherever it is now.
    ControlFrame frame;
    bool any = false;
    while (controls_.pop(frame)) any = true;
    if (any) {
        latest_ = frame;
        applyControl(latest_);
    }

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
