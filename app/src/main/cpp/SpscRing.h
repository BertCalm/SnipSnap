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
    bool push(const T& item) { return pushAll(&item, 1); }

    /**
     * Producer side, as one publication: the consumer sees every one of
     * [n] items or none of them. The slots are written first and `head`
     * moves once, with release, after the last of them - so a consumer
     * that loads `head` with acquire either has not seen the group at all
     * or has all of it.
     *
     * That is what a group of voices meant to sound *together* needs. Push
     * them one at a time and the callback can fire between two of them,
     * starting one layer a buffer (a few milliseconds) after its
     * neighbours - rare, audible as a flam, and miserable to chase.
     *
     * Returns false having written nothing when the ring cannot hold all
     * [n]: a half-published group would be the very thing this prevents.
     */
    bool pushAll(const T* items, size_t n) {
        if (n == 0) return true;
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        // One slot always stays empty, so head == tail can only mean empty:
        // kMask items fit, not CapacityPow2.
        const size_t used = (head - tail) & kMask;
        if (n > kMask - used) return false;
        for (size_t i = 0; i < n; ++i) slots_[(head + i) & kMask] = items[i];
        head_.store((head + n) & kMask, std::memory_order_release);
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
