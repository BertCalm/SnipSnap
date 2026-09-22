#pragma once

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "ParameterSmoother.h"

namespace snipsnap {

/**
 * LIVE's own voice (PHOTO_SPECS.md §8): a wavetable oscillator reading
 * whatever [pushFrame] last delivered, its table cross-fading to a new
 * frame over a fixed ~50 ms window rather than swapping in a click, its
 * three continuous macros ([setMacros] - TUNE/BRIGHT/GRIT) smoothed
 * toward each new reading the same way [ParameterSmoother] de-zippers
 * SurfaceEngine's own corners. DECAY has no meaning here: LIVE never
 * stops sounding to decay away, unlike every one-shot SNAP render.
 *
 * Threading, the same shape SurfaceEngine documents for itself:
 *  - UI thread: `start`/`stop`, `pushFrame`, `setMacros`.
 *  - Audio thread: `onAudioReady` only.
 *  - A table swap is a pointer handshake, one slot (unlike SurfaceEngine's
 *    four sample slots - LIVE only ever has one live source): the UI
 *    parks a new table in `pending_`, the callback adopts it into
 *    `current_` (retiring the old `current_` into `previous_`, the table
 *    the crossfade fades *from*) and parks whatever `previous_` held
 *    before that in `retired_` for the UI to free on its next call - the
 *    audio thread never calls `delete`.
 */
class LiveSnapEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    /** [Snap.TABLE_SIZE] on the Kotlin side; kept as a plain constant here since :synth is not reachable from :app's native tree. */
    static constexpr int32_t kTableSize = 256;

    explicit LiveSnapEngine(int32_t preferredSampleRate);
    ~LiveSnapEngine() override;

    bool start();
    void stop();
    int32_t sampleRate() const { return sampleRate_; }
    bool needsRestart() const { return restartNeeded_.load(std::memory_order_acquire); }
    bool isShared() const { return sharedMode_.load(std::memory_order_acquire); }
    /** Round-trip latency in ms, or -1 when unknown. UI thread only. */
    double latencyMillis() const;

    /**
     * UI thread. [table] is copied; exactly [kTableSize] points read
     * (short is zero-padded, long is truncated) so a caller's own length
     * mistake never reads or writes past this engine's own buffer.
     * Already -1..1: the Kotlin side does the same zero-mean, seam-blended
     * normalization every one-shot render's table gets (`Snap.liveCycle`),
     * so this engine's own crossfade only has to blend two comparable
     * cycles, never fix up a raw 0..255 line itself.
     */
    void pushFrame(const float* table, int32_t count);

    /** UI thread. Every macro 0..1; a non-finite one reads as its own SNAP default rather than corrupting the smoother it feeds forever. */
    void setMacros(float tune, float bright, float grit);

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    struct LiveTable {
        float points[kTableSize] = {};
    };

    void adoptPendingTable();
    void renderMono(float* out, int32_t numFrames);
    static float readTable(const LiveTable* t, float phase);
    /** Sizes/configures every rate-dependent piece for [fs] - the constructor calls this at the default sampleRate_ (48000) and start() again at the device's real rate, the same reasoning SurfaceEngine's own constructor documents: the host test suite calls onAudioReady directly and never start() at all. */
    void configureForRate(float fs);

    std::shared_ptr<oboe::AudioStream> stream_;
    int32_t preferredRate_;
    int32_t sampleRate_ = 48000;
    std::atomic<bool> restartNeeded_{false};
    std::atomic<bool> sharedMode_{false};

    // The table handshake (see this class's own KDoc above).
    std::atomic<LiveTable*> pending_{nullptr};
    std::atomic<LiveTable*> retired_{nullptr};
    LiveTable* current_ = nullptr;   // audio thread only
    LiveTable* previous_ = nullptr;  // audio thread only - the crossfade-from table

    // The crossfade's own position: 0 at the moment a new table is
    // adopted, kCrossfadeFrames once it has fully taken over. Held past
    // that point (rather than reset) so `previous_` can stay set with no
    // per-sample branch to tell "mid-fade" from "long since finished" -
    // renderMono only reads `previous_` for its value, never this counter,
    // once a fade has completed as far as kCrossfadeFrames.
    int32_t crossfadeFrame_ = 0;
    int32_t crossfadeFrames_ = 1;  // ~50ms of frames at sampleRate_, set in start()

    double phase_ = 0.0;  // audio thread only, 0..kTableSize

    // The three live macros, UI -> audio as atomics (a position, not an
    // event - only the latest reading before a render matters, the same
    // reasoning SurfaceEngine's own corners rely on).
    std::atomic<float> targetTune_{0.5f};
    std::atomic<float> targetBright_{0.6f};
    std::atomic<float> targetGrit_{0.15f};

    // Per-sample smoothing of every macro. `filter_` doubles as both a
    // macro smoother's own math (it IS a one-pole lowpass) and the
    // audio-rate tone filter BRIGHT drives - the identical shape, so
    // reusing it here is exact, not an approximation.
    ParameterSmoother tuneSmoother_, brightSmoother_, gritSmoother_;
    ParameterSmoother filter_;

    // Derived values, recomputed every kControlInterval samples (the
    // trig/exp behind them is the "expensive" work SurfaceEngine's own
    // comment names; the smoothers themselves still advance every sample).
    static constexpr int32_t kControlInterval = 32;
    int32_t untilCoefficients_ = 0;
    float freqHz_ = 220.0f;

    // Pre-sized scratch so the callback never allocates; larger bursts render in chunks.
    static constexpr size_t kScratchFrames = 4096;
    std::vector<float> scratch_;
};

}  // namespace snipsnap
