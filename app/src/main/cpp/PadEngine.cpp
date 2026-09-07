#include "PadEngine.h"

#include <android/log.h>

#include <algorithm>
#include <cmath>

#include "OboeOutput.h"

#define LOG_TAG "PadEngine"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace snipsnap {

PadEngine::PadEngine(int32_t preferredSampleRate) : preferredRate_(preferredSampleRate) {}

PadEngine::~PadEngine() {
    stop();
    // With the stream closed the audio thread is gone; every slot is ours.
    delete pending_.exchange(nullptr);
    delete retired_.exchange(nullptr);
    delete current_;
    current_ = nullptr;
}

bool PadEngine::start() {
    stop();
    restartNeeded_.store(false, std::memory_order_release);
    sharedMode_.store(false, std::memory_order_release);

    const OpenedOutput opened = openStereoFloatOutput(preferredRate_, this, this, stream_, LOG_TAG);
    if (!opened.ok) return false;
    sampleRate_ = opened.sampleRate;
    sharedMode_.store(opened.shared, std::memory_order_release);

    const oboe::Result started = stream_->requestStart();
    if (started != oboe::Result::OK) {
        LOGW("requestStart failed: %s", oboe::convertToText(started));
        stream_->close();
        stream_.reset();
        return false;
    }
    return true;
}

void PadEngine::stop() {
    if (!stream_) return;
    stream_->stop();
    stream_->close();
    stream_.reset();
}

void PadEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    LOGW("stream closed: %s", oboe::convertToText(error));
    restartNeeded_.store(true, std::memory_order_release);
}

// ---- the bank (UI thread) ---------------------------------------------------

void PadEngine::beginBank() {
    building_ = std::make_unique<Bank>();
}

int32_t PadEngine::addSample(std::vector<float>&& interleaved, int32_t channels, int32_t rate) {
    if (!building_) beginBank();
    BankSample s;
    s.frames = std::move(interleaved);
    s.channels = channels == 2 ? 2 : 1;
    s.rate = rate > 0 ? rate : 44100;
    building_->samples.push_back(std::move(s));
    return static_cast<int32_t>(building_->samples.size() - 1);
}

void PadEngine::commitBank() {
    if (!building_) return;
    // The generation moves before the bank is parked: from here every
    // command the UI pushes is for this bank, and the callback drops (and
    // reports) any it still finds stamped with the old one.
    building_->generation = uiGeneration_.load(std::memory_order_acquire) + 1;
    uiGeneration_.store(building_->generation, std::memory_order_release);
    // A bank the callback never got round to adopting is ours to free,
    // and so is the one it retired last time.
    delete pending_.exchange(building_.release(), std::memory_order_acq_rel);
    delete retired_.exchange(nullptr, std::memory_order_acq_rel);
}

size_t PadEngine::drainEnded(int32_t* out, size_t max) {
    size_t n = 0;
    int32_t id;
    while (n < max && ended_.pop(id)) out[n++] = id;
    return n;
}

// ---- audio thread from here down ---------------------------------------------

void PadEngine::adoptPendingBank() {
    if (pending_.load(std::memory_order_acquire) == nullptr) return;
    // The UI has not collected the last retiree yet: wait for the next
    // callback rather than hand anything back. retired_ only goes
    // null -> non-null here, so the check-then-store cannot race.
    if (retired_.load(std::memory_order_acquire) != nullptr) return;
    Bank* incoming = pending_.exchange(nullptr, std::memory_order_acq_rel);
    if (!incoming) return;
    // Every voice belonged to the old bank: silence them and say so.
    for (auto& v : voices_) {
        if (v.active) endVoice(v);
    }
    retired_.store(current_, std::memory_order_release);
    current_ = incoming;
}

void PadEngine::endVoice(Voice& v) {
    v.active = false;
    v.fadeStep = 0.0f;
    // A full ring drops the report; the UI drains every frame, so that
    // takes hundreds of endings inside one screen frame.
    ended_.push(v.id);
}

PadEngine::Voice& PadEngine::freeVoice() {
    // An idle slot first; then the voice already fading out furthest along;
    // then the oldest - the ear misses old tails least.
    Voice* pick = nullptr;
    for (auto& v : voices_) {
        if (!v.active) return v;
    }
    for (auto& v : voices_) {
        if (v.fadeStep > 0.0f && (!pick || v.fade < pick->fade)) pick = &v;
    }
    if (!pick) {
        for (auto& v : voices_) {
            if (!pick || v.serial < pick->serial) pick = &v;
        }
    }
    endVoice(*pick);
    return *pick;
}

