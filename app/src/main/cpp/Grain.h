#pragma once

#include <cmath>
#include <cstdint>

namespace snipsnap {
namespace grain {

/**
 * GRAIN mode's arithmetic, kept out of SurfaceEngine so the host harness
 * can call it without an engine: what a knob position means in
 * milliseconds or hertz, where a grain starts once SPRAY has had its say,
 * and - the part that is easy to get wrong silently - which note a pitch
 * snaps to in the kit's key. SurfaceEngine::renderMono only ever calls
 * these; it holds no second copy of any rule here.
 *
 * Every knob is 0..1 and every function is total: a NaN reads as its
 * neutral value rather than travelling into a smoother or a phase
 * accumulator, the same door `control01` keeps on the surface's other
 * macros.
 */

/** 0..1, NaN-safe (`!(v > 0)` catches NaN where `v < 0` would pass it on). */
inline float clamp01(float v) { return !(v > 0.0f) ? 0.0f : (v > 1.0f ? 1.0f : v); }

/** SIZE's range: a grain is somewhere between a click and a quarter second. */
constexpr float kMinLengthMs = 10.0f;
constexpr float kMaxLengthMs = 250.0f;

/** DENSITY's range: a slow tick to a continuous cloud. */
constexpr float kMinRateHz = 2.0f;
constexpr float kMaxRateHz = 64.0f;

/** The pitch axis: ±1 octave across the pad, the same reach XY's pitch macro has. */
constexpr float kPitchSemitoneSpan = 24.0f;

/** Twelve set bits: every pitch class allowed, which is what "no key" means. */
constexpr uint32_t kChromaticMask = 0xFFFu;

/** SIZE 0..1 → 10 ms .. 250 ms, exponential, the way a length knob reads. */
inline float lengthMs(float size) {
    return kMinLengthMs * std::pow(kMaxLengthMs / kMinLengthMs, clamp01(size));
}

/** DENSITY 0..1 → 2 Hz .. 64 Hz grains per second, exponential. */
inline float rateHz(float density) {
    return kMinRateHz * std::pow(kMaxRateHz / kMinRateHz, clamp01(density));
}

/**
 * The scale mask for [semitones] above [root] - a pitch class test that
 * wraps, so a note eleven semitones below the root is degree 1, not -11.
 */
inline bool inKey(int32_t midi, int32_t root, uint32_t mask) {
    const int32_t degree = ((midi - root) % 12 + 12) % 12;
    return (mask >> degree) & 1u;
}

/**
 * The nearest MIDI note to [targetMidi] whose pitch class is in [mask]
 * rooted at [root] - `Scales.nearestInKey` in :audio, ported: the search
 * is ±12 around the rounded target (the nearest in-key note is never
 * further than an octave away, and for any scale this app ships it is
 * within three semitones), ascending, keeping the first on an exact tie
 * so a note halfway between two degrees lands on the lower one every
 * time rather than flickering with float noise.
 *
 * An empty mask is not a key with no notes; it is no key at all, and
 * reads as chromatic - otherwise the search would find nothing and the
 * caller would have to invent an answer inside an audio callback.
 */
inline int32_t snapToKey(float targetMidi, int32_t root, uint32_t mask) {
    const float target = std::isfinite(targetMidi) ? targetMidi : 0.0f;
    const uint32_t degrees = (mask & kChromaticMask) == 0u ? kChromaticMask : (mask & kChromaticMask);
    const int32_t r = ((root % 12) + 12) % 12;
    const int32_t centre = static_cast<int32_t>(std::lround(target));
    int32_t best = centre;
    float bestDist = 1e9f;
    for (int32_t midi = centre - 12; midi <= centre + 12; ++midi) {
        if (!inKey(midi, r, degrees)) continue;
        const float d = std::fabs(static_cast<float>(midi) - target);
        if (d < bestDist) {
            bestDist = d;
            best = midi;
        }
    }
    return best;
}

/**
 * The playback ratio for a finger at [pitchAxis] (0..1, up is higher) on
 * a pad whose own note is [sourceMidi]: the axis asks for a transposition
 * of up to an octave either way, the sum is snapped to the key, and the
 * ratio is whatever takes the pad from where it *is* to that note. So a
 * pad sitting a little flat of A comes out exactly on A at the axis's
 * centre - the snap retunes as well as quantises.
 *
 * With no key the mask is chromatic and this is a semitone quantiser;
 * with no known source note the caller passes 0 for both root and
 * source, and the degrees become intervals from the pad's own pitch.
 */
inline float pitchRatio(float pitchAxis, float sourceMidi, int32_t root, uint32_t mask) {
    const float source = std::isfinite(sourceMidi) ? sourceMidi : 0.0f;
    const float offset = (clamp01(pitchAxis) - 0.5f) * kPitchSemitoneSpan;
    const int32_t note = snapToKey(source + offset, root, mask);
    return std::exp2((static_cast<float>(note) - source) / 12.0f);
}

/**
 * Where a grain starts, as a fraction 0..1 of its source: POSITION, pushed
 * by up to half the source either way at full SPRAY ([random] is 0..1
 * uniform), wrapped rather than clamped so a scatter past either end
 * comes round the loop the way the surface's own playback does. SPRAY 0
 * is exactly POSITION whatever [random] says - the cloud freezes on one
 * spot, and a test can hold it to that.
 */
inline float startFraction(float position, float spray, float random) {
    const float scatter = clamp01(spray) * 0.5f * (2.0f * clamp01(random) - 1.0f);
    float start = clamp01(position) + scatter;
    start -= std::floor(start);
    return start >= 1.0f ? 0.0f : start;
}

/**
 * A grain's level so that SIZE and DENSITY change texture, not loudness:
 * [overlap] grains sounding at once (length × rate) sum roughly as the
 * square root of their count, so each is scaled by the inverse. Never
 * above unity - a sparse cloud plays each grain as the source is, it
 * does not boost it.
 */
inline float gainFor(float overlap) {
    return 1.0f / std::sqrt(overlap > 1.0f ? overlap : 1.0f);
}

/** xorshift32: deterministic, allocation-free, and plenty for scattering grain starts. Never yields 0, so the state cannot stick. */
inline uint32_t nextRandom(uint32_t& state) {
    uint32_t x = state == 0u ? 0x67721A1Eu : state;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    state = x;
    return x;
}

/** [nextRandom] as a 0..1 float. */
inline float random01(uint32_t& state) {
    return static_cast<float>(nextRandom(state) >> 8) / 16777216.0f;  // 24 bits: exact in a float
}

}  // namespace grain
}  // namespace snipsnap
