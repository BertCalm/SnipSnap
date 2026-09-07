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
 *  - Mono. The surface's DSP is mono duplicated to both channels, so the
 *    print keeps one copy; a printed sample lands on TAPE like any other.
 */
class PrintBuffer {
public:
    enum class State : int { Idle = 0, Recording = 1, Stopping = 2, Done = 3 };

    /** UI thread: reserve `maxFrames` and start recording from the next callback. */
    void arm(size_t maxFrames) {
        frames_.assign(maxFrames, 0.0f);
        written_ = 0;
        state_.store(State::Recording, std::memory_order_release);
    }

    /** UI thread: ask the callback to finish; it flips to Done on its next pass. */
    void requestStop() {
        State expected = State::Recording;
        state_.compare_exchange_strong(expected, State::Stopping, std::memory_order_acq_rel);
    }

    /** Audio thread: copy `count` mono frames in; returns false once full or stopped. */
    inline bool record(const float* mono, size_t count) {
        const State s = state_.load(std::memory_order_acquire);
        if (s == State::Stopping) {
            state_.store(State::Done, std::memory_order_release);
            return false;
        }
        if (s != State::Recording) return false;
        const size_t room = frames_.size() - written_;
        const size_t n = count < room ? count : room;
        for (size_t i = 0; i < n; ++i) frames_[written_ + i] = mono[i];
        written_ += n;
        if (written_ >= frames_.size()) {
            state_.store(State::Done, std::memory_order_release);
            return false;
        }
        return true;
    }

    State state() const { return state_.load(std::memory_order_acquire); }

    /** UI thread, only when `state() == Done`: the frames actually captured. */
    size_t framesWritten() const { return written_; }
    const float* data() const { return frames_.data(); }

    /** UI thread: release the memory and go back to Idle. */
    void clear() {
        std::vector<float>().swap(frames_);
        written_ = 0;
        state_.store(State::Idle, std::memory_order_release);
    }

private:
    std::vector<float> frames_;
    size_t written_ = 0;
    std::atomic<State> state_{State::Idle};
};

}  // namespace snipsnap
