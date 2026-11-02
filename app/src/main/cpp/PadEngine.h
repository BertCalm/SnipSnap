#pragma once

#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

#include "PrintBuffer.h"
#include "SpscRing.h"
#include "aquarium/PerformanceBus.h"

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
    /**
     * SetPitch and SetMaster are the whole engine's, not one voice's, so
     * their `voiceId` is ignored. SetVibrato is the mod wheel: a slow pitch
     * wobble on every voice, `pitch` its depth in semitones either way
     * (0 is off, clamped to [PadEngine::kMaxVibratoSemitones]), at a fixed
     * rate, gliding to the new depth over `fadeMs`. SetPitch is a bend: every voice, the
     * sounding ones, their release tails and notes started later, reads at
     * its own speed times `pitch`. SetMaster scales the whole mix by
     * `gainL`. Both glide over `fadeMs` from wherever they are.
     */
    enum class Type : int32_t { NoteOn = 0, Stop = 1, AllOff = 2, SetGain = 3, SetPitch = 4, SetMaster = 5, SetVibrato = 6 };
    Type type = Type::NoteOn;
    int32_t voiceId = 0;
    int32_t sample = -1;
    int64_t start = 0;
    int64_t end = 0;
    /**
     * -1 plays once to `end`; otherwise the loop is `[loopStart, end)`: the read wraps back to this frame when it
     * reaches `end`, the last frame is played, and the frame after it is `loopStart` (a keygroup's sustain; the
     * period is `end - loopStart`). A loop of fewer than two frames plays once.
     */
    int64_t loopStart = -1;
    float gainL = 0.0f, gainR = 0.0f;
    double pitch = 1.0;
    /**
     * Play the window backwards: the read starts at `end - 1` and walks
     * down to `start`, a loop wrapping from `loopStart` back up to the
     * last frame. The *speed* stays positive - direction is its own
     * thing, not a negative pitch, so the guard that keeps `pitch` a
     * real speed still means what it says.
     */
    bool reverse = false;
    /** Stop / AllOff: the fade, so a choke is a short fade and not a cut. SetGain, SetPitch, SetMaster: the glide. */
    float fadeMs = 5.0f;
    /**
     * The frame this lands on, counted from the first frame of the block
     * in which the callback first sees it. 0 (and anything below it) is
     * the top of that block, which is where every command used to land;
     * 37 is the thirty-eighth frame of it; a value past the block's end
     * carries over, less the block, into the next one, so a command may
     * be queued any number of frames ahead. Commands due on one frame
     * apply in the order they were pushed.
     */
    int32_t frameOffset = 0;
    /** Stamped by pushCommand: the bank the sample index belongs to. */
    uint32_t generation = 0;
};

/**
 * The pads' voice (M4) and the keys' (a looping voice is a keygroup's
 * sustain; a note-off is a Stop with the instrument's release as its
 * fade). Up to [kMaxVoices] sample players, each a windowed, repitched,
 * four-point cubic (Catmull-Rom) read of one bank sample, mixed to stereo under
 * Oboe's callback. The UI keys every
 * voice by the id the tested VoiceAllocator gave it; the engine reports
 * each voice's end back on a second ring, so the allocator learns of an
 * ending exactly when it happens instead of guessing from a timer.
 *
 * Threading (the SurfaceEngine's rules, the same handshake):
 *  - UI thread: start/stop, beginBank/addSample/commitBank, pushCommand,
 *    drainEnded.
 *  - Audio thread: onAudioReady only. It reads the command ring, adopts a
 *    pending bank, writes the ended ring; it never allocates or blocks.
 *  - A command lands on the frame its `frameOffset` names: the callback
 *    takes every command off the ring into a held set (audio thread
 *    only, fixed size), renders up to the earliest frame due, applies
 *    what is due there, and renders on; what is still due after the
 *    block's last frame waits, its offset less the block, for the next.
 *  - A bank swap parks the new bank in `pending_`; the callback adopts it
 *    (silencing every voice, which belonged to the old one) and parks the
 *    old bank in `retired_` for the UI to free. The audio thread frees
 *    nothing.
 */
class PadEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    static constexpr int kMaxVoices = 32;
    /**
     * The fastest a voice may read, in frames per output frame. Six octaves
     * up is far past anything a kit asks for; the ceiling is here so a
     * corrupt tune cannot make the loop wrap in render() spin.
     */
    static constexpr double kMaxSpeed = 64.0;
    /**
     * The widest a bend may go, as a speed ratio either way: two octaves,
     * the most any keyboard's bend range is set to. A SetPitch past it is
     * clamped to it; one that is not a positive number is ignored.
     */
    static constexpr double kMaxBend = 4.0;
    /** The deepest vibrato, in semitones either way of the note, and the rate it wobbles at. */
    static constexpr double kMaxVibratoSemitones = 2.0;
    static constexpr double kVibratoHz = 5.5;

    explicit PadEngine(int32_t preferredSampleRate);
    ~PadEngine() override;

    bool start();
    void stop();
    int32_t sampleRate() const { return sampleRate_; }
    bool needsRestart() const { return restartNeeded_.load(std::memory_order_acquire); }
    /**
     * Frames onAudioReady has written since construction: monotonic, never
     * reset by start() or stop(). Safe from any thread.
     */
    int64_t framesRendered() const { return framesRendered_.load(std::memory_order_acquire); }
    bool isShared() const { return sharedMode_.load(std::memory_order_acquire); }
    /** Round-trip latency in ms, or -1 when unknown. UI thread only (see OboeOutput.h). */
    double latencyMillis() const;
    /**
     * The hardware's own playback position: the frame count the device says it
     * has played and the CLOCK_MONOTONIC nanoseconds it says that was true at.
     * UI thread only, never from the audio callback (Oboe documents
     * getTimestamp as unsafe in a data callback before Android R; minSdk is
     * 29). stream_ is only set and reset on the UI thread, so no lock is
     * needed. Returns false, leaving both outputs untouched, with no stream or
     * on any Oboe error (a fresh stream has no timestamp for a moment).
     */
    bool hardwarePosition(int64_t* framePosition, int64_t* timeNanos);
    /** Frames per hardware burst, or 0 with no stream. UI thread only. */
    int32_t framesPerBurst() const;
    /** oboe::AudioApi as an int (0 unspecified, 1 OpenSL ES, 2 AAudio), or -1 with no stream. UI thread only. */
    int32_t audioApi() const;

    /** Serialized control thread; false means no settings were queued. */
    bool configureAquarium(const aquarium::PerformanceSettings& settings, bool reset = false);
    bool aquariumFaulted() const { return aquarium_.faulted() || (stream_ && !aquarium_.isPrepared()); }
    uint64_t aquariumLimitedFrames() const { return aquarium_.limitedFrames(); }

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

    /**
     * UI thread. The same, for voices that must sound *together*: the
     * whole group reaches the callback in one pass or not at all (see
     * SpscRing::pushAll), so layers of one sample cannot start a buffer
     * apart. Refuses a group larger than the engine has voices, and
     * refuses rather than half-publish when the ring is full.
     */
    bool pushCommands(const PadCommand* cs, size_t n) {
        if (n == 0) return true;
        if (n > static_cast<size_t>(kMaxVoices)) return false;
        PadCommand stamped[kMaxVoices];
        const uint32_t generation = uiGeneration_.load(std::memory_order_acquire);
        for (size_t i = 0; i < n; ++i) {
            stamped[i] = cs[i];
            stamped[i].generation = generation;
        }
        return commands_.pushAll(stamped, n);
    }

    /** UI thread: the ids of voices that ended since the last drain. */
    size_t drainEnded(int32_t* out, size_t max);

    /**
     * The resample tap, the one the surface has had since it was built
     * (see PrintBuffer for the ownership rules). Two channels, not the
     * surface's one: the pads mix every voice under its own left and
     * right gain, so a mono print would be a record of a performance
     * nobody heard.
     *
     * This is what a BOUNCE of the pads is made of. It prints the bus as
     * it plays rather than rendering the kit again offline, which means
     * it cannot disagree with what came out of the speaker - the clamp,
     * the voice stealing, the fades are all already in it - and it reuses
     * the state machine the surface's print has been tested on since the
     * first round.
     */
    bool armPrint(size_t maxFrames) { return print_.arm(maxFrames, 2); }
    void requestStopPrint() { print_.requestStop(); }
    PrintBuffer::State printState() const { return print_.state(); }
    size_t printFrames() const { return print_.framesWritten(); }
    /** The length of [printData] - twice [printFrames], the print being stereo. */
    size_t printSamples() const { return print_.samplesWritten(); }
    const float* printData() const { return print_.data(); }
    bool clearPrint() { return print_.clear(); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct Voice {
        bool active = false;
        int32_t id = 0;
        int32_t sample = -1;
        double pos = 0.0;
        int64_t start = 0;
        int64_t end = 0;
        int64_t loopStart = -1;
        double inc = 1.0;
        bool reverse = false;
        // Where the gains are heading and how many frames are left of the
        // glide. A fader on a sounding voice moves it here rather than
        // retriggering the note, which would be a click per drag frame.
        float targetL = 0.0f, targetR = 0.0f;
        int32_t gainRamp = 0;
        float gainL = 0.0f, gainR = 0.0f;
        float fade = 1.0f;
        float fadeStep = 0.0f;  // > 0 while stopping
        uint64_t serial = 0;
    };

    void adoptPendingBank();
    /** Ring to held set. Full means the command applies at the top of this block rather than never. */
    void takeCommands();
    /** Apply every held command due at or before `frame`, in push order; return the next frame something is due on, or `numFrames`. */
    int32_t applyDue(int32_t frame, int32_t numFrames);
    void apply(const PadCommand& c);
    /** A glide's length in frames: at least one, at most ten seconds, a glide that is not a number one frame. */
    int32_t glideFrames(float ms) const {
        const double frames = static_cast<double>(ms) * 0.001 * static_cast<double>(sampleRate_);
        if (!(frames >= 1.0)) return 1;
        return static_cast<int32_t>(std::min(frames, 10.0 * static_cast<double>(sampleRate_)));
    }
    Voice& freeVoice();
    void endVoice(Voice& v);
    void render(float* out, int32_t numFrames);
    void renderChunk(float* out, int32_t numFrames);
    /** The master gain over one rendered piece, after the Aquarium bus. */
    void applyMaster(float* out, int32_t numFrames);

    std::shared_ptr<oboe::AudioStream> stream_;
    int32_t preferredRate_;
    int32_t sampleRate_ = 48000;
    std::atomic<bool> restartNeeded_{false};
    std::atomic<bool> sharedMode_{false};
    // The engine's only clock, the one the loudspeaker is on. The fit from
    // nanoseconds to these frames waits for bench S1; it will read this.
    std::atomic<int64_t> framesRendered_{0};
    static_assert(std::atomic<int64_t>::is_always_lock_free, "the frame counter must be lock-free on every ABI the app builds for");

    std::unique_ptr<Bank> building_;  // UI thread only
    std::atomic<uint32_t> uiGeneration_{0};  // the last committed bank's generation
    std::atomic<Bank*> pending_{nullptr};
    std::atomic<Bank*> retired_{nullptr};
    Bank* current_ = nullptr;  // audio thread only

    Voice voices_[kMaxVoices];
    uint64_t serial_ = 0;

    /**
     * A glide the whole engine shares (the bend, the master gain), audio
     * thread only. It moves in a straight line, as SetGain's does: after k
     * of its n frames it is k/n of the way. [at] is its value on frame i of
     * the piece render() is drawing; [advance] moves it on by the piece.
     */
    struct Glide {
        double value = 1.0;
        double target = 1.0;
        int32_t ramp = 0;
        double at(int32_t i) const {
            return i < ramp ? value + (target - value) * static_cast<double>(i + 1) / static_cast<double>(ramp) : target;
        }
        void advance(int32_t frames) {
            if (frames >= ramp) {
                value = target;
                ramp = 0;
            } else if (frames > 0) {
                value += (target - value) * static_cast<double>(frames) / static_cast<double>(ramp);
                ramp -= frames;
            }
        }
        void glideTo(double to, int32_t frames) {
            target = to;
            ramp = frames;
            if (ramp <= 0) value = to;
        }
    };
    Glide bend_;
    Glide master_;
    /** The vibrato's depth in semitones (0 at rest) and where its wobble is, in radians. */
    Glide depth_ = {0.0, 0.0, 0};
    double vibratoPhase_ = 0.0;
    /**
     * The speed multiplier for each frame of the chunk being rendered: the
     * bend times the vibrato, worked out once per frame for all voices
     * rather than once per voice per frame.
     */
    static constexpr int32_t kPitchChunk = 512;
    double pitchMul_[kPitchChunk];
    void fillPitch(int32_t frames);

    SpscRing<PadCommand, 256> commands_;
    SpscRing<int32_t, 256> ended_;

    /**
     * Commands taken off the ring and not yet due, audio thread only. As
     * deep as the ring, so one block's whole drain fits; a command that
     * finds it full plays at the top of the block it arrived in, which is
     * early rather than lost. A NoteOn waiting here is reported ended by
     * stop(), the way one waiting on the ring is.
     */
    static constexpr int32_t kHeldMax = 256;
    PadCommand held_[kHeldMax];
    int32_t heldCount_ = 0;

    aquarium::PerformanceBus aquarium_;
    PrintBuffer print_;
};

}  // namespace snipsnap
