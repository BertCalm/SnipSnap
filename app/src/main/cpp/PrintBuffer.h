#pragma once

#include <atomic>
#include <cstddef>
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
 *  - One or two channels, chosen at `arm`, because the two engines' buses
 *    differ: the surface's DSP is mono duplicated to both channels, so
 *    its print keeps one copy, while the pads mix every voice under its
 *    own left and right gain - printing one channel of that would be a
 *    different record than the one that was heard. Two channels are
 *    stored interleaved, the way every other buffer in this app is.
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
    bool arm(size_t maxFrames, int channels = 1) {
        const State s = state_.load(std::memory_order_acquire);
        if (s == State::Recording || s == State::Stopping) return false;
        if (channels < 1) return false;
        channels_ = static_cast<size_t>(channels);
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
     * use-after-free. Ask with `requestStop` first and take the print when
     * it says Done. Clearing from Stopping is safe: the callback makes no
     * further write once it has seen that state.
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
    std::atomic<State> state_{State::Idle};
};

}  // namespace snipsnap
