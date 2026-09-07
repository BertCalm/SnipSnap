#pragma once

#include <array>
#include <atomic>
#include <cstddef>

namespace snipsnap {

/**
 * A single-producer, single-consumer ring: the UI thread pushes, the audio
 * callback pops, and neither ever waits on a lock or touches the heap.
 *
 * Capacity is a power of two so the index wrap is a mask. The two indices
 * are atomics with acquire/release pairing: the producer publishes a slot
 * by storing `head` with release after writing it, the consumer sees the
 * slot only after loading `head` with acquire. When the ring is full the
 * producer *drops* the new frame rather than blocking - for a stream of
 * control values the newest one is what matters and it will be along in
 * a few milliseconds.
 */
template <typename T, size_t CapacityPow2>
class SpscRing {
    static_assert((CapacityPow2 & (CapacityPow2 - 1)) == 0, "capacity is a power of two");

public:
    /** Producer side. Returns false when the ring is full (frame dropped). */
    bool push(const T& item) {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t next = (head + 1) & kMask;
        if (next == tail_.load(std::memory_order_acquire)) return false;
        slots_[head] = item;
        head_.store(next, std::memory_order_release);
        return true;
    }

    /** Consumer side. Returns false when there is nothing to take. */
    bool pop(T& out) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        out = slots_[tail];
        tail_.store((tail + 1) & kMask, std::memory_order_release);
        return true;
    }

private:
    static constexpr size_t kMask = CapacityPow2 - 1;
    std::array<T, CapacityPow2> slots_{};
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

}  // namespace snipsnap
