#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "SpscRing.h"

namespace snipsnap {

/** One loaded WAV: interleaved floats at the file's own rate. */
struct BankSample {
    std::vector<float> frames;
    int32_t channels = 1;
    int32_t rate = 44100;
    int64_t frameCount() const { return channels > 0 ? static_cast<int64_t>(frames.size() / channels) : 0; }
};

/** A kit's samples, built on the UI thread and adopted whole by the callback. */
struct Bank {
    std::vector<BankSample> samples;
    /** Which commit built it; a command stamped with another generation is not for this bank. */
    uint32_t generation = 0;
};

/**
 * What the UI asks of a voice. Every pad semantic - which layer a velocity
 * taps, which slice a chain steps to, level, pan, tune - is resolved on
 * the JVM (PadHit, tested); the engine only ever hears "this sample, these
 * frames, these gains, this speed".
 */
struct PadCommand {
    enum class Type : int32_t { NoteOn = 0, Stop = 1, AllOff = 2 };
    Type type = Type::NoteOn;
    int32_t voiceId = 0;
    int32_t sample = -1;
    int64_t start = 0;
    int64_t end = 0;
    float gainL = 0.0f, gainR = 0.0f;
    double pitch = 1.0;
    /** Stop / AllOff: the fade, so a choke is a short fade and not a cut. */
    float fadeMs = 5.0f;
    /** Stamped by pushCommand: the bank the sample index belongs to. */
    uint32_t generation = 0;
};

/**
 * The pads' voice: M4's latency milestone. Up to [kMaxVoices] sample
 * players, each a windowed, repitched, linearly interpolated read of one
 * bank sample, mixed to stereo under Oboe's callback. The UI keys every
 * voice by the id the tested VoiceAllocator gave it; the engine reports
 * each voice's end back on a second ring, so the allocator learns of an
 * ending exactly when it happens instead of guessing from a timer.
 *
 * Threading (the SurfaceEngine's rules, the same handshake):
 *  - UI thread: start/stop, beginBank/addSample/commitBank, pushCommand,
 *    drainEnded.
 *  - Audio thread: onAudioReady only. It reads the command ring, adopts a
 *    pending bank, writes the ended ring; it never allocates or blocks.
 *  - A bank swap parks the new bank in `pending_`; the callback adopts it
 *    (silencing every voice, which belonged to the old one) and parks the
 *    old bank in `retired_` for the UI to free. The audio thread frees
 *    nothing.
 */
class PadEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kMaxVoices = 32;

    explicit PadEngine(int32_t preferredSampleRate);
    ~PadEngine() override;

    bool start();
    void stop();
    int32_t sampleRate() const { return sampleRate_; }
    bool needsRestart() const { return restartNeeded_.load(std::memory_order_acquire); }
    bool isShared() const { return sharedMode_.load(std::memory_order_acquire); }

    // The bank, UI thread: begin, add each sample (index returned), commit.
    void beginBank();
    int32_t addSample(std::vector<float>&& interleaved, int32_t channels, int32_t rate);
    void commitBank();

    /**
     * UI thread. Never blocks; a full ring drops the command (256 deep - a
     * burst of hits is dozens). Stamped with the bank generation the caller
     * is playing against, so a hit queued before a kit swap never reaches
     * the new bank's samples by index.
     */
    bool pushCommand(PadCommand c) {
        c.generation = uiGeneration_.load(std::memory_order_acquire);
        return commands_.push(c);
    }

    /** UI thread: the ids of voices that ended since the last drain. */
    size_t drainEnded(int32_t* out, size_t max);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Voice {
        bool active = false;
        int32_t id = 0;
        int32_t sample = -1;
        double pos = 0.0;
        int64_t end = 0;
        double inc = 1.0;
        float gainL = 0.0f, gainR = 0.0f;
        float fade = 1.0f;
        float fadeStep = 0.0f;  // > 0 while stopping
        uint64_t serial = 0;
    };

    void adoptPendingBank();
    void apply(const PadCommand& c);
    Voice& freeVoice();
    void endVoice(Voice& v);
    void render(float* out, int32_t numFrames);

    std::shared_ptr<oboe::AudioStream> stream_;
    int32_t preferredRate_;
    int32_t sampleRate_ = 48000;
    std::atomic<bool> restartNeeded_{false};
    std::atomic<bool> sharedMode_{false};

    std::unique_ptr<Bank> building_;  // UI thread only
    std::atomic<uint32_t> uiGeneration_{0};  // the last committed bank's generation
    std::atomic<Bank*> pending_{nullptr};
    std::atomic<Bank*> retired_{nullptr};
    Bank* current_ = nullptr;  // audio thread only

    Voice voices_[kMaxVoices];
    uint64_t serial_ = 0;

    SpscRing<PadCommand, 256> commands_;
    SpscRing<int32_t, 256> ended_;
};

}  // namespace snipsnap