void PadEngine::apply(const PadCommand& c) {
    // A command for another bank (queued across a kit swap) is honest
    // silence: a NoteOn is reported ended so the allocator lets it go, a
    // Stop is moot (the swap silenced every voice), AllOff always applies.
    const bool stale = c.type != PadCommand::Type::AllOff &&
        (!current_ || c.generation != current_->generation);
    if (stale) {
        if (c.type == PadCommand::Type::NoteOn) ended_.push(c.voiceId);
        return;
    }
    switch (c.type) {
        case PadCommand::Type::NoteOn: {
            if (!current_ || c.sample < 0 || c.sample >= static_cast<int32_t>(current_->samples.size())) {
                ended_.push(c.voiceId);  // nothing to play: the allocator must not count it
                return;
            }
            const BankSample& s = current_->samples[static_cast<size_t>(c.sample)];
            const int64_t frames = s.frameCount();
            const int64_t start = std::max<int64_t>(0, std::min<int64_t>(c.start, frames));
            const int64_t end = std::max<int64_t>(start, std::min<int64_t>(c.end, frames));
            if (end - start < 2) {
                ended_.push(c.voiceId);
                return;
            }
            Voice& v = freeVoice();
            v.active = true;
            v.id = c.voiceId;
            v.sample = c.sample;
            v.pos = static_cast<double>(start);
            v.end = end;
            v.inc = (static_cast<double>(s.rate) / static_cast<double>(sampleRate_)) * c.pitch;
            v.gainL = c.gainL;
            v.gainR = c.gainR;
            v.fade = 1.0f;
            v.fadeStep = 0.0f;
            v.serial = ++serial_;
            return;
        }
        case PadCommand::Type::Stop:
        case PadCommand::Type::AllOff: {
            const float fadeFrames = std::max(1.0f, c.fadeMs * 0.001f * static_cast<float>(sampleRate_));
            for (auto& v : voices_) {
                if (!v.active) continue;
                if (c.type == PadCommand::Type::Stop && v.id != c.voiceId) continue;
                if (v.fadeStep <= 0.0f) v.fadeStep = 1.0f / fadeFrames;
            }
            return;
        }
    }
}

void PadEngine::render(float* out, int32_t numFrames) {
    for (int32_t i = 0; i < numFrames * 2; ++i) out[i] = 0.0f;
    if (!current_) return;
    for (auto& v : voices_) {
        if (!v.active) continue;
        const BankSample& s = current_->samples[static_cast<size_t>(v.sample)];
        const float* f = s.frames.data();
        const int64_t last = v.end - 1;
        for (int32_t i = 0; i < numFrames; ++i) {
            const int64_t i0 = static_cast<int64_t>(v.pos);
            if (i0 >= v.end) {
                endVoice(v);
                break;
            }
            const int64_t i1 = std::min<int64_t>(i0 + 1, last);
            const float frac = static_cast<float>(v.pos - static_cast<double>(i0));
            float l, r;
            if (s.channels == 2) {
                const float l0 = f[2 * i0], l1 = f[2 * i1];
                const float r0 = f[2 * i0 + 1], r1 = f[2 * i1 + 1];
                l = l0 + (l1 - l0) * frac;
                r = r0 + (r1 - r0) * frac;
            } else {
                const float m = f[i0] + (f[i1] - f[i0]) * frac;
                l = r = m;
            }
            if (v.fadeStep > 0.0f) {
                v.fade -= v.fadeStep;
                if (v.fade <= 0.0f) {
                    endVoice(v);
                    break;
                }
            }
            out[2 * i] += l * v.gainL * v.fade;
            out[2 * i + 1] += r * v.gainR * v.fade;
            v.pos += v.inc;
        }
    }
    // Thirty-two voices at kit levels rarely sum past full scale; when they
    // do, a clamp beats wrapping.
    for (int32_t i = 0; i < numFrames * 2; ++i) {
        if (out[i] > 1.0f) out[i] = 1.0f;
        else if (out[i] < -1.0f) out[i] = -1.0f;
    }
}

oboe::DataCallbackResult PadEngine::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    adoptPendingBank();
    PadCommand c;
    while (commands_.pop(c)) apply(c);
    render(static_cast<float*>(audioData), numFrames);
    return oboe::DataCallbackResult::Continue;
}

}  // namespace snipsnap
