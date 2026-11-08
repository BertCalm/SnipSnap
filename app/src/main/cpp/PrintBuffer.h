#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace snipsnap {

/**
 * The resample tap: while printing, the master bus is copied here as it
 * plays, so the gesture you just made becomes a plain sample - the
 * SP-404 / OP-1 move, and how the surface saves CPU later (a printed
 * performance plays back as one pad, no DSP).
 *
 * Memory rules, because the audio thread writes into it:
 *  - `arm` allocates on the *calling* (UI) thread, to a fixed frame
 *    ceiling, before the callback is told to record. The callback never
 *    allocates.
 *  - The callback records while the state is Recording and flips itself
 *    to Done when the buffer fills or a Stop request arrives - the audio
 *    thread always makes the last write, so `take` on the UI thread only
 *    reads once the state says Done.
 *  - Channel count is chosen at `arm`. Both live engines now print their
 *    stereo output, including Aquarium width. Mono remains available to
 *    other callers. Stereo is interleaved, as every other app buffer is.
 */
class PrintBuffer {
public:
    enum class State : int { Idle = 0, Recording = 1, Stopping = 2, Done = 3 };

    /**
     * UI thread: reserve `maxFrames` of `channels` and start recording
     * from the next callback. Refuses (false) while a print is Recording
     * or Stopping - the callback may still be writing into the old
     * frames, and reassigning them under it would be a data race. Take or
     * clear the finished print first.
     *
     * `channels_` is written here and read by the callback, which is safe
     * for the same reason `frames_` is: it is set before the release
     * store of Recording, and the callback reads it only after the
     * matching acquire load.
     */
    bool arm(size_t maxFrames, int channels = 1) { return armAt(maxFrames, channels, -1, -1); }

    /**
     * As [arm], but addressed to the engine's own frame count (what [recordAt] is given as `blockStart`):
     * the print keeps nothing before `startFrame` and finishes, Done, on the block that reaches
     * `stopFrame`, having kept exactly the frames in `[startFrame, stopFrame)`. Either may be -1 for
     * "no address": record from the next callback, or until the buffer is full or a stop is asked for.
     *
     * Like `channels_`, both are written before the release store of Recording and read by the callback
     * only after its acquire load, so they need no atomics of their own.
     */
    bool armAt(size_t maxFrames, int channels, int64_t startFrame, int64_t stopFrame) {
        const State s = state_.load(std::memory_order_acquire);
        if (s == State::Recording || s == State::Stopping) return false;
        if (channels < 1) return false;
        channels_ = static_cast<size_t>(channels);
        startFrame_ = startFrame;
        stopFrame_ = stopFrame;
        frames_.assign(maxFrames * channels_, 0.0f);
        written_ = 0;
        state_.store(State::Recording, std::memory_order_release);
        return true;
    }

    /** UI thread: ask the callback to finish; it flips to Done on its next pass. */
    void requestStop() {
        State expected = State::Recording;
        state_.compare_exchange_strong(expected, State::Stopping, std::memory_order_acq_rel);
    }

    /**
     * Audio thread: copy `count` frames of interleaved bus in; returns
     * false once full or stopped. A partial copy is cut on a frame
     * boundary, never between a frame's channels: the room left is a
     * whole number of frames to begin with and every copy takes a whole
     * number of them, so it stays one.
     */
    inline bool record(const float* bus, size_t count) {
        const State s = state_.load(std::memory_order_acquire);
        if (s == State::Stopping) {
            state_.store(State::Done, std::memory_order_release);
            return false;
        }
        if (s != State::Recording) return false;
        const size_t room = frames_.size() - written_;
        const size_t want = count * channels_;
        const size_t n = want < room ? want : room;
        for (size_t i = 0; i < n; ++i) frames_[written_ + i] = bus[i];
        written_ += n;
        if (written_ >= frames_.size()) {
            state_.store(State::Done, std::memory_order_release);
            return false;
        }
        return true;
    }

    /**
     * Audio thread: one callback's block, `count` frames of interleaved bus that begin at engine frame
     * `blockStart`. With no address this is [record]. With one, only the part of the block inside
     * `[startFrame, stopFrame)` is kept: a block before the start is skipped whole, one that straddles it
     * keeps its tail, and the block that reaches the stop keeps its head and flips to Done. A start already
     * behind `blockStart` (the UI thread was late arming) records from the top of the block, so the print
     * ends on the stop frame but is short by the lateness, never shifted.
     */
    inline bool recordAt(const float* bus, size_t count, int64_t blockStart) {
        if (startFrame_ < 0 && stopFrame_ < 0) return record(bus, count);
        const State s = state_.load(std::memory_order_acquire);
        if (s == State::Stopping) {
            state_.store(State::Done, std::memory_order_release);
            return false;
        }
        if (s != State::Recording) return false;
        const int64_t blockEnd = blockStart + static_cast<int64_t>(count);
        size_t skip = 0;
        if (startFrame_ > blockStart) {
            if (startFrame_ >= blockEnd) return true;  // not yet
            skip = static_cast<size_t>(startFrame_ - blockStart);
        }
        size_t take = count - skip;
        bool reachedStop = false;
        if (stopFrame_ >= 0 && stopFrame_ < blockEnd) {
            const int64_t keep = stopFrame_ - (blockStart + static_cast<int64_t>(skip));
            take = keep > 0 ? static_cast<size_t>(keep) : 0;
            reachedStop = true;
        }
        const size_t room = frames_.size() - written_;
        const size_t want = take * channels_;
        const size_t n = want < room ? want : room;
        const float* from = bus + skip * channels_;
        for (size_t i = 0; i < n; ++i) frames_[written_ + i] = from[i];
        written_ += n;
        if (reachedStop || written_ >= frames_.size()) {
            state_.store(State::Done, std::memory_order_release);
            return false;
        }
        return true;
    }

    State state() const { return state_.load(std::memory_order_acquire); }

    /** The channel count the live print was armed with. */
    int channels() const { return static_cast<int>(channels_); }

    /** UI thread, only when `state() == Done`: the frames actually captured. */
    size_t framesWritten() const { return written_ / channels_; }

    /** The same in samples - the length of what `data()` points at. */
    size_t samplesWritten() const { return written_; }

    const float* data() const { return frames_.data(); }

    /**
     * UI thread: release the memory and go back to Idle. Refuses (false)
     * while a print is Recording, for the reason `arm` does - the callback
     * is writing into those frames, and freeing them under it is a
     * use-after-free.
     *
     * Clearing from Stopping is NOT the same guarantee, and review was
     * right to say so: a call to `record` already past its own state
     * check when `requestStop` runs commits to finishing that write
     * regardless, so "the callback has seen Stopping" does not mean it
     * has made its last write. Only Done means that. Callers of this
     * class wait for it (see `jni.cpp`'s `stopPrinting`, which does not
     * read or clear until `state() == Done`) rather than treating
     * Stopping, or a bounded wait that gives up still inside it, as
     * license to free this buffer.
     */
    bool clear() {
        if (state_.load(std::memory_order_acquire) == State::Recording) return false;
        std::vector<float>().swap(frames_);
        written_ = 0;
        state_.store(State::Idle, std::memory_order_release);
        return true;
    }

private:
    std::vector<float> frames_;
    size_t written_ = 0;  // in samples, not frames
    size_t channels_ = 1;
    int64_t startFrame_ = -1;  // engine frames; -1 = no address (see armAt)
    int64_t stopFrame_ = -1;
    std::atomic<State> state_{State::Idle};
};

}  // namespace snipsnap
